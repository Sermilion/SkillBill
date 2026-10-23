package skillbill.infrastructure.sqlite.core.migration.area.blockincident

import java.sql.Connection

internal object BlockIncidentOccurrenceJsonMigration {
  fun apply(connection: Connection) {
    connection.createStatement().use { statement ->
      statement.execute("ALTER TABLE block_incidents ADD COLUMN occurrence_json TEXT")
      statement.execute("ALTER TABLE block_incident_legacy_v01_occurrences ADD COLUMN occurrence_json TEXT")
      statement.execute("DROP TRIGGER block_incidents_immutable_occurrence")
      statement.execute(
        """
        CREATE TRIGGER block_incidents_immutable_occurrence
        BEFORE UPDATE OF occurrence_token, contract_version, workflow_id, parent_workflow_id,
          phase_id, phase_run_id, invocation_id, execution_id, attempt, generation, origin,
          reason, failure_code, failure_disposition, throwable_type, exit_code, observed_at,
          evidence_availability, exit_behavior, identity_availability, occurrence_json ON block_incidents
        BEGIN
          SELECT RAISE(ABORT, 'block incident occurrence is immutable');
        END
        """.trimIndent(),
      )
      statement.execute("DROP TRIGGER block_incident_legacy_v01_capture_insert")
      statement.execute(
        """
        CREATE TRIGGER block_incident_legacy_v01_capture_insert
        AFTER INSERT ON block_incidents
        WHEN NEW.contract_version = '0.1'
        BEGIN
          $ARCHIVE_SNAPSHOT_INSERT
        END
        """.trimIndent(),
      )
      statement.execute("DROP TRIGGER block_incident_legacy_v01_capture_state")
      statement.execute(
        """
        CREATE TRIGGER block_incident_legacy_v01_capture_state
        AFTER UPDATE OF diagnosis, recovery, report, revision, deadline, retry_count,
          next_eligible_at, owner_token, owner_generation, host_identity, process_id, lease_expires_at
        ON block_incidents
        WHEN NEW.contract_version = '0.1'
        BEGIN
          $ARCHIVE_SNAPSHOT_INSERT
        END
        """.trimIndent(),
      )
    }
  }

  internal val ARCHIVE_SNAPSHOT_INSERT =
    """
    INSERT INTO block_incident_legacy_v01_occurrences (
      occurrence_token, contract_version, workflow_id, parent_workflow_id, phase_id, phase_run_id,
      invocation_id, execution_id, attempt, generation, origin, reason, failure_code,
      failure_disposition, throwable_type, exit_code, observed_at, evidence_availability, diagnosis,
      recovery, report, revision, deadline, retry_count, next_eligible_at, owner_token,
      owner_generation, host_identity, process_id, lease_expires_at, exit_behavior,
      identity_availability, occurrence_json, archive_disposition
    ) VALUES (
      NEW.occurrence_token, NEW.contract_version, NEW.workflow_id, NEW.parent_workflow_id,
      NEW.phase_id, NEW.phase_run_id, NEW.invocation_id, NEW.execution_id, NEW.attempt, NEW.generation,
      NEW.origin, NEW.reason, NEW.failure_code, NEW.failure_disposition, NEW.throwable_type,
      NEW.exit_code, NEW.observed_at, NEW.evidence_availability, NEW.diagnosis, NEW.recovery, NEW.report,
      NEW.revision, NEW.deadline, NEW.retry_count, NEW.next_eligible_at, NEW.owner_token,
      NEW.owner_generation, NEW.host_identity, NEW.process_id, NEW.lease_expires_at, NEW.exit_behavior,
      NEW.identity_availability, NEW.occurrence_json, 'legacy_incomplete'
    );
    """.trimIndent()
}
