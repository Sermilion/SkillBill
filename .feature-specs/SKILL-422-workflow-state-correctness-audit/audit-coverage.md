# SKILL-422 audit coverage (subtask 3 closure)

Maps every upstream scope item, seven deliverable groups, and SKILL-414/417/418 state requirements to current tree artifacts. All mapping tables share the same columns. Test execution is **defined** in implement; **executed** only after validate.

## Mapping column schema

| Column | Meaning |
| --- | --- |
| Scope item | Upstream inventory heading, consumer, classification, deliverable requirement, or cross-issue state requirement |
| Current source owner | Repo-relative path and symbol |
| Transition-matrix entry | `transition-matrix.md` section and row, or explicit none |
| Regression or finding ID | Named T1/T2/T3 test, F1–F15, verified no-finding area, or none |
| Limitation | External limitation or none |
| Execution status | source-complete; tests authored / pending validate; candidate / next verification named; external limitation |

## SQLite table families and schema registry

| Scope item | Current source owner | Transition-matrix entry | Regression or finding ID | Limitation | Execution status |
| --- | --- | --- | --- | --- | --- |
| DatabaseSchema.tableNames vs migration-created tables | `runtime-kotlin/runtime-infra/sqlite/src/main/kotlin/skillbill/infrastructure/sqlite/core/schema/DatabaseSchema.kt` `tableNames`; omissions per inventory registry table | none — schema registry coherence | none | registry omits settlements, review_accounting, operation_proposals, work_store_identity, desktop_change_revisions, room_master_table | source-complete |
| schema_migrations | `DatabaseSchemaStatements`; migration insert into `schema_migrations` | none — supporting/telemetry family | none | none | source-complete |
| feature_task_phase_settlements | `SqliteFeatureTaskPhaseSettlementStore` / `FeatureTaskPhaseSettlementService`; MCP `McpFeatureTaskSettlementHandlers` | Phase / step — settlement admission | F2; T1-02 | not in `DatabaseSchema.tableNames` | tests authored / pending validate |
| operation_proposals | `SqliteOperationProposalStore` / `OperationConfirmationGate` | none — supporting/telemetry family | none | none | source-complete |
| work_store_identity | `DesktopReadSchema.IDENTITY_TABLE`; `SQLiteWorkListRepository` reads | Display — work list identity | F9; T2-07 | none | tests authored / pending validate |
| desktop_change_revisions | `DesktopChangeRevisions.kt` triggers; `SQLiteDatabaseSessionFactory` topic reads | Freshness — revision bumps | none | cache; no full-table rebuild | source-complete |
| room_master_table | `installDesktopReadCompatibility`; `DatabaseRuntime` hash check | none — supporting/telemetry family | none | none | source-complete |
| feature_task_workflows | `WorkflowStateStore` / `WorkflowStateWrites`; `FeatureTaskRuntimeWorkflowPersistence` | Lifecycle — terminal fence | F1; T1-01 | none | tests authored / pending validate |
| feature_verify_workflows | `FeatureVerifyWorkflowStateStore`; `WorkflowService` VERIFY; MCP `McpWorkflowToolHandlers` | Lifecycle — verify family | none | none | source-complete |
| feature_task_execution_identities | `FeatureTaskExecutionLookupStore.saveFeatureTaskExecutionIdentity` | Lifecycle — admit identity | none | none | source-complete |
| feature_task_runtime_worker_leases | `FeatureTaskRuntimeWorkerStore` / `FeatureTaskRuntimeWorkerCoordinator` | Liveness — lease CAS | F3; T1-04 | none | tests authored / pending validate |
| goal_runner_controls | `GoalRunnerControlStore` / `GoalRunnerStatusControlVerbs` | Controls — pause/stop/resume | F7; T1-06; T1-08 | none | tests authored / pending validate |
| goal_planning_preparations | `GoalPlanningPreparationCheckpoint`; `GoalPlanningPreparationRecordSql` | Subtask 2 — planning checkpoint | T2-04 | none | tests authored / pending validate |
| goal_shared_preplans | `SharedGoalPreplanStore` via `GoalPlanningPreparationCheckpoint` | Subtask 2 — preplan checkpoint | T2-04 | disk projection derived | tests authored / pending validate |
| goal_subtask_plans | `GoalSubtaskPlanStore` via `GoalPlanningPreparationCheckpoint` | Subtask 2 — subtask plan | T2-04 | none | tests authored / pending validate |
| goal_run_sessions | `GoalTelemetrySave.saveGoalStarted` / `saveGoalFinished` | Progress — goal session telemetry | none | none | source-complete |
| goal_subtask_events | `GoalTelemetrySave.saveGoalSubtaskFinished` | Progress — subtask events | none | none | source-complete |
| goal_issue_progress | `GoalTelemetrySave` / `LifecycleTelemetryGoalEmission` | Progress — issue segments | none | none | source-complete |
| standalone_phase_status | `StandalonePhaseStatusStore` / `StandalonePhaseStatusEventSink` | Freshness — supersede monitor | T1-07 | none | tests authored / pending validate |
| ide_status_execution_registry | `StandalonePhaseStatusStore.ensureStore` | Display — execution registry | none | none | source-complete |
| ide_status_workflow_execution | `StandalonePhaseStatusStore.registerWorkflowInTransaction` | Display — workflow execution registration | none | none | source-complete |
| quality_check_sessions | `QualityCheckTelemetrySave` → lifecycle adapters | none — supporting/telemetry family | none | none | source-complete |
| feature_verify_sessions | `LifecycleTelemetrySave` feature verify session adapters | none — supporting/telemetry family | none | none | source-complete |
| feature_task_runtime_sessions | `LifecycleTelemetryRuntimeSave` feature-task session adapters | none — supporting/telemetry family | none | none | source-complete |
| feature_implement_sessions | schema/backfill only; no live INSERT writer per inventory | none — supporting/telemetry family | none | historical rows only | source-complete |
| producer_output_evidence | `RejectedOutputDiagnosticService.retainProducerOutput` / `FeatureTaskRuntimeRejectedOutputRecorder` | Phase / step — output evidence | F5 partial | next: validate T1-08 remaining mutators | candidate / next verification named |
| rejected_output_diagnostics | `SqliteRejectedOutputDiagnosticRepository` / `FeatureTaskRuntimeRejectedOutputRecorder` | Phase / step — rejected output | none | none | source-complete |
| agent_activity_stamps | `AgentActivityStampWriter` → `unitOfWork.agentActivityStamps` | none — supporting/telemetry family | none | none | source-complete |
| worktree_edit_journal | `WorktreeEditJournalWriter` | none — supporting/telemetry family | none | none | source-complete |
| telemetry_outbox | `TelemetryOutboxStore.enqueue` | none — supporting/telemetry family | none | none | source-complete |
| telemetry_reconciliation_state | `StaleSessionReconciler.claimReconciliationCadence` | none — supporting/telemetry family | none | none | source-complete |
| telemetry_local_secrets | `TelemetryRedactionSaltStore` | none — supporting/telemetry family | none | none | source-complete |
| review_runs | review run store; `import_review` | none — review ledger family | none | none | source-complete |
| review_run_lanes | review lane store | none — review ledger family | none | none | source-complete |
| review_run_finding_lanes | review finding lane store | none — review ledger family | none | none | source-complete |
| review_accounting | review accounting store; v12 migration | none — review ledger family | none | absent from `tableNames` | source-complete |
| findings | findings store; `import_review` | none — review ledger family | none | none | source-complete |
| unaddressed_findings | unaddressed findings store | none — review ledger family | none | none | source-complete |
| review_finding_outcomes | review finding outcomes store | none — review ledger family | none | none | source-complete |
| review_run_finding_verdicts | verdict store | none — review ledger family | none | none | source-complete |
| review_run_stage_boundaries | stage boundary store | none — review ledger family | none | none | source-complete |
| review_run_pass_claims | pass claims store | none — review ledger family | none | none | source-complete |
| review_run_spec_projections | spec projection store | none — review ledger family | none | none | source-complete |
| feedback_events | findings/learnings pipelines | none — supporting/telemetry family | none | none | source-complete |
| learnings | learnings store; MCP `resolve_learnings` | none — supporting/telemetry family | none | none | source-complete |
| session_learnings | session learnings store | none — supporting/telemetry family | none | none | source-complete |
| Pre-install preserve wipe | `uninstall.sh` `remove_state_dir_preserving_source` (794+); `install.sh` `run_pre_install_uninstall` (742–766) | Subtask 3 — preserve pre-install cleanup | T3-01; F13 verified no-finding | none | tests authored / pending validate |
| Explicit shell uninstall | `uninstall.sh` `remove_state_dir_full` (776+) | Subtask 3 — explicit shell destructive | T3-04; F14 verified no-finding | none | tests authored / pending validate |
| Application uninstall | `SkillBillUninstallService.apply` → `removeRecursively(stateRoot)` | Subtask 3 — application destructive | T3-04 | none | tests authored / pending validate |
| Session factory recovery | `SQLiteDatabaseSessionFactory` / `DatabaseRuntime` | Subtask 3 — session open boundary | T3-03; F15 verified no-finding | none | tests authored / pending validate |
| Nested write rollback | `ConnectionTransactions` / `inNestedWriteTransaction` | Subtask 3 — transactional rollback | T3-02; F13 context | none | tests authored / pending validate |
| Corrupt row evidence retention | `WorkflowStateSqlReads`; `SqliteFeatureTaskPhaseSettlementStore` | Lifecycle — refuse corrupt parse | T3-05; AC-004 | none | tests authored / pending validate |
| Install staging promotion | `InstallStagingAtomicMoves.promoteByBackupAndMove` | Subtask 3 — staging promotion (out of DB tree) | existing `InstallStagingTest` | not T3 substitute | source-complete |
| Historical WAL incident | n/a — no reproducible incident artifact | none — not a transition | EL-422-WAL | historical progress unrecoverable | external limitation |

