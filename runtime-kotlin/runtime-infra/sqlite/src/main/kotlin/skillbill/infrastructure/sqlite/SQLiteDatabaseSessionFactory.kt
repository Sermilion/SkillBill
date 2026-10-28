package skillbill.infrastructure.sqlite

import me.tatarka.inject.annotations.Inject
import org.sqlite.SQLiteErrorCode
import org.sqlite.SQLiteException
import skillbill.contracts.persistence.DesktopReadSchema
import skillbill.error.core.DatabaseAccessOperation
import skillbill.error.core.DatabaseFailureCode
import skillbill.error.core.SkillBillRuntimeException
import skillbill.error.core.databaseBusy
import skillbill.infrastructure.sqlite.core.ops.DatabaseTransactionBeginMode
import skillbill.infrastructure.sqlite.core.ops.DatabaseTransactionSpec
import skillbill.infrastructure.sqlite.core.ops.ReadConnectionLease
import skillbill.infrastructure.sqlite.core.ops.ReadConnectionPool
import skillbill.infrastructure.sqlite.core.ops.inDatabaseTransaction
import skillbill.infrastructure.sqlite.core.schema.DatabaseRuntime
import skillbill.infrastructure.sqlite.core.schema.OpenDatabase
import skillbill.infrastructure.sqlite.core.schema.databaseAccessError
import skillbill.infrastructure.sqlite.core.schema.fileObservation
import skillbill.infrastructure.sqlite.core.schema.requireResolvedEnvironmentContext
import skillbill.model.EnvironmentContext
import skillbill.ports.blockincident.BlockIncidentContractValidator
import skillbill.ports.db.DatabaseReadConnections
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
) : DatabaseSessionFactory, DatabaseReadConnections {
  private val readConnections = ReadConnectionPool(diagnostics)
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
    val dbPath = resolveDbPath()
    if (!databaseExists()) {
      readConnections.invalidate()
      return perConnectionRead(dbPath, block)
    }
    val pooled = pooledRead({ openPooledReadDb(dbPath) }, block)
    return if (pooled != null) pooled.value else perConnectionRead(dbPath, block)
  }

  override fun <T> readIfPresent(block: (UnitOfWork) -> T): T? {
    activeWrite.get()?.let { return block(it) }
    activeRead.get()?.let { return block(it) }
    if (!databaseExists()) {
      readConnections.invalidate()
      return null
    }
    val dbPath = resolveDbPath()
    return pooledRead({ DatabaseRuntime.openExistingReadDbAt(dbPath, diagnostics) }, block)?.value
  }

  override fun release() {
    readConnections.invalidate()
  }

  private fun openPooledReadDb(dbPath: Path): OpenDatabase? =
    try {
      DatabaseRuntime.openCompatibleReadOnlyDb(dbPath, diagnostics)
    } catch (error: SkillBillRuntimeException) {
      if (error.code != DatabaseFailureCode.INCOMPATIBLE_SCHEMA && error.code != DatabaseFailureCode.MISSING) {
        throw error
      }
      diagnostics.info("Pooled read connection unavailable (${error.code}); reading through a per-read connection.")
      null
    }

  private fun <T> perConnectionRead(
    dbPath: Path,
    block: (UnitOfWork) -> T,
  ): T =
    DatabaseRuntime.openReadDbAt(dbPath, diagnostics).use { openDb ->
      readTransaction(openDb, { true }) { runReadBlock(openDb, block) }
    }

  private fun <T> pooledRead(
    open: () -> OpenDatabase?,
    block: (UnitOfWork) -> T,
  ): LeasedRead.Done<T>? {
    while (true) {
      val lease = readConnections.lease(open) ?: return null
      if (lease.reused && !readConnections.isCurrentFile(lease)) {
        readConnections.discard(lease)
      } else {
        val outcome = readOnLease(lease, block)
        if (outcome is LeasedRead.Done) return outcome
      }
      readConnections.invalidate()
    }
  }

  private fun <T> readOnLease(
    lease: ReadConnectionLease,
    block: (UnitOfWork) -> T,
  ): LeasedRead<T> {
    val openDb = lease.database
    val outcome =
      runCatching {
        readTransaction<LeasedRead<T>>(openDb, { it is LeasedRead.Done }) {
          if (lease.reused && !readConnections.isCurrentGeneration(this, lease)) {
            LeasedRead.Stale
          } else {
            LeasedRead.Done(runReadBlock(openDb, block))
          }
        }
      }.onFailure { readConnections.discard(lease) }.getOrThrow()
    if (outcome is LeasedRead.Done) readConnections.giveBack(lease) else readConnections.discard(lease)
    return outcome
  }

  private fun <R> readTransaction(
    openDb: OpenDatabase,
    commitWhen: (R) -> Boolean,
    body: Connection.() -> R,
  ): R =
    runCatching {
      openDb.connection.inDatabaseTransaction(
        DatabaseTransactionSpec(
          dbPath = openDb.dbPath,
          beginMode = DatabaseTransactionBeginMode.DEFERRED,
          operation = DatabaseAccessOperation.READ,
          diagnostics = diagnostics,
        ),
        commitWhen,
        body,
      )
    }.getOrElse { error -> throwReadFailure(openDb.dbPath, error) }

  private fun <T> runReadBlock(
    openDb: OpenDatabase,
    block: (UnitOfWork) -> T,
  ): T {
    activeRead.set(unitOfWork(openDb, transactionActive = true))
    try {
      return block(requireNotNull(activeRead.get()))
    } finally {
      activeRead.remove()
    }
  }

  override fun <T> selfManagedWrite(block: (UnitOfWork) -> T): T {
    activeWrite.get()?.let { return block(it) }
    repeat(DatabaseRuntime.SELF_MANAGED_WRITE_BUSY_ATTEMPTS - 1) {
      val outcome =
        runCatching {
          withWriteDatabase { openDb ->
            val (result, changedTopics) = withDesktopTopicSignal(openDb.connection) { block(unitOfWork(openDb)) }
            if (changedTopics.isNotEmpty()) {
              publishDesktopCommitSignal(openDb.dbPath, openDb.connection, changedTopics, diagnostics)
            }
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
        val (result, changedTopics) = withDesktopTopicSignal(openDb.connection) { block(unitOfWork(openDb)) }
        if (changedTopics.isNotEmpty()) {
          publishDesktopCommitSignal(openDb.dbPath, openDb.connection, changedTopics, diagnostics)
        }
        result
      }
    }
  }

  override fun <T> transaction(block: (UnitOfWork) -> T): T {
    activeWrite.get()?.let { return block(it) }
    return translatingBusyFailures {
      withWriteDatabase { openDb ->
        val (transactionResult, changedTopics) =
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
        if (changedTopics.isNotEmpty()) {
          publishDesktopCommitSignal(openDb.dbPath, openDb.connection, changedTopics, diagnostics)
        }
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
        val (transactionResult, changedTopics) =
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
        if (transactionResult is FencedTransactionResult.Committed<*> && changedTopics.isNotEmpty()) {
          publishDesktopCommitSignal(openDb.dbPath, openDb.connection, changedTopics, diagnostics)
        }
        transactionResult
      }
    }
  }

  private fun <T> withDesktopTopicSignal(
    connection: Connection,
    block: Connection.() -> T,
  ): Pair<T, Set<String>> {
    val before = connection.desktopTopicRevisions()
    val result = connection.block()
    val after = connection.desktopTopicRevisions()
    return result to after.filter { (topic, revision) -> before[topic] != revision }.keys
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

private sealed interface LeasedRead<out T> {
  data class Done<T>(val value: T) : LeasedRead<T>

  data object Stale : LeasedRead<Nothing>
}

private fun throwReadFailure(
  dbPath: Path,
  error: Throwable,
): Nothing {
  if (error is SQLException) {
    throw databaseAccessError(dbPath, DatabaseAccessOperation.READ, error)
  }
  throw error
}
