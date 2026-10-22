package skillbill.infrastructure.sqlite.core.migration.area.blockincident

import skillbill.infrastructure.sqlite.core.schema.addBlockIncidentRevisionTriggers
import java.sql.Connection

internal object BlockIncidentContractV02Migration {
  fun apply(connection: Connection) {
    connection.createStatement().use { statement ->
      statement.execute("ALTER TABLE block_incident_aliases RENAME TO block_incident_aliases_pre_v02")
      statement.execute("ALTER TABLE block_incidents RENAME TO block_incidents_pre_v02")
      statement.execute(INCIDENTS_TABLE)
      statement.execute(ALIASES_TABLE)
      statement.execute(
        "INSERT INTO block_incidents ($INCIDENT_COLUMNS) SELECT $INCIDENT_COLUMNS FROM block_incidents_pre_v02",
      )
      statement.execute(
        "INSERT INTO block_incident_aliases (alias_kind, alias_value, occurrence_token) " +
          "SELECT alias_kind, alias_value, occurrence_token FROM block_incident_aliases_pre_v02",
      )
      statement.execute("DROP TABLE block_incident_aliases_pre_v02")
      statement.execute("DROP TABLE block_incidents_pre_v02")
      statement.execute(
        "CREATE INDEX idx_block_incidents_pending ON block_incidents(diagnosis, next_eligible_at, deadline)",
      )
      statement.execute("CREATE INDEX idx_block_incidents_workflow ON block_incidents(workflow_id, observed_at)")
      statement.execute(
        "CREATE INDEX idx_block_incidents_scan ON block_incidents(contract_version, diagnosis, observed_at, " +
          "occurrence_token)",
      )
      PROTECTION_TRIGGERS.forEach(statement::execute)
      statement.execute(LEGACY_ALIAS_CAPTURE_TRIGGER)
      statement.execute(
        """
        CREATE TRIGGER block_incident_legacy_v01_capture_insert
        AFTER INSERT ON block_incidents
        WHEN NEW.contract_version = '0.1'
        BEGIN
          ${BlockIncidentOccurrenceJsonMigration.ARCHIVE_SNAPSHOT_INSERT}
        END
        """.trimIndent(),
      )
      statement.execute(
        """
        CREATE TRIGGER block_incident_legacy_v01_capture_state
        AFTER UPDATE OF diagnosis, recovery, report, revision, deadline, retry_count,
          next_eligible_at, owner_token, owner_generation, host_identity, process_id, lease_expires_at
        ON block_incidents
        WHEN NEW.contract_version = '0.1'
        BEGIN
          ${BlockIncidentOccurrenceJsonMigration.ARCHIVE_SNAPSHOT_INSERT}
        END
        """.trimIndent(),
      )
    }
    addBlockIncidentRevisionTriggers(connection)
  }

  private const val INCIDENT_COLUMNS =
    "occurrence_token, contract_version, workflow_id, parent_workflow_id, phase_id, phase_run_id, " +
      "invocation_id, execution_id, attempt, generation, origin, reason, failure_code, failure_disposition, " +
      "throwable_type, exit_code, observed_at, evidence_availability, diagnosis, recovery, report, revision, " +
      "deadline, retry_count, next_eligible_at, owner_token, owner_generation, host_identity, process_id, " +
      "lease_expires_at, exit_behavior, identity_availability, occurrence_json"

