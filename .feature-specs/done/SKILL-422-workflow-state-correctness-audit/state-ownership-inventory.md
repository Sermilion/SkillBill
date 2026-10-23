# SKILL-422 subtask 1: state and ownership inventory

Source basis: `DatabaseSchema.kt`, `DatabaseSchemaStatements.kt`, `DatabaseMigrationEntries.kt`, area migrations under `runtime-infra/sqlite/.../migration/`, `DesktopReadSchema.kt`, `DurableWorkflowArtifactFamily.kt`, and production writers cited per row.

**Classifications:** authoritative (lifecycle truth); derived/checkpoint (rebuildable projection or checkpoint of named authority); cache (invalidation-only, not lifecycle truth); telemetry-only; supporting evidence/configuration.

**Per-family fields (every row below):**

| Field | Meaning |
| --- | --- |
| Schema / migration | Base DDL in `DatabaseSchemaStatements` and/or migration name/version in `DatabaseMigrationEntries` |
| Row identity | Primary key or stable natural key |
| Immutable vs mutable | PK and admission-governed columns vs columns updated in place |
| Contract admission | CHECK constraints, `contract_version` columns, or service-layer refusal |
| Source of truth | Authoritative store when this row is not authoritative |
| Engine writers | Domain/engine symbols that initiate durable writes |
| SQLite writers | Store/repository methods executing SQL |
| CLI / MCP triggers | Operator or agent entry points |
| Projection consumers | Desktop revision topics, tray, work board, history readers |
| Transaction owner | `SQLiteDatabaseSessionFactory.transaction` (BEGIN IMMEDIATE, busy retry via `ConnectionTransactions` / `DatabaseRuntime.SELF_MANAGED_WRITE_BUSY_ATTEMPTS`) or `selfManagedWrite` replay; reads use BEGIN DEFERRED |
| Terminal evidence | What durable fact proves a terminal or accepted outcome |
| Recovery | Typed conflict, rebuild path, or explicit absent-store note |

---

## `DatabaseSchema.tableNames` versus migration-created tables

`DatabaseSchema.tableNames` in `DatabaseSchema.kt` is the in-code schema registry set used for coherence checks; it is **not** the same object as the `schema_migrations` applied-history ledger.

| Registry member | In `tableNames`? | Live DDL owner |
| --- | --- | --- |
| Most lifecycle, review, telemetry, session tables | yes | `DatabaseSchemaStatements` base schema |
| `standalone_phase_status`, `ide_status_execution_registry`, `ide_status_workflow_execution` | yes | v50 `add-standalone-phase-status-authority` (`StandalonePhaseStatusMigration.createSchema`); in `tableNames` but no CREATE in `DatabaseSchemaStatements` |
| `feature_task_phase_settlements` | **no** | v35 `add-feature-task-phase-settlements` (`FeatureTaskPhaseSettlementsMigration`) |
| `review_accounting` | **no** | v12 `add-bounded-review-accounting` + `DatabaseSchemaStatements` CREATE |
| `operation_proposals` | **no** | v47 `add-operation-proposals` (`OperationProposalsMigration`) |
| `work_store_identity` | **no** | v53 `bind-work-observations-and-accepted-results` (`DesktopReadSchema.IDENTITY_TABLE`) |
| `desktop_change_revisions` | **no** | v52 `installDesktopReadCompatibility` (`DesktopReadSchema.REVISIONS_TABLE`) |
| `room_master_table` | **no** | v52 `installDesktopReadCompatibility` (Room sentinel) |
| `schema_migrations` | yes | `DatabaseSchemaStatements`; PK `name` only (`version` NOT NULL, not in PK) |

`feature_task_phase_settlements` holds MCP/agent phase settlement envelopes with PK `(workflow_id, phase_id, attempt)`. It is **not** listed in `DatabaseSchema.tableNames` (registry omission). It is also **not** the `schema_migrations` history table.

---

## SQLite table families

### `schema_migrations`

| Field | Value |
| --- | --- |
| Classification | supporting evidence/configuration |
| Schema / migration | Base `DatabaseSchemaStatements` |
| Row identity | `name` (TEXT PRIMARY KEY) |
| Immutable vs mutable | `name` immutable; `version`, `applied_at` set at apply |
| Contract admission | none |
| Source of truth | migration runner apply order |
| Engine writers | migration apply pipeline |
| SQLite writers | migration insert into `schema_migrations` |
| CLI / MCP triggers | implicit on any DB open/migrate |
| Projection consumers | none |
| Transaction owner | migration connection apply (single-threaded migrate) |
| Terminal evidence | row present for migration name |
| Recovery | re-run migrate; name PK prevents duplicate apply |

### `feature_task_phase_settlements`

| Field | Value |
| --- | --- |
| Classification | authoritative (phase settlement envelope per workflow/phase/attempt) |
| Schema / migration | v35 `add-feature-task-phase-settlements` |
| Row identity | `(workflow_id, phase_id, attempt)` |
| Immutable vs mutable | identity columns immutable; `kind`, `envelope_json`, `recorded_at` updated via upsert semantics |
| Contract admission | `settlementAdmissionRefusal` in `SqliteFeatureTaskPhaseSettlementStore`; runtime workflow terminal fence |
| Source of truth | this table for accepted/blocked phase settlement envelope |
| Engine writers | `FeatureTaskPhaseSettlementService.complete` / `block` |
| SQLite writers | `SqliteFeatureTaskPhaseSettlementRepository.upsert` → `SqliteFeatureTaskPhaseSettlementStore.upsert` (`databaseSessionFactory.transaction`, BEGIN IMMEDIATE) |
| CLI / MCP triggers | MCP `feature_task_phase_complete`, `feature_task_phase_block` (`McpFeatureTaskSettlementHandlers.kt`) |
| Projection consumers | `FeatureTaskPhaseSettlementService.findEnvelope` / `history`; `WorkExecutionHistory` / `resolveExecutionReason` via `unitOfWork.featureTaskPhaseSettlements.findReasonAtOrBefore`; `desktop_change_feature_task_phase_settlements_*` triggers → `desktop_change_revisions` for `DesktopReadSchema.TOPICS` (`goals` and `phases`) / tray |
| Transaction owner | `SqliteFeatureTaskPhaseSettlementRepository.upsert` → `transaction` |
| Terminal evidence | accepted envelope row; refusal codes on contradiction |
| Recovery | typed settlement conflict; not last-write-wins across attempts |

### `operation_proposals`

| Field | Value |
| --- | --- |
| Classification | supporting evidence/configuration (operation confirmation gate) |
| Schema / migration | v47 `add-operation-proposals` |
| Row identity | `token` (TEXT PRIMARY KEY) |
| Immutable vs mutable | `token`, `operation_id`, `repo_root`, `anchors_json`, `proposal_value`, `created_at` immutable at create; `superseded_at`, `consumed_at` mutable |
| Contract admission | gate consumes token once |
| Source of truth | proposal row until consumed/superseded |
| Engine writers | `OperationConfirmationGate` |
| SQLite writers | `SqliteOperationProposalStore.createSupersedingPrior`, `markConsumed`; `SqliteOperationProposalRepository` wraps in `transaction` |
| CLI / MCP triggers | operation confirmation flows (release, feature-guard, etc.) |
| Projection consumers | none durable beyond gate read |
| Transaction owner | `SqliteOperationProposalRepository` → `transaction` |
| Terminal evidence | `consumed_at` set |
| Recovery | supersede prior token; refuse double consume |

### `work_store_identity`

