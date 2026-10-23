package skillbill.infrastructure.sqlite.core.migration.area.blockincident

import java.sql.Connection

internal object BlockIncidentScanProgressMigration {
  fun apply(connection: Connection) {
    connection.createStatement().use { statement ->
      statement.execute(
        """
        CREATE TABLE block_incident_scan_cursors (
          diagnosis TEXT PRIMARY KEY CHECK (diagnosis IN ('pending', 'investigating')),
          observed_at TEXT NOT NULL,
          occurrence_token TEXT NOT NULL
        )
        """.trimIndent(),
      )
      statement.execute(
        """
        CREATE TABLE block_incident_quarantine (
          occurrence_token TEXT PRIMARY KEY,
          failure_code TEXT NOT NULL,
          failure_message TEXT NOT NULL CHECK (length(failure_message) <= 1024),
          raw_row_digest TEXT NOT NULL,
          quarantined_at TEXT NOT NULL
        )
        """.trimIndent(),
      )
      statement.execute(
        """
        CREATE TRIGGER block_incident_quarantine_immutable
        BEFORE UPDATE ON block_incident_quarantine
        BEGIN
          SELECT RAISE(ABORT, 'block incident quarantine records are immutable');
        END
        """.trimIndent(),
      )
    }
  }
}
