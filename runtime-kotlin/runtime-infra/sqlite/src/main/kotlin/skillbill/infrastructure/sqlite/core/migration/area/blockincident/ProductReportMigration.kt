package skillbill.infrastructure.sqlite.core.migration.area.blockincident

import skillbill.infrastructure.sqlite.core.schema.addProductReportRevisionTriggers
import java.sql.Connection

internal object ProductReportMigration {
  fun apply(connection: Connection) {
    connection.createStatement().use { statement ->
      statement.execute("ALTER TABLE block_incident_diagnoses ADD COLUMN reproduction_steps TEXT")
      statement.execute("ALTER TABLE block_incident_diagnoses ADD COLUMN provider_id TEXT")
      statement.execute("ALTER TABLE block_incident_diagnoses ADD COLUMN phase_strategy_id TEXT")
      statement.execute("ALTER TABLE block_incident_diagnoses ADD COLUMN model_id TEXT")
      statement.execute(
        """
        CREATE TABLE block_incident_reports (
          report_id TEXT PRIMARY KEY CHECK (report_id GLOB 'bir_*'),
          fingerprint TEXT NOT NULL UNIQUE CHECK (length(fingerprint) = 64),
          status TEXT NOT NULL,
          failure_reason TEXT,
          attempts INTEGER NOT NULL DEFAULT 0 CHECK (attempts >= 0),
          next_attempt_at TEXT,
          issue_number INTEGER,
          issue_url TEXT,
          body TEXT NOT NULL,
          body_sha256 TEXT NOT NULL CHECK (length(body_sha256) = 64),
          redaction_outcomes TEXT NOT NULL,
          created_at TEXT NOT NULL,
          updated_at TEXT NOT NULL
        )
        """.trimIndent(),
      )
      statement.execute(
        """
        CREATE TABLE block_incident_report_links (
          report_id TEXT NOT NULL REFERENCES block_incident_reports(report_id) ON DELETE CASCADE,
          occurrence_token TEXT NOT NULL REFERENCES block_incidents(occurrence_token),
          comment_state TEXT NOT NULL,
          PRIMARY KEY (report_id, occurrence_token)
        )
        """.trimIndent(),
      )
      statement.execute(
        "CREATE INDEX idx_block_incident_reports_due ON block_incident_reports(status, next_attempt_at, updated_at)",
      )
    }
    addProductReportRevisionTriggers(connection)
  }
}
