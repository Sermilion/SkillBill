package skillbill.infrastructure.sqlite.core.migration.area.blockincident

import skillbill.infrastructure.sqlite.core.migration.DatabaseMigration
import skillbill.infrastructure.sqlite.core.schema.addBlockIncidentRevisionTriggers

internal val blockIncidentMigrations: List<DatabaseMigration> =
  listOf(
    DatabaseMigration(
      version = 66,
      name = "add-durable-block-incidents",
      operation = { connection ->
        connection.createStatement().use { statement ->
          statement.execute(
            """
            CREATE TABLE block_incidents (
              occurrence_token TEXT PRIMARY KEY CHECK (
                length(occurrence_token) = 67 AND substr(occurrence_token, 1, 3) = 'bi_' AND
                substr(occurrence_token, 4) NOT GLOB '*[^0-9a-f]*'
              ),
              contract_version TEXT NOT NULL CHECK (contract_version = '0.1'),
              workflow_id TEXT,
              parent_workflow_id TEXT,
              phase_id TEXT,
              phase_run_id TEXT,
              invocation_id TEXT,
              execution_id TEXT,
              attempt INTEGER CHECK (attempt IS NULL OR attempt > 0),
              generation INTEGER NOT NULL CHECK (generation > 0),
              origin TEXT NOT NULL CHECK (origin IN ('accepted_phase_block', 'accepted_goal_block', 'entry_failure', 'admission_refusal')),
              reason TEXT NOT NULL CHECK (length(reason) BETWEEN 1 AND 8192),
              failure_code TEXT,
              failure_disposition TEXT,
              throwable_type TEXT,
              exit_code INTEGER,
              observed_at TEXT NOT NULL,
              evidence_availability TEXT NOT NULL CHECK (evidence_availability IN ('database', 'fallback_spool', 'unsaved')),
              diagnosis TEXT NOT NULL DEFAULT 'pending' CHECK (diagnosis IN ('pending', 'investigating', 'complete', 'partial', 'exhausted', 'timed_out', 'canceled', 'unavailable_agent', 'persist_failed', 'crashed')),
              recovery TEXT NOT NULL DEFAULT 'unavailable' CHECK (recovery IN ('unavailable', 'not_started', 'complete', 'failed')),
              report TEXT NOT NULL DEFAULT 'unavailable' CHECK (report IN ('unavailable', 'not_requested', 'pending', 'delivered', 'failed')),
              revision INTEGER NOT NULL DEFAULT 1 CHECK (revision > 0),
              deadline TEXT NOT NULL,
              retry_count INTEGER NOT NULL DEFAULT 0 CHECK (retry_count BETWEEN 0 AND 2),
              next_eligible_at TEXT,
              owner_token TEXT,
              owner_generation INTEGER,
              host_identity TEXT,
              process_id INTEGER,
              lease_expires_at TEXT,
              CHECK ((owner_token IS NULL AND owner_generation IS NULL AND host_identity IS NULL AND process_id IS NULL AND lease_expires_at IS NULL) OR
                (owner_token IS NOT NULL AND owner_generation > 0 AND host_identity IS NOT NULL AND process_id > 0 AND lease_expires_at IS NOT NULL))
            )
            """.trimIndent(),
          )
          statement.execute(
            """
            CREATE TABLE block_incident_aliases (
              alias_kind TEXT NOT NULL CHECK (alias_kind IN ('settlement', 'phase_transition', 'child_cause', 'entry_boundary')),
              alias_value TEXT NOT NULL,
              occurrence_token TEXT NOT NULL,
              PRIMARY KEY(alias_kind, alias_value),
              FOREIGN KEY(occurrence_token) REFERENCES block_incidents(occurrence_token) ON DELETE CASCADE
            )
            """.trimIndent(),
          )
          statement.execute(
            "CREATE INDEX idx_block_incidents_pending ON block_incidents(diagnosis, next_eligible_at, deadline)",
          )
          statement.execute("CREATE INDEX idx_block_incidents_workflow ON block_incidents(workflow_id, observed_at)")
          statement.execute(
            """
            CREATE TRIGGER block_incidents_immutable_occurrence
            BEFORE UPDATE OF occurrence_token, contract_version, workflow_id, parent_workflow_id,
              phase_id, phase_run_id, invocation_id, execution_id, attempt, generation, origin,
              reason, failure_code, failure_disposition, throwable_type, exit_code, observed_at,
              evidence_availability ON block_incidents
            BEGIN
              SELECT RAISE(ABORT, 'block incident occurrence is immutable');
            END
            """.trimIndent(),
          )
        }
        addBlockIncidentRevisionTriggers(connection)
      },
    ),
    DatabaseMigration(
      version = 67,
      name = "persist-block-incident-exit-behavior",
      operation = { connection ->
        connection.createStatement().use { statement ->
          statement.execute(
            "ALTER TABLE block_incidents ADD COLUMN exit_behavior TEXT NOT NULL " +
              "DEFAULT 'unknown_historical' CHECK (exit_behavior IN " +
              "('returned_failure', 'thrown', 'canceled', 'interrupted', 'exited_with_code', " +
              "'exit_code_unavailable', 'unknown_historical'))",
          )
          statement.execute("DROP TRIGGER block_incidents_immutable_occurrence")
          statement.execute(
            """
            CREATE TRIGGER block_incidents_immutable_occurrence
            BEFORE UPDATE OF occurrence_token, contract_version, workflow_id, parent_workflow_id,
              phase_id, phase_run_id, invocation_id, execution_id, attempt, generation, origin,
              reason, failure_code, failure_disposition, throwable_type, exit_code, observed_at,
              evidence_availability, exit_behavior ON block_incidents
            BEGIN
              SELECT RAISE(ABORT, 'block incident occurrence is immutable');
            END
            """.trimIndent(),
          )
        }
      },
    ),
    DatabaseMigration(
      version = 68,
      name = "retain-block-incident-alias-reservations",
      operation = { connection ->
        connection.createStatement().use { statement ->
          statement.execute(
            """
            CREATE TRIGGER block_incident_aliases_immutable_update
            BEFORE UPDATE ON block_incident_aliases
            BEGIN
              SELECT RAISE(ABORT, 'block incident alias reservation is immutable');
            END
            """.trimIndent(),
          )
          statement.execute(
            """
            CREATE TRIGGER block_incident_aliases_immutable_delete
            BEFORE DELETE ON block_incident_aliases
            BEGIN
              SELECT RAISE(ABORT, 'block incident alias reservation is retained');
            END
            """.trimIndent(),
          )
          statement.execute(
            """
            CREATE TRIGGER block_incidents_retain_occurrence
            BEFORE DELETE ON block_incidents
            BEGIN
              SELECT RAISE(ABORT, 'block incident occurrence is retained');
            END
            """.trimIndent(),
          )
        }
      },
    ),
    DatabaseMigration(
      version = 69,
      name = "persist-block-incident-identity-availability",
      operation = { connection ->
        connection.createStatement().use { statement ->
          statement.execute(
            "ALTER TABLE block_incidents ADD COLUMN identity_availability TEXT NOT NULL " +
              "DEFAULT 'unknown_historical' CHECK (identity_availability IN " +
              "('available', 'partial', 'unavailable', 'unknown_historical'))",
          )
          statement.execute("DROP TRIGGER block_incidents_immutable_occurrence")
          statement.execute(
            """
            CREATE TRIGGER block_incidents_immutable_occurrence
            BEFORE UPDATE OF occurrence_token, contract_version, workflow_id, parent_workflow_id,
              phase_id, phase_run_id, invocation_id, execution_id, attempt, generation, origin,
              reason, failure_code, failure_disposition, throwable_type, exit_code, observed_at,
              evidence_availability, exit_behavior, identity_availability ON block_incidents
            BEGIN
              SELECT RAISE(ABORT, 'block incident occurrence is immutable');
            END
            """.trimIndent(),
          )
        }
      },
    ),
    DatabaseMigration(
      version = 70,
      name = "archive-incomplete-block-incident-v01-records",
      operation = { connection ->
        connection.createStatement().use { statement ->
          statement.execute(
            """
            CREATE TABLE block_incident_legacy_v01_occurrences AS
            SELECT *, 'legacy_incomplete' AS archive_disposition
            FROM block_incidents
            WHERE contract_version = '0.1'
            """.trimIndent(),
          )
          statement.execute(
            """
            CREATE TABLE block_incident_legacy_v01_aliases AS
            SELECT aliases.*, incidents.contract_version AS archived_contract_version,
              'legacy_incomplete' AS archive_disposition
            FROM block_incident_aliases AS aliases
            JOIN block_incidents AS incidents USING (occurrence_token)
            WHERE incidents.contract_version = '0.1'
            """.trimIndent(),
          )
          statement.execute(
            """
            CREATE TRIGGER block_incident_legacy_v01_occurrences_immutable_update
            BEFORE UPDATE ON block_incident_legacy_v01_occurrences
            BEGIN
              SELECT RAISE(ABORT, 'legacy block incident archive is immutable');
            END
            """.trimIndent(),
          )
          statement.execute(
            """
            CREATE TRIGGER block_incident_legacy_v01_occurrences_immutable_delete
            BEFORE DELETE ON block_incident_legacy_v01_occurrences
            BEGIN
              SELECT RAISE(ABORT, 'legacy block incident archive is retained');
            END
            """.trimIndent(),
          )
          statement.execute(
            """
            CREATE TRIGGER block_incident_legacy_v01_aliases_immutable_update
            BEFORE UPDATE ON block_incident_legacy_v01_aliases
            BEGIN
              SELECT RAISE(ABORT, 'legacy block incident alias archive is immutable');
            END
            """.trimIndent(),
          )
          statement.execute(
            """
            CREATE TRIGGER block_incident_legacy_v01_aliases_immutable_delete
            BEFORE DELETE ON block_incident_legacy_v01_aliases
            BEGIN
              SELECT RAISE(ABORT, 'legacy block incident alias archive is retained');
            END
            """.trimIndent(),
          )
        }
      },
    ),
    DatabaseMigration(
      version = 71,
      name = "retain-later-block-incident-v01-archive-snapshots",
      operation = { connection ->
        connection.createStatement().use { statement ->
          statement.execute(
            """
            CREATE TRIGGER block_incident_legacy_v01_capture_insert
            AFTER INSERT ON block_incidents
            WHEN NEW.contract_version = '0.1'
            BEGIN
              INSERT INTO block_incident_legacy_v01_occurrences (
                occurrence_token, contract_version, workflow_id, parent_workflow_id, phase_id, phase_run_id,
                invocation_id, execution_id, attempt, generation, origin, reason, failure_code,
                failure_disposition, throwable_type, exit_code, observed_at, evidence_availability, diagnosis,
                recovery, report, revision, deadline, retry_count, next_eligible_at, owner_token,
                owner_generation, host_identity, process_id, lease_expires_at, exit_behavior,
                identity_availability, archive_disposition
              ) VALUES (
                NEW.occurrence_token, NEW.contract_version, NEW.workflow_id, NEW.parent_workflow_id,
                NEW.phase_id, NEW.phase_run_id, NEW.invocation_id, NEW.execution_id, NEW.attempt, NEW.generation,
                NEW.origin, NEW.reason, NEW.failure_code, NEW.failure_disposition, NEW.throwable_type,
                NEW.exit_code, NEW.observed_at, NEW.evidence_availability, NEW.diagnosis, NEW.recovery, NEW.report,
                NEW.revision, NEW.deadline, NEW.retry_count, NEW.next_eligible_at, NEW.owner_token,
                NEW.owner_generation, NEW.host_identity, NEW.process_id, NEW.lease_expires_at, NEW.exit_behavior,
                NEW.identity_availability, 'legacy_incomplete'
              );
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
              INSERT INTO block_incident_legacy_v01_occurrences (
                occurrence_token, contract_version, workflow_id, parent_workflow_id, phase_id, phase_run_id,
                invocation_id, execution_id, attempt, generation, origin, reason, failure_code,
                failure_disposition, throwable_type, exit_code, observed_at, evidence_availability, diagnosis,
                recovery, report, revision, deadline, retry_count, next_eligible_at, owner_token,
                owner_generation, host_identity, process_id, lease_expires_at, exit_behavior,
                identity_availability, archive_disposition
              ) VALUES (
                NEW.occurrence_token, NEW.contract_version, NEW.workflow_id, NEW.parent_workflow_id,
                NEW.phase_id, NEW.phase_run_id, NEW.invocation_id, NEW.execution_id, NEW.attempt, NEW.generation,
                NEW.origin, NEW.reason, NEW.failure_code, NEW.failure_disposition, NEW.throwable_type,
                NEW.exit_code, NEW.observed_at, NEW.evidence_availability, NEW.diagnosis, NEW.recovery, NEW.report,
                NEW.revision, NEW.deadline, NEW.retry_count, NEW.next_eligible_at, NEW.owner_token,
                NEW.owner_generation, NEW.host_identity, NEW.process_id, NEW.lease_expires_at, NEW.exit_behavior,
                NEW.identity_availability, 'legacy_incomplete'
              );
            END
            """.trimIndent(),
          )
          statement.execute(
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
            """.trimIndent(),
          )
        }
      },
    ),
    DatabaseMigration(
      version = 72,
      name = "persist-full-block-incident-occurrence",
      operation = BlockIncidentOccurrenceJsonMigration::apply,
    ),
    DatabaseMigration(
      version = 73,
      name = "accept-block-incident-contract-v02",
      operation = BlockIncidentContractV02Migration::apply,
    ),
    DatabaseMigration(
      version = 74,
      name = "track-block-incident-scan-progress",
      operation = BlockIncidentScanProgressMigration::apply,
    ),
    DatabaseMigration(
      version = 75,
      name = "record-uncaptured-block-incident-generation",
      operation = BlockIncidentNullableGenerationMigration::apply,
    ),
    DatabaseMigration(
      version = 76,
      name = "activate-block-incident-rollout",
      operation = BlockIncidentRolloutMigration::apply,
    ),
    DatabaseMigration(
      version = 77,
      name = "add-block-incident-diagnoses",
      operation = BlockIncidentDiagnosisMigration::apply,
    ),
  )
