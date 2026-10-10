# Subtask 2. ide-status selects and projects the live goal step

## Scope

For the 0AC-46 shape, ide-status must select and project the live goal step: lifecycle active (or the truthful live state, not paused-as-handle) and `current_step` monitor, not implement and not plan.

Depends on subtask 1 because `projectGoal` calls `goalRunnerStatusService.status` and then `assembleGoalStatusSnapshot`.

Files:

- `runtime-kotlin/runtime-engine/src/main/kotlin/skillbill/engine/work/IdeStatusService.kt`
- `runtime-kotlin/runtime-engine/src/main/kotlin/skillbill/engine/work/IdeStatusProjector.kt`
- `runtime-kotlin/runtime-engine/src/main/kotlin/skillbill/engine/work/IdeStatusProjectorMapping.kt`
- `runtime-kotlin/runtime-engine/src/main/kotlin/skillbill/engine/work/IdeStatusSelectionPolicy.kt`

Identity registration stays insert-once (`StandalonePhaseStatusStore.registerWorkflowInTransaction` writes `ide_status_workflow_execution` with `status_revision` `'1'` and `updated_at` equal to `started_at`). There is no UPDATE path for that table. Do not bump `status_revision`. Projection is computed at read time.

## Implementation Details

`IdeStatusService.status` collects work-list candidates, filters `isExcludedGoalChild` when `routeScope == FeatureTaskRouteScope.GOAL_CHILD` and the issue has a `FEATURE_GOAL` work item, then `IdeStatusSelectionPolicy.select`, then `projector.project`. Keep `isExcludedGoalChild`. Do not use it to hide the parked parent; the parked parent is `WorkflowFamily.TASK_RUNTIME` / `FEATURE_TASK_RUNTIME`, not `GOAL_CHILD`.

`IdeStatusProjector.project` routes `FEATURE_GOAL` to `projectGoal` and `FEATURE_TASK_RUNTIME` to `projectRuntime`. `projectGoal` already maps a `PAUSED` candidate to `ACTIVE` when `projection.executionLiveness == LIVE && !projection.paused`. `goalLifecycleForOperatorBlock` maps `ACTIVE` plus child operator-decision pause on quality-gate phases to `BLOCKED`. Keep those operator-pause mappings.

`goalCurrentStep(planningStep, childContext.currentPhaseId, projection?.currentStep, lifecycle, openCiMonitor)` prefers `childPhaseId` over `projection.currentStep` over `OPEN_CI_MONITOR_STEP` (`"monitor"`) when `goalStaysOnOpenCiMonitor`. If `currentChildWorkflowId` still points at the completed child, `childOptionalContext` reads `FeatureTaskRuntimeStatusRequest(workflowId)` and can show `commit_push` / implement instead of monitor. A completed child's `currentPhaseId` must not beat monitor.

`projectRuntime` uses `featureTaskRuntimeStatusService.status` or `snapshot.currentStepId`, so a selected parked parent shows plan (or the pending implement label) and `candidate.lifecycleState` `PAUSED`.

`IdeStatusSelectionPolicy.select` keeps `ACTIVE` / `PAUSED` / `BLOCKED` non-stale rows as the live cohort, so a still-paused parent outranks a terminal child. Comparator order is non-null `runSequence` first, then higher `runSequence`, then freshness, then `selectionTier.rank` (`ACTIVE`, `PAUSED`, `BLOCKED`, `FAILED`, `RECENTLY_TERMINAL`, `IDLE`), then `isGoalAuthoritative`, then `updatedAt`, then `workflowId`. Goal-authoritative loses to a higher `runSequence` in the same live cohort. Feature-goal outranks child runtime only after those keys, which is why the existing test `feature-goal outranks child runtime for the same issue within a tier` has no execution identities.

Fix projection so the 0AC-46 selected snapshot is `FEATURE_GOAL` (or the parent projected through `projectGoal`) with `current_step` monitor and lifecycle active/live, not implement and not plan. Parked-parent `PAUSED` must not win over a live goal on monitor.

Assumption to confirm during implement: the 0AC-46 work list may or may not include a `FEATURE_GOAL` item besides the parked parent and completed child. Cover both shapes. Select the live goal projection whenever the goal runner is on monitor/PR/CI, even if the parked parent is the only non-terminal work-list row.

