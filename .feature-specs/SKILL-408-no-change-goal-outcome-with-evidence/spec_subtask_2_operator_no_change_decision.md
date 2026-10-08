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

## Implementation Details

These details come from the preplan digest. Paths use the prefixes defined under Scope.

**How decisions are applied (settled design).**
- `record` validates the decision and writes it to the pause artifact. It changes nothing else.
- The goal runner applies the recorded decision the next time the goal resumes.
  `AWAITING_NO_CHANGE_DECISION` is in `RESUMABLE_STOP_REASONS`, so a resume reaches the paused
  subtask.
- On resume the runner reads the child's latest `no_change_pause` artifact:
  - `accept_and_advance`: mark the subtask `completed_no_change`, close the child workflow, and
    select the next pending subtask.
  - `retry_fix`: resume the child into `audit_implement_fix` with the instructions as fix input.
  - `abandon_subtask`: run the existing abandon handling.
  - `operator_decision` still null: stop again with `AWAITING_NO_CHANGE_DECISION`. The runner
    never decides on its own.
- Why this split: a crash between recording and applying leaves a decided artifact, and the next
  resume applies it. Applying is idempotent, and there is only one site that applies decisions.
- Implement confirms that this matches how the dispatcher resumes a goal after a decision. If the
  existing operator-decision command already drives the goal forward itself, apply the decision
  from that same site instead and keep the idempotence.

**Ordered tasks.**

1. **Shared Contract presence check (AC 1, 2, 8, 9).**
   - Check whether subtask 1 has landed the following:
     - DOM `FeatureTaskRuntimeNoChangePause` with its strict `fromArtifactMap` and its
       `toArtifactMap`, built on the `FeatureTaskRuntimeWorkflowArtifactMap` pattern in
       `workflow/taskruntime/model/core/`.
     - `GoalRunnerStopReason.AWAITING_NO_CHANGE_DECISION` in `GoalRunnerTerminalModels.kt`
       (lines 28-48), included in `RESUMABLE_STOP_REASONS` (line 46).
     - The `awaiting_no_change_decision` value in `goalRunnerStopReasonEnum`.
     - The optional `no_change_reason` field on `goal_finished` and `goal_issue_finished`.
   - If they exist, reuse them as they are. For any that are missing, create it exactly as the
     parent Shared Contract defines it. A newly created stop reason also needs the exhaustive
     `when` entries the digest lists:
     - `supervisionEvent` in `GoalRunnerStopReports.kt` (126-139).
     - `toLedgerAction`, `toDiagnosticClass` and `nextSafeAction` in
       `GoalRunnerTerminalModels.kt` (141-189).
     - Mirror the `AWAITING_OPERATOR_DECISION` handling in `GoalCliExitCodes.kt`,
       `IdeStatusModels.kt`, `IdeStatusProjector.kt` and `GoalRunnerPolicy.kt`.
   - The write side and the strict read side of the artifact must have the same field set.
     `operator_decision` and `operator_instructions` must be writable by a copy-and-rewrite of
     the artifact. Assumption: the child-workflow artifact store supports replacing an artifact
     by key. Implement confirms the store API. If artifacts can only be appended, write a new
     `no_change_pause` record that carries the decision, and read the latest one.
   - Tests: none for this task. The artifact round-trip is subtask 1's test obligation. If this
     subtask creates the artifact, add one round-trip test for the strict read. It catches a
     field-set mismatch between writer and reader.

2. **Request model and CLI option (AC 2).**
   - In ENG `goalrunner/model/GoalRunnerOperatorDecisionModels.kt`, add
     `instructions: String? = null` to `GoalRunnerOperatorDecisionRequest`. Placing it last
     keeps existing call sites compiling.
   - In CLI `goal/control/GoalCliControlCommands.kt` `GoalOperatorDecisionCommand` (292-334),
     add an optional `--instructions` option and pass it into the request built at 322-330.
   - Keep the `goalOperatorDecisionText` and map helpers unchanged. Extend them only if they
     render request fields.
   - If the operator-decision surface is exposed over MCP, add the parameter there and update
     `golden/mcp-tools-list.json`. Otherwise leave MCP alone, as the Non-Goals say.
   - The `GoalSubtaskOperatorDecision` wire values in
     DOM `workflow/model/goalreview/GoalSubtaskReviewConstantsAndDispositions.kt` (9-13) stay
     unchanged.