| Field | Value |
| --- | --- |
| Classification | supporting evidence/configuration (desktop store identity) |
| Schema / migration | v53 `bind-work-observations-and-accepted-results` |
| Row identity | `id` CHECK `(id = 1)`; `store_id` immutable |
| Immutable vs mutable | `store_id` immutable after insert |
| Contract admission | single-row CHECK |
| Source of truth | this row for desktop work-list store id |
| Engine writers | migration seed insert |
| SQLite writers | migration INSERT; reads `SQLiteWorkListRepository` |
| CLI / MCP triggers | none direct |
| Projection consumers | work list revision sum with `desktop_change_revisions` |
| Transaction owner | migrate insert; reads DEFERRED |
| Terminal evidence | row id=1 present |
| Recovery | reinstall/migrate |

### `desktop_change_revisions`

| Field | Value |
| --- | --- |
| Classification | **cache** |
| Schema / migration | v52 `installDesktopReadCompatibility`; v54 `separateAgentActivityRevisions` splits activity topic |
| Row identity | `topic` (TEXT PRIMARY KEY) |
| Immutable vs mutable | `topic` immutable; `revision` monotonic bump |
| Contract admission | none |
| Source of truth | underlying authoritative table writes (triggers) |
| Engine writers | n/a (trigger side effect) |
| SQLite writers | AFTER INSERT/UPDATE/DELETE triggers on `goal_issue_progress`, `goal_runner_controls`, `goal_planning_preparations`, `goal_shared_preplans`, `goal_subtask_plans`, `goal_subtask_events`, `feature_task_phase_settlements`, `feature_task_workflows`, `feature_task_execution_identities`, `feature_task_runtime_worker_leases`, `feature_verify_workflows`, `standalone_phase_status`, `ide_status_workflow_execution`, `agent_activity_stamps`, `worktree_edit_journal` per `installDesktopChangeRevisions` (`DesktopChangeRevisions.kt`); v54 `separateAgentActivityRevisions` moves `agent_activity_stamps` bumps from `DesktopReadSchema.TOPICS` to `ACTIVITY_TOPIC` only |
| CLI / MCP triggers | any mutation on source tables |
| Projection consumers | `SQLiteDatabaseSessionFactory` topic reads; `DesktopCommitSignalPublisher`; tray topics `goals` / `phases` (`DesktopReadSchema.TOPICS`) and `activity` (`DesktopReadSchema.ACTIVITY_TOPIC` after v54) |
| Transaction owner | same IMMEDIATE transaction as source-table write (trigger) |
| Terminal evidence | revision integer |
| Recovery | rebuild from full table scan not implemented; cache repopulates on next bump |

### `room_master_table`

| Field | Value |
| --- | --- |
| Classification | supporting evidence/configuration (Room compatibility sentinel) |
| Schema / migration | v52 `installDesktopReadCompatibility` |
| Row identity | `id` INTEGER PRIMARY KEY (fixed row id 42) |
| Immutable vs mutable | `identity_hash` set to `DesktopReadSchema.ROOM_IDENTITY_HASH` |
| Contract admission | hash equality check in `DatabaseRuntime` |
| Source of truth | install compatibility contract |
| Engine writers | migration install |
| SQLite writers | `installDesktopReadCompatibility` |
| CLI / MCP triggers | none |
| Projection consumers | Room/desktop read compatibility probe |
| Transaction owner | migrate |
| Terminal evidence | row 42 with expected hash |
| Recovery | re-run v52 install path |

### `feature_task_workflows`

| Field | Value |
| --- | --- |
| Classification | authoritative |
| Schema / migration | base `DatabaseSchemaStatements` |
| Row identity | `workflow_id` |
| Immutable vs mutable | `workflow_id` immutable; status, steps, artifacts, timestamps mutable under terminal fence |
| Contract admission | `contract_version`; `WorkflowStateWrites` terminal snapshot equality |
| Source of truth | this row |
| Engine writers | `FeatureTaskRuntimeRunEntry` → `WorkflowService.openFeatureTask`; durable updates via `WorkflowService.update` / `resume` / `continueWorkflow` and `FeatureTaskRuntimeWorkflowPersistence` |
| SQLite writers | `WorkflowStateStore` / `FeatureTaskWorkflowRowStore.upsertFeatureTaskWorkflowRow` |
| CLI / MCP triggers | CLI `FeatureTaskRuntimeRunExecution` → `FeatureTaskRuntimeRunEntry` (no `feature_task_workflow_*` MCP tools; verify workflow MCP is separate on `feature_verify_workflows`) |
| Projection consumers | desktop revision triggers; `WorkBoardCandidateStore`; tray |
| Transaction owner | `SQLiteDatabaseSessionFactory.transaction` (BEGIN IMMEDIATE) via UoW / `WorkflowStateStore` write path |
| Terminal evidence | `workflow_status` terminal + `finished_at` |
| Recovery | explicit recovery/rerun APIs; terminal WHERE rejects reopen |

### `feature_verify_workflows`

| Field | Value |
| --- | --- |
| Classification | authoritative |
| Schema / migration | base schema |
| Row identity | `workflow_id` |
| Immutable vs mutable | same pattern as feature_task_workflows |
| Contract admission | `contract_version` default on column |
| Source of truth | this row |
| Engine writers | `WorkflowService.open` / `update` / `continueWorkflow` (`WorkflowFamilyKind.VERIFY`); `VerifyOperation` as engine consumer of those methods (not a second persistence owner) |
| SQLite writers | `WorkflowStateStore.saveRecord` → `FeatureVerifyWorkflowStateStore.saveWorkflow` (upsert on caller `Connection`) |
| CLI / MCP triggers | CLI `skill-bill verify-workflow open` / `update` / `continue` (`WorkflowCliCommands.kt`); MCP `feature_verify_workflow_open` / `update` / `continue` (`McpWorkflowToolHandlers.kt`); `resume` / `show` / `get` / `list` / `latest` are read-only (`database.read`) |
| Projection consumers | desktop triggers on verify workflow mutations |
| Transaction owner | `SQLiteDatabaseSessionFactory.transaction` opened by `WorkflowServiceInputMapping.persistOpenedWorkflow` / `WorkflowService.update` / `continueWorkflow`; store SQL on caller connection |
| Terminal evidence | terminal workflow_status + finished_at |
| Recovery | verify-specific resume |

### `feature_task_execution_identities`

| Field | Value |
| --- | --- |
| Classification | authoritative |
| Schema / migration | base schema |
| Row identity | `workflow_id` |
| Immutable vs mutable | admission fields immutable after INSERT; `contract_version` CHECK `0.1` |
| Contract admission | CHECK on `contract_version`, `mode`, `route_scope` |
| Source of truth | this row per workflow |
| Engine writers | `WorkflowServiceInputMapping`, `WorkflowServiceFeatureTaskIdentityRepair`, `WorkflowGoalRunnerChildWorkflowPersistence` |
| SQLite writers | `FeatureTaskExecutionLookupStore.saveFeatureTaskExecutionIdentity` (INSERT ON CONFLICT DO NOTHING) |
| CLI / MCP triggers | workflow open/admit paths |
| Projection consumers | identity lookup indexes |
| Transaction owner | same UoW as workflow admit `transaction` |
| Terminal evidence | row exists for workflow |
| Recovery | repair identity path |

### `feature_task_runtime_worker_leases`

