package skillbill.infrastructure.sqlite.core.ops

import skillbill.contracts.persistence.DesktopReadSchema
import skillbill.error.core.DatabaseAccessOperation
import skillbill.infrastructure.sqlite.core.schema.OpenDatabase
import skillbill.infrastructure.sqlite.core.schema.fileObservation
import skillbill.ports.diagnostics.RuntimeDiagnostics
import java.sql.Connection

internal const val READ_CONNECTION_POOL_SIZE: Int = 2

internal class PooledReadConnection(
  val database: OpenDatabase,
  val generation: String?,
  val epoch: Long,
  val pooled: Boolean,
)

internal class ReadConnectionLease(
  val connection: PooledReadConnection,
  val reused: Boolean,
) {
  val database: OpenDatabase get() = connection.database
}

internal class ReadConnectionPool(private val diagnostics: RuntimeDiagnostics) {
  private val lock = Any()
  private val idle = ArrayDeque<PooledReadConnection>()
  private var openCount = 0
  private var epoch = 0L

  fun lease(open: () -> OpenDatabase?): ReadConnectionLease? {
    val (pooled, leaseEpoch) =
      synchronized(lock) {
        idle.removeFirstOrNull()?.let { return ReadConnectionLease(it, reused = true) }
        val pooled = openCount < READ_CONNECTION_POOL_SIZE
        if (pooled) openCount++
        pooled to epoch
      }
    val database =
      runCatching(open).getOrElse { failure ->
        if (pooled) freeSlot()
        throw failure
      }
    if (database == null) {
      if (pooled) freeSlot()
      return null
    }
    val generation =
      runCatching { database.connection.storedDesktopGeneration() }.getOrElse { failure ->
        if (pooled) freeSlot()
        close(database)
        throw failure
      }
    return ReadConnectionLease(PooledReadConnection(database, generation, leaseEpoch, pooled), reused = false)
  }

  fun isCurrentFile(lease: ReadConnectionLease): Boolean {
    val database = lease.database
    return runCatching {
      fileObservation(database.dbPath, DatabaseAccessOperation.READ) == database.fileObservation
    }.getOrElse { failure ->
      diagnostics.info("Pooled read connection target is unreadable (${failure::class.simpleName}); reopening.")
      false
    }
  }

  fun isCurrentGeneration(
    connection: Connection,
    lease: ReadConnectionLease,
  ): Boolean {
    val recorded = lease.connection.generation ?: return false
    return connection.storedDesktopGeneration() == recorded
  }

  fun giveBack(lease: ReadConnectionLease) {
    val entry = lease.connection
    if (!entry.pooled) {
      close(entry.database)
      return
    }
    val kept =
      synchronized(lock) {
        if (entry.epoch == epoch) {
          idle.addLast(entry)
          true
        } else {
          openCount--
          false
        }
      }
    if (!kept) close(entry.database)
  }

  fun discard(lease: ReadConnectionLease) {
    if (lease.connection.pooled) freeSlot()
    close(lease.database)
  }

  fun invalidate() {
    val drained =
      synchronized(lock) {
        epoch++
        val drained = idle.toList()
        idle.clear()
        openCount -= drained.size
        drained
      }
    drained.forEach { close(it.database) }
  }

  private fun freeSlot() {
    synchronized(lock) { openCount-- }
  }

  private fun close(database: OpenDatabase) {
    runCatching { database.close() }.onFailure { failure ->
      diagnostics.warning("Pooled read connection close failed.", failure)
    }
  }
}

internal fun Connection.storedDesktopGeneration(): String? =
  prepareStatement("SELECT generation FROM ${DesktopReadSchema.GENERATION_TABLE} WHERE id = 1").use { statement ->
    statement.executeQuery().use { rows -> if (rows.next()) rows.getString(1) else null }
  }
