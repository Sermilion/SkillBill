package skillbill.infrastructure.sqlite

import skillbill.contracts.persistence.DesktopReadSchema
import skillbill.idestatus.model.IdeStatusPolicy
import skillbill.infrastructure.sqlite.core.migration.DatabaseColumnMigrations
import skillbill.infrastructure.sqlite.core.migration.DatabaseMigration
import skillbill.infrastructure.sqlite.core.migration.addDelegatedReviewLifecycleProjection
import skillbill.infrastructure.sqlite.core.migration.addGoalRunnerControlState
import skillbill.infrastructure.sqlite.core.migration.addGoalRunnerControls
import skillbill.infrastructure.sqlite.core.migration.addReviewFindingOutcomeKey
import skillbill.infrastructure.sqlite.core.migration.addReviewRunLaneAttribution
import skillbill.infrastructure.sqlite.core.migration.area.BlockIncidentContractV02Migration
import skillbill.infrastructure.sqlite.core.migration.area.BlockIncidentScanProgressMigration
import skillbill.infrastructure.sqlite.core.migration.area.BlockIncidentOccurrenceJsonMigration
import skillbill.infrastructure.sqlite.core.migration.area.FeatureTaskPhaseSettlementsMigration
import skillbill.infrastructure.sqlite.core.migration.area.FeatureTaskPhaseStrategyTelemetryMigration
import skillbill.infrastructure.sqlite.core.migration.area.FeedbackEventMigration
import skillbill.infrastructure.sqlite.core.migration.area.GoalNoChangeReasonMigration
import skillbill.infrastructure.sqlite.core.migration.area.GoalTelemetryMigration
import skillbill.infrastructure.sqlite.core.migration.area.OperationProposalsMigration
import skillbill.infrastructure.sqlite.core.migration.area.ReviewAttributionBackfillMigration
import skillbill.infrastructure.sqlite.core.migration.area.TelemetryOutboxDeliveryIdentityMigration
import skillbill.infrastructure.sqlite.core.migration.area.TelemetryOutboxLastErrorMigration
import skillbill.infrastructure.sqlite.core.migration.area.idestatus.StandalonePhaseStatusMigration
import skillbill.infrastructure.sqlite.core.migration.area.idestatus.recordFeatureTaskQaCriteriaSummary
import skillbill.infrastructure.sqlite.core.migration.area.idestatus.recordFeatureTaskQaFindingsSummary
import skillbill.infrastructure.sqlite.core.migration.area.idestatus.recordFeatureTaskQaOutcomes
import skillbill.infrastructure.sqlite.core.migration.area.idestatus.recordStandaloneMonitorTarget
import skillbill.infrastructure.sqlite.core.migration.area.idestatus.recordStandalonePlanReport
import skillbill.infrastructure.sqlite.core.migration.area.idestatus.recordStandaloneQaOutcome
import skillbill.infrastructure.sqlite.core.migration.area.idestatus.recordStandaloneQaReport
import skillbill.infrastructure.sqlite.core.migration.area.optionalRepairEvidenceColumn
import skillbill.infrastructure.sqlite.core.migration.area.rebuildGoalPlanningPlansForPhaseOutputVersion2
import skillbill.infrastructure.sqlite.core.migration.area.rebuildGoalPlanningPlansForPhaseOutputVersion4
import skillbill.infrastructure.sqlite.core.migration.area.rebuildGoalPlanningPlansForPhaseOutputVersion5
import skillbill.infrastructure.sqlite.core.migration.area.rebuildGoalPlanningPlansForPhaseOutputVersion6
import skillbill.infrastructure.sqlite.core.migration.area.rebuildGoalPlanningPlansForPhaseOutputVersion7
import skillbill.infrastructure.sqlite.core.migration.area.rekeyDiagnosticEvidenceByRepairTurn
import skillbill.infrastructure.sqlite.core.migration.area.requireGoalPlanningPhaseOutputVersion2
import skillbill.infrastructure.sqlite.core.migration.dropDelegatedReviewLifecycleTables
import skillbill.infrastructure.sqlite.core.migration.ensureSchemaColumnsAndHeals
import skillbill.infrastructure.sqlite.core.migration.persistGoalPlanningRepairEvidence
import skillbill.infrastructure.sqlite.core.migration.persistLegacyGoalPlanningRepairEvidence
import skillbill.infrastructure.sqlite.core.migration.rekeyProducerOutputEvidenceByAgent
import skillbill.infrastructure.sqlite.core.schema.DatabaseReviewColumnMigrations
import skillbill.infrastructure.sqlite.core.schema.installDesktopReadCompatibility
import skillbill.infrastructure.sqlite.core.schema.addBlockIncidentRevisionTriggers
import skillbill.infrastructure.sqlite.core.schema.recordLaunchAgentEnvironment
import skillbill.infrastructure.sqlite.core.schema.recordStandalonePhaseLaunchScope
import skillbill.infrastructure.sqlite.core.schema.saveStandalonePhaseLaunch
import skillbill.infrastructure.sqlite.core.schema.separateAgentActivityRevisions
import skillbill.infrastructure.sqlite.review.stats.migrateLegacyTelemetryOutboxLedger
import skillbill.infrastructure.sqlite.workflow.goalrunner.runner.applyLegacyGoalRunnerControlLedgerMigration