| Field | Value |
| --- | --- |
| Classification | authoritative (liveness) |
| Schema / migration | base schema |
| Row identity | `workflow_id` (PK); `owner_token`, `generation`, `lease_state`, `expires_at`, `heartbeat_at`, `pid`, etc. are **mutable fencing fields** |
| Immutable vs mutable | `workflow_id` immutable; fencing columns mutable under coordinator CAS |
| Contract admission | `contract_version`; CHECK on `lease_state`, `generation`, `pid` |
| Source of truth | this row |
| Engine writers | `FeatureTaskRuntimeWorkerCoordinator` |
| SQLite writers | `FeatureTaskRuntimeWorkerStore.acquireFeatureTaskRuntimeWorker`, `transferFeatureTaskRuntimeWorker`, `heartbeatFeatureTaskRuntimeWorker`, `releaseFeatureTaskRuntimeWorker` |
| CLI / MCP triggers | indirect via goal/feature-task run admission |
| Projection consumers | `JdkFeatureTaskRuntimeWorkerSupervisor`; desktop revision triggers |
| Transaction owner | `FeatureTaskRuntimeWorkerCoordinator` → `SQLiteDatabaseSessionFactory.transaction` for acquire/recover/heartbeat/release; `FeatureTaskRuntimeWorkerStore` writes on caller connection; `inNestedWriteTransaction` only when `transactionActive` is false |
| Terminal evidence | released lease or terminal workflow refusal |
| Recovery | crash reconciler `FeatureTaskRuntimeCrashReconciler` (recovery path, not a second live write owner for this row) |

### `goal_runner_controls`

| Field | Value |
| --- | --- |
| Classification | authoritative |
| Schema / migration | base schema |
| Row identity | `parent_workflow_id` |
| Immutable vs mutable | whole JSON document replaced under CAS |
| Contract admission | generation/owner checks in coordinator |
| Source of truth | this row |
| Engine writers | `GoalRunnerStatusControlVerbs.pause` / `stop` / `resume`; `WorkflowGoalRunnerManifestStore` control paths |
| SQLite writers | `GoalRunnerControlStore.persistControlState` (INSERT ON CONFLICT SQL on caller `Connection`; does not open a transaction) |
| CLI / MCP triggers | `skill-bill goal pause|stop|resume|reset` (`GoalPauseCommand` and siblings) via `GoalRunnerStatusService` |
| Projection consumers | goal status readers; desktop `GOALS_TOPIC` revision triggers |
| Transaction owner | `GoalRunnerControlCoordinator.persistControlState` / `updateControlState` → `database.transaction` (BEGIN IMMEDIATE) wrapping store writes |
| Terminal evidence | completion fields in JSON |
| Recovery | pause preserved on failed stop |

### `goal_planning_preparations`

| Field | Value |
| --- | --- |
| Classification | authoritative |
| Schema / migration | base schema |
| Row identity | `(parent_goal_workflow_id, subtask_id)` |
| Immutable vs mutable | preparation payload mutable until sweep completes |
| Contract admission | planning contract columns |
| Source of truth | this table |
| Engine writers | `GoalPlanningPreparationCheckpoint.checkpoint` / sweep paths |
| SQLite writers | `GoalPlanningPreparationRecordSql.markPrepared` via `unitOfWork.goalPlanningPreparations` (`inNestedWriteTransaction`) |
| CLI / MCP triggers | goal planning / preplan / plan phases |
| Projection consumers | desktop `GOALS_TOPIC` revision triggers |
| Transaction owner | `GoalPlanningPreparationCheckpoint` → `database.selfManagedWrite` (except `checkpointBundle` uses `database.transaction`) |
| Terminal evidence | preparation status columns |
| Recovery | replan/reset |

### `goal_shared_preplans`

| Field | Value |
| --- | --- |
| Classification | authoritative |
| Schema / migration | base schema |
| Row identity | `parent_goal_workflow_id` |
| Immutable vs mutable | checkpoint vs replace semantics |
| Contract admission | planning contract |
| Source of truth | this table (disk `.feature-specs` is projection) |
| Engine writers | preplan phases via `GoalPlanningPreparationCheckpoint.checkpointSharedPreplan` / `recheckpoint*` |
| SQLite writers | `SharedGoalPreplanStore.checkpointSharedPreplan`, `replaceSharedPreplan` via `unitOfWork.goalPlanningPreparations` |
| CLI / MCP triggers | goal preplan phases |
| Projection consumers | disk spec writers optional; desktop `GOALS_TOPIC` triggers |
| Transaction owner | `GoalPlanningPreparationCheckpoint` → `database.selfManagedWrite` (bundle path uses `database.transaction`) |
| Terminal evidence | checkpoint row |
| Recovery | replace checkpoint |

### `goal_subtask_plans`

| Field | Value |
| --- | --- |
| Classification | authoritative |
| Schema / migration | base schema |
| Row identity | `(parent_goal_workflow_id, subtask_id)` |
| Immutable vs mutable | plan blob mutable via checkpoint/replace |
| Contract admission | planning contract |
| Source of truth | this table |
| Engine writers | plan phases via `GoalPlanningPreparationCheckpoint.checkpointSubtaskPlan` / `recheckpoint*` |
| SQLite writers | `GoalSubtaskPlanStore.checkpointSubtaskPlan`, `replaceSubtaskPlan` via `unitOfWork.goalPlanningPreparations` |
| CLI / MCP triggers | goal plan phases |
| Projection consumers | manifest readers; desktop `GOALS_TOPIC` triggers |
| Transaction owner | `GoalPlanningPreparationCheckpoint` → `database.selfManagedWrite` (bundle path uses `database.transaction`) |
| Terminal evidence | plan checkpoint |
| Recovery | replan |

### `goal_run_sessions`

| Field | Value |
| --- | --- |
| Classification | derived/checkpoint |
| Schema / migration | v3 `GoalTelemetryMigration` + base columns |
| Row identity | `workflow_id` |
| Immutable vs mutable | session fields updated on start/finish |
| Contract admission | telemetry contract |
| Source of truth | goal runner start/finish events (not workflow row replacement) |
| Engine writers | `LifecycleTelemetryGoalEmission.goalStarted` / `goalFinished` |
| SQLite writers | `GoalTelemetrySave.saveGoalStarted` / `saveGoalFinished` via `LifecycleTelemetryStore` → `LifecycleTelemetryGoalSessionAdapter` on caller `Connection` |
| CLI / MCP triggers | CLI `skill-bill goal` run via `GoalRunnerTelemetryEmitter`; MCP none for these tables |
| Projection consumers | goal telemetry readers |
| Transaction owner | `LifecycleTelemetryGoalEmission` → `database.transaction`; adapter/save SQL on caller connection |
| Terminal evidence | finished session timestamp |
| Recovery | rebuild from event log not primary |

### `goal_subtask_events`

| Field | Value |
| --- | --- |
| Classification | supporting evidence |
| Schema / migration | base schema |
| Row identity | `(issue_key, subtask_id, workflow_id)` |
| Immutable vs mutable | append-oriented |
| Contract admission | none |
| Source of truth | goal runner transitions |
| Engine writers | `LifecycleTelemetryGoalEmission.goalSubtaskFinished` |
| SQLite writers | `GoalTelemetrySave.saveGoalSubtaskFinished` via `LifecycleTelemetryGoalSessionAdapter` on caller `Connection` |
| CLI / MCP triggers | CLI `skill-bill goal` run via `GoalRunnerTelemetryEmitter`; MCP none |
| Projection consumers | history projections |
| Transaction owner | `LifecycleTelemetryGoalEmission` → `database.transaction`; adapter/save SQL on caller connection |
| Terminal evidence | event type terminal |
| Recovery | append-only |

### `goal_issue_progress`

