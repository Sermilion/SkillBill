# SKILL-422 subtask 1: findings register

Severity uses project consequence ordering: **critical** (false completion / data loss), **high** (cross-identity / stale owner), **medium** (stale presentation), **low** (diagnostics). Classifications: source-confirmed defect, source-supported candidate, verified no-finding area, external limitation. Reproduction-confirmed reserved for validate execution.

## F1 — Terminal workflow reopen via ordinary write

| Field | Value |
| --- | --- |
| ID | F1 |
| Classification | source-confirmed defect (repaired) |
| Severity | critical |
| Trigger | Late `running` update after `completed`/`failed`/`abandoned` on the same `workflow_id` |
| Identities — repository | not applicable (`saveOrdinary` does not read repository identity) |
| Identities — branch | not applicable |
| Identities — issue | `issue_key` on `feature_task_workflows` when present; not applicable for isolated store scenario below |
| Identities — parent | not applicable |
| Identities — subtask | not applicable |
| Identities — workflow | `feature_task_workflows.workflow_id` (runtime mode) |
| Identities — run | `feature_task_workflows.session_id` |
| Identities — execution | not applicable |
| Identities — owner | not applicable at `WorkflowStateStore.saveOrdinary` |
| Before state | `workflow_id=wftr-terminal-reopen`, `session_id=ftr-terminal-reopen`, `workflow_status=completed`, `current_step_id=pr`, `finished_at=2026-10-13T12:00:00Z`, terminal `steps_json` / `artifacts_json` from prior completed save |
| After / intended | Same terminal snapshot; no regression to `running`, `finished_at` remains set |
| Pending capture recipe | Isolated temp DB + `WorkflowStateStore`: save running row, then completed terminal row, then `saveOrdinary` with late snapshot (`workflow_status=running`, `current_step_id=implement`, `finished_at=null`); reopen DB; capture row `workflow_status`, `finished_at`, `steps_json`, `artifacts_json`, and `WorkflowStateWriteResult` |
| Root cause | Late non-terminal write meets `saveOrdinary` terminal guard: when current runtime row is terminal and `sameTerminalSnapshot` fails (status, `currentStepId`, `stepsJson`, `artifactsJson`, `finishedAt`), Kotlin returns `TerminalRefused`; `Connection.upsertFeatureTaskWorkflowRow` WHERE clause also refuses non-identical terminal replacement — causal path is late write + SQL/Kotlin fence, not a missing predicate in one method alone |
| Production owner | `runtime-kotlin/runtime-infra/sqlite/src/main/kotlin/skillbill/infrastructure/sqlite/workflow/WorkflowStateWrites.kt` (`upsertFeatureTaskWorkflowRow`); `WorkflowStateStore.saveOrdinary` (`WorkflowStateStore.kt`) |
| Isolated reproduction — inputs | Late snapshot on `workflow_id=wftr-terminal-reopen`: `workflow_status=running`, `current_step_id=implement`, `finished_at=null` against authoritative completed row above |
| Isolated reproduction — expected outcome | `WorkflowStateWriteResult.TerminalRefused`; reopened row still `completed` with `finished_at=2026-10-13T12:00:00Z` |
| Repair — rejected bytes (provenance: authored isolated store scenario; field shapes match production write path) | **Refused late snapshot:** `workflow_id=wftr-terminal-reopen`, `session_id=ftr-terminal-reopen`, `workflow_status=running`, `current_step_id=implement`, `finished_at=null`. **Authoritative terminal row retained:** `workflow_status=completed`, `current_step_id=pr`, `finished_at=2026-10-13T12:00:00Z` |
| Repair — historical outcome | `TerminalRefused`; durable row unchanged after reopen |
| Repair (implementation) | SQL terminal fence + `TerminalRefused` |
| Test pointer (validate-owned) | T1-01 `WorkflowStateStoreTest` — execution evidence pending validate |
| Execution evidence | pending validate |

## F2 — Contradictory phase settlement overwrite

