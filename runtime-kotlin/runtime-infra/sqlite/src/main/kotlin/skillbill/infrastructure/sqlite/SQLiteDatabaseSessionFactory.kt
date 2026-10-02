package skillbill.infrastructure.sqlite

import me.tatarka.inject.annotations.Inject
import org.sqlite.SQLiteErrorCode
import org.sqlite.SQLiteException
import skillbill.error.core.DatabaseAccessOperation
import skillbill.error.core.databaseBusy
import skillbill.infrastructure.sqlite.core.ops.DatabaseTransactionBeginMode
import skillbill.infrastructure.sqlite.core.ops.DatabaseTransactionSpec
import skillbill.infrastructure.sqlite.core.ops.inDatabaseTransaction
import skillbill.infrastructure.sqlite.core.schema.DatabaseRuntime
import skillbill.infrastructure.sqlite.core.schema.OpenDatabase
import skillbill.infrastructure.sqlite.core.schema.databaseAccessError
import skillbill.infrastructure.sqlite.core.schema.requireResolvedEnvironmentContext
import skillbill.model.EnvironmentContext
import skillbill.ports.db.DatabaseSessionFactory
import skillbill.ports.diagnostics.RuntimeDiagnostics
import skillbill.ports.persistence.UnitOfWork
import skillbill.ports.workflow.WorkflowSnapshotValidator
import java.nio.file.Files
import java.nio.file.Path
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
) : DatabaseSessionFactory {
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

  override fun <T> read(block: (UnitOfWork) -> T): T =
    DatabaseRuntime.openReadDbAt(resolveDbPath(), diagnostics).use { openDb ->
      runCatching {
        openDb.connection.inDatabaseTransaction(
          DatabaseTransactionSpec(
            dbPath = openDb.dbPath,
            beginMode = DatabaseTransactionBeginMode.DEFERRED,
            operation = DatabaseAccessOperation.READ,
            diagnostics = diagnostics,
          ),
        ) {
          block(unitOfWork(openDb, transactionActive = true))
        }
      }.getOrElse { error -> throwReadFailure(openDb.dbPath, error) }
    }

  override fun <T> readIfPresent(block: (UnitOfWork) -> T): T? =
    DatabaseRuntime.openReadDbIfPresentAt(resolveDbPath())?.use { openDb ->
      runCatching {
        openDb.connection.inDatabaseTransaction(
          DatabaseTransactionSpec(
            dbPath = openDb.dbPath,
            beginMode = DatabaseTransactionBeginMode.DEFERRED,
            operation = DatabaseAccessOperation.READ,
            diagnostics = diagnostics,
          ),
        ) {
          block(unitOfWork(openDb, transactionActive = true))
        }
      }.getOrElse { error -> throwReadFailure(openDb.dbPath, error) }
    }

  override fun <T> selfManagedWrite(block: (UnitOfWork) -> T): T {
    repeat(DatabaseRuntime.SELF_MANAGED_WRITE_BUSY_ATTEMPTS - 1) {
      val outcome = runCatching { withWriteDatabase { openDb -> block(unitOfWork(openDb)) } }
      val error = outcome.exceptionOrNull() ?: return outcome.getOrThrow()
      error.rethrowIfCooperativeCancellationOrInterruption()
      if (!error.isSqliteBusy()) throw error
    }
    return translatingBusyFailures { withWriteDatabase { openDb -> block(unitOfWork(openDb)) } }
  }

  override fun <T> transaction(block: (UnitOfWork) -> T): T =
    translatingBusyFailures {
      withWriteDatabase { openDb ->
        openDb.connection.inDatabaseTransaction(
          DatabaseTransactionSpec(
            dbPath = openDb.dbPath,
            beginMode = DatabaseTransactionBeginMode.IMMEDIATE,
            operation = DatabaseAccessOperation.WRITE,
            diagnostics = diagnostics,
          ),
        ) {
          block(unitOfWork(openDb, transactionActive = true))
        }
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
      transactionActive,
    )

  private fun <T> withWriteDatabase(block: (OpenDatabase) -> T): T {
    val dbPath = resolveDbPath()
    DatabaseRuntime.ensureWriteReady(dbPath, diagnostics)
    return DatabaseRuntime.openWriteDbAt(dbPath).use(block)
  }
}

private fun <T> translatingBusyFailures(block: () -> T): T =
  runCatching(block).getOrElse { error ->
    error.rethrowIfCooperativeCancellationOrInterruption()
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