Operator pause from `controlState.paused` and operator-decision pause must still project Paused/Blocked as they do today.

## Acceptance Criteria

1. An ide-status test for the 0AC-46 shape (paused parent at plan, completed child at commit_push, goal on monitor) asserts lifecycle is not paused-as-handle and `currentStep.id` is monitor. It does not project implement or plan as the live phase.
2. The same 0AC-46 shape is covered with and without a `FEATURE_GOAL` work-list candidate. In both shapes the selected snapshot is the live goal projection (`FEATURE_GOAL`, or the parent projected through `projectGoal`) with `current_step` monitor.
3. A completed child's `currentPhaseId` does not beat monitor once the live goal step is monitor.
4. Parked-parent `PAUSED` at a lower or equal live-cohort rank does not win over a live goal on monitor. `IdeStatusSelectionPolicyTest` adds parked-parent `PAUSED` seq 20 versus completed child seq 21 versus live `FEATURE_GOAL` on monitor.
5. Existing operator-pause and operator-decision-pause tests in `IdeStatusServiceGoalProjectionTest` still assert Paused/Blocked. Existing SKILL-362 idle-versus-pause and SKILL-416 current-branch monitor tests still assert those behaviors. `IdeStatusServiceTest` still asserts that goal-child runtime is suppressed when an authoritative feature-goal exists.

## Test Obligations

Each obligation names the realistic bug it would catch while the rest of the suite stayed green.

1. New `IdeStatusServiceGoalProjectionTest` 0AC-46 shape: paused parent at plan, completed child at commit_push, goal on monitor. Bug: selected snapshot is the parked parent (`plan` / paused-as-handle) or the completed child's `commit_push` / implement, so ide-status shows Implementation after children finished.
2. Selection case in `IdeStatusSelectionPolicyTest`: parked-parent `PAUSED` seq 20 versus completed child seq 21 versus live `FEATURE_GOAL` on monitor. Bug: the still-paused parent stays in the live cohort and beats the goal on `runSequence` / tier, so the projector never sees the goal snapshot.

Extend, do not replace:

- `IdeStatusServiceGoalProjectionTest.kt`: `active goal candidate projects paused once the pause is consumed`; `active goal candidate with an unconsumed pause request stays active`; `blocked goal candidate stays blocked under paused and pause_requested controls`; `active goal with child validate blocked on operator action projects blocked lifecycle`; `running goal whose parent lease expired projects idle or active without operator pause`.
- `IdeStatusSelectionPolicyTest.kt`: `paused outranks blocked failed and terminal`; `active outranks paused...`; `feature-goal outranks child runtime for the same issue within a tier`; `higher execution runSequence wins among active candidates with the same timestamp`; SKILL-416 pair `a stale active monitor does not mask a later terminal monitor` / `a fresh active monitor still outranks an older terminal monitor`.
- `IdeStatusServiceTest.kt`: `goal-child runtime is suppressed when an authoritative feature-goal exists for the issue`.
- `IdeStatusServiceBranchScopingTest.kt`: `currentStep.id` monitor and summary `Goal SKILL-148 is active on monitor.` from a `GOAL_FINALIZATION_OPERATION_KIND` parent progress event.

Do not run these tests in implement. Validate owns execution.

## Non-Goals

- Goal-runner extras / `latestLivenessSignal` (subtask 1).
- Plugin `acceptsNewerStatus` (subtask 3). Each poll is one selected snapshot; this subtask is what that snapshot is.
- Bumping `ide_status_workflow_execution.status_revision` or adding an UPDATE path for identity rows.
- Removing `isExcludedGoalChild`.
- Treating parent `WorkflowStatus.PAUSED` as an operator pause.

## Dependency Notes

Depends on subtask 1. `projectGoal` consumes the corrected goal-status extras. A completed or non-running child must already be non-authoritative for `current_step` and liveness once monitor/PR/CI is live, or `goalCurrentStep` can still prefer `childPhaseId`.

## Validation Strategy

Validate phase runs the pack gate / `./gradlew check`. This subtask is proven by the named ide-status projection and selection tests in the tree. Build phase owns compile.

## Next path

```bash
skill-bill goal SKILL-417
```