3. **Routing and validation in `record` (AC 1, 2).**
   - In ENG `goalrunner/GoalOperatorDecisionService.kt` `record()` (17-35), after
     `resolveChildWorkflow` (37-60) and before the review-remediation rejection, load the
     child's latest `no_change_pause` artifact.
   - If that artifact is absent, or its `operator_decision` is non-null, return the existing
     rejection unchanged. Leave its text and branch alone.
   - If it is present and undecided, validate the request:
     - `retry_fix` with null or blank `instructions` returns `Rejected` with a reason naming
       `--instructions`.
     - Non-null `instructions` with `accept_and_advance` or `abandon_subtask` returns
       `Rejected`, saying instructions apply only to `retry_fix`.
   - A valid request writes `operator_decision` and `operator_instructions` (trimmed) to the
     artifact and returns `Recorded`.
   - Keep the service in its current file. If helpers are needed, add them as private functions
     there or put them in an existing sibling, so that `PackageSiblingCountArchitectureTest`
     does not trip. Do not add a public port, so that `RuntimeEngineInboundApiTest` does not
     trip.
   - Tests, in `FeatureTaskRuntimeOperatorDecisionEntryPointTest`:
     - The existing review-remediation rejection test stays green. If no such test exists, add
       one with a child workflow and no pause artifact. It catches the new branch swallowing
       review decisions.
     - `retry_fix` without instructions is rejected and leaves the artifact undecided. It
       catches a silent retry with an empty fix input.

4. **Subtask outcome `completed_no_change` (AC 3).**
   - Settled: add `completed_no_change` to the subtask status that the goal state store
     persists, which is the manifest subtask `status` field next to `commit_sha`. This is the
     smallest additive change. Add it to the subtask-status enum or model and to the manifest or
     goal-state schema enum if one exists. Do not bump any contract const.
   - Every exhaustive `when` over subtask status gets the new entry.
   - Subtask selection treats `completed_no_change` like `completed` when it picks the next
     pending subtask, and accepts a null `commit_sha` for it.
   - Assumption for implement to confirm: the status is a closed enum. If it is free text,
     add a named constant next to the existing ones instead.

5. **Apply decisions on resume (AC 3, 6, 7).**
   - Apply the decision at the selected-subtask step, in `GoalRunnerSelectedSubtaskLoop.kt` or
     the resume path it calls, before the child workflow is driven. The trigger is a child
     workflow whose latest `no_change_pause` artifact is present.
   - `accept_and_advance`:
     - Set the subtask to `completed_no_change`.
     - Close the child workflow in its terminal state without running review, verify_findings,
       validate, write_history, commit_push or pr. Use the existing terminal status for a
       finished workflow.
     - If a store guard requires those phases before completion, add a narrow exception keyed
       on an accepted `no_change_pause`.
     - Do not call `GoalRunnerAcceptanceCoordinator.accept`, which resolves commit evidence.
     - Return to the goal loop so selection advances.
   - `retry_fix`:
     - Resume the child into `audit_implement_fix`, using the same fix-input carrier subtask 1
       uses for a rejected claim. Set the fix input to the operator instructions, prefixed
       `Operator instructions:`.
     - The retry goes through the normal repair loop with no special counter handling.
     - A later confirmed claim writes a new undecided pause artifact, and that newest artifact
       governs.
   - `abandon_subtask`: call the existing abandon handling, which sets subtask status
     `abandoned` and telemetry status `abandoned`. Add no new branch.
   - Undecided: stop with `AWAITING_NO_CHANGE_DECISION` again through
     `stopped(StoppedReportArgs(...))` (`GoalRunnerStopReports.kt` line 22).
   - Wall-clock cap (AC 6): the pause ends the runner invocation (Decision 6), so no timer runs
     while it waits.
     - Assumption for implement to confirm: the child wall-clock cap is anchored at a run or
       phase start, not at the goal start.
     - If the anchor is a persisted start timestamp from before the pause, re-anchor it when the
       decision is applied on resume. Re-anchoring excludes the paused interval.
   - Tests:
     - The `retry_fix` path ends in `audit_implement_fix` with the instructions in its fix input
       (AC 2, 6). It catches instructions that are dropped on resume.
     - Fake clock: record `retry_fix` after more than the cap has elapsed, and the resumed run
       is not stopped by the wall-clock cap (AC 6). It catches paused time counting against
       the cap.