## `DurableWorkflowArtifactFamily` members (`feature_task_workflows.artifacts_json`)

| Scope item | Current source owner | Transition-matrix entry | Regression or finding ID | Limitation | Execution status |
| --- | --- | --- | --- | --- | --- |
| WORKER_SUBTASK_REQUEST_OUTCOMES | `DurableWorkflowArtifactFamily.WORKER_SUBTASK_REQUEST_OUTCOMES`; `WorkflowStateStore` / `FeatureTaskRuntimeWorkflowPersistence` | Phase / step — artifact patch; Lifecycle terminal fence | T1-01 / T1-05 as applicable | classification per inventory § DurableWorkflowArtifactFamily | source-complete |
| GOAL_ATTEMPT_LEDGER | `DurableWorkflowArtifactFamily.GOAL_ATTEMPT_LEDGER`; `WorkflowStateStore` / `FeatureTaskRuntimeWorkflowPersistence` | Phase / step — artifact patch; Lifecycle terminal fence | T1-01 / T1-05 as applicable | classification per inventory § DurableWorkflowArtifactFamily | source-complete |
| GOAL_CONTINUATION_OUTCOME_DISPLACEMENT | `DurableWorkflowArtifactFamily.GOAL_CONTINUATION_OUTCOME_DISPLACEMENT`; `WorkflowStateStore` / `FeatureTaskRuntimeWorkflowPersistence` | Phase / step — artifact patch; Lifecycle terminal fence | T1-01 / T1-05 as applicable | classification per inventory § DurableWorkflowArtifactFamily | source-complete |
| GOAL_OUT_OF_BAND_ACCEPTANCE | `DurableWorkflowArtifactFamily.GOAL_OUT_OF_BAND_ACCEPTANCE`; `WorkflowStateStore` / `FeatureTaskRuntimeWorkflowPersistence` | Phase / step — artifact patch; Lifecycle terminal fence | T1-01 / T1-05 as applicable | classification per inventory § DurableWorkflowArtifactFamily | source-complete |
| GOAL_REVIEW_POLICY | `DurableWorkflowArtifactFamily.GOAL_REVIEW_POLICY`; `WorkflowStateStore` / `FeatureTaskRuntimeWorkflowPersistence` | Phase / step — artifact patch; Lifecycle terminal fence | T1-01 / T1-05 as applicable | classification per inventory § DurableWorkflowArtifactFamily | source-complete |
| GOAL_OBSERVABILITY_LATEST_EVENT | `DurableWorkflowArtifactFamily.GOAL_OBSERVABILITY_LATEST_EVENT`; `WorkflowStateStore` / `FeatureTaskRuntimeWorkflowPersistence` | Phase / step — artifact patch; Lifecycle terminal fence | T1-01 / T1-05 as applicable | classification per inventory § DurableWorkflowArtifactFamily | source-complete |
| GOAL_OBSERVABILITY_RUN_HISTORY | `DurableWorkflowArtifactFamily.GOAL_OBSERVABILITY_RUN_HISTORY`; `WorkflowStateStore` / `FeatureTaskRuntimeWorkflowPersistence` | Phase / step — artifact patch; Lifecycle terminal fence | T1-01 / T1-05 as applicable | classification per inventory § DurableWorkflowArtifactFamily | source-complete |
| GOAL_PROGRESS_LATEST_EVENT | `DurableWorkflowArtifactFamily.GOAL_PROGRESS_LATEST_EVENT`; `WorkflowStateStore` / `FeatureTaskRuntimeWorkflowPersistence` | Phase / step — artifact patch; Lifecycle terminal fence | T1-01 / T1-05 as applicable | classification per inventory § DurableWorkflowArtifactFamily | source-complete |
| GOAL_PROGRESS_RUN_HISTORY | `DurableWorkflowArtifactFamily.GOAL_PROGRESS_RUN_HISTORY`; `WorkflowStateStore` / `FeatureTaskRuntimeWorkflowPersistence` | Phase / step — artifact patch; Lifecycle terminal fence | T1-01 / T1-05 as applicable | classification per inventory § DurableWorkflowArtifactFamily | source-complete |
| GOAL_REVIEW_BASE_RECOVERIES | `DurableWorkflowArtifactFamily.GOAL_REVIEW_BASE_RECOVERIES`; `WorkflowStateStore` / `FeatureTaskRuntimeWorkflowPersistence` | Phase / step — artifact patch; Lifecycle terminal fence | T1-05; F4 candidate | F4 recovery-history artifact; next: WorkflowEngine.resumeView / continueStatusFor | candidate / next verification named |
| GOAL_SUBTASK_REVIEW_INPUT | `DurableWorkflowArtifactFamily.GOAL_SUBTASK_REVIEW_INPUT`; `WorkflowStateStore` / `FeatureTaskRuntimeWorkflowPersistence` | Phase / step — artifact patch; Lifecycle terminal fence | T1-01 / T1-05 as applicable | classification per inventory § DurableWorkflowArtifactFamily | source-complete |
| GOAL_SUBTASK_REVIEW_RESULTS | `DurableWorkflowArtifactFamily.GOAL_SUBTASK_REVIEW_RESULTS`; `WorkflowStateStore` / `FeatureTaskRuntimeWorkflowPersistence` | Phase / step — artifact patch; Lifecycle terminal fence | T1-01 / T1-05 as applicable | classification per inventory § DurableWorkflowArtifactFamily | source-complete |
| GOAL_SUBTASK_REVIEW_STATE | `DurableWorkflowArtifactFamily.GOAL_SUBTASK_REVIEW_STATE`; `WorkflowStateStore` / `FeatureTaskRuntimeWorkflowPersistence` | Phase / step — artifact patch; Lifecycle terminal fence | T1-01 / T1-05 as applicable | classification per inventory § DurableWorkflowArtifactFamily | source-complete |
| DECOMPOSITION_RUNTIME | `DurableWorkflowArtifactFamily.DECOMPOSITION_RUNTIME`; `WorkflowStateStore` / `FeatureTaskRuntimeWorkflowPersistence` | Phase / step — artifact patch; Lifecycle terminal fence | T1-01 / T1-05 as applicable | disk manifest derived; authority stays artifact + SQLite planning tables | source-complete |
| DECOMPOSITION_MANIFEST_PROJECTION_FAILURE | `DurableWorkflowArtifactFamily.DECOMPOSITION_MANIFEST_PROJECTION_FAILURE`; `WorkflowStateStore` / `FeatureTaskRuntimeWorkflowPersistence` | Phase / step — artifact patch; Lifecycle terminal fence | T1-01 / T1-05 as applicable | classification per inventory § DurableWorkflowArtifactFamily | source-complete |
| RUNTIME_REPOSITORY_EVIDENCE | `DurableWorkflowArtifactFamily.RUNTIME_REPOSITORY_EVIDENCE`; `WorkflowStateStore` / `FeatureTaskRuntimeWorkflowPersistence` | Phase / step — artifact patch; Lifecycle terminal fence | T1-01 / T1-05 as applicable | classification per inventory § DurableWorkflowArtifactFamily | source-complete |
| FEATURE_TASK_RUNTIME_DIAGNOSTIC_SIGNALS | `DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_DIAGNOSTIC_SIGNALS`; `WorkflowStateStore` / `FeatureTaskRuntimeWorkflowPersistence` | Phase / step — artifact patch; Lifecycle terminal fence | T1-01 / T1-05 as applicable | classification per inventory § DurableWorkflowArtifactFamily | source-complete |
| FEATURE_TASK_RUNTIME_QUARANTINED_RECORDS | `DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_QUARANTINED_RECORDS`; `WorkflowStateStore` / `FeatureTaskRuntimeWorkflowPersistence` | Phase / step — artifact patch; Lifecycle terminal fence | T1-01 / T1-05 as applicable | classification per inventory § DurableWorkflowArtifactFamily | source-complete |
| FEATURE_TASK_RUNTIME_DECOMPOSE_TERMINAL | `DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_DECOMPOSE_TERMINAL`; `WorkflowStateStore` / `FeatureTaskRuntimeWorkflowPersistence` | Phase / step — artifact patch; Lifecycle terminal fence | T1-01 / T1-05 as applicable | classification per inventory § DurableWorkflowArtifactFamily | source-complete |
| FEATURE_TASK_RUNTIME_CHECKPOINT_IDENTITIES | `DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_CHECKPOINT_IDENTITIES`; `WorkflowStateStore` / `FeatureTaskRuntimeWorkflowPersistence` | Phase / step — artifact patch; Lifecycle terminal fence | T1-01 / T1-05 as applicable | classification per inventory § DurableWorkflowArtifactFamily | source-complete |
| FEATURE_TASK_RUNTIME_DELIVERED_PROJECTIONS | `DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_DELIVERED_PROJECTIONS`; `WorkflowStateStore` / `FeatureTaskRuntimeWorkflowPersistence` | Phase / step — artifact patch; Lifecycle terminal fence | T1-01 / T1-05 as applicable | classification per inventory § DurableWorkflowArtifactFamily | source-complete |
| FEATURE_TASK_RUNTIME_FINDING_VERIFICATION_BOUNDARY_SELECTION | `DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_FINDING_VERIFICATION_BOUNDARY_SELECTION`; `WorkflowStateStore` / `FeatureTaskRuntimeWorkflowPersistence` | Phase / step — artifact patch; Lifecycle terminal fence | T1-01 / T1-05 as applicable | classification per inventory § DurableWorkflowArtifactFamily | source-complete |
| FEATURE_TASK_RUNTIME_FINDING_VERIFICATION_CHECKPOINT | `DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_FINDING_VERIFICATION_CHECKPOINT`; `WorkflowStateStore` / `FeatureTaskRuntimeWorkflowPersistence` | Phase / step — artifact patch; Lifecycle terminal fence | T1-01 / T1-05 as applicable | classification per inventory § DurableWorkflowArtifactFamily | source-complete |
| FEATURE_TASK_RUNTIME_FINDING_VERIFICATION_DISPOSITIONS | `DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_FINDING_VERIFICATION_DISPOSITIONS`; `WorkflowStateStore` / `FeatureTaskRuntimeWorkflowPersistence` | Phase / step — artifact patch; Lifecycle terminal fence | T1-01 / T1-05 as applicable | classification per inventory § DurableWorkflowArtifactFamily | source-complete |
| FEATURE_TASK_RUNTIME_EXECUTION_PLAN | `DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_EXECUTION_PLAN`; `WorkflowStateStore` / `FeatureTaskRuntimeWorkflowPersistence` | Phase / step — artifact patch; Lifecycle terminal fence | T1-01 / T1-05 as applicable | classification per inventory § DurableWorkflowArtifactFamily | source-complete |
| FEATURE_TASK_RUNTIME_GOAL_CONTINUATION | `DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_GOAL_CONTINUATION`; `WorkflowStateStore` / `FeatureTaskRuntimeWorkflowPersistence` | Phase / step — artifact patch; Lifecycle terminal fence | T1-01 / T1-05 as applicable | classification per inventory § DurableWorkflowArtifactFamily | source-complete |
| FEATURE_TASK_RUNTIME_GOAL_CONTINUATION_FIELD_ADOPTION | `DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_GOAL_CONTINUATION_FIELD_ADOPTION`; `WorkflowStateStore` / `FeatureTaskRuntimeWorkflowPersistence` | Phase / step — artifact patch; Lifecycle terminal fence | T1-01 / T1-05 as applicable | classification per inventory § DurableWorkflowArtifactFamily | source-complete |
| FEATURE_TASK_RUNTIME_GOAL_CONTINUATION_OUTCOME | `DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_GOAL_CONTINUATION_OUTCOME`; `WorkflowStateStore` / `FeatureTaskRuntimeWorkflowPersistence` | Phase / step — artifact patch; Lifecycle terminal fence | T1-01 / T1-05 as applicable | classification per inventory § DurableWorkflowArtifactFamily | source-complete |
| FEATURE_TASK_RUNTIME_GOAL_PLANNING_IMPORT | `DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_GOAL_PLANNING_IMPORT`; `WorkflowStateStore` / `FeatureTaskRuntimeWorkflowPersistence` | Phase / step — artifact patch; Lifecycle terminal fence | T1-01 / T1-05 as applicable | classification per inventory § DurableWorkflowArtifactFamily | source-complete |
| FEATURE_TASK_RUNTIME_PLAN_SEED | `DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_PLAN_SEED`; `WorkflowStateStore` / `FeatureTaskRuntimeWorkflowPersistence` | Phase / step — artifact patch; Lifecycle terminal fence | T1-01 / T1-05 as applicable | classification per inventory § DurableWorkflowArtifactFamily | source-complete |
| FEATURE_TASK_RUNTIME_PLAN_WORKFLOW | `DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_PLAN_WORKFLOW`; `WorkflowStateStore` / `FeatureTaskRuntimeWorkflowPersistence` | Phase / step — artifact patch; Lifecycle terminal fence | T1-01 / T1-05 as applicable | classification per inventory § DurableWorkflowArtifactFamily | source-complete |
| FEATURE_TASK_RUNTIME_IMPLEMENTATION_ATTEMPTS | `DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_IMPLEMENTATION_ATTEMPTS`; `WorkflowStateStore` / `FeatureTaskRuntimeWorkflowPersistence` | Phase / step — artifact patch; Lifecycle terminal fence | T1-01 / T1-05 as applicable | classification per inventory § DurableWorkflowArtifactFamily | source-complete |
| FEATURE_TASK_RUNTIME_OPERATOR_BLOCK_RETRY | `DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_OPERATOR_BLOCK_RETRY`; `WorkflowStateStore` / `FeatureTaskRuntimeWorkflowPersistence` | Phase / step — artifact patch; Lifecycle terminal fence | T1-01 / T1-05 as applicable | classification per inventory § DurableWorkflowArtifactFamily | source-complete |
| FEATURE_TASK_RUNTIME_PHASE_BRIEFINGS | `DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_PHASE_BRIEFINGS`; `WorkflowStateStore` / `FeatureTaskRuntimeWorkflowPersistence` | Phase / step — artifact patch; Lifecycle terminal fence | T1-01 / T1-05 as applicable | classification per inventory § DurableWorkflowArtifactFamily | source-complete |
| FEATURE_TASK_RUNTIME_PHASE_LEDGER | `DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_PHASE_LEDGER`; `WorkflowStateStore` / `FeatureTaskRuntimeWorkflowPersistence` | Phase / step — artifact patch; Lifecycle terminal fence | T1-01; F1 | classification per inventory § DurableWorkflowArtifactFamily | source-complete |
| FEATURE_TASK_RUNTIME_PHASE_RECORDS | `DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_PHASE_RECORDS`; `WorkflowStateStore` / `FeatureTaskRuntimeWorkflowPersistence` | Phase / step — artifact patch; Lifecycle terminal fence | T1-01; F1 | classification per inventory § DurableWorkflowArtifactFamily | source-complete |
| FEATURE_TASK_RUNTIME_RESOLVED_BRANCH | `DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_RESOLVED_BRANCH`; `WorkflowStateStore` / `FeatureTaskRuntimeWorkflowPersistence` | Phase / step — artifact patch; Lifecycle terminal fence | T1-01 / T1-05 as applicable | classification per inventory § DurableWorkflowArtifactFamily | source-complete |
| FEATURE_TASK_RUNTIME_REVIEW_GENERATION | `DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_REVIEW_GENERATION`; `WorkflowStateStore` / `FeatureTaskRuntimeWorkflowPersistence` | Phase / step — artifact patch; Lifecycle terminal fence | T1-01 / T1-05 as applicable | classification per inventory § DurableWorkflowArtifactFamily | source-complete |
| FEATURE_TASK_RUNTIME_RUN_INVARIANTS | `DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_RUN_INVARIANTS`; `WorkflowStateStore` / `FeatureTaskRuntimeWorkflowPersistence` | Phase / step — artifact patch; Lifecycle terminal fence | T1-01 / T1-05 as applicable | classification per inventory § DurableWorkflowArtifactFamily | source-complete |
| FEATURE_TASK_RUNTIME_BUILD_GATE_PROGRESS | `DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_BUILD_GATE_PROGRESS`; `WorkflowStateStore` / `FeatureTaskRuntimeWorkflowPersistence` | Phase / step — artifact patch; Lifecycle terminal fence | T1-01 / T1-05 as applicable | classification per inventory § DurableWorkflowArtifactFamily | source-complete |
| FEATURE_TASK_RUNTIME_READINESS_EVIDENCE | `DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_READINESS_EVIDENCE`; `WorkflowStateStore` / `FeatureTaskRuntimeWorkflowPersistence` | Phase / step — artifact patch; Lifecycle terminal fence | T1-01 / T1-05 as applicable | classification per inventory § DurableWorkflowArtifactFamily | source-complete |
| FEATURE_TASK_RUNTIME_NO_CHANGE_PAUSE | `DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_NO_CHANGE_PAUSE`; `WorkflowStateStore` / `FeatureTaskRuntimeWorkflowPersistence` | Phase / step — artifact patch; Lifecycle terminal fence | T1-01 / T1-05 as applicable | classification per inventory § DurableWorkflowArtifactFamily | source-complete |
| FEATURE_TASK_RUNTIME_VALIDATION_GATE_PROGRESS | `DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_VALIDATION_GATE_PROGRESS`; `WorkflowStateStore` / `FeatureTaskRuntimeWorkflowPersistence` | Phase / step — artifact patch; Lifecycle terminal fence | T1-01 / T1-05 as applicable | classification per inventory § DurableWorkflowArtifactFamily | source-complete |