| Field | Value |
| --- | --- |
| ID | F2 |
| Classification | source-confirmed defect (repaired) |
| Severity | critical |
| Trigger | Second settlement with same `(workflow_id, phase_id, attempt)` and different `kind` / `envelope_json` |
| Identities — repository | not applicable on settlement row |
| Identities — branch | not applicable |
| Identities — issue | not applicable on settlement row |
| Identities — parent | not applicable |
| Identities — subtask | not applicable |
| Identities — workflow | `feature_task_phase_settlements.workflow_id` |
| Identities — run | not applicable |
| Identities — execution | not applicable |
| Identities — owner | not applicable (settlement PK is workflow/phase/attempt) |
| Identities — settlement key | `(workflow_id, phase_id, attempt)` = `(wftr-conflict, implement, 2)` |
| Before state | First accepted envelope at key above |
| After / intended | First envelope retained; no second row; no overwrite of `envelope_json` |
| Pending capture recipe | Isolated `SqliteFeatureTaskPhaseSettlementStore.upsert` then contradictory upsert on same key; capture both `envelope_json` values, write result, and `find()` |
| Root cause | `INSERT ... ON CONFLICT DO NOTHING` then kind/envelope compare; mismatch yields `Conflicting` without overwrite; `FeatureTaskPhaseSettlementService.persist` maps conflict to error status and withholds late envelope |
| Production owner | `runtime-kotlin/runtime-infra/sqlite/src/main/kotlin/skillbill/infrastructure/sqlite/SqliteFeatureTaskPhaseSettlementStore.kt`; `FeatureTaskPhaseSettlementService` (`runtime-engine/.../FeatureTaskPhaseSettlementService.kt`) |
| Isolated reproduction — inputs | Same key `(wftr-conflict, implement, 2)`; first `kind=complete` with envelope A; second `kind=block` with envelope B |
| Isolated reproduction — expected outcome | Store `Conflicting`; repository/service `PHASE_SETTLEMENT_CONFLICT`; `find()` returns first envelope only |
| Repair — rejected bytes (provenance: authored repository fixture envelopes) | **Kept:** `{"status":"completed","produced_outputs":{"value":"accepted"}}`, `kind=complete`, `recorded_at=2026-10-13T19:00:00Z` at `(wftr-conflict, implement, 2)`. **Refused:** `{"status":"blocked","produced_outputs":{"value":"late"}}`, `kind=block`, `recorded_at=2026-10-13T19:01:00Z` at same key |
| Repair — historical outcome | First envelope remains; diagnostic `PHASE_SETTLEMENT_CONFLICT` |
| Repair (implementation) | `ON CONFLICT DO NOTHING` + `Conflicting` + repository throw on conflict |
| Test pointer (validate-owned) | T1-02 `SqliteFeatureTaskPhaseSettlementRepositoryTest`, `FeatureTaskPhaseSettlementServiceTest` |
| Execution evidence | pending validate |

## F3 — Stale worker phase writes after takeover