| Field | Value |
| --- | --- |
| Classification | derived/checkpoint |
| Schema / migration | base schema |
| Row identity | `(parent_workflow_id, issue_key)` |
| Immutable vs mutable | progress aggregates mutable |
| Contract admission | none |
| Source of truth | goal runner issue segment lifecycle (not a separate store) |
| Engine writers | `LifecycleTelemetryGoalEmission.goalStarted` / `goalFinished` / `goalIssueFinished` (recover path) |
| SQLite writers | `GoalTelemetrySave.recordGoalIssueSegmentStarted` / `recordGoalIssueSegmentEnd`; `saveGoalIssueFinished` may call `GoalIssueProgressRecovery.recoverGoalIssueProgress`; adapter `cancelGoalIssue` / `restartCanceledGoalIssue` UPDATE on caller `Connection` |
| CLI / MCP triggers | CLI `skill-bill goal` run via `GoalRunnerTelemetryEmitter`; MCP none |
| Projection consumers | tray/UI issue cards |
| Transaction owner | `LifecycleTelemetryGoalEmission` → `database.transaction`; adapter/save SQL on caller connection |
| Terminal evidence | progress terminal fields |
| Recovery | re-aggregate |

### `standalone_phase_status`

| Field | Value |
| --- | --- |
| Classification | authoritative (standalone monitor) |
| Schema / migration | v50 `add-standalone-phase-status-authority` (`StandalonePhaseStatusMigration.createSchema`) |
| Row identity | `execution_id` |
| Immutable vs mutable | lease and status under CAS |
| Contract admission | finite lease |
| Source of truth | this row |
| Engine writers | `StandalonePhaseStatusPublisherFactory`, `StandalonePhaseStatusEventSink` |
| SQLite writers | `StandalonePhaseStatusStore.register` / `update` / `registerWorkflow` (`registerInTransaction` / `updateInTransaction` / `registerWorkflowInTransaction` on caller `Connection`) |
| CLI / MCP triggers | standalone phase run |
| Projection consumers | IDE status; desktop revision triggers |
| Transaction owner | `StandalonePhaseStatusEventSink.ensureRegistered` / `persist` and `StandalonePhaseStatusPublisherFactory.registerWorkflow` → `SQLiteDatabaseSessionFactory.transaction`; store uses `inNestedWriteTransaction(diagnostics, transactionActive)` |
| Terminal evidence | terminal status + expiry |
| Recovery | owner-only renewal path when proven necessary |

### `ide_status_execution_registry`

| Field | Value |
| --- | --- |
| Classification | derived/checkpoint |
| Schema / migration | v50 `add-standalone-phase-status-authority` |
| Row identity | `repository_identity` |
| Immutable vs mutable | registration rows |
| Contract admission | none |
| Source of truth | standalone/workflow execution registrations |
| Engine writers | IDE status pipeline |
| SQLite writers | `StandalonePhaseStatusStore.ensureStore` INSERT OR IGNORE |
| CLI / MCP triggers | standalone/workflow registration |
| Projection consumers | desktop tray execution list |
| Transaction owner | store `transaction` |
| Terminal evidence | high-water `run_sequence` |
| Recovery | re-register |

### `ide_status_workflow_execution`

| Field | Value |
| --- | --- |
| Classification | derived/checkpoint |
| Schema / migration | v50 `add-standalone-phase-status-authority` |
| Row identity | `execution_id` |
| Immutable vs mutable | workflow execution registration |
| Contract admission | none |
| Source of truth | workflow execution registrations |
| Engine writers | IDE status |
| SQLite writers | `StandalonePhaseStatusStore.registerWorkflowInTransaction` INSERT |
| CLI / MCP triggers | workflow run registration |
| Projection consumers | desktop tray |
| Transaction owner | same transaction as registration |
| Terminal evidence | registration row |
| Recovery | re-register |

### `quality_check_sessions`

| Field | Value |
| --- | --- |
| Classification | supporting evidence/configuration |
| Schema / migration | base `DatabaseSchemaStatements` |
| Row identity | `session_id` |
| Immutable vs mutable | open/close timestamps and outcome fields |
| Contract admission | session contract columns |
| Source of truth | workflow rows remain lifecycle authority |
| Engine writers | quality-check lifecycle telemetry |
| SQLite writers | `QualityCheckTelemetrySave.saveQualityCheckStarted` / `saveQualityCheckFinished` → `LifecycleTelemetryQualityCheckSessionAdapter` |
| CLI / MCP triggers | MCP `quality_check_started` / `quality_check_finished` (`McpLifecycleToolHandlers.kt` / `McpToolRegistry`) |
| Projection consumers | `StaleSessionReconciler` indexes |
| Transaction owner | lifecycle telemetry save path on caller connection inside telemetry service transaction / `selfManagedWrite` as invoked |
| Terminal evidence | session finished timestamp |
| Recovery | correlate by `session_id` |

### `feature_verify_sessions`

| Field | Value |
| --- | --- |
| Classification | supporting evidence/configuration |
| Schema / migration | base `DatabaseSchemaStatements` |
| Row identity | `session_id` |
| Immutable vs mutable | open/close timestamps |
| Contract admission | session contract columns |
| Source of truth | `feature_verify_workflows` row remains lifecycle authority |
| Engine writers | feature-verify lifecycle telemetry |
| SQLite writers | `LifecycleTelemetrySave.saveFeatureVerifyStarted` / `saveFeatureVerifyFinished` → `LifecycleTelemetryFeatureVerifySessionAdapter` |
| CLI / MCP triggers | MCP `feature_verify_started` / `feature_verify_finished` |
| Projection consumers | `StaleSessionReconciler` |
| Transaction owner | lifecycle telemetry save transaction boundary |
| Terminal evidence | session closed |
| Recovery | correlate by `session_id` |

### `feature_task_runtime_sessions`

| Field | Value |
| --- | --- |
| Classification | supporting evidence/configuration |
| Schema / migration | base `DatabaseSchemaStatements` |
| Row identity | `session_id` |
| Immutable vs mutable | open/close timestamps |
| Contract admission | session contract columns |
| Source of truth | `feature_task_workflows` row remains lifecycle authority |
| Engine writers | `FeatureTaskRuntimeLifecycleTelemetry` → `LifecycleTelemetryService` |
| SQLite writers | `LifecycleTelemetryRuntimeSave.saveFeatureTaskRuntimeStarted` / `saveFeatureTaskRuntimeFinished` → `LifecycleTelemetryFeatureTaskSessionAdapter` |
| CLI / MCP triggers | feature-task runtime run lifecycle (no dedicated MCP session tool) |
| Projection consumers | `StaleSessionReconciler` |
| Transaction owner | lifecycle telemetry save transaction boundary |
| Terminal evidence | session closed |
| Recovery | correlate by `session_id` |

### `feature_implement_sessions`

| Field | Value |
| --- | --- |
| Classification | supporting evidence/configuration |
| Schema / migration | base `DatabaseSchemaStatements` |
| Row identity | `session_id` |
| Immutable vs mutable | schema/backfill columns only in current tree |
| Contract admission | session contract columns |
| Source of truth | workflow rows remain lifecycle authority |
| Engine writers | **no live production INSERT writer** (table retained; `GoalTelemetrySave` joins for reads; `StaleSessionReconciler.lifecycleTargets` omits this family so reconcile count stays zero) |
| SQLite writers | column-ensure / backfill only |
| CLI / MCP triggers | none live |
| Projection consumers | none |
| Transaction owner | n/a for live writes |
| Terminal evidence | n/a |
| Recovery | historical rows only |

### `producer_output_evidence`