## Disk projection, git checkpoint, and absent/dropped families

| Scope item | Current source owner | Transition-matrix entry | Regression or finding ID | Limitation | Execution status |
| --- | --- | --- | --- | --- | --- |
| .feature-specs/<issue-key>-<feature>/ | `FeatureSpecPreparationWriter`; `DecompositionManifestWriter.writeFromWorkflowUpdate` | Subtask 2 — planning disk projection | T2-04 | derived/checkpoint; SQLite planning tables authoritative | tests authored / pending validate |
| refs/skill-bill/checkpoints/<issue-key>/<subtask-id>/<sequence> | `FeatureTaskRuntimeSubtaskCommitPreservation`; `GitCheckpointHistoryOperations`; prune: `FeatureTaskRuntimeCheckpointRefPrune` | Subtask 2 — checkpoint ref | F8 repaired; T2-06 pending validate | git ref outside SQLite IMMEDIATE coupling for identities artifact | tests authored / pending validate |
| review_delegated_lifecycle | dropped v22; no live writer | none | none | historical v21 only | source-complete |
| review_lifecycle_events | dropped v22 | none | none | no live writer | source-complete |
| experiment_pairs | dropped v45 SKILL-378 | none | none | no live writer | source-complete |
| experiment_observations | dropped v45 | none | none | no live writer | source-complete |
| experiment_arm_outcomes | dropped v45 | none | none | no live writer | source-complete |
| experiment_pair_leases | dropped v45 | none | none | no live writer | source-complete |
| experiment_reports | dropped v45 | none | none | no live writer | source-complete |
| Generic cancelled/interrupted/superseded/idle workflow enums | `ClosedStatusTypes.kt` / `WorkflowStatus` vocabulary | Explicitly unsupported as workflow statuses | none | not persisted as standalone tables | source-complete |
| goal_shared_preplans_pre_0_3 / goal_subtask_plans_pre_0_3 | `GoalPlanningSchemaMigrations` staging | none | none | no live writer | source-complete |
| goal_shared_preplans_pre_0_2 / STRICT_LEGACY / pre_0_4–pre_0_7 rebuild aliases | `GoalPlanningSchemaMigrations` LEGACY tables | none | none | migrate-only then dropped | source-complete |
| producer_output_evidence_pre_* rebuild tables | `DiagnosticEvidenceRepairTurnMigration` | none | none | no live writer | source-complete |
| rejected_output_diagnostics_pre_repair_turn | diagnostic rebuild migration | none | none | no live writer | source-complete |
| telemetry_outbox_legacy | `TelemetryOutboxLastErrorMigration` | none | none | no live writer | source-complete |
| feedback_events_legacy | `FeedbackEventMigration` | none | none | no live writer | source-complete |
| schema_migrations_version_keyed | `MigrationLedger` drop | none | none | no live writer | source-complete |
| feature_task_runtime_audit_generations | not live family; pre-drop DBs only | none | none | fresh migrate has no table | source-complete |

