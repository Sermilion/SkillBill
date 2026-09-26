package skillbill.infrastructure.sqlite

import skillbill.infrastructure.sqlite.core.migration.DatabaseColumnMigrations
import skillbill.infrastructure.sqlite.core.migration.DatabaseMigration
import skillbill.infrastructure.sqlite.core.migration.addDelegatedReviewLifecycleProjection
import skillbill.infrastructure.sqlite.core.migration.addGoalRunnerControlState
import skillbill.infrastructure.sqlite.core.migration.addGoalRunnerControls
import skillbill.infrastructure.sqlite.core.migration.addReviewFindingOutcomeKey
import skillbill.infrastructure.sqlite.core.migration.addReviewRunLaneAttribution
import skillbill.infrastructure.sqlite.core.migration.area.FeatureTaskPhaseSettlementsMigration
import skillbill.infrastructure.sqlite.core.migration.area.FeedbackEventMigration
import skillbill.infrastructure.sqlite.core.migration.area.GoalTelemetryMigration
import skillbill.infrastructure.sqlite.core.migration.area.ReviewAttributionBackfillMigration
import skillbill.infrastructure.sqlite.core.migration.area.TelemetryOutboxDeliveryIdentityMigration
import skillbill.infrastructure.sqlite.core.migration.area.TelemetryOutboxLastErrorMigration
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
  )