| Field | Value |
| --- | --- |
| Classification | supporting evidence |
| Schema / migration | base schema |
| Row identity | `(workflow_id, phase_id, generation, attempt, repair_turn, agent_id)` |
| Immutable vs mutable | retained bytes immutable once stored |
| Contract admission | output verification contract |
| Source of truth | phase output authority remains workflow/settlement |
| Engine writers | `FeatureTaskRuntimeRejectedOutputRecorder.retainProducerOutput`; `FeatureTaskRuntimeRejectedOutputRecorder.recordRejectedOutput` (co-writer: same transaction calls `RejectedOutputDiagnosticService.retainProducerOutput` before `record`) |
| SQLite writers | `SqliteRejectedOutputDiagnosticRepository.retainProducerOutput` (INSERT OR IGNORE) via `RejectedOutputDiagnosticService.retainProducerOutput` on caller `Connection` |
| CLI / MCP triggers | none for writes (diagnostic inspect reads sibling `rejected_output_diagnostics` only) |
| Projection consumers | verification readers |
| Transaction owner | `FeatureTaskRuntimeRejectedOutputRecorder` → `database.transaction`; repository SQL on caller connection |
| Terminal evidence | evidence row linked to output |
| Recovery | retain originals per quarantine policy |

### `rejected_output_diagnostics`

| Field | Value |
| --- | --- |
| Classification | supporting evidence |
| Schema / migration | base schema |
| Row identity | `identity` |
| Immutable vs mutable | diagnostic payload immutable; lifecycle column governs retention |
| Contract admission | output verification contract |
| Source of truth | phase output authority remains workflow/settlement |
| Engine writers | `FeatureTaskRuntimeRejectedOutputRecorder.recordRejectedOutput` |
| SQLite writers | `SqliteRejectedOutputDiagnosticRepository.insert` via `RejectedOutputDiagnosticService.record` on caller `Connection` |
| CLI / MCP triggers | CLI `skill-bill feature-task rejected-output` (read; `RejectedOutputInspectCliCommand` under `FeatureTaskRuntimeRunCommand`); `skill-bill feature-task rejected-output-cleanup` (delete-only, not a lifecycle write owner). runtime-mcp has no rejected-output tool. |
| Projection consumers | verification readers |
| Transaction owner | `FeatureTaskRuntimeRejectedOutputRecorder` → `database.transaction`; service/repository SQL on caller connection |
| Terminal evidence | diagnostic row with `lifecycle` |
| Recovery | retain originals per quarantine policy |

### `agent_activity_stamps`

| Field | Value |
| --- | --- |
| Classification | supporting evidence |
| Schema / migration | v36 `add-agent-activity-stamps` |
| Row identity | `workflow_id` |
| Immutable vs mutable | stamp fields updated in place |
| Contract admission | none |
| Source of truth | observability only |
| Engine writers | `AgentActivityStampWriter.persist` |
| SQLite writers | `unitOfWork.agentActivityStamps` via writer |
| CLI / MCP triggers | agent activity paths during goal/feature runs |
| Projection consumers | desktop `TOPICS` until v54, then `ACTIVITY_TOPIC` only |
| Transaction owner | `AgentActivityStampWriter` → `database.selfManagedWrite` |
| Terminal evidence | n/a |
| Recovery | overwrite per workflow |

### `worktree_edit_journal`

| Field | Value |
| --- | --- |
| Classification | supporting evidence |
| Schema / migration | v38 `add-worktree-edit-journal` |
| Row identity | `id` INTEGER PRIMARY KEY AUTOINCREMENT |
| Immutable vs mutable | append with cap trim |
| Contract admission | none |
| Source of truth | observability only |
| Engine writers | `WorktreeEditJournalWriter.persistMeasuredTick` |
| SQLite writers | `unitOfWork.worktreeEditJournal.append` / `trimToCap` |
| CLI / MCP triggers | worktree edit tracing during goal runs |
| Projection consumers | desktop `TOPICS` revision triggers |
| Transaction owner | `WorktreeEditJournalWriter` → `database.selfManagedWrite` |
| Terminal evidence | n/a |
| Recovery | append-only with cap |

### `telemetry_outbox`

| Field | Value |
| --- | --- |
| Classification | telemetry-only |
| Schema / migration | base `DatabaseSchemaStatements` |
| Row identity | `id` INTEGER PRIMARY KEY AUTOINCREMENT |
| Immutable vs mutable | dequeue/sync fields mutable |
| Contract admission | telemetry envelope contract |
| Source of truth | external telemetry sink (local queue) |
| Engine writers | `LifecycleTelemetryEmit` and related emit paths |
| SQLite writers | `TelemetryOutboxStore.enqueue` and dequeue updates |
| CLI / MCP triggers | implicit on lifecycle/telemetry emit |
| Projection consumers | telemetry proxy delivery |
| Transaction owner | outbox write on enqueue connection / repository transaction |
| Terminal evidence | `synced_at` set |
| Recovery | retry outbox delivery |

### `telemetry_reconciliation_state`

| Field | Value |
| --- | --- |
| Classification | telemetry-only |
| Schema / migration | base `DatabaseSchemaStatements` |
| Row identity | `state_key` |
| Immutable vs mutable | `last_completed_at` updated on reconcile |
| Contract admission | none |
| Source of truth | reconcile cadence cursor |
| Engine writers | `StaleSessionReconciler` |
| SQLite writers | `claimReconciliationCadence` INSERT ON CONFLICT on `telemetry_reconciliation_state` |
| CLI / MCP triggers | implicit on reconcile scheduler |
| Projection consumers | none |
| Transaction owner | reconcile transaction on caller connection |
| Terminal evidence | `last_completed_at` advanced |
| Recovery | retry reconcile |

### `telemetry_local_secrets`

| Field | Value |
| --- | --- |
| Classification | supporting evidence/configuration |
| Schema / migration | base `DatabaseSchemaStatements` |
| Row identity | `secret_key` (`telemetry_redaction_salt` for redaction salt) |
| Immutable vs mutable | `secret_value` set once via INSERT OR IGNORE |
| Contract admission | none |
| Source of truth | local redaction configuration |
| Engine writers | telemetry redaction bootstrap |
| SQLite writers | `telemetryRedactionSalt` (`TelemetryRedactionSaltStore.kt`) INSERT OR IGNORE |
| CLI / MCP triggers | first telemetry emit needing redaction |
| Projection consumers | in-process redaction only |
| Transaction owner | on caller connection during salt read/create |
| Terminal evidence | row for `telemetry_redaction_salt` |
| Recovery | salt stable after first insert |

### `review_runs`

| Field | Value |
| --- | --- |
| Classification | supporting evidence (lifecycle coupling via review phase artifacts only) |
| Schema / migration | base `DatabaseSchemaStatements` |
| Row identity | `review_run_id` |
| Immutable vs mutable | import metadata mutable until finished |
| Contract admission | review contracts |
| Source of truth | review run row + artifacts for phase coupling |
| Engine writers | review import and review phases |
| SQLite writers | review run store |
| CLI / MCP triggers | `import_review`, review phases |
| Projection consumers | review UI |
| Transaction owner | review repositories `transaction` |
| Terminal evidence | `review_finished_at` |
| Recovery | re-import guards |

### `review_run_lanes`

| Field | Value |
| --- | --- |
| Classification | supporting evidence |
| Schema / migration | `DatabaseReviewLedgerSchema` |
| Row identity | `(review_run_id, lane_skill_name)` |
| Immutable vs mutable | lane accounting fields |
| Contract admission | review contracts |
| Source of truth | parent `review_runs` row |
| Engine writers | review phases |
| SQLite writers | review lane store |
| CLI / MCP triggers | review phases |
| Projection consumers | review UI |
| Transaction owner | review repositories `transaction` |
| Terminal evidence | lane disposition rows |
| Recovery | cascade on run delete |