## Consumer scope items

| Scope item | Current source owner | Transition-matrix entry | Regression or finding ID | Limitation | Execution status |
| --- | --- | --- | --- | --- | --- |
| CLI goal status/watch | `GoalCliStatusCommands`; `GoalCliStatusFormatting`; `GoalCliWatchPresentation`; `GoalRunnerStatusService.status` | Subtask 2 — status projection read-only | F8; T2-06 | none | tests authored / pending validate |
| CLI workflow list/latest/get | `WorkflowService.list` / `latest` / `readableSummaries` | Lifecycle — list reads | SKILL-414 listing rows below | none | source-complete |
| CLI work list | `WorkListService`; `SQLiteWorkListRepository` | Display — work list | F9; T2-07 | none | tests authored / pending validate |
| CLI work status/board/detail | `WorkCliCommands`; `WorkBoardCommand`; `IdeStatusService.board` / `detail` | Display — board/detail | F12 candidate; T2-07; `CliWorkBoardTest` / `CliWorkStatusTest` | board scan candidate | candidate / next verification named |
| MCP feature_verify_workflow_* | `McpWorkflowToolHandlers` → `WorkflowService` | Lifecycle — verify MCP | T2-11 | none | tests authored / pending validate |
| MCP feature_task_phase_complete / block | `McpFeatureTaskSettlementHandlers` | Phase / step — settlement | F2; T1-02 | none | tests authored / pending validate |
| IntelliJ status refresh ordering | `intellij-plugin/.../StatusRefreshCoordinator.acceptsNewerStatus` (`isPostChildMonitor`) | Subtask 2 — display ordering | T2-10 | SKILL-417 child implement snapshot hold | tests authored / pending validate |
| VS Code companion status refresh | `vscode-extension/src/application/StatusRefreshCoordinator.ts` | Subtask 2 — display ordering | T2-10 | same SKILL-417 requirement | tests authored / pending validate |
| Desktop tray presentation | `DashboardController` / `TrayPresentation` | Display — tray counts | F10; T2-09 | none | tests authored / pending validate |
| Desktop data/control (Room + WorkControlService) | `RoomBoardSource` implements board/detail/control repos; `WorkControlService.request` PAUSE/STOP/CANCEL/LAUNCH | Controls — desktop mutations | SKILL-418 desktop row below | SKILL-423 Room/SQLite reads; mutations not CLI-only | source-complete |