| Field | Value |
| --- | --- |
| ID | F3 |
| Classification | source-confirmed defect (repaired) |
| Severity | high |
| Trigger | Old `owner_token` / `generation` persists phase state after `transferFeatureTaskRuntimeWorker` increments generation |
| Identities — repository | `feature_task_execution_identities.repository_identity` when row exists for workflow |
| Identities — branch | route scope branch correlation when `routeScope=GOAL_CHILD` |
| Identities — issue | `feature_task_workflows.issue_key` |
| Identities — parent | `goal_runner_controls.parent_workflow_id` when goal-child route |
| Identities — subtask | subtask id from goal route when applicable; not applicable for coordinator-only barrier scenario |
| Identities — workflow | `feature_task_workflows.workflow_id` |
| Identities — run | `feature_task_workflows.session_id` |
| Identities — execution | `feature_task_execution_identities` row for workflow when present |
| Identities — owner | `feature_task_runtime_worker_leases.owner_token` + `generation` (admitted ACTIVE lease) |
| Before state | New generation ACTIVE on lease after transfer; new owner phase records/ledger as written by current worker |
| After / intended | New owner artifacts unchanged; old-owner persist skipped |
| Pending capture recipe | Barrier-style generation transfer, then old token/generation invokes owned persist path; capture lease `generation`, phase records, ledger entries |
| Root cause | `requireCurrentWorker` requires token/generation match, `ACTIVE` lease, unexpired `expires_at`, and non-terminal workflow before `requiredWrite` mutates records/ledger; mismatch returns false — no stored late envelope because mutation never runs |
| Production owner | `FeatureTaskRuntimePhaseStateRecorder.requireCurrentWorker` (`runtime-engine/.../FeatureTaskRuntimePhaseStateRecorder.kt`) |
| Isolated reproduction — inputs | Old `owner_token` / `generation` after generation++; non-terminal workflow; phase persist request from superseded worker |
| Isolated reproduction — expected outcome | `requireCurrentWorker` false; no artifact overwrite for new owner |
| Repair — refused write shape (no durable late envelope — provenance: source fence before write) | Requested persist from stale worker: prior `owner_token`/`generation` after `generation++`, non-terminal `workflow_status`, phase state patch — **no JSON persisted** because `requiredWrite` path returns false before patch |
| Repair — historical outcome | false from owned write path; new owner records/ledger unchanged |
| Repair (implementation) | Owned write paths require active lease match + non-terminal workflow |
| Test pointer (validate-owned) | T1-04 `WorkerTakeoverFencingTest` |
| Execution evidence | pending validate |

## F4 — Governed recovery-history artifact

| Field | Value |
| --- | --- |
| ID | F4 |
| Classification | source-supported candidate |
| Severity | medium |
| Trigger | Explicit recovery/rerun without a separate governed recovery-history artifact table |
| Identities — repository | not applicable |
| Identities — branch | not applicable |
| Identities — issue | `feature_task_workflows.issue_key` when present on resumed row |
| Identities — parent | parent workflow id when row is goal child |
| Identities — subtask | not applicable at engine resume boundary |
| Identities — workflow | retained `feature_task_workflows.workflow_id` on resume |
| Identities — run | `session_id` on resumed workflow row |
| Identities — execution | execution identity when bound to workflow |
| Identities — owner | not applicable (recovery APIs, not a late competing write) |
| Before state | Terminal or paused/blocked durable snapshot eligible for named resume |
| After / intended | Named continuation (`WorkflowResumeMode.RECOVER` / continue statuses), same `workflow_id`, no new recovery-history artifact row |
| Pending capture recipe | Source walk of `WorkflowEngine.resumeView` and `WorkflowEngineContinuationAssembly.continueStatusFor`; validate execution not required to retain candidate label |
| Root cause | No separate governed recovery-history artifact; recovery is the named resume/continue path on existing workflow row and embedded artifacts |
| Production owner | `WorkflowEngine.resumeView` (`runtime-domain/.../WorkflowEngine.kt`); `WorkflowEngineContinuationAssembly.continueStatusFor` / `DurableChildRecoveryClass` (`runtime-domain/.../engine/`) |
| Isolated reproduction — inputs | Paused or definition-terminal snapshot with stable `workflow_id`; invoke supported resume/continue API |
| Isolated reproduction — expected outcome | Continue status from assembly; no additional recovery ledger table write |
| Status | deferred: intentional recovery uses named transitions; no new artifact in this subtask |
| Handoff | subtask 2 if parent aggregate requires shared recovery ledger |
| Execution evidence | source review only |

## F5 — Monotone admission for all artifact-coupled writers

