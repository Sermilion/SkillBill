# SKILL-422 subtask 2: evidence

Source review and repair for goal finalization, durable planning, public projection, and read-only status boundaries. Execution confirmation for reproduction recipes remains validate-owned (`tests_executed` empty in implement).

## Architecture checklist (source-based)

| Area | Production owner | Result | Notes |
| --- | --- | --- | --- |
| Parent checkpoint prune on status read | `GoalRunnerStatusProjectionAssembler.reconcileStatusManifest` | repaired | Pruning removed from status projection; authorized owners remain `GoalRunnerFinalization.pruneCompletedCheckpointRefs`, `FeatureTaskRuntimeSubtaskFinalisation`, reset/purge coordinators |
| Status request default | `GoalRunnerStatusRequest.pruneCheckpointRefs` | repaired | Default `false`; CLI status sets `false` explicitly |
| Work list aggregate parse | `SQLiteWorkListRepository.list` | repaired | Malformed rows skipped; `findByWorkflowId` still typed-rejects |
| Tray goal completion signal | `TransitionTracker`, `project` in `DashboardController.kt` | repaired | `goal_completed` and 24h Done count require `finishedAtSource == goal_completion` |
| Issue finish guard | `GoalTelemetrySave.saveGoalIssueFinished` | verified no change | `finished_event_emitted_at IS NULL` overwrite guard retained; validate owns duplicate-finish scenarios (T2-02) |
| Run session finish guard | `GoalTelemetrySave.updateGoalFinished` | verified no change | Requires `status IS NULL AND finished_at IS NULL` |
| Ide status read paths | `IdeStatusService`, `IdeStatusProjector` | verified | Already pass `pruneCheckpointRefs = false` |
| Planning bundle before completed | `StandalonePlanPreparation`, checkpoint import | candidate | Source walk deferred to validate T2-04 |
| Purge / stop / abandon separation | `GoalRunnerPurgeCoordinator`, `GoalRunnerStatusControlVerbs` | verified partial | Subtask 1 stop honesty; purge ownership traced, validate T2-05 |

## Findings (subtask 2)

| ID | Classification | Severity | Summary | Owner | Test pointer |
| --- | --- | --- | --- | --- | --- |
| F8 | source-confirmed defect (repaired) | high | CLI/default status read could delete checkpoint refs via `pruneCheckpointRefs=true` | `GoalRunnerStatusProjectionAssembler` | T2-06 `GoalRunnerStatusReadOnlyMutationTest` |
| F9 | source-confirmed defect (repaired) | medium | Malformed first work-list row aborted entire list | `SQLiteWorkListRepository.list` | T2-07 `SQLiteWorkListRepositoryTest` |
| F10 | source-confirmed defect (repaired) | medium | Tray counted/notify any goal TERMINAL, not successful completion | `TransitionTracker`, `DashboardController.project` | T2-09 `TransitionTrackerTest`, `ProjectionTest` |
| F11 | source-supported candidate | high | Parent aggregate completion vs child-only completion visibility | `GoalRunnerFinalization`, `GoalRunnerCiMonitor` | T2-01 OpenCiMonitorStatusTest (validate) |
| F12 | source-supported candidate | medium | Board list limit may hide eligible rows when scan not bounded | `IdeStatusService` board assembly | T2-07 validate |

Subtask 1 findings F1–F7 retained in `findings-register.md` with full trigger/evidence/owner/reproduction fields.

## AC mapping

| AC | Implement evidence | Validate closure |
| --- | --- | --- |
| AC-001 | Extended `transition-matrix.md` subtask 2 section; this file; findings F8–F12 | Matrix spot-check + doc audit |
| AC-002 | F11 traced; no production change without validate proof | T2-01–T2-03 |
| AC-003 | Planning paths listed in checklist; no bundle refusal change in this diff | T2-04 |
| AC-004 | Administrative paths documented in matrix; no new purge behavior | T2-05 |
| AC-005 | F8 repair; `projectWorkflow` still `require(!pruneCheckpointRefs)` | T2-06 |
| AC-006 | F9 repair for list aggregate | T2-07 |
| AC-007 | F10 repair; retention windows unchanged | T2-09 |
| AC-008 | Tests named above added/updated | Full suite in validate |
| AC-009 | This evidence file + findings register | Audit phase |

## Reproduction recipes (pending validate execution)

| ID | Scenario | Before | Expected after |
| --- | --- | --- | --- |
| R2-01 | Status/watch repeated read | Checkpoint refs under `refs/skill-bill/checkpoints/...` | Ref count unchanged; manifest `commit_sha` unchanged |
| R2-02 | List with malformed + healthy row | INSERT invalid `state_entered_at` plus valid runtime row | CLI list returns healthy id only |
| R2-03 | Abandoned goal tray row | `lifecycle=CANCELED` or TERMINAL without `goal_completion` source | No `goal_completed` notification; not in Done 24h count |
| R2-04 | Parent with completed children, open PR monitor | `goalCompletedAt` unset, finalization step active | Status shows monitor/PR work; parent not falsely complete |

## Explicitly verified areas

- Post-push checkpoint prune still invoked from `GoalRunnerIterationOutcome` / finalization path (not removed).
- `WorkListService.retainReadableWork` skip semantics unchanged for invalid workflow snapshots.
- Work board schema diagnostic enum unchanged (no new `incompatible_record` token in this pass).

## Deviations / deferred

- No `implementation_plan_subtask_2.md` file in tree (plan hydrated from goal-planning import only); implement followed `spec_subtask_2` implementation sequence.
- IntelliJ `StatusRefreshCoordinator` ordering: no code change; SKILL-417 exception preserved pending validate T2-10.
- Malformed row diagnostics on work list CLI: skip is silent at repository boundary; board diagnostics path unchanged.
- Format/lint: no module-scoped formatter invocation established safe for this mixed diff; validate owns compile and check.