## Classification scope items

| Scope item | Current source owner | Transition-matrix entry | Regression or finding ID | Limitation | Execution status |
| --- | --- | --- | --- | --- | --- |
| lifecycle | `ClosedStatusTypes.kt` `WorkflowStatus`; `IdeStatusPolicy.workflowLifecycle` | Lifecycle — workflow row | F1; F7 | unsupported statuses on matrix unsupported row | tests authored / pending validate |
| current-step | `GoalRunnerStatusProjectionAssembler` liveStepId; `IdeStatusProjector` current step | Display — live step | T2-01; `IdeStatusServiceGoalProjectionTest` | none | tests authored / pending validate |
| liveness | `feature_task_runtime_worker_leases`; `GoalRunnerLivenessState`; `latestLivenessSignal` | Liveness — worker lease | T1-04; T1-05; T2-08; `GoalRunnerStatusProjectionLivenessTest` | none | tests authored / pending validate |
| freshness | `IdeStatusLivenessAnchors`; `IdeStatusFreshness`; board `freshness=stale` | Freshness — stale vs live | T2-08; T2-09 | none | tests authored / pending validate |
| progress | `goal_issue_progress` / `goal_subtask_events`; IdeStatus progress fields | Progress — aggregates | none | none | source-complete |
| labels/display | `IdeStatusLifecycleState` CANCELED for abandoned; `IdeStatusPolicy.liveSection`; tray `finishedAtSource=goal_completion` | Display — labels and tray Done | F10; T2-09 | none | tests authored / pending validate |