| Field | Value |
| --- | --- |
| ID | F5 |
| Classification | source-supported candidate (partial repair) |
| Severity | high |
| Trigger | Lower `attemptCount` or post-complete mutation via paths that bypass recorder fencing |
| Identities — repository | not applicable at phase record boundary |
| Identities — branch | not applicable |
| Identities — issue | `issue_key` on parent workflow when present |
| Identities — parent | goal parent when child workflow |
| Identities — subtask | not applicable |
| Identities — workflow | `workflow_id` in phase records / ledger |
| Identities — run | `session_id` on workflow row |
| Identities — execution | not applicable |
| Identities — owner | worker lease when write is worker-gated (see F3); not applicable for pure `phaseEventAdmissible` unit boundary |
| Identities — phase attempt | `(workflow_id, phase_id, attemptCount)` in embedded phase records |
| Before state | Completed phase record at attempt N |
| After / intended | Lower attempt refused; identical completed replay admitted; mismatched completed replay refused |
| Pending capture recipe | Recorder `phaseEventAdmissible` with lower `attemptCount` vs matching completed replay; capture previous vs requested record fields |
| Root cause | Attempt high-water plus `completedPhaseRequestMatches` inside `phaseEventAdmissible`; not every artifact mutator routes through this fence (documented gap) |
| Production owner | `FeatureTaskRuntimePhaseStateRecorder.phaseEventAdmissible` (`FeatureTaskRuntimePhaseStateRecorder.kt`) |
| Isolated reproduction — inputs | `request.attemptCount` less than `previous.attemptCount`; vs identical completed replay at same attempt |
| Isolated reproduction — expected outcome | false on lower attempt; true only when `completedPhaseRequestMatches` |
| Gap | not every artifact mutator routes through recorder fencing |
| Repair (partial) | attempt high-water + completed-phase match for replays; ledger/coupled writers use same transaction where recorder owns write |
| Test pointer (validate-owned) | phase recorder unit boundaries; T1-03 fault injection pending validate |
| Execution evidence | pending validate |

## F6 — Settlement admission without workflow context

| Field | Value |
| --- | --- |
| ID | F6 |
| Classification | source-confirmed defect (partially repaired) |
| Severity | high |
| Trigger | MCP settlement upsert when runtime workflow row missing or terminal |
| Identities — repository | not applicable at admission gate |
| Identities — branch | not applicable |
| Identities — issue | `issue_key` on workflow row when present |
| Identities — parent | not applicable |
| Identities — subtask | not applicable |
| Identities — workflow | `feature_task_workflows.workflow_id` + settlement key `(workflow_id, phase_id, attempt)` |
| Identities — run | `session_id` when row exists |
| Identities — execution | not applicable |
| Identities — owner | not applicable at settlement admission |
| Before state | Missing runtime workflow row, or terminal `workflow_status` with `finished_at` set (e.g. completed at `2026-10-13T12:00:00Z`) |
| After / intended | No INSERT; `Refused(WORKFLOW_NOT_SETTLEABLE)` or `Refused(TERMINAL_WORKFLOW)`; `find()` null |
| Pending capture recipe | `upsert` against missing `workflow_id`; `upsert` against completed workflow; capture refusal reason and `find()` |
| Root cause | `settlementAdmissionRefusal` reads runtime `workflow_status` (and row existence) before INSERT; terminal/missing refuses admission — `STALE_ATTEMPT`, `OWNER_MISMATCH`, `LEASE_EXPIRED` exist on enum but are not returned at this boundary (phase loop owns attempt/owner) |
| Production owner | `SqliteFeatureTaskPhaseSettlementStore.settlementAdmissionRefusal` (`SqliteFeatureTaskPhaseSettlementStore.kt`) |
| Isolated reproduction — inputs | `{"status":"completed"}` envelope at `(wftr-settle-terminal, implement, 1)` against completed runtime workflow |
| Isolated reproduction — expected outcome | `Refused(TERMINAL_WORKFLOW)`; no settlement row |
| Repair — rejected bytes (provenance: authored repository terminal-refusal fixture) | **Refused:** `envelope_json={"status":"completed"}`, `kind=complete`, key `(wftr-settle-terminal, implement, 1)` against `workflow_status=completed`, `finished_at=2026-10-13T12:00:00Z` |
| Repair — historical outcome | `Refused(TERMINAL_WORKFLOW)`; `find` null; no row inserted |
| Repair — second branch (recipe-only) | Missing workflow id: expected `Refused(WORKFLOW_NOT_SETTLEABLE)` — capture on validate with isolated upsert against unknown `workflow_id` |
| Gap | `STALE_ATTEMPT`, `OWNER_MISMATCH`, `LEASE_EXPIRED` not enforced at settlement boundary |
| Repair (implementation) | `TERMINAL_WORKFLOW` / `WORKFLOW_NOT_SETTLEABLE` refusal wired at store |
| Test pointer (validate-owned) | T1-02 terminal refusal test |
| Execution evidence | pending validate |