### `review_run_finding_lanes`

| Field | Value |
| --- | --- |
| Classification | supporting evidence |
| Schema / migration | `DatabaseReviewLedgerSchema` |
| Row identity | `(review_run_id, finding_id)` |
| Immutable vs mutable | lane mapping |
| Contract admission | review contracts |
| Source of truth | parent `review_runs` row |
| Engine writers | review phases |
| SQLite writers | review finding lane store |
| CLI / MCP triggers | review phases |
| Projection consumers | review UI |
| Transaction owner | review repositories `transaction` |
| Terminal evidence | mapping row |
| Recovery | cascade on run delete |

### `review_accounting`

| Field | Value |
| --- | --- |
| Classification | supporting evidence/configuration |
| Schema / migration | base `DatabaseSchemaStatements` + v12 `add-bounded-review-accounting` |
| Row identity | `review_id` |
| Immutable vs mutable | bounded payload updated |
| Contract admission | review contracts |
| Source of truth | bounded review accounting envelope |
| Engine writers | review import |
| SQLite writers | review accounting store |
| CLI / MCP triggers | `import_review` |
| Projection consumers | review UI |
| Transaction owner | review repositories `transaction` |
| Terminal evidence | row present |
| Recovery | re-import guards |

**Registry note:** live table, **absent from `DatabaseSchema.tableNames`** (same omission pattern as `feature_task_phase_settlements`).

### `findings`

| Field | Value |
| --- | --- |
| Classification | supporting evidence |
| Schema / migration | base schema |
| Row identity | `(review_run_id, finding_id)` |
| Immutable vs mutable | finding text immutable after import |
| Contract admission | review contracts |
| Source of truth | parent `review_runs` row |
| Engine writers | review import |
| SQLite writers | findings store |
| CLI / MCP triggers | `import_review` |
| Projection consumers | review UI |
| Transaction owner | review repositories `transaction` |
| Terminal evidence | finding row |
| Recovery | cascade on run delete |

### `unaddressed_findings`

| Field | Value |
| --- | --- |
| Classification | supporting evidence |
| Schema / migration | base schema |
| Row identity | `(workflow_id, review_pass_number, finding_ordinal)` |
| Immutable vs mutable | verdict fields updated during review phases |
| Contract admission | review contracts |
| Source of truth | workflow review phase state |
| Engine writers | review phases |
| SQLite writers | unaddressed findings store |
| CLI / MCP triggers | review phases |
| Projection consumers | review UI |
| Transaction owner | review repositories `transaction` |
| Terminal evidence | outcome columns |
| Recovery | phase replay |

### `review_finding_outcomes`

| Field | Value |
| --- | --- |
| Classification | supporting evidence |
| Schema / migration | `DatabaseReviewLedgerSchema` |
| Row identity | `(workflow_id, review_pass_number, finding_ordinal)` |
| Immutable vs mutable | outcome recorded once |
| Contract admission | review contracts |
| Source of truth | workflow review coupling |
| Engine writers | review phases |
| SQLite writers | review finding outcomes store |
| CLI / MCP triggers | review phases |
| Projection consumers | review UI |
| Transaction owner | review repositories `transaction` |
| Terminal evidence | outcome row |
| Recovery | phase replay |

### `review_run_finding_verdicts`

| Field | Value |
| --- | --- |
| Classification | supporting evidence |
| Schema / migration | `DatabaseReviewLedgerSchema` |
| Row identity | `(review_run_id, finding_id, stage)` |
| Immutable vs mutable | verdict fields |
| Contract admission | review contracts |
| Source of truth | parent `review_runs` row |
| Engine writers | review phases |
| SQLite writers | verdict store |
| CLI / MCP triggers | review phases |
| Projection consumers | review UI |
| Transaction owner | review repositories `transaction` |
| Terminal evidence | verdict row |
| Recovery | cascade on run delete |

### `review_run_stage_boundaries`

| Field | Value |
| --- | --- |
| Classification | supporting evidence |
| Schema / migration | `DatabaseReviewLedgerSchema` |
| Row identity | `(review_run_id, stage)` |
| Immutable vs mutable | boundary markers |
| Contract admission | review contracts |
| Source of truth | parent `review_runs` row |
| Engine writers | review phases |
| SQLite writers | stage boundary store |
| CLI / MCP triggers | review phases |
| Projection consumers | review UI |
| Transaction owner | review repositories `transaction` |
| Terminal evidence | `reached` row |
| Recovery | cascade on run delete |

### `review_run_pass_claims`

| Field | Value |
| --- | --- |
| Classification | supporting evidence |
| Schema / migration | `DatabaseReviewLedgerSchema` |
| Row identity | `review_run_id` |
| Immutable vs mutable | claims JSON |
| Contract admission | review contracts |
| Source of truth | parent `review_runs` row |
| Engine writers | review phases |
| SQLite writers | pass claims store |
| CLI / MCP triggers | review phases |
| Projection consumers | review UI |
| Transaction owner | review repositories `transaction` |
| Terminal evidence | claims row |
| Recovery | cascade on run delete |

### `review_run_spec_projections`

| Field | Value |
| --- | --- |
| Classification | supporting evidence |
| Schema / migration | `DatabaseReviewLedgerSchema` |
| Row identity | `review_run_id` |
| Immutable vs mutable | projection digest fields |
| Contract admission | review contracts |
| Source of truth | parent `review_runs` row |
| Engine writers | review phases |
| SQLite writers | spec projection store |
| CLI / MCP triggers | review phases |
| Projection consumers | review UI |
| Transaction owner | review repositories `transaction` |
| Terminal evidence | projection row |
| Recovery | cascade on run delete |

### `feedback_events`

| Field | Value |
| --- | --- |
| Classification | supporting evidence/configuration |
| Schema / migration | base schema |
| Row identity | `id` INTEGER PRIMARY KEY AUTOINCREMENT |
| Immutable vs mutable | per store |
| Contract admission | CHECK on enums where present |
| Source of truth | not workflow lifecycle |
| Engine writers | findings/learnings pipelines |
| SQLite writers | respective stores |
| CLI / MCP triggers | triage/learnings MCP |
| Projection consumers | none lifecycle |
| Transaction owner | UoW `transaction` |
| Terminal evidence | n/a |
| Recovery | manual |

### `learnings`

| Field | Value |
| --- | --- |
| Classification | supporting evidence/configuration |
| Schema / migration | base schema |
| Row identity | `id` INTEGER PRIMARY KEY AUTOINCREMENT |
| Immutable vs mutable | rule text mutable while active |
| Contract admission | CHECK on scope/status |
| Source of truth | not workflow lifecycle |
| Engine writers | learnings pipelines |
| SQLite writers | learnings store |
| CLI / MCP triggers | `resolve_learnings` MCP |
| Projection consumers | none lifecycle |
| Transaction owner | UoW `transaction` |
| Terminal evidence | n/a |
| Recovery | manual |

### `session_learnings`

| Field | Value |
| --- | --- |
| Classification | supporting evidence/configuration |
| Schema / migration | base schema |
| Row identity | `review_session_id` |
| Immutable vs mutable | `learnings_json` updated |
| Contract admission | none |
| Source of truth | not workflow lifecycle |
| Engine writers | learnings session capture |
| SQLite writers | session learnings store |
| CLI / MCP triggers | learnings MCP |
| Projection consumers | none lifecycle |
| Transaction owner | UoW `transaction` |
| Terminal evidence | n/a |
| Recovery | manual |