## Deliverable group 1 — inventory and matrix completeness

| Scope item | Current source owner | Transition-matrix entry | Regression or finding ID | Limitation | Execution status |
| --- | --- | --- | --- | --- | --- |
| Inventory completeness — SQLite families | `state-ownership-inventory.md` § SQLite table families | all matrix sections cross-linked per family rows above | none | subtask 3 maintenance table included | source-complete |
| Inventory completeness — artifacts/disk/git/dropped | `state-ownership-inventory.md` § DurableWorkflowArtifactFamily, disk, git, absent | see artifact and disk sections above | none | none | source-complete |
| Matrix completeness — lifecycle | `transition-matrix.md` § Lifecycle | Lifecycle — workflow row | F1 | none | source-complete |
| Matrix completeness — phase/step | `transition-matrix.md` § Phase / step | Phase / step | F2 | none | source-complete |
| Matrix completeness — liveness | `transition-matrix.md` § Liveness | Liveness | F3 | none | source-complete |
| Matrix completeness — freshness | `transition-matrix.md` § Freshness | Freshness | T1-07 | none | source-complete |
| Matrix completeness — controls | `transition-matrix.md` § Controls | Controls | F7; T1-08 | none | source-complete |
| Matrix completeness — progress | `transition-matrix.md` § Progress | Progress | none | none | source-complete |
| Matrix completeness — display | `transition-matrix.md` § Display | Display | F9–F10 | none | source-complete |
| Matrix completeness — subtask 2 | `transition-matrix.md` § Subtask 2 | Subtask 2 | T2-01–T2-11 | none | source-complete |
| Matrix completeness — subtask 3 | `transition-matrix.md` § Subtask 3 | Subtask 3 | T3-01–T3-05 | none | source-complete |
| Matrix completeness — unsupported statuses | `transition-matrix.md` § Explicitly unsupported | unsupported row | none | none | source-complete |

## Deliverable group 2 — ranked findings and limitations

| Scope item | Current source owner | Transition-matrix entry | Regression or finding ID | Limitation | Execution status |
| --- | --- | --- | --- | --- | --- |
| Finding F1 — terminal workflow reopen fence | `findings-register.md`; `evidence_subtask_1.md` / `evidence_subtask_2.md` / `evidence_subtask_3.md` | per finding register matrix row | T1-01 | none | tests authored / pending validate |
| Finding F2 — phase settlement conflicts | `findings-register.md`; `evidence_subtask_1.md` / `evidence_subtask_2.md` / `evidence_subtask_3.md` | per finding register matrix row | T1-02 | none | tests authored / pending validate |
| Finding F3 — worker takeover fencing | `findings-register.md`; `evidence_subtask_1.md` / `evidence_subtask_2.md` / `evidence_subtask_3.md` | per finding register matrix row | T1-04 | none | tests authored / pending validate |
| Finding F4 — recovery-history artifact absent | `findings-register.md`; `evidence_subtask_1.md` / `evidence_subtask_2.md` / `evidence_subtask_3.md` | per finding register matrix row | candidate next WorkflowEngine.resumeView | not recorded as completed repair | candidate / next verification named |
| Finding F5 — recorder gap partial | `findings-register.md`; `evidence_subtask_1.md` / `evidence_subtask_2.md` / `evidence_subtask_3.md` | per finding register matrix row | T1-08 | not recorded as completed repair | candidate / next verification named |
| Finding F6 — phase recorder boundaries | `findings-register.md`; `evidence_subtask_1.md` / `evidence_subtask_2.md` / `evidence_subtask_3.md` | per finding register matrix row | T1-03 pending | none | tests authored / pending validate |
| Finding F7 — stop honesty | `findings-register.md`; `evidence_subtask_1.md` / `evidence_subtask_2.md` / `evidence_subtask_3.md` | per finding register matrix row | T1-08 | none | tests authored / pending validate |
| Finding F8 — status read purity (checkpoint prune) | `findings-register.md`; `evidence_subtask_1.md` / `evidence_subtask_2.md` / `evidence_subtask_3.md` | per finding register matrix row | T2-06 | none | tests authored / pending validate |
| Finding F9 — work list malformed row abort | `findings-register.md`; `evidence_subtask_1.md` / `evidence_subtask_2.md` / `evidence_subtask_3.md` | per finding register matrix row | T2-07 | none | tests authored / pending validate |
| Finding F10 — tray terminal vs successful completion | `findings-register.md`; `evidence_subtask_1.md` / `evidence_subtask_2.md` / `evidence_subtask_3.md` | per finding register matrix row | T2-09 | none | tests authored / pending validate |
| Finding F11 — parent completion candidate | `findings-register.md`; `evidence_subtask_1.md` / `evidence_subtask_2.md` / `evidence_subtask_3.md` | per finding register matrix row | T2-01 | not recorded as completed repair | candidate / next verification named |
| Finding F12 — board scan candidate | `findings-register.md`; `evidence_subtask_1.md` / `evidence_subtask_2.md` / `evidence_subtask_3.md` | per finding register matrix row | T2-07 | not recorded as completed repair | candidate / next verification named |
| Finding F13 — pre-install preserve verified no-finding | `findings-register.md`; `evidence_subtask_1.md` / `evidence_subtask_2.md` / `evidence_subtask_3.md` | per finding register matrix row | T3-01 | none | verified no-finding area |
| Finding F14 — destructive uninstall semantics verified no-finding | `findings-register.md`; `evidence_subtask_1.md` / `evidence_subtask_2.md` / `evidence_subtask_3.md` | per finding register matrix row | T3-04 | none | verified no-finding area |
| Finding F15 — corrupt backup read verified no-finding | `findings-register.md`; `evidence_subtask_1.md` / `evidence_subtask_2.md` / `evidence_subtask_3.md` | per finding register matrix row | T3-03 | none | verified no-finding area |
| Finding EL-422-WAL — historical WAL incident | `findings-register.md`; `evidence_subtask_1.md` / `evidence_subtask_2.md` / `evidence_subtask_3.md` | per finding register matrix row | none | no reproducible incident bytes | external limitation |

## Deliverable group 3 — confirmed repairs and boundary verification

