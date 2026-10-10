# Subtask 1. Goal-status liveness agrees with the live goal step

## Scope

Make goal status `current_step` and `latest_liveness_signal` agree on the live goal step when the goal runner is on monitor, PR, or CI after children finished implement through commit_push.

Owner: runtime-kotlin goal runner. Do not invent a second `progress` implementation; `WorkflowGoalRunnerOutcomeStore.progress` already forwards to `progressRecording.progress`.

Files:

- `runtime-kotlin/runtime-engine/src/main/kotlin/skillbill/engine/goalrunner/persist/WorkflowGoalRunnerProgressRecording.kt`
- `runtime-kotlin/runtime-engine/src/main/kotlin/skillbill/engine/goalrunner/status/GoalRunnerStatusProjectionAssembler.kt`
- `runtime-kotlin/runtime-domain/src/main/kotlin/skillbill/goalrunner/model/GoalRunnerStatusProjectionModels.kt`
- `runtime-kotlin/runtime-domain/src/main/kotlin/skillbill/goalrunner/model/GoalRunnerStatusProjectionContext.kt`

Related readers, not a second progress path: `GoalRunnerStatusProjector.project` / `assembleGoalRunnerStatusProjection` (current step from `extras.currentStepOverride`, liveness from `extras.latestLivenessSignal`), and CLI formatting in `GoalCliStatusFormatting` as `latest_liveness_signal`.

## Implementation Details

`WorkflowGoalRunnerProgressRecording.progress(workflowId)` reads the named workflow row and sets `latestLivenessSignal` to `observabilityEvent?.compactLivenessSummary() ?: progressEvent?.summary() ?: "workflow_status=${record.workflowStatus}; step=$currentStep"`. `currentStep` is `record.currentStepId` unless the row is completed or has step `pr` completed, in which case it is `"pr"`. That fallback is how 0AC-46 printed `workflow_status=PAUSED; step=plan` from the parked parent.

`GoalRunnerStatusProjectionAssembler.statusProjectionRuntimeInputs` builds extras as: child workflow progress when `childWorkflowId` is present, otherwise parent progress only when parent execution liveness is `LIVE` and the latest declared progress event operation kind is `goal_finalization` (`GOAL_FINALIZATION_OPERATION_KIND` in `GoalRunnerCiMonitor.kt`). The parent copy then sets `currentStepId` from that event's `stepId` (monitor/PR/CI) while leaving `latestLivenessSignal` on the parked-row fallback.

`GoalRunnerCiMonitor` records that declared event on `state.parentWorkflowId` with `stepId = event.phaseId`. `extras.paused` / `pauseRequested` / `pauseReason` / `pausedAt` already come from `assembly.loadedState.controlState`, not from the parent feature-task `WorkflowStatus`. Keep that.

Fix so that when the live goal step is monitor/PR/CI (`goal_finalization` declared event or equivalent live goal step):

- `latestLivenessSignal` is not the parked parent `PAUSED`/`plan` string.
- `currentStep` and `latestLivenessSignal` agree on that live step.
- `GoalRunnerStatusProjectionRuntimeInputs` is the extras bag. `currentStepOverride` and `latestLivenessSignal` are separate fields and must be updated together when rewriting the parent progress copy.

If a completed child still has a `workflowId`, do not let that child's completed `commit_push` row supply the live signal or override monitor. Assumption to confirm during implement: `currentSubtask.workflowId` may still point at the completed child during monitor. Treat a completed or non-running child as non-authoritative for step and liveness once `goal_finalization` / monitor (or PR/CI) is the live goal step.

Do not treat `controlState.paused` or `pauseRequested` as false when the operator really paused. Do not change `DecompositionWorkflowContinuation` parking. Do not walk the parent through implement.

## Acceptance Criteria

1. A goal-status assembler or progress test for the 0AC-46 shape (parked parent `PAUSED` at plan, goal_finalization declared event with `stepId` monitor) asserts `latestLivenessSignal` is not `workflow_status=PAUSED; step=plan` and that `currentStep` / `latestLivenessSignal` agree on monitor.
2. When a completed child `workflowId` is still present, that child's completed `commit_push` row does not become the live signal and does not override monitor as `currentStep`.
3. Existing `GoalRunnerStatusProjectorTest` cases for stale versus live signals and blocked child versus running child still assert those behaviors. Existing `GoalRunnerTest` assertions on `status.latestLivenessSignal` still assert a live signal rather than a parked-row fallback when the goal is live.
4. Operator pause from `controlState.paused` / `pauseRequested` is still projected as paused; this subtask does not clear a real operator pause.

## Test Obligations

Each obligation names the realistic bug it would catch while the rest of the suite stayed green.

1. New assembler/progress case: parked parent `PAUSED` at plan plus `goal_finalization` `stepId` monitor. Bug: extras copy `currentStepId` from the goal_finalization event and leave `latestLivenessSignal` on the parked-row fallback, so CLI prints `workflow_status=PAUSED; step=plan` while `current_step` is monitor (0AC-46 AC2).
2. New case: completed child `workflowId` still set while the live goal step is monitor. Bug: assembler still calls `progress(childWorkflowId)` and publishes the child's completed `commit_push` row as the live signal.

Extend, do not replace: `runtime-kotlin/runtime-domain/src/test/kotlin/skillbill/goalrunner/GoalRunnerStatusProjectorTest.kt` (stale versus live signals, blocked child versus running child) and `runtime-kotlin/runtime-engine/src/test/kotlin/skillbill/engine/goalrunner/GoalRunnerTest.kt` assertions on `status.latestLivenessSignal`.

Do not run these tests in implement. Validate owns execution.

## Non-Goals

- ide-status selection, `projectGoal` / `goalCurrentStep`, or plugin `acceptsNewerStatus` (subtasks 2 and 3).
- Changing parent parking or advancing the parent through implement/review.
- A second `progress` implementation on `WorkflowGoalRunnerOutcomeStore`.
- Clearing or ignoring a real operator pause.

## Dependency Notes

No earlier subtask. Subtask 2 reads the corrected extras (`currentStepOverride` and `latestLivenessSignal` together) through `goalRunnerStatusService.status`.

## Validation Strategy

Validate phase runs the pack gate / `./gradlew check`. This subtask is proven by the named assembler/progress and projector tests remaining or landing in the tree. Build phase owns compile.

## Next path

```bash
skill-bill goal SKILL-417
```