  private val INCIDENTS_TABLE =
    """
    CREATE TABLE block_incidents (
      occurrence_token TEXT PRIMARY KEY CHECK (
        length(occurrence_token) = 67 AND substr(occurrence_token, 1, 3) = 'bi_' AND
        substr(occurrence_token, 4) NOT GLOB '*[^0-9a-f]*'
      ),
      contract_version TEXT NOT NULL CHECK (contract_version IN ('0.1', '0.2')),
      workflow_id TEXT,
      parent_workflow_id TEXT,
      phase_id TEXT,
      phase_run_id TEXT,
      invocation_id TEXT,
      execution_id TEXT,
      attempt INTEGER CHECK (attempt IS NULL OR attempt > 0),
      generation INTEGER NOT NULL CHECK (generation > 0),
      origin TEXT NOT NULL CHECK (origin IN
        ('accepted_phase_block', 'accepted_goal_block', 'entry_failure', 'admission_refusal')),
      reason TEXT NOT NULL CHECK (length(reason) BETWEEN 1 AND 8192),
      failure_code TEXT,
      failure_disposition TEXT,
      throwable_type TEXT,
      exit_code INTEGER,
      observed_at TEXT NOT NULL,
      evidence_availability TEXT NOT NULL CHECK (evidence_availability IN ('database', 'fallback_spool', 'unsaved')),
      diagnosis TEXT NOT NULL DEFAULT 'pending' CHECK (diagnosis IN ('pending', 'investigating', 'complete',
        'partial', 'exhausted', 'timed_out', 'canceled', 'unavailable_agent', 'persist_failed', 'crashed')),
      recovery TEXT NOT NULL DEFAULT 'unavailable' CHECK (recovery IN
        ('unavailable', 'not_started', 'complete', 'failed')),
      report TEXT NOT NULL DEFAULT 'unavailable' CHECK (report IN
        ('unavailable', 'not_requested', 'pending', 'delivered', 'failed')),
      revision INTEGER NOT NULL DEFAULT 1 CHECK (revision > 0),
      deadline TEXT NOT NULL,
      retry_count INTEGER NOT NULL DEFAULT 0 CHECK (retry_count BETWEEN 0 AND 2),
      next_eligible_at TEXT,
      owner_token TEXT,
      owner_generation INTEGER,
      host_identity TEXT,
      process_id INTEGER,
      lease_expires_at TEXT,
      exit_behavior TEXT NOT NULL CHECK (exit_behavior IN ('returned_failure', 'thrown', 'canceled',
        'interrupted', 'exited_with_code', 'exit_code_unavailable', 'unknown_historical')),
      identity_availability TEXT NOT NULL CHECK (identity_availability IN
        ('available', 'partial', 'unavailable', 'unknown_historical')),
      occurrence_json TEXT,
      CHECK (contract_version = '0.1' OR (occurrence_json IS NOT NULL AND
        exit_behavior <> 'unknown_historical' AND identity_availability <> 'unknown_historical')),
      CHECK ((owner_token IS NULL AND owner_generation IS NULL AND host_identity IS NULL AND process_id IS NULL AND
          lease_expires_at IS NULL) OR
        (owner_token IS NOT NULL AND owner_generation > 0 AND host_identity IS NOT NULL AND process_id > 0 AND
          lease_expires_at IS NOT NULL))
    )
    """.trimIndent()

  private val ALIASES_TABLE =
    """
    CREATE TABLE block_incident_aliases (
      alias_kind TEXT NOT NULL CHECK (alias_kind IN
        ('settlement', 'phase_transition', 'child_cause', 'entry_boundary')),
      alias_value TEXT NOT NULL,
      occurrence_token TEXT NOT NULL,
      PRIMARY KEY(alias_kind, alias_value),
      FOREIGN KEY(occurrence_token) REFERENCES block_incidents(occurrence_token)
    )
    """.trimIndent()

  private val LEGACY_ALIAS_CAPTURE_TRIGGER =
    """
    CREATE TRIGGER block_incident_legacy_v01_capture_alias
    AFTER INSERT ON block_incident_aliases
    WHEN EXISTS (
      SELECT 1 FROM block_incidents
      WHERE occurrence_token = NEW.occurrence_token AND contract_version = '0.1'
    )
    BEGIN
      INSERT INTO block_incident_legacy_v01_aliases (
        alias_kind, alias_value, occurrence_token, archived_contract_version, archive_disposition
      ) VALUES (NEW.alias_kind, NEW.alias_value, NEW.occurrence_token, '0.1', 'legacy_incomplete');
    END
    """.trimIndent()

  private val PROTECTION_TRIGGERS =
    listOf(
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
      """
      CREATE TRIGGER block_incidents_retain_occurrence
      BEFORE DELETE ON block_incidents
      BEGIN
        SELECT RAISE(ABORT, 'block incident occurrence is retained');
      END
      """.trimIndent(),
      """
      CREATE TRIGGER block_incident_aliases_immutable_update
      BEFORE UPDATE ON block_incident_aliases
      BEGIN
        SELECT RAISE(ABORT, 'block incident alias reservation is immutable');
      END
      """.trimIndent(),
      """
      CREATE TRIGGER block_incident_aliases_immutable_delete
      BEFORE DELETE ON block_incident_aliases
      BEGIN
        SELECT RAISE(ABORT, 'block incident alias reservation is retained');
      END
      """.trimIndent(),
    )
}