| Scope item | Current source owner | Transition-matrix entry | Regression or finding ID | Limitation | Execution status |
| --- | --- | --- | --- | --- | --- |
| Tree repair F1–F3 | subtask 1 production fences; register marks repaired | Lifecycle / Liveness / Phase | T1-01–T1-04 | validate owns execution evidence | tests authored / pending validate |
| Tree repair F8–F10 | subtask 2 read/projection repairs | Subtask 2 display/status | T2-06–T2-09 | validate owns execution evidence | tests authored / pending validate |
| Subtask 3 maintenance production | no new Kotlin/shell repair required this audit | Subtask 3 | T3 suite definitions | none | source-complete |
| Pending validate closure | n/a | none | all T1/T2/T3 pointers | baseline reset cannot close | tests authored / pending validate |

## Deliverable group 4 — durable and consumer regression definitions (T1/T2)

| Scope item | Current source owner | Transition-matrix entry | Regression or finding ID | Limitation | Execution status |
| --- | --- | --- | --- | --- | --- |
| T1-01 | `evidence_subtask_1.md` / `spec_subtask_2` → WorkflowStateStoreTest | per test family matrix | T1-01 | none | tests authored / pending validate |
| T1-02 | `evidence_subtask_1.md` / `spec_subtask_2` → SqliteFeatureTaskPhaseSettlementRepositoryTest; FeatureTaskPhaseSettlementServiceTest | per test family matrix | T1-02 | none | tests authored / pending validate |
| T1-03 | `evidence_subtask_1.md` / `spec_subtask_2` → transactional tests + fault injection pending validate | per test family matrix | T1-03 | none | tests authored / pending validate |
| T1-04 | `evidence_subtask_1.md` / `spec_subtask_2` → WorkerTakeoverFencingTest | per test family matrix | T1-04 | none | tests authored / pending validate |
| T1-05 | `evidence_subtask_1.md` / `spec_subtask_2` → FeatureTaskRuntimeWorkerCoordinatorTest; FeatureTaskRuntimeCrashReconcilerTest | per test family matrix | T1-05 | none | tests authored / pending validate |
| T1-06 | `evidence_subtask_1.md` / `spec_subtask_2` → GoalRunnerControlStoreTest; GoalRunnerExecutionLeaseInstantTest | per test family matrix | T1-06 | none | tests authored / pending validate |
| T1-07 | `evidence_subtask_1.md` / `spec_subtask_2` → StandalonePhaseStatusStoreTest | per test family matrix | T1-07 | none | tests authored / pending validate |
| T1-08 | `evidence_subtask_1.md` / `spec_subtask_2` → GoalRunnerStopVerbTest; JdkFeatureTaskRuntimeWorkerSupervisorTest | per test family matrix | T1-08 | none | tests authored / pending validate |
| T2-01 | `evidence_subtask_1.md` / `spec_subtask_2` → OpenCiMonitorStatusTest | per test family matrix | T2-01 | none | tests authored / pending validate |
| T2-02 | `evidence_subtask_1.md` / `spec_subtask_2` → finalization duplicate finish tests | per test family matrix | T2-02 | none | tests authored / pending validate |
| T2-03 | `evidence_subtask_1.md` / `spec_subtask_2` → CI integration finalization fixture | per test family matrix | T2-03 | none | tests authored / pending validate |
| T2-04 | `evidence_subtask_1.md` / `spec_subtask_2` → StandalonePlanPreparation / checkpoint import | per test family matrix | T2-04 | none | tests authored / pending validate |
| T2-05 | `evidence_subtask_1.md` / `spec_subtask_2` → purge/control ownership tests | per test family matrix | T2-05 | none | tests authored / pending validate |
| T2-06 | `evidence_subtask_1.md` / `spec_subtask_2` → GoalRunnerStatusReadOnlyMutationTest | per test family matrix | T2-06 | none | tests authored / pending validate |
| T2-07 | `evidence_subtask_1.md` / `spec_subtask_2` → SQLiteWorkListRepositoryTest; CliWorkBoardTest; CliWorkStatusTest | per test family matrix | T2-07 | none | tests authored / pending validate |
| T2-08 | `evidence_subtask_1.md` / `spec_subtask_2` → GoalRunnerStatusProjectionLivenessTest; IdeStatus liveness | per test family matrix | T2-08 | none | tests authored / pending validate |
| T2-09 | `evidence_subtask_1.md` / `spec_subtask_2` → TransitionTrackerTest; ProjectionTest | per test family matrix | T2-09 | none | tests authored / pending validate |
| T2-10 | `evidence_subtask_1.md` / `spec_subtask_2` → IntelliJ/VS Code StatusRefreshCoordinator | per test family matrix | T2-10 | none | tests authored / pending validate |
| T2-11 | `evidence_subtask_1.md` / `spec_subtask_2` → VerifyOperation / MCP verify supersede | per test family matrix | T2-11 | none | tests authored / pending validate |

## Deliverable group 5 — truthful complete/running/administrative state

| Scope item | Current source owner | Transition-matrix entry | Regression or finding ID | Limitation | Execution status |
| --- | --- | --- | --- | --- | --- |
| Terminal fence blocks false completion | `WorkflowStateWrites` terminal snapshot equality | Lifecycle | F1; T1-01 | none | tests authored / pending validate |
| Stop honesty preserves pause on failed stop | `GoalRunnerStatusControlVerbs.stop` | Controls | F7; T1-08 | none | tests authored / pending validate |
| Parent completion candidate | `GoalRunnerFinalization`; `GoalRunnerCiMonitor`; `admittedParentComplete` | Subtask 2 parent finalization | F11; T2-01 | candidate | candidate / next verification named |
| Operator pause vs parked parent | `GoalRunnerStatusControlVerbs.pause`; matrix pause boundary | Controls; Subtask 2 | T2-10; SKILL-417 | none | tests authored / pending validate |
| Administrative abandon not success | `IdeStatusLifecycleState.CANCELED`; supersede monitors | Display | F10 | none | tests authored / pending validate |

## Deliverable group 6 — SQLite maintenance recovery (T3)

| Scope item | Current source owner | Transition-matrix entry | Regression or finding ID | Limitation | Execution status |
| --- | --- | --- | --- | --- | --- |
| T3-01 | InstallerShellMaintenanceRecoveryTest | Subtask 3 | T3-01; F13–F15 as applicable | none | tests authored / pending validate |
| T3-02 | SqliteMaintenanceRecoveryRegressionTest | Subtask 3 | T3-02; F13–F15 as applicable | none | tests authored / pending validate |
| T3-03 | SqliteMaintenanceRecoveryRegressionTest | Subtask 3 | T3-03; F13–F15 as applicable | none | tests authored / pending validate |
| T3-04 | InstallerShellMaintenanceRecoveryTest; SkillBillUninstallDestructiveSemanticsTest | Subtask 3 | T3-04; F13–F15 as applicable | none | tests authored / pending validate |
| T3-05 | SqliteMaintenanceRecoveryRegressionTest | Subtask 3 | T3-05; F13–F15 as applicable | none | tests authored / pending validate |
| F13–F15 verified no-finding areas | `findings-register.md` | Subtask 3 | T3-01/T3-03/T3-04 | none | verified no-finding area |
| EL-422-WAL | evidence limitation only | none | EL-422-WAL | historical WAL unrecoverable | external limitation |

