# SKILL-408 Subtask 2: Operator decision and completed_no_change outcome

Parent: `.feature-specs/SKILL-408-no-change-goal-outcome-with-evidence/spec.md`. Use the parent's
Shared Contract names exactly.

## Scope

This subtask lets the operator resolve a no-change pause. `accept_and_advance` completes the goal
as `completed_no_change` with no commit, push or PR. `retry_fix` resumes with operator
instructions, and `abandon_subtask` abandons the subtask. It also reports the outcome in
`goal status` and telemetry, and makes the `/skill-bill` dispatcher ask once.

Path prefixes: DOM = `runtime-kotlin/runtime-domain/src/main/kotlin/skillbill/`,
ENG = `runtime-kotlin/runtime-engine/src/main/kotlin/skillbill/engine/`,
CLI = `runtime-kotlin/runtime-cli/src/main/kotlin/skillbill/cli/`, CON = `orchestration/contracts/`.

## Owned Paths

- ENG `goalrunner/GoalOperatorDecisionService.kt` (`record`, lines 17-35).
- ENG `goalrunner/model/GoalRunnerOperatorDecisionModels.kt` (the request model).
- CLI `goal/control/GoalCliControlCommands.kt` (`GoalOperatorDecisionCommand`, lines 292-334).
- DOM `goalrunner/model/GoalRunnerTerminalModels.kt` (`GoalRunnerTerminalStatus`,
  `GoalRunnerRunReport` and `GoalPullRequestStatus`; the latter is not changed).
- ENG `goalrunner/execution/core/GoalRunnerFinalization.kt` and
  `GoalRunnerAcceptanceCoordinator.kt`.
- ENG `goalrunner/telemetry/GoalRunnerTelemetryEmitter.kt`.
- `GoalRunnerStatusProjectionAssembler.kt` and the goal status service.
- The goal-state store for subtask outcomes, wherever the subtask status or outcome is persisted.
- DOM `FeatureTaskRuntimeNoChangePause`, used to read and record the decision. Subtask 1 creates
  it; if it is absent, create it per the parent Shared Contract.
- CON `telemetry-event-schema.yaml` (already has uncommitted edits).
- `skills/skill-bill/content.md` and
  `runtime-kotlin/runtime-infra/skills/src/repoTest/kotlin/skillbill/install/DispatcherSkillInstallRepoTest.kt`.
- Tests: ENG `src/test/.../featuretask/lifecycle/core/FeatureTaskRuntimeOperatorDecisionEntryPointTest.kt`
  and the finalization, status and telemetry tests next to the changed code.

## Implementation Steps

1. **Route decisions by pause kind.**
   - In `GoalOperatorDecisionService.record`, check whether the subtask's child workflow holds a
     `no_change_pause` artifact whose `operator_decision` is null. Do this ahead of the existing
     "Operator decisions over review remediation are removed" branch, and ahead of any guard
     that requires a review-state `PAUSED` disposition.
   - If it does, handle the decision as below. If it does not, keep the existing rejection
     unchanged.

   This step exists so that the existing wire values serve the new pause without reopening
   review remediation. (AC 1)
2. **Carry operator instructions.**
   - Add a nullable `instructions` field to `GoalRunnerOperatorDecisionRequest` and an
     `--instructions` option to `goal operator-decision`.
   - Record the decision and instructions on the pause artifact.
   - For a no-change pause, `retry_fix` requires non-blank instructions. Reject instructions
     given with any other decision, so they are never silently dropped.

   (AC 2)
3. **Accept.**
   - Record `accept_and_advance` on the artifact and mark the subtask's outcome
     `completed_no_change`.
   - Close the child workflow without running review, verify_findings, validate, write_history,
     commit_push or pr. Then advance to the next pending subtask.
   - At finalization, if the goal produced no subtask commits and at least one subtask is
     `completed_no_change`, finish with `GoalRunnerTerminalStatus.COMPLETED_NO_CHANGE` and a new
     `GoalRunnerRunReport` variant. That variant carries the issue key, the no-change reason and
     the subtask ids, and has no PR fields.
   - Skip `commitAllRemainingWorktree`, the push helpers, `pullRequestPort.open` and
     `GoalRunnerAcceptanceCoordinator` commit-evidence resolution.
   - A goal with commits finalizes exactly as today.

   (AC 3, 4)
4. **Evidence persistence.** The accepted `no_change_pause` artifact in the child-workflow store
   is the persisted evidence, and spec-scratch cleanup must not delete it. (AC 5)
5. **Retry and abandon.**
   - `retry_fix` resumes the child workflow into `audit_implement_fix` with the operator
     instructions as its fix input. Time spent paused does not count toward the resumed run's
     wall-clock cap.
   - `abandon_subtask` follows the existing abandon semantics and telemetry status `abandoned`.

   (AC 6, 7)
6. **Status and telemetry.**
   - `goal status` shows `completed_no_change` with its `no_change_reason`. For a paused goal it
     shows `awaiting_no_change_decision` with the reason and the suggested handoff.
   - Add `completed_no_change` to `goalFinishedStatusEnum`.
   - `goalFinishedStatus` maps the new report variant to `completed_no_change` and sets
     `no_change_reason` on `goal_finished` and `goal_issue_finished`. If subtask 1 has not added
     the optional `no_change_reason` field to either event, add it there.
   - The `"1.12.0"` const is unchanged.

   (AC 8, 9)
7. **Dispatcher.** In `skills/skill-bill/content.md`:
   - Add an exception to the goal Relay section (lines 243-248) for the
     `awaiting_no_change_decision` line.
   - Add a block modeled on the awaiting_confirmation handling (lines 378-386). It shows the
     report, asks the user once (accept, retry with instructions, or abandon), then runs
     `skill-bill goal operator-decision` with the answer. It never decides without an answer and
     never re-asks.
   - Update `DispatcherSkillInstallRepoTest` only where it pins the changed text.

   (AC 10)

