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
import skillbill.infrastructure.sqlite.core.ops.publishDesktopCommitSignal
import skillbill.infrastructure.sqlite.core.schema.OpenDatabase
import skillbill.infrastructure.sqlite.core.schema.databaseAccessError
import skillbill.infrastructure.sqlite.core.schema.fileObservation
import skillbill.infrastructure.sqlite.core.schema.requireResolvedEnvironmentContext
import skillbill.infrastructure.sqlite.pathlock.DatabasePathFileLock
import skillbill.model.EnvironmentContext
import skillbill.ports.WorkBoardReadModelProjector
import skillbill.ports.blockincident.BlockIncidentContractValidator
import skillbill.ports.db.DatabaseCheckpoint
import skillbill.ports.db.DatabaseReadConnections
import skillbill.ports.db.DatabaseSessionFactory
import skillbill.ports.db.model.DatabaseCheckpointResult
import skillbill.ports.db.model.DatabasePathProtectionResult
import skillbill.ports.db.model.FencedTransactionResult
import skillbill.ports.db.model.OwningTransactionResult
import skillbill.ports.diagnostics.RuntimeDiagnostics
import skillbill.ports.filesystem.model.DatabaseFileObservation
import skillbill.ports.persistence.UnitOfWork
import skillbill.ports.work.model.WORK_BOARD_CANDIDATE_BATCH_SIZE
import skillbill.ports.workflow.WorkflowSnapshotValidator
import kotlin.coroutines.cancellation.CancellationException
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.SQLException
import java.time.Clock