## Deliverable group 7 — contracts and diagnostics

| Scope item | Current source owner | Transition-matrix entry | Regression or finding ID | Limitation | Execution status |
| --- | --- | --- | --- | --- | --- |
| DatabaseFailureCode ACCESS/CORRUPT | `databaseAccessError` at SQLite open | Subtask 3 session boundary | T3-03; T3-05 | none | source-complete |
| invalidWorkflowStateSchemaError | `WorkflowStateSqlReads` refuses corrupt JSON | Lifecycle list/read | T3-05 | none | source-complete |
| UninstallMutationRecorder failed_with_degradations | `SkillBillUninstallService.apply` partial failure | Subtask 3 application uninstall | T3-04 | none | source-complete |
| WORKFLOW_STATE_CONTRACT_VERSION 0.3 | `WorkflowStateContractVersions.kt` | Lifecycle admission | none | no bump solely for audit | source-complete |
| WORKFLOW_STATE_READABLE_CONTRACT_VERSIONS 0.1/0.2/0.3 | `WorkflowStateContractVersions.kt`; `WorkflowStateWrites` bind restamp | SKILL-414 readable rows | 414 rows below | none | source-complete |
| work-board 1.2 incompatible_record | `IdeStatusService` / work-board contract | Display | T2-07 | none | tests authored / pending validate |

## SKILL-414 state requirements (current source)

| Scope item | Current source owner | Transition-matrix entry | Regression or finding ID | Limitation | Execution status |
| --- | --- | --- | --- | --- | --- |
| 414(a) pre-current rows readable/abandonable | `WORKFLOW_STATE_READABLE_CONTRACT_VERSIONS`; `WorkflowStateSchemaValidator`; `WorkflowStateWrites` restamp | Lifecycle — ordinary reads | none | not accepted from archived SKILL-414 folder | source-complete |
| 414(b) listing without collapse | `WorkflowService.list` / `readableSummaries` skips `isInvalidWorkflowStateFailure` per row | Lifecycle — list | T2-07 related | none | source-complete |
| 414(c) latest without family failure | `WorkflowService.latest` uses `readableSummaries` | Lifecycle — latest | none | unreadable newest sibling skipped | source-complete |
| 414(d) work list drops unreadable TASK_RUNTIME/VERIFY | `WorkListService.validateWorkflowSnapshots` / `retainReadableWork` | Display — work list | F9; T2-07 | none | tests authored / pending validate |
| 414(e) IDE status per-candidate wrap | `IdeStatusService` maps invalid failure; `IdeStatusProjector.projectWorkflowFamily` | Display | F9 | readable sibling still projects | source-complete |
| 414(f) verify supersede past unreadable row | `VerifyOperation.supersedeParked`; store.write Rejected | Lifecycle — verify | T2-11 | none | tests authored / pending validate |

## SKILL-417 state requirements (current source)

| Scope item | Current source owner | Transition-matrix entry | Regression or finding ID | Limitation | Execution status |
| --- | --- | --- | --- | --- | --- |
| 417(a) 0AC-46 live monitor/PR/CI projection not implement/plan | `IdeStatusService` / `IdeStatusProjector` / `IdeStatusSelectionPolicy`; `IdeStatusServiceGoalProjectionTest` | Subtask 2 display | T2-01 shapes | not F8 theme only | tests authored / pending validate |
| 417(b) latest_liveness_signal agrees with live step | `GoalRunnerStatusProjectionAssembler.resolvedLivenessSignal(liveStepId)` | Liveness | T2-08; `GoalRunnerStatusProjectionLivenessTest` | not parked-parent PAUSED with step=plan | tests authored / pending validate |
| 417(c) acceptsNewerStatus child implement snapshot hold | IntelliJ + VS Code `StatusRefreshCoordinator` `isPostChildMonitor` / `isLiveOverDone` | Subtask 2 display | T2-10 | evidence_subtask_2 no IntelliJ change ≠ closure | tests authored / pending validate |
| 417(d) parked parent vs operator pause | matrix pause request vs pause boundary; `GoalRunnerStatusControlVerbs.pause` | Controls; Subtask 2 parent finalization | T2-10 | operator pause shows Paused; live goal monitor differs | tests authored / pending validate |

## SKILL-418 state requirements (current source)

| Scope item | Current source owner | Transition-matrix entry | Regression or finding ID | Limitation | Execution status |
| --- | --- | --- | --- | --- | --- |
| 418(a) board/detail from admitted durable facts | `IdeStatusService.board` / `detail`; `IdeStatusProjector`; `WorkCliCommands`; `WorkBoardModels` | Display | F12 candidate; T2-07 | no second derivation in CLI/tray | candidate / next verification named for F12 |
| 418(b) absent_database vs stale vs idle vs incompatible | `WorkDetailModels` / `IdeStatusModels` ABSENT_DATABASE; `CliWorkBoardTest` / `CliWorkStatusTest` | Display | T2-07; T2-09 | distinct diagnostics | tests authored / pending validate |
| 418(c) abandoned/cancelled/superseded not successful execution | `TransitionTracker` / `DashboardController.project` require `finishedAtSource=goal_completion`; `IdeStatusLifecycleState.CANCELED` | Display | F10; T2-09 | supersede monitors ≠ workflow success | tests authored / pending validate |
| 418(d) desktop mutations current path | `RoomBoardSource` → `WorkControlService.request` PAUSE/STOP (+ CANCEL/LAUNCH) | Controls | none | SKILL-423 Room reads; not archived CLI-only-only constraint | source-complete |

## Defects without completed repair

| Item | Status | Next verification | Limitation |
| --- | --- | --- | --- |
| F4 recovery-history artifact | candidate | WorkflowEngine.resumeView / continueStatusFor; subtask 2/validate aggregate | not completed repair |
| F5 recorder gap | partial | validate T1-08; remaining artifact mutators | not completed repair |
| F11 parent completion | candidate | validate T2-01 OpenCiMonitorStatusTest | not completed repair |
| F12 board scan | candidate | validate T2-07 | not completed repair |
| EL-422-WAL | external limitation | none — no fabricated history | historical incident evidence unavailable |

No unresolved **source-confirmed** maintenance defect remains without a test pointer. F4, F5, F11, F12, and EL-422-WAL are not recorded as completed repairs.