## F7 — False STOPPED on failed termination

| Field | Value |
| --- | --- |
| ID | F7 |
| Classification | source-confirmed defect (repaired in prior checkpoint) |
| Severity | critical |
| Trigger | `GoalRunnerStatusControlVerbs.stop` when inspect/terminate fails or process remains live |
| Identities — repository | `feature_task_execution_identities.repository_identity` when present |
| Identities — branch | branch correlation on control/execution when applicable |
| Identities — issue | `goal_runner_controls` issue key |
| Identities — parent | `goal_runner_controls.parent_workflow_id` |
| Identities — subtask | not applicable at stop verb |
| Identities — workflow | parent/child workflow ids implicated by stop target |
| Identities — run | not applicable |
| Identities — execution | execution lease identity when stop targets live worker |
| Identities — owner | worker lease `owner_token` / `generation` when present |
| Before state | After `reserveStop`: `pause_requested=true`, `pauseConsumed=true`, `paused=true`, `pause_reason=GOAL_PAUSE_REASON_OPERATOR_STOP`; workflow row not `ABANDONED` |
| After / intended | Same pause JSON retained; verb status `TERMINATION_INCOMPLETE` with `termination_failed` or `owner_still_live`; must not emit `GoalRunnerStopStatus.STOPPED` |
| Pending capture recipe | Persist pause via stop reserve, then inject inspect/terminate failure or still-live `ExactLive`; capture `control_state_json` pause fields and `GoalRunnerStopVerbResult` |
| Root cause | `GoalRunnerStatusControlVerbs.stop` pauses first, then maps inspect/terminate failure through `runCatching` to `TERMINATION_INCOMPLETE` instead of `STOPPED` |
| Production owner | `GoalRunnerStatusControlVerbs.stop` (`runtime-engine/.../GoalRunnerStatusControlVerbs.kt`); `GoalRunnerControlCoordinator` |
| Isolated reproduction — inputs | Live lease + supervisor returns `ExactLive` after termination attempts |
| Isolated reproduction — expected outcome | `TERMINATION_INCOMPLETE`, `terminationFailureCode=owner_still_live`, `paused=true`, pause reason operator stop |
| Repair — rejected success token (provenance: authored stop verb boundary) | **Would-be false success:** `GoalRunnerStopStatus.STOPPED` — must not be emitted when termination unverified. **Retained control bytes:** pause fragment with `paused=true`, `pauseReason=GOAL_PAUSE_REASON_OPERATOR_STOP` |
| Repair — historical outcome | `TERMINATION_INCOMPLETE` with `owner_still_live` or `termination_failed`; pause retained. Prior remediation commit `925672c6a` — historical implementation provenance only |
| Test pointer (validate-owned) | T1-08 `GoalRunnerStopVerbTest` |
| Execution evidence | pending validate |

## Classified durable/ownership scenarios (transition-matrix alignment)

Source classification for matrix-documented scenarios not already covered by F1–F7 defect rows. Execution reproduction (T1-03, T1-05, T1-06, T1-07) is validate-owned and does not change this source classification.