@Inject
class SQLiteDatabaseSessionFactory(
  context: EnvironmentContext,
  private val clock: Clock,
  private val diagnostics: RuntimeDiagnostics,
  private val workflowSnapshotValidator: WorkflowSnapshotValidator,
  private val runtimeVersion: String,
  private val blockIncidentContractValidator: BlockIncidentContractValidator,
  private val boardReadModelProjector: Lazy<WorkBoardReadModelProjector>,
) : DatabaseSessionFactory, DatabaseReadConnections, DatabaseCheckpoint {
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

  override fun acquireDatabasePathProtection(): DatabasePathProtectionResult =
    DatabasePathFileLock.acquire(resolveDbPath(), diagnostics)

  override fun databaseExists(): Boolean = Files.exists(resolveDbPath())

  override fun truncateWal(): DatabaseCheckpointResult {
    if (activeWrite.get() != null || activeRead.get() != null) {
      recordCheckpointBusy("active_transaction")
      return DatabaseCheckpointResult.Busy
    }
    if (!databaseExists()) return DatabaseCheckpointResult.Absent
    val dbPath = resolveDbPath()
    val outcome =
      runCatching {
        DatabaseRuntime.openWriteDbAt(dbPath, diagnostics).use { openDb ->
          openDb.connection.createStatement().use { it.execute("PRAGMA busy_timeout = 0") }
          val pageSize =
            openDb.connection.createStatement().use { statement ->
              statement.executeQuery("PRAGMA page_size").use { rows ->
                check(rows.next()) { "SQLite did not return a page size." }
                rows.getInt(1)
              }
            }
          val walPagesBefore = walFrameCount(Path.of("$dbPath-wal"), pageSize)
          val busy =
            openDb.connection.createStatement().use { statement ->
              statement.executeQuery("PRAGMA wal_checkpoint(TRUNCATE)").use { rows ->
                check(rows.next()) { "SQLite did not return a WAL checkpoint result." }
                rows.getInt(1) == 1
              }
            }
          if (busy) {
            recordCheckpointBusy("checkpoint_blocked")
            DatabaseCheckpointResult.Busy
          } else {
            DatabaseCheckpointResult.Truncated(walPagesBefore)
          }
        }
      }
    return outcome.getOrElse { error ->
      error.rethrowIfCooperativeCancellationOrInterruption()
      if (error.isSqliteBusy()) {
        recordCheckpointBusy("checkpoint_blocked")
        DatabaseCheckpointResult.Busy
      } else {
        throw error
      }
    }
  }

  private fun recordCheckpointBusy(cause: String) {
    diagnostics.warning(
      "seam=wal_checkpoint value_expected=truncated value_used=busy cause=$cause db_path=${resolveDbPath()}",
    )
  }

  private fun walFrameCount(
    walPath: Path,
    pageSize: Int,
  ): Int {
    if (!Files.exists(walPath)) return 0
    val size = Files.size(walPath)
    if (size < WAL_HEADER_BYTES) return 0
    return ((size - WAL_HEADER_BYTES) / (WAL_FRAME_HEADER_BYTES + pageSize)).toInt()
  }

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
      var blockCompleted = false
      val outcome =
        runCatching {
          withWriteDatabase { openDb ->
            runSelfManagedWrite(openDb, block) { blockCompleted = true }
          }
        }
      val error = outcome.exceptionOrNull()
      if (error == null) {
        return outcome.getOrThrow()
      }
      error.rethrowIfCooperativeCancellationOrInterruption()
      if (blockCompleted) throw error
      if (!error.isSqliteBusy()) throw error
    }
    return translatingBusyFailures {
      withWriteDatabase { openDb ->
        runSelfManagedWrite(openDb, block) {}
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
            withDesktopTopicSignal(this) { before ->
              activeWrite.set(unitOfWork(openDb, transactionActive = true))
              try {
                val unit = requireNotNull(activeWrite.get())
                val result = block(unit)
                refreshBoardReadModel(openDb, before, unit)
                result
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
            withDesktopTopicSignal(this) { before ->
              val unit = unitOfWork(openDb, transactionActive = true)
              activeWrite.set(unit)
              try {
                val result = fencedResult(unit, expected, block)
                if (result is FencedTransactionResult.Committed<*>) refreshBoardReadModel(openDb, before, unit)
                result
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
    block: Connection.(Map<String, Long>) -> T,
  ): Pair<T, Set<String>> {
    val before = connection.desktopTopicRevisions()
    val result = connection.block(before)
    val after = connection.desktopTopicRevisions()
    return result to after.filter { (topic, revision) -> before[topic] != revision }.keys
  }

  private fun refreshBoardReadModel(
    openDb: OpenDatabase,
    before: Map<String, Long>,
    unit: UnitOfWork = unitOfWork(openDb, transactionActive = true),
  ) {
    val store = unit.workBoardReadModel
    if (!store.isComplete()) {
      boardReadModelProjector.value.rebuild(unit, clock.instant())
      return
    }
    val changed = openDb.connection.changedBoardWorkIds(before)
    if (changed.requiresRebuild) {
      boardReadModelProjector.value.rebuild(unit, clock.instant())
    } else if (changed.workIds.isNotEmpty()) {
      boardReadModelProjector.value.refresh(unit, changed.workIds, clock.instant())
    }
  }

  private fun refreshBoardReadModelAfterSelfManagedWrite(
    openDb: OpenDatabase,
    before: Map<String, Long>,
  ) {
    openDb.connection.inDatabaseTransaction(
      DatabaseTransactionSpec(
        dbPath = openDb.dbPath,
        beginMode = DatabaseTransactionBeginMode.IMMEDIATE,
        operation = DatabaseAccessOperation.WRITE,
        diagnostics = diagnostics,
      ),
    ) {
      val unit = unitOfWork(openDb, transactionActive = true)
      activeWrite.set(unit)
      try {
        refreshBoardReadModel(openDb, before, unit)
      } finally {
        activeWrite.remove()
      }
    }
  }

  private fun <T> runSelfManagedWrite(
    openDb: OpenDatabase,
    block: (UnitOfWork) -> T,
    onBlockCompleted: () -> Unit,
  ): T {
    var refreshFailure: Throwable? = null
    val (result, changedTopics) =
      withDesktopTopicSignal(openDb.connection) { before ->
        val result = block(unitOfWork(openDb))
        onBlockCompleted()
        refreshFailure =
          runCatching {
            refreshBoardReadModelAfterSelfManagedWrite(openDb, before)
          }.exceptionOrNull()
        result
      }
    val error = refreshFailure
    if (error != null) {
      recoverFromSelfManagedProjectionFailure(openDb, changedTopics, error)
      throw error
    }
    if (changedTopics.isNotEmpty()) {
      publishDesktopCommitSignal(openDb.dbPath, openDb.connection, changedTopics, diagnostics)
    }
    return result
  }

  private fun recoverFromSelfManagedProjectionFailure(
    openDb: OpenDatabase,
    changedTopics: Set<String>,
    refreshFailure: Throwable,
  ) {
    val recovery =
      runCatching {
        openDb.connection.inDatabaseTransaction(
          DatabaseTransactionSpec(
            dbPath = openDb.dbPath,
            beginMode = DatabaseTransactionBeginMode.IMMEDIATE,
            operation = DatabaseAccessOperation.WRITE,
            diagnostics = diagnostics,
          ),
        ) {
          unitOfWork(openDb, transactionActive = true).workBoardReadModel.setComplete(false)
        }
      }
    val recoveryFailure = recovery.exceptionOrNull()
    if (recoveryFailure != null) {
      diagnostics.error("Could not invalidate the work board read model after refresh failed.", recoveryFailure)
      refreshFailure.addSuppressed(recoveryFailure)
      recoveryFailure.rethrowIfCooperativeCancellationOrInterruption()
      return
    }
    diagnostics.warning("Invalidated the work board read model after refresh failed.", refreshFailure)
    if (changedTopics.isNotEmpty()) {
      runCatching {
        publishDesktopCommitSignal(openDb.dbPath, openDb.connection, changedTopics, diagnostics)
      }.exceptionOrNull()?.let { publishFailure ->
        diagnostics.error("Could not publish the desktop commit signal after read model invalidation.", publishFailure)
        refreshFailure.addSuppressed(publishFailure)
        publishFailure.rethrowIfCooperativeCancellationOrInterruption()
      }
    }
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

  private companion object {
    const val WAL_HEADER_BYTES = 32L
    const val WAL_FRAME_HEADER_BYTES = 24
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

internal data class ChangedBoardWorkIds(val workIds: Set<String>, val requiresRebuild: Boolean)

internal fun Connection.changedBoardWorkIds(before: Map<String, Long>): ChangedBoardWorkIds {
  val topics = listOf(DesktopReadSchema.GOALS_TOPIC, DesktopReadSchema.PHASES_TOPIC, DesktopReadSchema.ACTIVITY_TOPIC)
  val current =
    prepareStatement("SELECT topic, revision FROM ${DesktopReadSchema.REVISIONS_TABLE}").use { statement ->
      statement.executeQuery().use { rows -> buildMap { while (rows.next()) put(rows.getString(1), rows.getLong(2)) } }
    }
  val requiresRebuild =
    topics.any { topic ->
      val delta = (current[topic] ?: 0L) - (before[topic] ?: 0L)
      delta > DesktopReadSchema.CHANGE_ITEM_RETENTION
    }
  if (requiresRebuild) return ChangedBoardWorkIds(emptySet(), true)
  val ids =
    buildSet {
      prepareStatement(
        "SELECT DISTINCT work_id FROM ${DesktopReadSchema.CHANGE_ITEMS_TABLE} WHERE topic = ? AND revision > ?",
      ).use { statement ->
        topics.forEach { topic ->
          statement.setString(1, topic)
          statement.setLong(2, before[topic] ?: 0L)
          statement.executeQuery().use { rows -> while (rows.next()) add(rows.getString(1)) }
        }
      }
    }
  return ChangedBoardWorkIds(ids, ids.size > WORK_BOARD_CANDIDATE_BATCH_SIZE)
}
