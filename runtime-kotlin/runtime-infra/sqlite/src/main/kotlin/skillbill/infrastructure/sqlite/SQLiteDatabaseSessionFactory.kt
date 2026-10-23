package skillbill.infrastructure.sqlite

import me.tatarka.inject.annotations.Inject
import org.sqlite.SQLiteErrorCode
import org.sqlite.SQLiteException
import skillbill.contracts.persistence.DesktopReadSchema
import skillbill.error.core.DatabaseAccessOperation
import skillbill.error.core.SkillBillRuntimeException
import skillbill.error.core.databaseBusy
import skillbill.infrastructure.sqlite.core.ops.DatabaseTransactionBeginMode
import skillbill.infrastructure.sqlite.core.ops.DatabaseTransactionSpec
import skillbill.infrastructure.sqlite.core.ops.inDatabaseTransaction
import skillbill.infrastructure.sqlite.core.schema.DatabaseRuntime
import skillbill.infrastructure.sqlite.core.schema.OpenDatabase
import skillbill.infrastructure.sqlite.core.schema.databaseAccessError
import skillbill.infrastructure.sqlite.core.schema.fileObservation
import skillbill.infrastructure.sqlite.core.schema.requireResolvedEnvironmentContext
import skillbill.model.EnvironmentContext
import skillbill.ports.blockincident.BlockIncidentContractValidator
import skillbill.ports.db.DatabaseSessionFactory
import skillbill.ports.db.model.FencedTransactionResult
import skillbill.ports.db.model.OwningTransactionResult
import skillbill.ports.diagnostics.RuntimeDiagnostics
import skillbill.ports.filesystem.model.DatabaseFileObservation
import skillbill.ports.persistence.UnitOfWork
import skillbill.ports.workflow.WorkflowSnapshotValidator
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.SQLException
import java.time.Clock
import kotlin.coroutines.cancellation.CancellationException

