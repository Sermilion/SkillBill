package skillbill.infrastructure.sqlite.core.migration

import skillbill.infrastructure.sqlite.core.ops.bindAll
import java.sql.Connection
import java.util.UUID

internal object TelemetryOutboxDeliveryIdentityMigration {
  fun apply(connection: Connection) {
    ensureColumns(connection)
    backfillPendingIdentities(connection)
  }

  fun ensureColumns(connection: Connection) {
    DatabaseColumnMigrations.ensureColumn(connection, TABLE, "event_uuid", "TEXT")
    DatabaseColumnMigrations.ensureColumn(connection, TABLE, "delivery_attempts", "INTEGER NOT NULL DEFAULT 0")
    DatabaseColumnMigrations.ensureColumn(connection, TABLE, "claim_token", "TEXT")
    DatabaseColumnMigrations.ensureColumn(connection, TABLE, "claimed_at", "TEXT")
  }

  private fun backfillPendingIdentities(connection: Connection) {
    val ids = pendingIdsWithoutIdentity(connection)
    if (ids.isEmpty()) return
    connection.prepareStatement(
      "UPDATE $TABLE SET event_uuid = ? WHERE id = ? AND (event_uuid IS NULL OR event_uuid = '')",
    ).use { statement ->
      ids.forEach { id ->
        statement.bindAll(UUID.randomUUID().toString(), id)
        statement.addBatch()
      }
      statement.executeBatch()
    }
  }

  private fun pendingIdsWithoutIdentity(connection: Connection): List<Long> =
    connection.prepareStatement(
      "SELECT id FROM $TABLE WHERE synced_at IS NULL AND (event_uuid IS NULL OR event_uuid = '')",
    ).use { statement ->
      statement.executeQuery().use { resultSet ->
        buildList {
          while (resultSet.next()) {
            add(resultSet.getLong("id"))
          }
        }
      }
    }

  private const val TABLE = "telemetry_outbox"
}
