package skillbill.ports.db

import skillbill.ports.db.model.DatabaseCheckpointResult

fun interface DatabaseCheckpoint {
  /** Truncates the WAL outside a transaction, returns busy without throwing, and never creates a missing database. */
  fun truncateWal(): DatabaseCheckpointResult
}
