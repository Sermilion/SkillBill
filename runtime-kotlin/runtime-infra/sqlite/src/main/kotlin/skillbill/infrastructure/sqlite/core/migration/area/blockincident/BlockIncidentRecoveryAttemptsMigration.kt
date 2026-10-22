package skillbill.infrastructure.sqlite.core.migration.area.blockincident

import skillbill.infrastructure.sqlite.core.schema.addBlockIncidentRecoveryRevisionTriggers
import java.sql.Connection

internal object BlockIncidentRecoveryAttemptsMigration {
  fun apply(connection: Connection) {
    connection.createStatement().use { statement ->
      statement.execute(
        """
        CREATE TABLE block_incident_recovery_attempts (
          attempt_id TEXT PRIMARY KEY CHECK (attempt_id GLOB 'bra_*'),
          occurrence_token TEXT NOT NULL REFERENCES block_incidents(occurrence_token),
          action TEXT NOT NULL,
          incident_revision INTEGER NOT NULL CHECK (incident_revision > 0),
          idempotency_key TEXT NOT NULL UNIQUE,
          status TEXT NOT NULL,
          failure TEXT,
          owner_token TEXT,
          owner_generation INTEGER,
          owner_expires_at TEXT,
          requested_at TEXT NOT NULL,
          updated_at TEXT NOT NULL,
          target_workflow_id TEXT NOT NULL,
          target_invocation_id TEXT,
          CHECK ((owner_token IS NULL AND owner_generation IS NULL AND owner_expires_at IS NULL) OR
            (owner_token IS NOT NULL AND owner_generation > 0 AND owner_expires_at IS NOT NULL))
        )
        """.trimIndent(),
      )
      statement.execute(
        "CREATE INDEX idx_block_incident_recovery_occurrence ON block_incident_recovery_attempts(occurrence_token, status)",
      )
      statement.execute(
        "CREATE INDEX idx_block_incident_recovery_interrupted ON block_incident_recovery_attempts(status, owner_expires_at)",
      )
    }
    addBlockIncidentRecoveryRevisionTriggers(connection)
  }
}
