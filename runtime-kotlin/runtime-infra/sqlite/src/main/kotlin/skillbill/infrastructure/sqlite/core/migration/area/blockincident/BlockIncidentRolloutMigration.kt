package skillbill.infrastructure.sqlite.core.migration.area.blockincident

import java.sql.Connection

internal object BlockIncidentRolloutMigration {
  private const val ACTIVATED_SCHEMA_VERSION = 76

  fun apply(connection: Connection) {
    connection.createStatement().use { statement ->
      statement.execute(
        "CREATE TABLE IF NOT EXISTS block_incident_rollout (id INTEGER PRIMARY KEY CHECK (id = 1), " +
          "activated_at TEXT NOT NULL, activated_schema_version INTEGER NOT NULL)",
      )
      statement.execute(
        "INSERT OR IGNORE INTO block_incident_rollout(id, activated_at, activated_schema_version) " +
          "VALUES (1, strftime('%Y-%m-%dT%H:%M:%fZ', 'now'), $ACTIVATED_SCHEMA_VERSION)",
      )
    }
  }
}