@Inject
class SQLiteDatabaseSessionFactory(
  context: EnvironmentContext,
  private val clock: Clock,
  private val diagnostics: RuntimeDiagnostics,
  private val workflowSnapshotValidator: WorkflowSnapshotValidator,
  private val runtimeVersion: String,
  private val blockIncidentContractValidator: BlockIncidentContractValidator,
) : DatabaseSessionFactory {
  private val activeRead = ThreadLocal<UnitOfWork>()
  private val activeWrite = ThreadLocal<UnitOfWork>()
  private val resolvedContext = requireResolvedEnvironmentContext(context)
  private val resolvedPath by lazy {
    DatabaseRuntime.resolveDbPath(
      cliValue = resolvedContext.dbPathOverride,
      environment = resolvedContext.environment,
      userHome = resolvedContext.userHome,
    )
  }

  override fun resolveDbPath(): Path = resolvedPath

  override fun databaseExists(): Boolean = Files.exists(resolveDbPath())

  override fun <T> read(block: (UnitOfWork) -> T): T {
    activeWrite.get()?.let { return block(it) }
    activeRead.get()?.let { return block(it) }
    return DatabaseRuntime.openReadDbAt(resolveDbPath(), diagnostics).use { openDb -> readInDatabase(openDb, block) }
  }

  override fun <T> readIfPresent(block: (UnitOfWork) -> T): T? {
    activeWrite.get()?.let { return block(it) }
    activeRead.get()?.let { return block(it) }
    if (!databaseExists()) return null
    return DatabaseRuntime.openExistingReadDbAt(resolveDbPath(), diagnostics).use {
        openDb ->
      readInDatabase(openDb, block)
    }
  }

  private fun <T> readInDatabase(
    openDb: OpenDatabase,
    block: (UnitOfWork) -> T,
  ): T =
    runCatching {
      openDb.connection.inDatabaseTransaction(
        DatabaseTransactionSpec(
          dbPath = openDb.dbPath,
          beginMode = DatabaseTransactionBeginMode.DEFERRED,
          operation = DatabaseAccessOperation.READ,
          diagnostics = diagnostics,
        ),
      ) {
        activeRead.set(unitOfWork(openDb, transactionActive = true))
        try {
          block(requireNotNull(activeRead.get()))
        } finally {
          activeRead.remove()
        }
      }
    }.getOrElse { error -> throwReadFailure(openDb.dbPath, error) }

  override fun <T> selfManagedWrite(block: (UnitOfWork) -> T): T {
    activeWrite.get()?.let { return block(it) }
    repeat(DatabaseRuntime.SELF_MANAGED_WRITE_BUSY_ATTEMPTS - 1) {
      val outcome =
        runCatching {
          withWriteDatabase { openDb ->
            val (result, workChanged) = withDesktopTopicSignal(openDb.connection) { block(unitOfWork(openDb)) }
            if (workChanged) publishDesktopCommitSignal(openDb.dbPath, openDb.connection, diagnostics)
            result
          }
        }
      val error = outcome.exceptionOrNull()
      if (error == null) {
        return outcome.getOrThrow()
      }
      error.rethrowIfCooperativeCancellationOrInterruption()
      if (!error.isSqliteBusy()) throw error
    }
    return translatingBusyFailures {
      withWriteDatabase { openDb ->
        val (result, workChanged) = withDesktopTopicSignal(openDb.connection) { block(unitOfWork(openDb)) }
        if (workChanged) publishDesktopCommitSignal(openDb.dbPath, openDb.connection, diagnostics)
        result
      }
    }
  }

  override fun <T> transaction(block: (UnitOfWork) -> T): T {
    activeWrite.get()?.let { return block(it) }
    return translatingBusyFailures {
      withWriteDatabase { openDb ->
        val (transactionResult, changedRevisions) =
          openDb.connection.inDatabaseTransaction(
            DatabaseTransactionSpec(
              dbPath = openDb.dbPath,
              beginMode = DatabaseTransactionBeginMode.IMMEDIATE,
              operation = DatabaseAccessOperation.WRITE,
              diagnostics = diagnostics,
            ),
          ) {
            withDesktopTopicSignal(this) {
              activeWrite.set(unitOfWork(openDb, transactionActive = true))
              try {
                block(requireNotNull(activeWrite.get()))
              } finally {
                activeWrite.remove()
              }
            }
          }
        if (changedRevisions) publishDesktopCommitSignal(openDb.dbPath, openDb.connection, diagnostics)
        transactionResult
      }
    }
  }

  override fun <T> owningTransaction(block: (UnitOfWork) -> T): OwningTransactionResult<T> {
    if (activeWrite.get() != null || activeRead.get() != null) {
      return OwningTransactionResult.Refused(OwningTransactionResult.Refusal.ACTIVE_SESSION)
    }
    return OwningTransactionResult.Committed(transaction(block))
  }

  override fun <T> transactionFenced(
    expected: DatabaseFileObservation,
    block: (UnitOfWork) -> T,
  ): FencedTransactionResult<T> {
    if (activeWrite.get() != null) return FencedTransactionResult.ChangedTarget
    return translatingBusyFailures {
      withWriteDatabase { openDb ->
        val (transactionResult, changedRevisions) =
          openDb.connection.inDatabaseTransaction(
            DatabaseTransactionSpec(
              openDb.dbPath,
              DatabaseTransactionBeginMode.IMMEDIATE,
              DatabaseAccessOperation.WRITE,
              diagnostics,
            ),
            commitWhen = { it.first is FencedTransactionResult.Committed<*> },
          ) {
            withDesktopTopicSignal(this) {
              val unit = unitOfWork(openDb, transactionActive = true)
              activeWrite.set(unit)
              try {
                fencedResult(unit, expected, block)
              } finally {
                activeWrite.remove()
              }
            }
          }
        if (transactionResult is FencedTransactionResult.Committed<*> && changedRevisions) {
          publishDesktopCommitSignal(openDb.dbPath, openDb.connection, diagnostics)
        }
        transactionResult
      }
    }
  }

  private fun <T> withDesktopTopicSignal(
    connection: Connection,
    block: Connection.() -> T,
  ): Pair<T, Boolean> {
    val before = connection.desktopTopicRevisions()
    val result = connection.block()
    val after = connection.desktopTopicRevisions()
    return result to (after != before)
  }

  private fun Connection.desktopTopicRevisions(): Map<String, Long> =
    prepareStatement("SELECT topic, revision FROM ${DesktopReadSchema.REVISIONS_TABLE}").use { statement ->
      statement.executeQuery().use { rows ->
        buildMap {
          while (rows.next()) put(rows.getString(1), rows.getLong(2))
        }
      }
    }

  private fun <T> fencedResult(
    unit: UnitOfWork,
    expected: DatabaseFileObservation,
    block: (UnitOfWork) -> T,
  ): FencedTransactionResult<T> {
    if (unit.databaseFileObservation != expected || fileObservation(resolveDbPath()) != expected) {
      return FencedTransactionResult.ChangedTarget
    }
    val result = block(unit)
    return if (fileObservation(resolveDbPath()) == expected) {
      FencedTransactionResult.Committed(result)
    } else {
      FencedTransactionResult.ChangedTarget
    }
  }

  private fun unitOfWork(
    openDb: OpenDatabase,
    transactionActive: Boolean = false,
  ): SQLiteUnitOfWork =
    SQLiteUnitOfWork(
      openDb.connection,
      openDb.dbPath,
      clock,
      diagnostics,
      workflowSnapshotValidator,
      runtimeVersion,
      blockIncidentContractValidator,
      transactionActive,
      openDb.fileObservation,
    )

  private fun <T> withWriteDatabase(block: (OpenDatabase) -> T): T {
    val dbPath = resolveDbPath()
    DatabaseRuntime.ensureWriteReady(dbPath, diagnostics)
    return DatabaseRuntime.openWriteDbAt(dbPath, diagnostics).use(block)
  }
}

private fun <T> translatingBusyFailures(block: () -> T): T =
  runCatching(block).getOrElse { error ->
    error.rethrowIfCooperativeCancellationOrInterruption()
    if (error is SkillBillRuntimeException) throw error
    if (error.isSqliteBusy()) throw databaseBusy(error)
    throw error
  }

private fun Throwable.rethrowIfCooperativeCancellationOrInterruption() {
  when (this) {
    is CancellationException -> throw this
    is InterruptedException -> throw this
  }
}

private fun Throwable.isSqliteBusy(): Boolean =
  generateSequence(this) { it.cause }.any { error ->
    error is SQLiteException && (error.resultCode.code and SQLITE_PRIMARY_CODE_MASK) == SQLiteErrorCode.SQLITE_BUSY.code
  }

private const val SQLITE_PRIMARY_CODE_MASK = 0xFF

private fun throwReadFailure(
  dbPath: Path,
  error: Throwable,
): Nothing {
  if (error is SQLException) {
    throw databaseAccessError(dbPath, DatabaseAccessOperation.READ, error)
  }
  throw error
}