internal val databaseMigrations: List<DatabaseMigration> =
  listOf(
    DatabaseMigration(
      version = 1,
      name = "add-review-workflow-session-columns",
      operation = DatabaseColumnMigrations::apply,
    ),
    DatabaseMigration(
      version = 2,
      name = "normalize-feedback-event-outcomes",
      operation = FeedbackEventMigration::apply,
    ),
    DatabaseMigration(
      version = 3,
      name = "add-goal-telemetry-tables",
      operation = GoalTelemetryMigration::apply,
    ),
    DatabaseMigration(
      version = 4,
      name = "add-work-list-state-metadata",
      operation = DatabaseColumnMigrations::applyWorkListMetadata,
    ),
    DatabaseMigration(
      version = 5,
      name = "recover-work-list-issue-keys",
      operation = DatabaseColumnMigrations::recoverWorkListIssueKeys,
    ),
    DatabaseMigration(
      version = 6,
      name = "add-feature-task-execution-identities",
      operation = { connection ->
        connection.createStatement().use { statement ->
          statement.execute(
            """
              CREATE TABLE IF NOT EXISTS feature_task_execution_identities (
                workflow_id TEXT PRIMARY KEY,
                contract_version TEXT NOT NULL CHECK (contract_version = '0.1'),
                normalized_issue_key TEXT NOT NULL,
                repository_identity TEXT NOT NULL,
                governed_spec_path TEXT NOT NULL,
                mode TEXT NOT NULL CHECK (mode IN ('prose', 'runtime')),
                route_scope TEXT NOT NULL CHECK (route_scope IN ('standalone', 'goal_child')),
                created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP${
              connection.optionalRepairEvidenceColumn("goal_shared_preplans_pre_0_2")
            },
                FOREIGN KEY (workflow_id) REFERENCES feature_task_workflows(workflow_id) ON DELETE CASCADE
              )
            """.trimIndent(),
          )
          statement.execute(
            """
            CREATE INDEX IF NOT EXISTS idx_feature_task_identity_lookup
              ON feature_task_execution_identities(normalized_issue_key, repository_identity, route_scope)
            """.trimIndent(),
          )
        }
      },
    ),
    DatabaseMigration(
      version = 7,
      name = "add-feature-task-runtime-worker-leases",
      operation = { connection ->
        connection.createStatement().use { statement ->
          statement.execute(
            """
            CREATE TABLE IF NOT EXISTS feature_task_runtime_worker_leases (
              workflow_id TEXT PRIMARY KEY,
              contract_version TEXT NOT NULL CHECK (contract_version = '0.1'),
              generation INTEGER NOT NULL CHECK (generation > 0),
              owner_token TEXT NOT NULL,
              host_identity TEXT NOT NULL,
              boot_identity TEXT NOT NULL,
              pid INTEGER NOT NULL CHECK (pid > 0),
              process_birth_token TEXT NOT NULL,
              lease_state TEXT NOT NULL CHECK (lease_state IN ('active', 'takeover_reserved')),
              heartbeat_at TEXT NOT NULL,
              expires_at TEXT NOT NULL,
              phase_id TEXT NOT NULL,
              phase_attempt INTEGER NOT NULL CHECK (phase_attempt > 0),
              FOREIGN KEY (workflow_id) REFERENCES feature_task_workflows(workflow_id) ON DELETE CASCADE
            )
            """.trimIndent(),
          )
        }
      },
    ),
    DatabaseMigration(
      version = 8,
      name = "add-goal-planning-preparations",
      operation = { connection ->
        connection.createStatement().use { statement ->
          statement.execute(
            """
              CREATE TABLE IF NOT EXISTS goal_planning_preparations (
                parent_goal_workflow_id TEXT NOT NULL,
                normalized_issue_key TEXT NOT NULL,
                repository_identity TEXT NOT NULL,
                subtask_id INTEGER NOT NULL CHECK (subtask_id > 0),
                governed_sub_spec_path TEXT NOT NULL,
                preparation_status TEXT NOT NULL CHECK (preparation_status IN ('pending',
                  'prepared')) DEFAULT 'prepared',
                contract_version TEXT NOT NULL CHECK (contract_version = '0.1'),
                parent_spec_hash TEXT NOT NULL,
                sub_spec_hash TEXT NOT NULL,
                decomposition_manifest_hash TEXT NOT NULL,
                phase_output_contract_id TEXT NOT NULL,
                phase_output_contract_version TEXT NOT NULL,
                preplan_payload_json TEXT NOT NULL,
                plan_payload_json TEXT NOT NULL,
                created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP${
              connection.optionalRepairEvidenceColumn("goal_subtask_plans_pre_0_2")
            },
                updated_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
                PRIMARY KEY (parent_goal_workflow_id, subtask_id)
              )
            """.trimIndent(),
          )
          statement.execute(
            """
            CREATE INDEX IF NOT EXISTS idx_goal_planning_preparations_lookup
              ON goal_planning_preparations(normalized_issue_key, repository_identity)
            """.trimIndent(),
          )
        }
      },
    ),
    DatabaseMigration(
      version = 9,
      name = "normalize-goal-planning-preparations",
      operation = { connection ->
        connection.createStatement().use { statement ->
          statement.execute(
            """
            CREATE TABLE IF NOT EXISTS goal_shared_preplans (
              parent_goal_workflow_id TEXT PRIMARY KEY,
              normalized_issue_key TEXT NOT NULL,
              repository_identity TEXT NOT NULL,
              preparation_status TEXT NOT NULL CHECK (preparation_status = 'prepared'),
              contract_version TEXT NOT NULL CHECK (contract_version = '0.2'),
              parent_spec_hash TEXT NOT NULL,
              decomposition_manifest_hash TEXT NOT NULL,
              planning_contract_id TEXT NOT NULL,
              planning_contract_version TEXT NOT NULL CHECK (planning_contract_version = '0.2'),
              phase_output_contract_id TEXT NOT NULL,
              phase_output_contract_version TEXT NOT NULL CHECK (phase_output_contract_version = '0.1'),
              payload_sha256 TEXT NOT NULL,
              preplan_payload_json TEXT NOT NULL,
              created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
              UNIQUE(normalized_issue_key, repository_identity)
            )
            """.trimIndent(),
          )
          statement.execute(
            """
            CREATE TABLE IF NOT EXISTS goal_subtask_plans (
              parent_goal_workflow_id TEXT NOT NULL,
              normalized_issue_key TEXT NOT NULL,
              repository_identity TEXT NOT NULL,
              subtask_id INTEGER NOT NULL CHECK (subtask_id > 0),
              manifest_order INTEGER NOT NULL CHECK (manifest_order >= 0),
              governed_sub_spec_path TEXT NOT NULL,
              sub_spec_hash TEXT NOT NULL,
              preparation_status TEXT NOT NULL CHECK (preparation_status = 'prepared'),
              contract_version TEXT NOT NULL CHECK (contract_version = '0.2'),
              parent_spec_hash TEXT NOT NULL,
              decomposition_manifest_hash TEXT NOT NULL,
              planning_contract_id TEXT NOT NULL,
              planning_contract_version TEXT NOT NULL CHECK (planning_contract_version = '0.2'),
              phase_output_contract_id TEXT NOT NULL,
              phase_output_contract_version TEXT NOT NULL CHECK (phase_output_contract_version = '0.1'),
              payload_sha256 TEXT NOT NULL,
              plan_payload_json TEXT NOT NULL,
              created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
              PRIMARY KEY(parent_goal_workflow_id, subtask_id),
              UNIQUE(parent_goal_workflow_id, governed_sub_spec_path),
              UNIQUE(parent_goal_workflow_id, manifest_order),
              FOREIGN KEY(parent_goal_workflow_id)
              REFERENCES goal_shared_preplans(parent_goal_workflow_id) ON DELETE CASCADE
            )
            """.trimIndent(),
          )
          statement.execute(
            "CREATE INDEX IF NOT EXISTS idx_goal_subtask_plans_ordered " +
              "ON goal_subtask_plans(parent_goal_workflow_id, manifest_order)",
          )
        }
      },
    ),
    DatabaseMigration(
      version = 10,
      name = "rebuild-goal-planning-plans-for-phase-output-0-2",
      operation = ::rebuildGoalPlanningPlansForPhaseOutputVersion2,
    ),
    DatabaseMigration(
      version = 11,
      name = "require-goal-planning-phase-output-0-2",
      operation = ::requireGoalPlanningPhaseOutputVersion2,
    ),
    DatabaseMigration(
      version = 12,
      name = "add-bounded-review-accounting",
      operation = { connection ->
        connection.createStatement().use { statement ->
          statement.execute(
            """
            CREATE TABLE IF NOT EXISTS review_accounting (
              review_id TEXT PRIMARY KEY,
              packet_digest TEXT NOT NULL,
              bounded_payload_json TEXT NOT NULL,
              updated_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
            )
            """.trimIndent(),
          )
        }
      },
    ),
    DatabaseMigration(
      version = 13,
      name = "allow-goal-planning-phase-output-0-3",
      operation = { connection ->
        connection.createStatement().use { statement ->
          statement.execute("ALTER TABLE goal_subtask_plans RENAME TO goal_subtask_plans_pre_0_3")
          statement.execute("ALTER TABLE goal_shared_preplans RENAME TO goal_shared_preplans_pre_0_3")
          statement.execute(
            """
              CREATE TABLE goal_shared_preplans (
                parent_goal_workflow_id TEXT PRIMARY KEY,
                normalized_issue_key TEXT NOT NULL,
                repository_identity TEXT NOT NULL,
                preparation_status TEXT NOT NULL CHECK (preparation_status = 'prepared'),
                contract_version TEXT NOT NULL CHECK (contract_version = '0.2'),
                parent_spec_hash TEXT NOT NULL,
                decomposition_manifest_hash TEXT NOT NULL,
                planning_contract_id TEXT NOT NULL,
                planning_contract_version TEXT NOT NULL CHECK (planning_contract_version = '0.2'),
                phase_output_contract_id TEXT NOT NULL,
                phase_output_contract_version TEXT NOT NULL CHECK (phase_output_contract_version IN ('0.2', '0.3')),
                payload_sha256 TEXT NOT NULL,
                preplan_payload_json TEXT NOT NULL,
                created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP${
              connection.optionalRepairEvidenceColumn("goal_shared_preplans_pre_0_3")
            },
                UNIQUE(normalized_issue_key, repository_identity)
              )
            """.trimIndent(),
          )
          statement.execute(
            """
              CREATE TABLE goal_subtask_plans (
                parent_goal_workflow_id TEXT NOT NULL,
                normalized_issue_key TEXT NOT NULL,
                repository_identity TEXT NOT NULL,
                subtask_id INTEGER NOT NULL CHECK (subtask_id > 0),
                manifest_order INTEGER NOT NULL CHECK (manifest_order >= 0),
                governed_sub_spec_path TEXT NOT NULL,
                sub_spec_hash TEXT NOT NULL,
                preparation_status TEXT NOT NULL CHECK (preparation_status = 'prepared'),
                contract_version TEXT NOT NULL CHECK (contract_version = '0.2'),
                parent_spec_hash TEXT NOT NULL,
                decomposition_manifest_hash TEXT NOT NULL,
                planning_contract_id TEXT NOT NULL,
                planning_contract_version TEXT NOT NULL CHECK (planning_contract_version = '0.2'),
                phase_output_contract_id TEXT NOT NULL,
                phase_output_contract_version TEXT NOT NULL CHECK (phase_output_contract_version IN ('0.2', '0.3')),
                payload_sha256 TEXT NOT NULL,
                plan_payload_json TEXT NOT NULL,
                created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP${
              connection.optionalRepairEvidenceColumn("goal_subtask_plans_pre_0_3")
            },
                PRIMARY KEY(parent_goal_workflow_id, subtask_id),
                UNIQUE(parent_goal_workflow_id, governed_sub_spec_path),
                UNIQUE(parent_goal_workflow_id, manifest_order),
                FOREIGN KEY(parent_goal_workflow_id)
                REFERENCES goal_shared_preplans(parent_goal_workflow_id) ON DELETE CASCADE
              )
            """.trimIndent(),
          )
          statement.execute("INSERT INTO goal_shared_preplans SELECT * FROM goal_shared_preplans_pre_0_3")
          statement.execute("INSERT INTO goal_subtask_plans SELECT * FROM goal_subtask_plans_pre_0_3")
          statement.execute("DROP TABLE goal_subtask_plans_pre_0_3")
          statement.execute("DROP TABLE goal_shared_preplans_pre_0_3")
          statement.execute(
            "CREATE INDEX IF NOT EXISTS idx_goal_subtask_plans_ordered " +
              "ON goal_subtask_plans(parent_goal_workflow_id, manifest_order)",
          )
        }
      },
    ),
    DatabaseMigration(
      version = 14,
      name = "add-rejected-output-diagnostics",
      operation = { connection ->
        connection.createStatement().use { statement ->
          statement.execute(
            """
            CREATE TABLE IF NOT EXISTS rejected_output_diagnostics (
              identity TEXT PRIMARY KEY,
              workflow_id TEXT NOT NULL,
              phase_id TEXT NOT NULL,
              attempt INTEGER NOT NULL CHECK (attempt > 0),
              rule TEXT NOT NULL,
              rejection_path TEXT NOT NULL,
              reason TEXT NOT NULL,
              agent_id TEXT NOT NULL,
              model TEXT NOT NULL,
              recorded_at TEXT NOT NULL,
              byte_size INTEGER NOT NULL CHECK (byte_size >= 0),
              sha256 TEXT NOT NULL,
              lifecycle TEXT NOT NULL CHECK (lifecycle IN ('stored', 'oversized', 'expired')),
              payload BLOB,
              UNIQUE(workflow_id, phase_id, attempt),
              CHECK (
                (lifecycle = 'stored' AND payload IS NOT NULL) OR
                (lifecycle IN ('oversized', 'expired') AND payload IS NULL)
              )
            )
            """.trimIndent(),
          )
          statement.execute(
            "CREATE INDEX IF NOT EXISTS idx_rejected_output_diagnostic_selection " +
              "ON rejected_output_diagnostics(workflow_id, phase_id, attempt)",
          )
          statement.execute(
            "CREATE INDEX IF NOT EXISTS idx_rejected_output_diagnostic_retention " +
              "ON rejected_output_diagnostics(lifecycle, recorded_at)",
          )
        }
      },
    ),
    DatabaseMigration(
      version = 15,
      name = "add-private-producer-output-evidence",
      operation = { connection ->
        connection.createStatement().use {
          it.execute(
            """
            CREATE TABLE IF NOT EXISTS producer_output_evidence (
              workflow_id TEXT NOT NULL, phase_id TEXT NOT NULL,
              attempt INTEGER NOT NULL CHECK (attempt > 0),
              agent_id TEXT NOT NULL, model TEXT NOT NULL, recorded_at TEXT NOT NULL,
              byte_size INTEGER NOT NULL CHECK (byte_size >= 0), sha256 TEXT NOT NULL, payload BLOB,
              PRIMARY KEY (workflow_id, phase_id, attempt)
            )
            """.trimIndent(),
          )
        }
      },
    ),
    DatabaseMigration(
      version = 16,
      name = "rekey-producer-output-evidence-by-generation",
      operation = { connection ->
        val alreadyRekeyed =
          connection.prepareStatement(
            "SELECT 1 FROM pragma_table_info('producer_output_evidence') WHERE name = 'generation'",
          ).use { statement ->
            statement.executeQuery().use { resultSet -> resultSet.next() }
          }
        if (!alreadyRekeyed) {
          connection.createStatement().use {
            it.execute(
              "ALTER TABLE producer_output_evidence RENAME TO producer_output_evidence_pre_generation",
            )
            it.execute(
              """
              CREATE TABLE IF NOT EXISTS producer_output_evidence (
                workflow_id TEXT NOT NULL, phase_id TEXT NOT NULL,
                generation INTEGER NOT NULL DEFAULT 0 CHECK (generation >= 0),
                attempt INTEGER NOT NULL CHECK (attempt > 0),
                agent_id TEXT NOT NULL, model TEXT NOT NULL, recorded_at TEXT NOT NULL,
                byte_size INTEGER NOT NULL CHECK (byte_size >= 0), sha256 TEXT NOT NULL, payload BLOB,
                PRIMARY KEY (workflow_id, phase_id, generation, attempt)
              )
              """.trimIndent(),
            )
            it.execute(
              """
              INSERT INTO producer_output_evidence (
                workflow_id, phase_id, generation, attempt, agent_id, model, recorded_at,
                byte_size, sha256, payload
              )
              SELECT workflow_id, phase_id, 0, attempt, agent_id, model, recorded_at,
                     byte_size, sha256, payload
              FROM producer_output_evidence_pre_generation
              """.trimIndent(),
            )
            it.execute("DROP TABLE producer_output_evidence_pre_generation")
          }
        }
      },
    ),
    DatabaseMigration(
      version = 17,
      name = "persist-goal-planning-repair-evidence",
      operation = ::persistGoalPlanningRepairEvidence,
    ),
    DatabaseMigration(
      version = 18,
      name = "persist-legacy-goal-planning-repair-evidence",
      operation = ::persistLegacyGoalPlanningRepairEvidence,
    ),
    DatabaseMigration(
      version = 19,
      name = "add-goal-runner-controls",
      operation = ::addGoalRunnerControls,
    ),
    DatabaseMigration(
      version = 20,
      name = "add-goal-runner-control-state",
      operation = ::addGoalRunnerControlState,
    ),
    DatabaseMigration(
      version = 21,
      name = "add-delegated-review-lifecycle-projection",
      operation = ::addDelegatedReviewLifecycleProjection,
    ),
    DatabaseMigration(
      version = 22,
      name = "drop-delegated-review-lifecycle-tables",
      operation = ::dropDelegatedReviewLifecycleTables,
    ),
    DatabaseMigration(
      version = 24,
      name = "backfill-review-attribution-canonicals",
      operation = ReviewAttributionBackfillMigration::apply,
    ),
    DatabaseMigration(
      version = 25,
      name = "add-review-run-lane-attribution",
      operation = ::addReviewRunLaneAttribution,
    ),
    DatabaseMigration(
      version = 26,
      name = "relax-telemetry-outbox-last-error",
      operation = TelemetryOutboxLastErrorMigration::apply,
    ),
    DatabaseMigration(
      version = 27,
      name = "add-review-finding-outcome-key",
      operation = ::addReviewFindingOutcomeKey,
    ),
    DatabaseMigration(
      version = 28,
      name = "rekey-producer-output-evidence-by-agent",
      operation = ::rekeyProducerOutputEvidenceByAgent,
    ),
    DatabaseMigration(
      version = 29,
      name = "rekey-diagnostic-evidence-by-repair-turn",
      operation = ::rekeyDiagnosticEvidenceByRepairTurn,
    ),
    DatabaseMigration(
      version = 30,
      name = "add-review-run-stage-state",
      operation = DatabaseReviewColumnMigrations::ensureReviewStageStateTables,
    ),
    DatabaseMigration(
      version = 31,
      name = "add-review-run-pass-claims",
      operation = DatabaseReviewColumnMigrations::ensureReviewStageStateTables,
    ),
    DatabaseMigration(
      version = 32,
      name = "allow-goal-planning-phase-output-0-4",
      operation = ::rebuildGoalPlanningPlansForPhaseOutputVersion4,
    ),
    DatabaseMigration(
      version = 33,
      name = "allow-goal-planning-phase-output-0-5",
      operation = ::rebuildGoalPlanningPlansForPhaseOutputVersion5,
    ),
    DatabaseMigration(
      version = 34,
      name = "allow-goal-planning-phase-output-0-6",
      operation = ::rebuildGoalPlanningPlansForPhaseOutputVersion6,
    ),
    DatabaseMigration(
      version = 35,
      name = "add-feature-task-phase-settlements",
      operation = FeatureTaskPhaseSettlementsMigration::apply,
    ),
    DatabaseMigration(
      version = 36,
      name = "add-agent-activity-stamps",
      operation = { connection ->
        connection.createStatement().use { statement ->
          statement.execute(
            """
            CREATE TABLE IF NOT EXISTS agent_activity_stamps (
              workflow_id TEXT PRIMARY KEY,
              recorded_at TEXT NOT NULL,
              label TEXT NOT NULL CHECK (
                label IN (
                  'worktree write',
                  'stdout',
                  'durable progress',
                  'evidence read',
                  'tool stream'
                )
              )
            )
            """.trimIndent(),
          )
        }
      },
    ),
    DatabaseMigration(
      version = 37,
      name = "add-telemetry-outbox-delivery-identity",
      operation = TelemetryOutboxDeliveryIdentityMigration::apply,
    ),
    DatabaseMigration(
      version = 38,
      name = "add-worktree-edit-journal",
      operation = { connection ->
        connection.createStatement().use { statement ->
          statement.execute(
            """
            CREATE TABLE IF NOT EXISTS worktree_edit_journal (
              id INTEGER PRIMARY KEY AUTOINCREMENT,
              workflow_id TEXT NOT NULL,
              phase_id TEXT,
              recorded_at TEXT NOT NULL,
              path TEXT NOT NULL,
              lines_added INTEGER NOT NULL,
              lines_removed INTEGER NOT NULL,
              source TEXT NOT NULL CHECK (source IN ('worktree_probe'))
            )
            """.trimIndent(),
          )
          statement.execute(
            "CREATE INDEX IF NOT EXISTS idx_worktree_edit_journal_workflow_recorded " +
              "ON worktree_edit_journal(workflow_id, recorded_at)",
          )
        }
      },
    ),
    DatabaseMigration(
      version = 39,
      name = "ensure-schema-columns-and-heals",
      operation = ::ensureSchemaColumnsAndHeals,
    ),
    DatabaseMigration(
      version = 40,
      name = "migrate-legacy-goal-runner-controls",
      operation = ::applyLegacyGoalRunnerControlLedgerMigration,
    ),
    DatabaseMigration(
      version = 41,
      name = "migrate-legacy-telemetry-outbox",
      operation = ::migrateLegacyTelemetryOutboxLedger,
    ),
    DatabaseMigration(
      version = 42,
      name = "skill-366-add-experiment-pair-tables",
      operation = { connection ->
        connection.createStatement().use { statement ->
          statement.execute(
            """
            CREATE TABLE IF NOT EXISTS experiment_pairs (
              pair_id TEXT PRIMARY KEY,
              contract_version TEXT NOT NULL CHECK (contract_version = '0.1'),
              execution_mode TEXT NOT NULL,
              selected_experiment_names_json TEXT NOT NULL,
              arm_order_json TEXT NOT NULL,
              random_seed TEXT,
              delivery_arm TEXT NOT NULL,
              pair_status TEXT NOT NULL,
              frozen_input_identity_json TEXT NOT NULL,
              delivery_status TEXT NOT NULL,
              created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
              updated_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
            )
            """.trimIndent(),
          )
          statement.execute(
            """
            CREATE TABLE IF NOT EXISTS experiment_observations (
              observation_id TEXT PRIMARY KEY,
              pair_id TEXT NOT NULL,
              arm_id TEXT NOT NULL,
              event_identity_json TEXT NOT NULL,
              recorded_at TEXT NOT NULL,
              measurements_json TEXT NOT NULL,
              UNIQUE(pair_id, arm_id, event_identity_json),
              FOREIGN KEY(pair_id) REFERENCES experiment_pairs(pair_id) ON DELETE CASCADE
            )
            """.trimIndent(),
          )
          statement.execute(
            """
            CREATE TABLE IF NOT EXISTS experiment_arm_outcomes (
              pair_id TEXT NOT NULL,
              arm_id TEXT NOT NULL,
              workflow_id TEXT,
              terminal_status TEXT NOT NULL,
              worktree_path TEXT,
              deferred_publication INTEGER NOT NULL DEFAULT 1,
              PRIMARY KEY(pair_id, arm_id),
              FOREIGN KEY(pair_id) REFERENCES experiment_pairs(pair_id) ON DELETE CASCADE
            )
            """.trimIndent(),
          )
        }
      },
    ),
    DatabaseMigration(
      version = 43,
      name = "skill-366-add-experiment-pair-leases",
      operation = { connection ->
        connection.createStatement().use { statement ->
          statement.execute(
            """
            CREATE TABLE IF NOT EXISTS experiment_pair_leases (
              pair_id TEXT PRIMARY KEY,
              owner_token TEXT NOT NULL,
              generation INTEGER NOT NULL,
              expires_at INTEGER NOT NULL
            )
            """.trimIndent(),
          )
          statement.execute(
            """
            CREATE TABLE IF NOT EXISTS experiment_reports (
              pair_id TEXT PRIMARY KEY,
              report_json TEXT NOT NULL,
              updated_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
              FOREIGN KEY(pair_id) REFERENCES experiment_pairs(pair_id) ON DELETE CASCADE
            )
            """.trimIndent(),
          )
        }
      },
    ),
    DatabaseMigration(
      version = 44,
      name = "skill-366-preserve-experiment-arm-outcomes",
      operation = { connection ->
        connection.createStatement().use { statement ->
          statement.execute(
            """
            ALTER TABLE experiment_arm_outcomes ADD COLUMN outcome_json TEXT
            """.trimIndent(),
          )
        }
      },
    ),
    DatabaseMigration(
      version = 45,
      name = "skill-378-drop-experiment-tables",
      operation = { connection ->
        connection.createStatement().use { statement ->
          statement.execute("DROP TABLE IF EXISTS experiment_reports")
          statement.execute("DROP TABLE IF EXISTS experiment_pair_leases")
          statement.execute("DROP TABLE IF EXISTS experiment_arm_outcomes")
          statement.execute("DROP TABLE IF EXISTS experiment_observations")
          statement.execute("DROP TABLE IF EXISTS experiment_pairs")
        }
      },
    ),
    DatabaseMigration(
      version = 46,
      name = "allow-goal-planning-phase-output-0-7",
      operation = ::rebuildGoalPlanningPlansForPhaseOutputVersion7,
    ),
    DatabaseMigration(
      version = 47,
      name = "add-operation-proposals",
      operation = OperationProposalsMigration::apply,
    ),
    DatabaseMigration(
      version = 48,
      name = "add-goal-no-change-reason",
      operation = GoalNoChangeReasonMigration::apply,
    ),
    DatabaseMigration(
      version = 49,
      name = "add-feature-task-phase-strategy-telemetry-columns",
      operation = FeatureTaskPhaseStrategyTelemetryMigration::apply,
    ),
    DatabaseMigration(
      version = 50,
      name = "add-standalone-phase-status-authority",
      operation = StandalonePhaseStatusMigration::apply,
    ),
    DatabaseMigration(
      version = 51,
      name = "index-feature-task-workflow-issue-lookup",
      operation = { connection ->
        connection.createStatement().use { statement ->
          statement.execute(
            "CREATE INDEX IF NOT EXISTS idx_feature_task_workflows_issue_key " +
              "ON feature_task_workflows(UPPER(issue_key))",
          )
          statement.execute(
            "CREATE INDEX IF NOT EXISTS idx_feature_task_workflows_normalized_issue_key " +
              "ON feature_task_workflows(UPPER(TRIM(issue_key)))",
          )
          statement.execute(
            "CREATE INDEX IF NOT EXISTS idx_feature_task_workflows_missing_issue_key " +
              "ON feature_task_workflows(issue_key) WHERE issue_key IS NULL",
          )
          statement.execute(
            "CREATE INDEX IF NOT EXISTS idx_feature_task_workflows_goal_parent " +
              "ON feature_task_workflows(CASE WHEN json_valid(artifacts_json) " +
              "THEN json_extract(artifacts_json, '$.goal_continuation.parent_workflow_id') END)",
          )
        }
      },
    ),
    DatabaseMigration(
      version = 52,
      name = "add-desktop-room-read-identity",
      operation = ::installDesktopReadCompatibility,
    ),
    DatabaseMigration(
      version = 53,
      name = "bind-work-observations-and-accepted-results",
      operation = { connection ->
        connection.createStatement().use { statement ->
          statement.execute("ALTER TABLE standalone_phase_status ADD COLUMN accepted_result TEXT")
          statement.execute("ALTER TABLE standalone_phase_status ADD COLUMN owner_host_identity TEXT")
          statement.execute("ALTER TABLE standalone_phase_status ADD COLUMN owner_boot_identity TEXT")
          statement.execute("ALTER TABLE standalone_phase_status ADD COLUMN owner_pid INTEGER")
          statement.execute("ALTER TABLE standalone_phase_status ADD COLUMN owner_birth_token TEXT")
          statement.execute(
            "CREATE TABLE ${DesktopReadSchema.IDENTITY_TABLE} " +
              "(id INTEGER PRIMARY KEY CHECK (id = 1), store_id TEXT NOT NULL)",
          )
          statement.execute(
            "INSERT INTO ${DesktopReadSchema.IDENTITY_TABLE} VALUES (1, lower(hex(randomblob(16))))",
          )
        }
      },
    ),
    DatabaseMigration(
      version = 54,
      name = "separate-agent-activity-revisions",
      operation = ::separateAgentActivityRevisions,
    ),
    DatabaseMigration(
      version = 55,
      name = "complete-desktop-launch-schema",
      operation = ::saveStandalonePhaseLaunch,
    ),
    DatabaseMigration(
      version = 56,
      name = "abandon-stale-verify-workflows",
      operation = { connection ->
        connection.prepareStatement(
          """
          UPDATE feature_verify_workflows
          SET workflow_status = 'abandoned',
              updated_at = strftime('%Y-%m-%d %H:%M:%S', 'now'),
              finished_at = strftime('%Y-%m-%dT%H:%M:%fZ', 'now'),
              state_entered_at = strftime('%Y-%m-%dT%H:%M:%fZ', 'now'),
              state_entered_at_estimated = 0
          WHERE workflow_status IN ('running', 'pending')
            AND finished_at IS NULL
            AND (julianday('now') - julianday(updated_at)) * 24 > ?
          """.trimIndent(),
        ).use { statement ->
          statement.setLong(1, IdeStatusPolicy.VERIFY_ABANDONED_AFTER.toHours())
          statement.executeUpdate()
        }
      },
    ),
    DatabaseMigration(
      version = 57,
      name = "record-launch-agent-environment",
      operation = ::recordLaunchAgentEnvironment,
    ),
    DatabaseMigration(
      version = 58,
      name = "record-standalone-phase-launch-scope",
      operation = ::recordStandalonePhaseLaunchScope,
    ),
    DatabaseMigration(
      version = 59,
      name = "record-standalone-monitor-target",
      operation = ::recordStandaloneMonitorTarget,
    ),
    DatabaseMigration(
      version = 60,
      name = "record-standalone-qa-outcome",
      operation = ::recordStandaloneQaOutcome,
    ),
    DatabaseMigration(
      version = 61,
      name = "record-feature-task-qa-outcomes",
      operation = ::recordFeatureTaskQaOutcomes,
    ),
    DatabaseMigration(
      version = 62,
      name = "record-feature-task-qa-findings-summary",
      operation = ::recordFeatureTaskQaFindingsSummary,
    ),
    DatabaseMigration(
      version = 63,
      name = "record-standalone-qa-report",
      operation = ::recordStandaloneQaReport,
    ),
    DatabaseMigration(
      version = 64,
      name = "record-feature-task-qa-criteria-summary",
      operation = ::recordFeatureTaskQaCriteriaSummary,
    ),
    DatabaseMigration(
      version = 65,
      name = "record-standalone-plan-report",
      operation = ::recordStandalonePlanReport,
    ),
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
  )