---

## `DurableWorkflowArtifactFamily` members (`feature_task_workflows.artifacts_json`)

Persistence: `FeatureTaskRuntimeWorkflowPersistence` / `WorkflowStateStore` in the workflow row IMMEDIATE transaction. Identity: `(workflow_id, artifact key)` within `artifacts_json`.

| Family | Classification | Primary engine writers |
| --- | --- | --- |
| `FEATURE_TASK_RUNTIME_PHASE_RECORDS` | authoritative | `FeatureTaskRuntimePhaseStateRecorder` |
| `FEATURE_TASK_RUNTIME_PHASE_LEDGER` | authoritative | `FeatureTaskRuntimePhaseRecorder` |
| `FEATURE_TASK_RUNTIME_IMPLEMENTATION_ATTEMPTS` | authoritative | phase recorder / `phaseEventAdmissible` |
| `FEATURE_TASK_RUNTIME_EXECUTION_PLAN` | authoritative | run loop / planning persistence |
| `FEATURE_TASK_RUNTIME_CHECKPOINT_IDENTITIES` | authoritative | checkpoint preservation |
| `FEATURE_TASK_RUNTIME_QUARANTINED_RECORDS` | authoritative | remediation / quarantine |
| `FEATURE_TASK_RUNTIME_NO_CHANGE_PAUSE` | authoritative | no-change pause gate |
| `DECOMPOSITION_RUNTIME` | authoritative | decomposition run state |
| `GOAL_CONTINUATION_OUTCOME_DISPLACEMENT` | authoritative | goal continuation recorders |
| `GOAL_ATTEMPT_LEDGER` | authoritative | goal attempt tracking |
| `FEATURE_TASK_RUNTIME_DECOMPOSE_TERMINAL` | authoritative | decompose terminal |
| `FEATURE_TASK_RUNTIME_GOAL_CONTINUATION` | authoritative | goal continuation |
| `FEATURE_TASK_RUNTIME_GOAL_CONTINUATION_OUTCOME` | authoritative | continuation outcome |
| `FEATURE_TASK_RUNTIME_GOAL_CONTINUATION_FIELD_ADOPTION` | authoritative | field adoption |
| `FEATURE_TASK_RUNTIME_GOAL_PLANNING_IMPORT` | authoritative | planning import |
| `FEATURE_TASK_RUNTIME_PLAN_SEED` | authoritative | plan seed |
| `FEATURE_TASK_RUNTIME_PLAN_WORKFLOW` | authoritative | plan workflow |
| `FEATURE_TASK_RUNTIME_RESOLVED_BRANCH` | authoritative | branch resolution |
| `FEATURE_TASK_RUNTIME_RUN_INVARIANTS` | authoritative | run invariants snapshot |
| `FEATURE_TASK_RUNTIME_FINDING_VERIFICATION_BOUNDARY_SELECTION` | authoritative | finding verification phase |
| `FEATURE_TASK_RUNTIME_FINDING_VERIFICATION_CHECKPOINT` | authoritative | finding verification phase |
| `FEATURE_TASK_RUNTIME_FINDING_VERIFICATION_DISPOSITIONS` | authoritative | finding verification phase |
| `FEATURE_TASK_RUNTIME_REVIEW_GENERATION` | supporting evidence | review generation |
| `WORKER_SUBTASK_REQUEST_OUTCOMES` | supporting evidence | worker subtask outcomes |
| `GOAL_OUT_OF_BAND_ACCEPTANCE` | supporting evidence | OOB acceptance |
| `GOAL_REVIEW_POLICY` | supporting evidence/configuration | review policy |
| `GOAL_REVIEW_BASE_RECOVERIES` | derived/checkpoint | review recovery |
| `GOAL_SUBTASK_REVIEW_INPUT` | derived/checkpoint | review input snapshot |
| `GOAL_SUBTASK_REVIEW_RESULTS` | derived/checkpoint | review results |
| `GOAL_SUBTASK_REVIEW_STATE` | derived/checkpoint | review state |
| `DECOMPOSITION_MANIFEST_PROJECTION_FAILURE` | supporting evidence | records `DECOMPOSITION_MANIFEST_PROJECTION_FAILURE` on projection failure |
| `RUNTIME_REPOSITORY_EVIDENCE` | supporting evidence | repo evidence capture |
| `FEATURE_TASK_RUNTIME_DIAGNOSTIC_SIGNALS` | telemetry-only | diagnostics |
| `FEATURE_TASK_RUNTIME_DELIVERED_PROJECTIONS` | derived/checkpoint | delivered projection markers |
| `FEATURE_TASK_RUNTIME_OPERATOR_BLOCK_RETRY` | supporting evidence | operator block retry state |
| `FEATURE_TASK_RUNTIME_PHASE_BRIEFINGS` | derived/checkpoint | injected phase briefings |
| `FEATURE_TASK_RUNTIME_BUILD_GATE_PROGRESS` | derived/checkpoint | build gate progress |
| `FEATURE_TASK_RUNTIME_VALIDATION_GATE_PROGRESS` | derived/checkpoint | validation gate progress |
| `FEATURE_TASK_RUNTIME_READINESS_EVIDENCE` | derived/checkpoint | readiness evidence |
| `GOAL_OBSERVABILITY_LATEST_EVENT` | telemetry-only | observability |
| `GOAL_OBSERVABILITY_RUN_HISTORY` | telemetry-only | observability history |
| `GOAL_PROGRESS_LATEST_EVENT` | derived/checkpoint | progress latest |
| `GOAL_PROGRESS_RUN_HISTORY` | derived/checkpoint | progress history |

Transaction owner for all: same `WorkflowStateStore` / `FeatureTaskRuntimeWorkflowPersistence` write as workflow row. Terminal evidence: artifact payload + workflow terminal status. Recovery: explicit recovery/rerun; quarantine retains originals.

Disk note: `DecompositionManifestWriter.writeFromWorkflowUpdate` projects decomposition manifest to disk; authoritative bytes remain `DECOMPOSITION_RUNTIME` artifact + SQLite planning tables.

---

## Disk projection families

### `.feature-specs/<issue-key>-<feature>/` tree (spec, subtask specs, decomposition manifest)

| Field | Value |
| --- | --- |
| Classification | derived/checkpoint |
| Schema / migration | n/a (filesystem) |
| Row identity | repo-relative path |
| Immutable vs mutable | files overwritten on replan/prepare |
| Contract admission | spec contracts on parse |
| Source of truth | SQLite `goal_shared_preplans`, `goal_subtask_plans`, `goal_planning_preparations`; workflow artifacts |
| Engine writers | `WorkflowService` post-commit projection |
| SQLite writers | n/a |
| Disk writers | `FeatureSpecPreparationWriter` (`spec.md`, `spec_subtask_N.md`); `DecompositionManifestWriter.writeFromWorkflowUpdate` (`decomposition-manifest.yaml`) |
| CLI / MCP triggers | workflow commit after plan/prepare phases |
| Projection consumers | agents, audit docs |
| Transaction owner | post-commit disk write (failure recorded in `DECOMPOSITION_MANIFEST_PROJECTION_FAILURE` artifact) |
| Terminal evidence | file bytes on disk |
| Recovery | replan/reset; prune via goal reset/replan not SQLite DROP |

---

## Git checkpoint ref family

### `refs/skill-bill/checkpoints/<issue-key>/<subtask-id>/<sequence>`