| Scenario | Classification | Severity (residual if fence removed) | Trigger | Identities | Production owner | Examination limit | Pending capture recipe |
| --- | --- | --- | --- | --- | --- | --- | --- |
| Worker lease expiry DELETE | verified no-finding area | medium | Heartbeat past `expires_at` with matching token/generation/ACTIVE | `feature_task_runtime_worker_leases.workflow_id`, `owner_token`, `generation` | `FeatureTaskRuntimeWorkerStore.releaseFeatureTaskRuntimeWorkerIfExpired` | Foreign host mismatch is not treated as process death (see coordinator row) | Expired lease row; capture DELETE rowcount and subsequent `find` |
| Crash reconciliation single owner | verified no-finding area | high | Crashed worker candidate after atomic re-read | `workflow_id`, lease, execution identity bytes | `FeatureTaskRuntimeCrashReconciler.reconcileAdmittedCandidate` → `reconcileFeatureTaskRuntimeCrashedWorker` in one IMMEDIATE transaction | T1-05 execution pending validate | Inject candidate; capture workflow+lease after reconcile |
| Live-but-expired vs foreign host | verified no-finding area | high | `inspectForRecovery` outcomes differ for dead expired, live expired, foreign host | `workflow_id`, `host_identity`, lease fields | `FeatureTaskRuntimeWorkerCoordinator.inspectForRecovery` / `recoverOwned` (`ExactLive`, `NotRunning`, `OwnershipMismatch`, `Unsupported`) | Uncertain/foreign host does not grant takeover | Stub supervisor inspections; capture coordinator result enum |
| Control JSON stale renewal | verified no-finding area | high | Renew with stale token/generation | `goal_runner_controls.parent_workflow_id`, lease token/generation | `GoalRunnerControlCoordinator.acquireExecutionLease` then `GoalRunnerControlStore.persistControlState` | Whole-JSON persist refuses mismatch before write | Concurrent completion + stale renew attempt; capture persisted JSON |
| Standalone finite lease / expiry fence | verified no-finding area | medium | Non-operator write after `leaseExpiresAt` | `standalone_phase_status.execution_id`, `repository_identity`, `branch_correlation` | `StandalonePhaseRegistrationFactory` (`LEASE_DURATION = 1 day`); `StandalonePhaseStatusStore.updateRejection` (`STALE_LEASE`); `supersedeActiveMonitors` | Readers do not renew; one-day renewal necessity remains F4-adjacent candidate | Expired lease update attempt; capture rejection reason |
| Save/commit acknowledgement | verified no-finding area | critical | Transaction block success vs rollback | connection-scoped write set | `ConnectionTransactions.inDatabaseTransaction` COMMIT only when `commitWhen(result)` | T1-03 fault injection pending validate | Force failure before commit; reopen durable state |

## Verified no-finding areas (source evidence)

Test class names are validate anchors only; they are not substitutes for production paths below.

| Area | Examined path (production) | Evidence | Examination limit |
| --- | --- | --- | --- |
| Takeover fencing | `FeatureTaskRuntimeWorkerCoordinator.recoverOwned`, `inspectForRecovery`, `transferFeatureTaskRuntimeWorker`; `FeatureTaskRuntimeWorkerStore` CAS reserve/transfer | Old-owner write after transfer is F3, not this row; process death on foreign host not proven | T1-04/T1-05 execution pending validate |
| Standalone supersede | `StandalonePhaseStatusStore.supersedeActiveMonitors` (`lifecycle_state=terminal`, `SUPERSEDED_MONITOR_ACTIVITY`); `updateRejection` (`STALE_LEASE`, terminal regression); readers do not renew | Finite one-day lease; expired non-operator writes fenced | One-day live-monitor renewal necessity remains F4-adjacent candidate |
| Planning DB failure stop | `FeatureTaskRuntimePlanningStopper.resolveAuthoredBundle` (`PlanDecompositionStop.kt`: `rethrowIfDatabaseFailure` vs bundle-not-ready `Blocked`); `FeatureTaskRuntimeRunTransitionOwner.persistDecomposeTerminal` | Stop-on-database-failure honesty; not goal-stop F7 | Distinct from operator stop pause retention |
| Schema registry vs settlements | migration v35 `feature_task_phase_settlements` vs `schema_migrations` PK `name` only (`DatabaseSchemaStatements.kt`) | Settlements table exists outside `DatabaseSchema.tableNames` registry list | Registry omission does not deny settlements durability (inventory AC-001) |

## F8 — Status read pruned checkpoint refs