6. **Finalization and the no-change report (AC 4, 5).**
   - In DOM `GoalRunnerTerminalModels.kt`, add
     `GoalRunnerTerminalStatus.COMPLETED_NO_CHANGE("completed_no_change")` (5-26).
   - Add a `GoalRunnerRunReport` variant `CompletedNoChange` (lines 113-139) carrying
     `issueKey`, `noChangeReason` and `subtaskIds`, with no PR fields. `Completed` keeps its
     meaning: a PR exists.
   - In ENG `goalrunner/execution/core/GoalRunnerFinalization.kt` `finalizeGoal` (called on
     selection `Done`, `GoalRunnerGoalLoop.kt` 54-61), branch first:
     - **No commits and at least one `completed_no_change`:** no subtask has a `commit_sha` and
       at least one subtask is `completed_no_change`.
       - Skip `commitAllRemainingWorktree`, the push helpers, `pullRequestPort.open` and
         acceptance-coordinator commit-evidence resolution.
       - Skip `reconcileBeforeFinalization` only if it commits or pushes. Otherwise keep it.
       - Run `deleteGoalSpecScratchOnSuccess` as today.
       - Return `CompletedNoChange`. Its `noChangeReason` is the reason of the first
         `completed_no_change` subtask in subtask order, read from that child's pause artifact.
     - **Otherwise:** finalize exactly as today.
   - Evidence (AC 5): the pause artifact lives in the child-workflow store. Confirm that
     `deleteGoalSpecScratchOnSuccess` touches only spec scratch, and that closing the child
     workflow does not prune its artifacts.
   - Update the exhaustive consumers of `GoalRunnerRunReport` and `GoalRunnerTerminalStatus`:
     - `GoalRunPresenter.kt` prints the issue key, `completed_no_change` and the reason.
     - `GoalCliExitCodes.kt` uses the success exit code that `Completed` uses.
     - `IdeStatusProjector.kt` and `IdeStatusModels.kt`, if they switch over either type.
   - Tests:
     - Goal-runner test (AC 3, 4, 5). Single-subtask goal with a recorded `accept_and_advance`,
       then resume. Expect the `CompletedNoChange` report, no open recorded on the fake PR port,
       no commit or push recorded on the fakes, and the pause artifact still readable from the
       child-workflow store after scratch cleanup. Assert outcomes on the fakes. Do not verify
       mock interactions.
     - Goal-runner test (AC 4). One subtask `completed_no_change` and one with a commit:
       finalizes as `Completed` with a PR. It catches a mixed goal that drops its PR.

7. **Status projection (AC 8).**
   - In `GoalRunnerStatusProjectionAssembler.kt` and `GoalRunnerStatusService.kt`, project a
     finished no-change goal as `completed_no_change` plus its `no_change_reason`.
   - Project a goal paused with `AWAITING_NO_CHANGE_DECISION` as `awaiting_no_change_decision`,
     with the reason and the suggested handoff read from the undecided pause artifact. If
     `suggested_handoff` is absent, use the derivation subtask 1 defines from `reason` and
     `owning_system`.
   - Assumption for implement to confirm: the assembler maps stop reasons through a `when` or a
     wire value. Add the entries the same way.
   - Test: one status projection test for `completed_no_change` with the reason. It catches
     status showing a no-change goal as plain `completed` or as unknown.

8. **Telemetry (AC 9).**
   - In CON `telemetry-event-schema.yaml`, add `completed_no_change` to
     `goalFinishedStatusEnum`.
   - Add `no_change_reason` to `goal_finished` and `goal_issue_finished` if task 1 found it
     missing. It is optional, with enum `out_of_repo`, `already_satisfied` and
     `not_reproducible`.
   - Keep the `"1.12.0"` const and every uncommitted SKILL-407 edit.
   - In ENG `goalrunner/telemetry/GoalRunnerTelemetryEmitter.kt`, `goalFinishedStatus` maps
     `CompletedNoChange` to `completed_no_change`. Both events set `no_change_reason` from the
     report. Other outcomes omit the field.
   - Test: the `goal_finished` payload for a no-change completion validates against the schema
     with `status: completed_no_change` and `no_change_reason`. It catches emitter and schema
     drift.

9. **Dispatcher (AC 10).**
   - In `skills/skill-bill/content.md`, add an exception to the goal Relay section (around
     lines 243-248) for a line starting `awaiting_no_change_decision:`.
   - Add a block modeled on the awaiting_confirmation handling (around lines 378-386). It:
     - Shows the reported reason, the verdict and evidence for each criterion, the citations,
       the boundary trace, the owning system and the suggested handoff.
     - Asks the user once to choose accept, retry with instructions, or abandon.
     - Runs `skill-bill goal operator-decision` with the issue key, the subtask id, the chosen
       wire value, and `--instructions "<text>"` for `retry_fix` only.
     - Then resumes the goal the way the existing operator-decision guidance does.
     - Never decides without the user's answer and never re-asks.
   - Copy the argument syntax exactly from `GoalOperatorDecisionCommand`, which is the
     assumption to confirm.
   - Update `DispatcherSkillInstallRepoTest` only where it pins the changed text. Do not run
     the installer.

10. **Constraints for every task.**
    - Mocks use `relaxUnitFun = true`, never `relaxed = true`. Prefer fakes for the PR, commit
      and push ports.
    - No contract-const bumps.
    - The review-state schema and `GoalSubtaskReviewState` stay unchanged.
    - Do not write tracker updates or worktree files on the no-change path.
    - Do not build, test or run check in implement. Validate runs `./gradlew check`.

## Validation Strategy

The validate phase runs `./gradlew check`, which includes `DispatcherSkillInstallRepoTest` and
agent-config validation. Implement and audit inspect the tree only.

## Next Path

```bash
skill-bill goal SKILL-408
```