| Field | Value |
| --- | --- |
| Classification | derived/checkpoint (recovery projection) |
| Schema / migration | n/a (git ref) |
| Row identity | ref path; sequence authority in `FEATURE_TASK_RUNTIME_CHECKPOINT_IDENTITIES` artifact |
| Immutable vs mutable | ref updated to new commit; pruned by policy |
| Contract admission | namespace `FEATURE_TASK_RUNTIME_CHECKPOINT_REF_NAMESPACE` |
| Source of truth | `FEATURE_TASK_RUNTIME_CHECKPOINT_IDENTITIES` artifact |
| Engine writers | `FeatureTaskRuntimeSubtaskCommitPreservation.preservePreAmendCheckpoint` |
| Git writers | `GitCheckpointHistoryOperations.updateCheckpointRef` |
| CLI / MCP triggers | subtask commit/checkpoint phases |
| Prune owners | `FeatureTaskRuntimeCheckpointRefPrune.pruneCompletedSubtaskCheckpointRefs`, `pruneGoalPurgeCheckpointRefs`, `pruneResetSubtaskCheckpointRefs`; CLI `skill-bill goal reset` / purge / replan |
| Transaction owner | git ref update (outside SQLite); coupled workflow row in IMMEDIATE transaction for identities artifact |
| Terminal evidence | ref points at preserved commit |
| Recovery | checkout ref; prune on completion/purge/reset |

---

## Absent / dropped families (explicitly not live)

| Family | Classification | Identity (historical) | Recovery |
| --- | --- | --- | --- |
| `review_delegated_lifecycle` | supporting evidence/configuration (historical) | `review_id` PK (v21 `addDelegatedReviewLifecycleProjection`) | **no live writer**; dropped v22 `dropDelegatedReviewLifecycleTables` |
| `review_lifecycle_events` | supporting evidence/configuration (historical) | v21 | dropped v22 |
| `experiment_pairs` | supporting evidence/configuration (historical) | `pair_id` (v42–44) | **no live writer**; dropped v45 `skill-378-drop-experiment-tables` |
| `experiment_observations` | supporting evidence/configuration (historical) | v42–44 | dropped v45 |
| `experiment_arm_outcomes` | supporting evidence/configuration (historical) | v42–44 | dropped v45 |
| `experiment_pair_leases` | supporting evidence/configuration (historical) | v42–44 | dropped v45 |
| `experiment_reports` | supporting evidence/configuration (historical) | v42–44 | dropped v45 |
| Generic `cancelled` / `interrupted` / `superseded` / `idle` workflow enums | n/a (no durable store) | vocabulary only in `ClosedStatusTypes.kt` / `WorkflowStatus` | not persisted as standalone tables |
| `goal_shared_preplans_pre_0_3`, `goal_subtask_plans_pre_0_3` | authoritative (historical rebuild staging) | copy source during v0.3 planning migrations | **no live writer**; renamed then dropped in `DatabaseMigrationEntries` / `GoalPlanningSchemaMigrations` |
| `goal_shared_preplans_pre_0_2`, `goal_subtask_plans_pre_0_2`, `STRICT_LEGACY_*`, `pre_0_4`–`pre_0_7` rebuild aliases | authoritative (historical) | `GoalPlanningSchemaMigrations` `LEGACY_PREPLANS` / `LEGACY_PLANS` and strict rebuild tables | **no live writer**; used during migrate then dropped |
| `producer_output_evidence_pre_generation`, `producer_output_evidence_pre_repair_turn`, `producer_output_evidence_pre_agent` | supporting evidence (historical) | diagnostic evidence rebuild | **no live writer**; `DiagnosticEvidenceRepairTurnMigration` copy then drop |
| `rejected_output_diagnostics_pre_repair_turn` | supporting evidence (historical) | diagnostic rebuild | **no live writer**; dropped after copy |
| `telemetry_outbox_legacy` | telemetry-only (historical) | prior `telemetry_outbox` shape | **no live writer**; `TelemetryOutboxLastErrorMigration` rename then drop |
| `feedback_events_legacy` | supporting evidence (historical) | prior `feedback_events` | **no live writer**; `FeedbackEventMigration` drop |
| `schema_migrations_version_keyed` | supporting evidence/configuration (historical) | alternate migration ledger | **no live writer**; `MigrationLedger` drop |
| `feature_task_runtime_audit_generations` | supporting evidence (historical) | legacy audit generation JSON | **not a live family**; fresh migrate has no table (`DatabaseMigrationsTest`); historical rows only if left from pre-drop DBs |

**Cache absence:** no cache family besides `desktop_change_revisions` is live; none other required.

---

## Subtask 3: filesystem maintenance owners (SKILL-422)

| Owner | Preservation vs destructive | Open connection | Filesystem / DB boundary | Partial failure | Recovery diagnostic |
| --- | --- | --- | --- | --- | --- |
| `../../../uninstall.sh` `remove_state_dir_preserving_source` | **preserve** copied source + `baseline-manifest.json` + `backups/` + all `*.db` / WAL / SHM / rollback journal sidecars; clear `runtime/` + `installed-skills/` | none required; must not delete DB files while external writer holds WAL | shell-only; does not open SQLite | continues when native unlink fails (`InstallerShellDelegationTest`) | stdout warnings; state DB paths remain on disk |
| `../../../uninstall.sh` `remove_state_dir_full` | **destructive** entire `$SKILL_BILL_STATE_DIR` | n/a | shell `rm -rf` | goal-continuation exit 64 aborts before mutation | exit code + message |
| `../../../install.sh` `run_pre_install_uninstall` | sets `SKILL_BILL_PRESERVE_SOURCE_ON_WIPE=1` only for pre-install path | n/a | invokes shell preserve wipe | delegates to uninstall.sh | install aborts on uninstall failure |
| `SkillBillUninstallService.apply` | **destructive** `stateRoot` via `removeRecursively`; not preserve wipe | n/a | application port `UninstallPathsPort.removeTree` | `UninstallMutationRecorder` records per-path failures; `UninstallResult.failed` | CLI `failed_with_degradations` when mutations fail |
| `SQLiteDatabaseSessionFactory` / `DatabaseRuntime` | n/a (session owner) | read: DEFERRED snapshot; write: IMMEDIATE | JDBC boundary | busy retry via `ConnectionTransactions` | typed `DatabaseFailureCode.ACCESS` at ports boundary (`SQLiteDatabaseSessionFactoryTypedFailureTest`) |
| `InstallStagingAtomicMoves.promoteByBackupAndMove` | staging dir promotion with sibling `.backup-<uuid>` | n/a | under `installed-skills/` staging only; not state DB tree | restore backup on failed move | IOException propagates; backup restore logged at SEVERE |

Staging/promotion under `installed-skills/` does not enter the state database tree; SQLite recovery regressions use disposable homes and `review-metrics.db` only.

---

## Cross-cutting transaction and fencing summary

- **Read path:** `SQLiteDatabaseSessionFactory.read` → BEGIN DEFERRED.
- **Write path:** `SQLiteDatabaseSessionFactory.transaction` → BEGIN IMMEDIATE with busy retry; some stores use `selfManagedWrite` replay for nested contention.
- **Workflow terminal fence:** `WorkflowStateWrites.upsertFeatureTaskWorkflowRow` / `WorkflowStateStore.saveOrdinary` terminal snapshot equality.
- **Settlement admission:** `SqliteFeatureTaskPhaseSettlementStore.settlementAdmissionRefusal` plus runtime workflow terminal check at durable boundary.
- **Worker fencing:** single row per `workflow_id`; coordinator compares generation/owner_token/process evidence before destructive writes.