| Field | Value |
| --- | --- |
| ID | F8 |
| Classification | source-confirmed defect (repaired) |
| Severity | high |
| Trigger | `skill-bill goal status` / watch with default `GoalRunnerStatusRequest.pruneCheckpointRefs=true` |
| Identities — workflow | parent `workflow_id` via manifest |
| Identities — issue | `issue_key` on goal manifest |
| Identities — repository | `repoRoot` on status request |
| Before state | Eligible checkpoint refs present under `refs/skill-bill/checkpoints/<issue>/` |
| After / intended | Refs unchanged after repeated status reads |
| Root cause | `GoalRunnerStatusProjectionAssembler.reconcileStatusManifest` called `pruneEligibleCheckpointRefsForManifest` when `pruneCheckpointRefs` true |
| Production owner | `GoalRunnerStatusProjectionAssembler.kt`; authorized prune: `GoalRunnerFinalization.pruneCompletedCheckpointRefs` |
| Repair (implementation) | Removed prune from status reconciliation; default `pruneCheckpointRefs=false`; CLI sets false |
| Test pointer (validate-owned) | T2-06 `GoalRunnerStatusReadOnlyMutationTest` |
| Execution evidence | pending validate |

## F9 — Work list aggregate abort on malformed row

| Field | Value |
| --- | --- |
| ID | F9 |
| Classification | source-confirmed defect (repaired) |
| Severity | medium |
| Trigger | `SQLiteWorkListRepository.list` hits `INVALID_WORK_LIST_ROW` on any row |
| Identities — workflow | `workflow_id` on offending row |
| Before state | Mixed healthy and malformed rows in union query |
| After / intended | Healthy rows returned; malformed skipped; `findByWorkflowId` still throws for corrupt target |
| Root cause | `toWorkItem()` throw aborted entire list build |
| Production owner | `SQLiteWorkListRepository.kt` |
| Repair (implementation) | `tryWorkItem()` skips `INVALID_WORK_LIST_ROW` in `list()` only |
| Test pointer (validate-owned) | T2-07 `SQLiteWorkListRepositoryTest` |
| Execution evidence | pending validate |

## F10 — Tray success signal on non-success terminal goals

| Field | Value |
| --- | --- |
| ID | F10 |
| Classification | source-confirmed defect (repaired) |
| Severity | medium |
| Trigger | Goal row transitions to `Lifecycle.TERMINAL` without `finishedAtSource=goal_completion` |
| Identities — issue | tray `issueKey` |
| Identities — parent | goal `workflowId` |
| Before state | Prior lifecycle non-terminal |
| After / intended | No `goal_completed` notification; excluded from Done 24h count |
| Root cause | `TransitionTracker` and `project()` treated any goal TERMINAL as success |
| Production owner | `DashboardController.kt` (`TransitionTracker`, `project`) |
| Repair (implementation) | `WorkItem.successfulGoalCompletion()` requires `goal_completion` source |
| Test pointer (validate-owned) | T2-09 `TransitionTrackerTest`, `ProjectionTest` |
| Execution evidence | pending validate |

## F11 — Parent completion vs completed children only

| Field | Value |
| --- | --- |
| ID | F11 |
| Classification | source-supported candidate |
| Severity | high |
| Trigger | All children terminal while PR/monitor finalization unfinished |
| Identities — parent | `parent_workflow_id` |
| Identities — subtask | completed child ids |
| Production owner | `GoalRunnerFinalization.kt`, `GoalRunnerCiMonitor.kt` |
| Status | traced; validate reproduction T2-01 |
| Execution evidence | pending validate |

## F12 — Board candidate scan under limit

| Field | Value |
| --- | --- |
| ID | F12 |
| Classification | source-supported candidate |
| Severity | medium |
| Trigger | First rejected board row consumes limit budget |
| Production owner | `IdeStatusService` board assembly |
| Status | deferred pending validate proof T2-07 |
| Execution evidence | pending validate |

## Subtask 2 handoff

Subtask 3 owns SQLite maintenance closure and overall coverage reconciliation per `spec_subtask_3_sqlite-maintenance-and-audit-closure.md`. F4 recovery ledger and F5 recorder gap remain as subtask 1 carry-over.
