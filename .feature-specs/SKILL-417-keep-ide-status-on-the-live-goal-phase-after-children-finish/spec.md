# SKILL-417 Keep IDE status on the live goal phase after children finish

## Intent

While a skill-bill goal is live on monitor, PR, or CI after children have finished implement through commit_push, IntelliJ, VS Code, skill-bill work status / ide-status, and skill-bill goal status `current_step` all show that live step. They must not show implement, and they must not report the parked parent plan/paused row as the live signal.

Observed on 0AC-46: IntelliJ showed Implementation while the worktree looked idle. Live goal status was `current_step=monitor`, `active_agent=none`, `paused=false`. The child had completed implement through commit_push. Three cooperating seams produced the freeze:

1. Decomposition parks the parent feature-task as `workflow_status=paused`, `current_step_id=plan`, with implement still pending. Children own implement through commit_push. Goal-level PR/monitor lives on the goal runner, not that parent row.
2. `latest_liveness_signal` falls back to that parked parent and prints `workflow_status=PAUSED; step=plan` while the live goal step is monitor.
3. `ide_status_workflow_execution` identity rows are write-once. After the child (higher `run_sequence`) goes terminal, selection prefers the still-paused parent, and both IDE plugins reject a later live monitor snapshot whose `run_sequence` is lower than the last accepted child implement snapshot.

This goal fixes projection, liveness, selection, and plugin acceptance so UIs follow the live goal step. It does not make the parent feature-task walk implement or review.

## Scope

- Goal-runner progress extras and `latestLivenessSignal` so a live monitor/PR/CI step is not reported as the parked parent `PAUSED`/`plan` row.
- ide-status selection and projection for the 0AC-46 shape, including a parked parent competing with a completed child with and without a `FEATURE_GOAL` work-list row.
- IntelliJ and VS Code `acceptsNewerStatus` so a held child implement snapshot yields to a later live parent/goal monitor snapshot at a lower `run_sequence` and different execution id.
- Operator pause and operator-decision pause stay Paused/Blocked. A parked parent used only as a decomposition handle is not an operator pause.
- SKILL-362 (truthful idle vs pause) and SKILL-416 (live work on the current branch) stay true.

## Acceptance Criteria

1. A reproduction of the 0AC-46 shape (parent paused at plan, child completed at commit_push, goal runner on monitor) projects lifecycle active (or the truthful live state) and `current_step` monitor to ide-status. It does not show implement or plan as the live phase. IntelliJ and VS Code status coordinators accept that live monitor snapshot after first displaying the child implement snapshot.
2. `latest_liveness_signal` for that shape is not `workflow_status=PAUSED; step=plan`. Goal status `current_step` and the liveness signal agree on the live step.
3. Plugin polling that first accepted a child `run_sequence` N on implement, then sees the child terminal and the parent/goal still live on monitor with `run_sequence` less than N and a different execution id, updates to monitor rather than keeping Implementation.
4. Operator pause and operator-decision pause still show Paused or Blocked. A parked parent used only as a decomposition handle is not an operator pause.
5. Existing SKILL-362 (truthful idle vs pause) and SKILL-416 (live work on current branch) tests remain and still assert those behaviors.

## Constraints

- Follow runtime-kotlin `ARCHITECTURE.md`, `docs/code-principles.md`, and `AGENTS.md`.
- Kotlin under `runtime-kotlin` and `intellij-plugin`: no `//` comments and no non-KDoc block comments. Wire keys stay in `runtime-contracts`.
- Do not make the parent feature-task walk implement or review. Children still own those phases. Fix projection, liveness, selection, and/or plugin acceptance.
- Keep one user-level review-metrics SQLite file.
- Do not change phase order, review policy, or commit-before-review.
- Keep `ide_status_workflow_execution` identity write-once. Do not bump `status_revision` as the status path. Projected `current_step` / lifecycle / freshness come from the live goal runner plus the current child if any.
- Parking stays `WorkflowStatus.PAUSED` at `plan`. Distinguish parking from operator pause at projection and liveness using `GoalRunnerControlState.paused` / `pauseRequested` / `pauseReason` and child operator-decision pause.
- `isExcludedGoalChild` stays. Do not rely on it to hide the parked parent; the parked parent is `FEATURE_TASK_RUNTIME`, not `GOAL_CHILD`.
- Both IDE plugins change `acceptsNewerStatus` in lockstep. Do not loosen the same-class live-versus-live rule (newer live standalone work must not be replaced by older live standalone work).

## Non-Goals

- Changing 0AC-46 product behavior in ZeroAccount.
- Per-repository metrics databases.
- Raising idle-timeouts as a substitute for truthful status.
- Changing `DecompositionWorkflowContinuation` parking into a parent phase walk.
- A schema or write-path change to `ide_status_workflow_execution.status_revision`.

## Decomposition

Three dependency-ordered subtasks, one commit each. All three must land before claiming parent AC1 through AC3; the bug is three cooperating seams.

1. Goal-status liveness agrees with the live goal step (`spec_subtask_1_goal-status-liveness.md`).
2. ide-status selects and projects the live goal step for the 0AC-46 shape (`spec_subtask_2_ide-status-live-goal-projection.md`). Depends on 1.
3. Both IDE plugins accept the post-child parent/goal snapshot (`spec_subtask_3_ide-plugins-accept-post-child-snapshot.md`). Depends on 2.

## Settled questions

1. The 0AC-46 `ide_status_workflow_execution` evidence named only the parked parent and completed child. Implement both work-list shapes: paused parent versus terminal child with a `FEATURE_GOAL` candidate, and the same pair without one. Select the live goal projection whenever the goal runner is on monitor/PR/CI, even if the parked parent is the only non-terminal work-list row. Confirm during implement whether a `FEATURE_GOAL` item is present at monitor; the tests cover both.
2. `currentSubtask.workflowId` may still point at the completed child during monitor. A completed or non-running child must not supply `current_step` or liveness once `goal_finalization` / monitor (or PR/CI) is the live goal step. Confirm the pointer during implement; the rule does not depend on that confirmation.
3. Linear issue SKILL-417 was not found. This key is locally allocated; these specs are authoritative. No tracker comment, account, or dependency coordinate is required.
4. `base_branch` is `main` (repository default; the digest did not name another branch).

## Validation Strategy

Validate phase owns `./gradlew check` and the pack validation gate. Build phase owns compile. This plan does not run either. After all three subtasks, validate must keep the named SKILL-362, SKILL-416, operator-pause, selection-ranking, and plugin live-versus-live tests green, plus the new 0AC-46 cases.

## Next path

```bash
skill-bill goal SKILL-417
```