## Acceptance Criteria

1. `GoalOperatorDecisionService.record` handles decisions for a subtask whose child workflow has
   an undecided `no_change_pause` artifact. For every other subtask that has a child workflow, it
   still returns the existing review-remediation rejection.
2. `GoalRunnerOperatorDecisionRequest` and the `goal operator-decision` command carry optional
   operator instructions (`--instructions`), and the decision and instructions are recorded on
   the pause artifact. `retry_fix` on a no-change pause without non-blank instructions is
   rejected, and instructions given with `accept_and_advance` or `abandon_subtask` are rejected.
3. `accept_and_advance` on a no-change pause records the decision and marks the subtask
   `completed_no_change`. It closes the child workflow without running review, verify_findings,
   validate, write_history, commit_push or pr, and advances to the next pending subtask.
4. A goal that ends with no subtask commits and at least one `completed_no_change` subtask
   finishes with `GoalRunnerTerminalStatus.COMPLETED_NO_CHANGE` through a `GoalRunnerRunReport`
   variant that has no PR fields. Its finalization path calls no commit, push or
   `pullRequestPort.open` code. A goal with commits still finalizes through `Completed` with a PR.
5. After a `completed_no_change` finalization, including spec-scratch cleanup, the accepted
   `no_change_pause` artifact (reason and evidence for each criterion) can still be read from the
   child-workflow store.
6. `retry_fix` on a no-change pause resumes the child workflow into `audit_implement_fix` with the
   operator instructions in its fix input, and time spent paused is excluded from the resumed
   run's wall-clock cap.
7. `abandon_subtask` on a no-change pause abandons the subtask through the existing abandon
   handling.
8. `goal status` output shows `completed_no_change` with the no-change reason for a finished
   no-change goal. For a paused one, it shows `awaiting_no_change_decision` with the reason and
   the suggested handoff.
9. `telemetry-event-schema.yaml` lists `completed_no_change` in `goalFinishedStatusEnum` and keeps
   the `"1.12.0"` const. The emitter sends `completed_no_change` and `no_change_reason` on
   `goal_finished` and `goal_issue_finished` for a no-change completion.
10. `skills/skill-bill/content.md` makes the `awaiting_no_change_decision` line an exception to the
    goal Relay rule. It contains a block that shows the report, asks the user once, and runs
    `skill-bill goal operator-decision` with the chosen decision (and the instructions for
    `retry_fix`), and it never decides without the user's answer.

## Test Obligations

- A goal-runner test (AC 3, 4, 5). On a single-subtask goal, accepting a no-change pause yields
  the `COMPLETED_NO_CHANGE` report variant, the fake PR port records no open, the commit and push
  helpers are not reached, and the pause artifact is still readable after scratch cleanup. It
  catches a no-change accept that opens an empty PR or loses its evidence.
- A goal-runner test (AC 4) for a goal where one subtask is `completed_no_change` and another
  committed: it finalizes as `Completed` with a PR. It catches a mixed goal that drops its PR.
- An operator-decision test (AC 2, 6). `retry_fix` with instructions resumes into
  `audit_implement_fix` with the instructions in the input, and `retry_fix` without instructions
  is rejected.
- An operator-decision test (AC 6). A decision made after more than the wall-clock cap has
  elapsed on a fake clock resumes without a wall-clock stop.
- Keep the existing review-remediation rejection test green in
  `FeatureTaskRuntimeOperatorDecisionEntryPointTest`, or add one if no such test exists (AC 1).
- A telemetry test that the `goal_finished` payload for a no-change completion validates against
  the schema with `status: completed_no_change` and `no_change_reason` (AC 9).
- A status projection test for `completed_no_change` plus the reason (AC 8).
- Abandon (AC 7) reuses the existing handling and needs no new test unless it gains a new branch.
- Use `relaxUnitFun = true`, never `relaxed = true`.

## Non-Goals

- Completing a goal without the operator's decision.
- Writing to the tracker.
- Investigating other repositories.
- Changing `GoalSubtaskOperatorDecision` wire values, the review-state schema, or the
  review-remediation rejection.
- Adding an MCP tool. `golden/mcp-tools-list.json` changes only if the operator-decision surface is
  already exposed over MCP and its parameters change.

## Dependency Notes

This subtask has no manifest dependencies and runs on the current tree. If subtask 1 has already
landed, use its `FeatureTaskRuntimeNoChangePause`, its stop reason and its telemetry additions. If
not, create the elements this subtask's criteria need, exactly as the parent Shared Contract
defines them. Keep every uncommitted SKILL-407 edit in `telemetry-event-schema.yaml`.

## Assumptions to Confirm

- Where the subtask outcome is persisted, and whether it needs a new `completed_no_change` value
  in a goal-state enum or schema. Use the smallest additive change.
- Whether `reconcileBeforeFinalization` must also be skipped for a no-change goal. Skip it only
  if it commits or pushes.
- How the wall-clock cap is measured. If it counts from goal start, subtract paused intervals.
  If each runner invocation measures it, AC 6 holds without a change. Confirm which applies.
- How the goal status projection carries the stop reason and terminal status
  (`GoalRunnerStatusProjectionAssembler.kt`, which has not been read).
- The existing `goal operator-decision` argument syntax. The dispatcher block must use it exactly.

## Validation Strategy

The validate phase runs `./gradlew check`, which includes `DispatcherSkillInstallRepoTest` and
agent-config validation. Implement and audit inspect the tree only.

## Next Path

```bash
skill-bill goal SKILL-408
```
