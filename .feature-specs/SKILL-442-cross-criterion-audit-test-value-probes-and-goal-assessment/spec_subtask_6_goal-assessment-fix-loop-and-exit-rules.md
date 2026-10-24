# SKILL-442 subtask 6 - goal assessment fix loop and exit rules

## Scope

Parent criterion AC-005, part 2 of 3: goal finalization runs the subtask 5 gate before goal QA, and the stage loops assess, verify, fix, validate, commit, reassess until an exit rule fires. PR, status, and desktop surfaces belong to subtask 7. Telemetry belongs to subtask 8.

Shared decisions restated: the stage never blocks the goal. Fix failures, validation failures, and assessor or verifier failures record a `RuntimeDiagnosticsBestEffortWarning` and the loop continues by its rules. The three-round cap is the loop's own exit, not a block or a new budget. The record contract from subtask 5 (`goal-assessment-record-schema.yaml` 0.1) holds every round, fix, and exit fact.

## Acceptance Criteria

1. Goal finalization runs assessment before goal QA and drives the loop.
   `GoalRunnerFinalization.finalizePublication` calls `GoalRunnerAssessmentGate` after `publishedGoalResult` and immediately before `qaGate.run`, so the stage order is subtask commits, `goal_assessment`, goal QA, then `commit_push`, `pr`, and `monitor`. The loop driver lives in a new file in the assessment package, because `GoalRunnerFinalization.kt` is near the line ceiling. It runs for single-subtask and multi-subtask goals, against the feature branch head (the final stack branch for a stacked goal).
   It runs once per goal. A stored record with a non-null `exit_reason` skips the stage. The no-change finalization path records `exit_reason: skipped_no_changes` with a skip entry per `docs/observability-policy.md`. On resume, the driver continues from the first step the stored record lacks: the next assessor run, verification, fix, validation, commit, or the next round.
   Exit rules, checked after each round's verification: when a carried finding is confirmed with `fix_rounds_survived` reaching 2, exit `persistent_finding` without another fix. Otherwise, when the round has no confirmed Blocker or Major, run the fix only if `not_restored` regressions exist, then exit `converged`. Otherwise run the fix, validation, and commit, then exit `round_cap` if this was round 3 or start the next round. `assessment_unavailable` from the gate exits at once.
   At exit, `open_finding_refs` lists every finding whose `state` is `open`. Findings fixed in the last round, which no later verifier saw, stay `open`. Then the goal continues to goal QA. Audit confirms it by reading the call site, the once-per-goal check, the resume logic, and the exit rules.
2. The fix step fixes the round's fix set in one pass.
   A `goal_assessment_fix` strategy, registered in `PhaseStrategyLookup` and added to the `GOAL_ASSESSMENT` slot if subtask 5 left it out, runs at the implementation tier through `phaseRunEntry.runForGoal` on `SkeletonDefinition.GOAL_ASSESSMENT`. It edits the feature branch working tree.
   Its input lists every `disposition: fix` finding with ref, severity, `path:line`, failure scenario, and verification evidence, plus every `not_restored` regression with its evidence, and no pipeline verdicts. Its prompt, a few sentences, says to fix all of them in one pass, never to weaken a test or delete behavior to silence a finding, not to build, test, or validate, and to settle with one line per item: `<ref>: fixed: <files and how>` or `<ref>: not fixed: <reason>`.
   The runtime sets `fix.changed_files` from the working-tree diff. It records `fix.status` as `completed` when files changed, `no_change` when none did, and `failed` with a warning when the agent fails. Audit confirms it by reading the strategy, its input, and the status recording.
3. A fix with changes is validated and committed as one round commit.
   After a `completed` fix, the driver runs the full project validation through the same `SkeletonDefinition.VALIDATION` seam that `DefaultGoalRunnerQaGate.settleAfterQa` uses, extracted into a shared helper rather than duplicated. It records `fix.validation` as `passed` or `failed`; a failure is a warning, not a block.
   The runtime then commits every non-runtime-private change as one commit with subject `<issue_key>: goal assessment round <n>`, through the same `WorkflowGitOperations` path `commitAllRemainingWorktree` uses, and records `fix.commit_sha`. A `no_change` or `failed` fix makes no commit. Pushing stays with the existing `commit_push`.
   `docs/runtime-command-guidance.md` states the stage order and, in `Commit Structure`, that a goal may carry up to three `goal_assessment` round commits after its subtask commits. Audit confirms it by reading the validation call, the commit call, and the doc text.
4. Later rounds reassess only what the last fix touched.
   Round N above 1 sets `scope_files` to the previous round's `fix.changed_files`. A per-subtask assessor runs only for subtasks whose commit range or the previous fix changed a scope file, and its prompt limits it to those files. The cross-subtask assessor runs when a recorded scenario or regression touches a scope file, meaning its evidence cites the file or it has no citations.
   The verifier receives the round's new Blockers and Majors plus every carried finding still `open` with `verification: confirmed`. A carried finding confirmed again has `fix_rounds_survived` incremented. A carried finding refuted moves to `state: fixed`. New findings get the subtask 5 selection rules.
   When the previous fix made no commit, the scope is empty and the round only re-verifies carried findings. Audit confirms it by reading the scope computation, the assessor filter, and the carried-finding handling.

## Criterion Sizing

1. About 200 production lines, 3 files, 1 area (`runtime-engine` goalrunner execution and assessment package). Fits.
2. About 140 production lines, 4 files, 2 areas (`runtime-engine` slot strategy, `runtime-engine` goalrunner assessment package). Fits.
3. About 110 production lines, 4 files plus one doc, 2 areas (`runtime-engine` goalrunner qa and assessment packages, `docs`). Fits.
4. About 130 production lines, 2 files, 1 area (`runtime-engine` goalrunner assessment package). Fits.

## Interaction Scenarios

1. IS-001. Criteria: AC-001, AC-004.
   Entry point: goal finalization for a goal with one confirmed Major in round 1.
   Preconditions: the fixes in rounds 1 and 2 do not resolve it.
   Steps: round 1 fixes and commits; round 2 re-verifies it confirmed (`fix_rounds_survived` 1), fixes and commits; round 3 re-verifies it confirmed (`fix_rounds_survived` 2).
   Outcome: the loop exits `persistent_finding` without a third fix, the finding is in `open_finding_refs`, and goal QA starts.
2. IS-002. Criteria: AC-002, AC-003.
   Entry point: the round 1 fix.
   Preconditions: the fix agent changes two files; the follow-up validation fails.
   Steps: the runtime validates and commits.
   Outcome: one warning, `fix.validation: failed`, one commit `<issue_key>: goal assessment round 1` holding both files, and round 2 starts with those two files in scope.
3. IS-003. Criteria: AC-001, AC-002.
   Entry point: the same fix step.
   Preconditions: the fix agent changes nothing.
   Steps: the driver records the fix.
   Outcome: `fix.status: no_change`, no commit, round 2 re-verifies the carried findings with an empty scope.
4. IS-004. Criteria: AC-001, AC-003.
   Entry point: `skill-bill goal <issue_key>` resume.
   Preconditions: round 2's commit is recorded; the process stopped before round 3.
   Steps: the goal resumes.
   Outcome: rounds 1 and 2 are not rerun, round 3 starts with round 2's changed files in scope, and no duplicate round commit is made.
5. IS-005. Criteria: AC-001, AC-004.
   Entry point: goal finalization.
   Preconditions: round 1 has no confirmed Blocker or Major and one `not_restored` regression.
   Steps: the round ends.
   Outcome: the fix runs for the regression, is validated and committed, and the loop exits `converged` with the regression listed open.

## Non-Goals

- PR body, status projection, CLI text, desktop (subtask 7). Telemetry (subtask 8).
- Scaling the round cap by first-round findings (parent open question).
- Replacing per-subtask audit or review, or goal-level QA for packs without QA support.

## Dependency Notes

Depends on subtask 5 for the gate, record, skeleton, and phase ids.

## Test Obligations

- Regression test from the parent constraints: a finding confirmed after two fix rounds ends the loop with `persistent_finding` and is listed in `open_finding_refs`. Subtask 7 adds the PR body assertion. Bug caught: the loop spins to the cap or drops the open finding.
- Resume test: with round 2's commit recorded, re-entry starts round 3 and makes no duplicate commit. Bug caught: a crash mid-loop replays a fix and stacks duplicate commits.
- Update `PhaseGoalQaRunTest` or the goal finalization tests for the new stage order where they assert it.

## Validation Strategy

The validate phase runs the runtime-kotlin Gradle `check` with repoTest guards. Nothing runs here.

## Implementation Details

The assigned sub-spec is authoritative for decomposition and exit precedence. The preplan digest calls this work S5, but the authored bundle assigns it to subtask 6 and supplies subtask 5 as its prerequisite. This plan implements local criteria AC-001 through AC-004, which together serve parent AC-005. Subtasks 7 and 8 consume the resulting record for presentation and telemetry. They do not own the loop.

### Evidence and prerequisites

Planning used the supplied preplan digest and targeted source reads at checkout HEAD `5c27271b9d4f5af618a78e00220cf0606f2e3360`, including current working-tree content. No sibling or parent spec was read or changed.

- `runtime-kotlin/runtime-engine/src/main/kotlin/skillbill/engine/goalrunner/execution/core/GoalRunnerFinalization.kt:82` currently returns early for no-change or already-published goals before calling `qaGate.run`. Assessment therefore needs both an insertion before QA and a no-change skip-record hook before the no-change return. Preserve the already-published goal shortcut.
- `runtime-kotlin/runtime-engine/src/main/kotlin/skillbill/engine/goalrunner/qa/DefaultGoalRunnerQaGate.kt:104` owns `settleAfterQa`. Its changed-tree branch calls `phaseRunEntry.runForGoal` with `SkeletonDefinition.VALIDATION`, the parent workflow identity, and the selected branch. This is an existing production validation capability. Extract that invocation and typed result handling; leave QA-specific outcome and stale-tree warnings with QA.
- `GoalRunnerFinalization.kt:341` and `:386` currently combine remaining-work staging, commit, and push. Reuse the Git operations and branch checks, but do not call that combined helper for an assessment round because it pushes immediately and performs finalization-specific scratch cleanup.
- `runtime-kotlin/runtime-ports/src/main/kotlin/skillbill/ports/workflow/gitops/WorkflowGitCommitHistoryOperations.kt` exposes `createCommit`, `headCommitSha`, `commitIdentity`, and ancestry checks. `WorkflowGitOperations` also inherits scoped staging, repository fingerprints, and phase file manifests. These local production capabilities support round commits and recovery. A commit and the durable assessment record are separate writes; resume must reconcile their identities rather than treating them as atomic.
- Subtask 5 supplies `GoalRunnerAssessmentGate`, the assessment skeleton and phase identities, read-only assessors and verifier, finding selection, and the version 0.1 assessment record with round/fix/exit facts. Consume those local interfaces and their implementations together. Do not introduce a second record or infer assessment facts from audit, review, validation receipts, or acceptance state. No external service capability is required.

### Ordered implementation tasks

1. Establish one durable loop owner and its resume transitions. Serves AC-001 and AC-004, including IS-001, IS-003, IS-004, and IS-005.

   Add the driver under `runtime-engine/src/main/kotlin/skillbill/engine/goalrunner/assessment/`, using cohesive round-state helpers rather than enlarging `GoalRunnerFinalization`. Keep pure round transitions beside the subtask 5 domain aggregate and orchestration in the engine. Use the existing assessment record and outcome-store seam for completed assessor identities, verification, fix, validation, commit, round scope, and terminal exit. Persist completion before advancing to the next step. Missing work resumes at that step; a recorded exit returns without relaunching any assessor or fix.

   Implement the criterion's precedence once in the transition owner. After verification, a carried confirmed finding with two survived fix rounds exits `persistent_finding` before any third fix. Otherwise, no confirmed Blocker or Major means an optional regression-only fix followed by `converged`. Otherwise complete the round's fix, optional validation and commit before either `round_cap` at round 3 or another round. `assessment_unavailable` exits immediately. Carry all open findings to `open_finding_refs`; a fix agent's claim or commit alone cannot close one. Record non-blocking failures as completed failed attempts so resume does not retry them indefinitely. Cancellation remains cancellation and propagates through the existing lifecycle.

   Test obligations: the persistent-finding regression and durable resume case in task 6. Pure helper wiring needs no separate tests that duplicate those boundaries.

2. Register and connect the implementation-tier fix strategy. Serves AC-002 and supports AC-001, including IS-002 and IS-003. Depends on task 1 and the subtask 5 phase registrations.

   Add the `goal_assessment_fix` strategy and short resource prompt under the owning `runtime-engine/.../featuretask/slot/` assessment family. Register it in `PhaseStrategyLookup` and include the fix step in `SkeletonDefinition.GOAL_ASSESSMENT` if absent. Preserve subtask 5's reasoning and implementation tier assignments. Launch only the selected fix step through `phaseRunEntry.runForGoal`, using the parent workflow and the feature branch, never replaying assessor steps as part of that launch.

   Assemble input from findings with `disposition: fix` and `not_restored` regressions. Include their refs, severity, locations, concrete failure scenarios, and verifier evidence. Exclude pipeline verdicts. Keep the specified one-line settlement format and prohibit weakening assertions, deleting required behavior, or running builds/tests/validation in the fix agent. The agent edits the workflow checkout. Runtime Git observations, not its prose, determine changed files. Record `completed`, `no_change`, or `failed` with the criterion's meanings, retaining a diagnostic for failures and any partial changes. A failed fix gets no assessment round commit.

   Test obligations: exercise actual strategy input and settlement through the driver regression in task 6, asserting resulting record and repository state. Retain existing skeleton and execution-matrix coverage; add cases only for registrations this subtask changes.

3. Extract shared post-change validation and implement round publication. Serves AC-003 and AC-004, including IS-002 and IS-004. Depends on tasks 1 and 2.

   Extract the common `SkeletonDefinition.VALIDATION` launch from `DefaultGoalRunnerQaGate.settleAfterQa` into an injected helper shared by QA and assessment. Preserve parent identity, branch, model/add-on selection, event/activity handling, and full validation behavior. Do not transfer QA-specific outcome persistence to assessment. The assessment driver invokes this helper only for a completed fix with changes and records passed or failed. Agent failure, blocked validation, or unavailable validation produces `failed` plus `RuntimeDiagnosticsBestEffortWarning`, then follows the same non-blocking round progression. Standalone validation semantics and goal-subtask probe eligibility remain unchanged.

   Add a round commit helper in the assessment package using `WorkflowGitOperations.stagePaths` and `createCommit` on the selected branch. Stage every non-runtime-private change as required by AC-003, including validation repairs, and create exactly `<issue_key>: goal assessment round <n>`. Reuse the runtime-private path policy rather than copying finalization's feature-spec exclusion. Never amend a subtask commit or push here. Refresh the round's changed-file set after validation so the next scope includes every file the round commit changed, including added, removed, or renamed paths. A no-change or failed fix records no round commit.

   Persist pre-commit HEAD, intended round identity, and resulting tree identity through the assessment record before the Git side effect. On resume, reconcile a matching owned commit against that intent before attempting creation, then persist its SHA. A matching message alone is insufficient evidence of ownership. Commit refusal or ambiguous recovery emits a warning, preserves the open findings and partial facts, and does not invent a successful commit. A round without a recorded commit has empty next-round scope and still follows the fixed exit rules. Use record fields provided by subtask 5; if this consumer requires additional recovery fields, author their schema, owning keys/version, codec/coherence, and parity/compatibility changes together in this subtask before using them.

   Test obligations: one failed-validation boundary test and the resume test in task 6. Extend the existing QA test only as needed to prove extraction preserves its validation outcome.

4. Limit subsequent assessment and carry findings through verification. Serves AC-004 and AC-001, including IS-001 and IS-005. Depends on task 3.

   Add scope computation beside the assessment round owner. Use the prior committed fix's changed files for round N greater than 1. Select subtask assessors by intersection with their commit-range files or attributed previous-fix files, and limit each selected prompt to scope files. Retain all specs as permitted assessment context while keeping the active inspection scope explicit. Select cross-subtask scenarios and regressions whose recorded evidence names a scope file; treat missing citations conservatively as touching the scope and record the fallback. An empty scope launches no reassessor and only re-verifies carried findings.

   Send all new Blockers/Majors and every carried open confirmed finding to the independent verifier. Match carried identity by normalized repository path and concrete failure scenario, preserving its ref. Increment `fix_rounds_survived` only when a completed intervening fix round is followed by another confirmed verification. Persist that increment exactly once per round. Refutation marks the carried finding fixed; missing or malformed verification cannot clear it. Apply subtask 5's selection rules to new findings and preserve unrelated open findings for downstream reporting. Do not use missing local evidence as proof that a regression is restored.

   Test obligations: combine persistent-finding coverage with changed-scope assertions and add a boundary case in task 6 for an untouched subtask and a refuted carried finding.

5. Wire finalization and document stage/commit ownership. Serves AC-001 and AC-003. Depends on tasks 1 through 4.

   Inject the loop owner through the existing composition root. Update `GoalRunnerFinalization.finalizePublication` to run it after the published-goal early-return check and immediately before `qaGate.run`, using the final publication branch for stacked goals. For no-change completion, persist `skipped_no_changes` and the required skip record before returning. Keep assessment independent of pack QA availability and run it for one-subtask goals as well. After any terminal assessment exit, preserve existing QA, remaining-work commit/push, PR, and monitoring behavior.

   Update only the stage-order and `Commit Structure` passages of `docs/runtime-command-guidance.md` during implementation. Explain the assessment stage and up to three round commits after subtask commits without changing checkpoint ownership or adding push authority to assessment. Preserve unrelated working-tree edits in this already-modified document. Subtasks 7 and 8 own PR/status/desktop/telemetry consumers, so expose the existing durable record rather than building those consumers here.

   Test obligations: extend existing goal finalization coverage for assessment-before-QA, already-recorded exit, and the recorded no-change skip. Assert observable stage/record outcomes rather than incidental prompt text or helper call counts.

6. Add a small set of boundary regressions and hand off validation. Serves AC-001 through AC-004. Depends on the production tasks above.

   Place tests beside assessment production code in the engine, and pure transition tests beside their domain owner. Reuse fixtures from subtask 5 and existing `PhaseGoalQaRunTest`/goal finalization tests instead of creating a parallel runner. Before adding each case, retain the following named bugs as its purpose:

   - A Major still confirmed after two fix rounds triggers a third fix or disappears. Drive finalization through IS-001 and assert `persistent_finding`, two round commits, retained open finding, and subsequent QA. Cover third-round cap and regression-only convergence as distinct exit branches in the same round-driver test family.
   - Resume repeats a completed fix or commit. Reopen the durable store with round 2 committed, resume IS-004, and assert round 3 scope and unchanged earlier commit identities. Include the Git-committed/record-not-yet-acknowledged recovery boundary from task 3.
   - Validation failure discards changed files or prevents goal continuation. Drive IS-002 with two changed files and failed validation, then assert warning, failed validation record, one exact-subject commit containing the changes, and next-round scope. Cover no-change/failed-fix outcomes without manufacturing a commit.
   - Scoped reassessment reopens unrelated work or fails to close a refuted carried defect. Include an untouched subtask, changed-file evidence, and a carried finding in a resumed later round. Assert retained unrelated state, scoped assessment outcomes, and verifier-driven closure.

   Preserve governed schema parity and existing regression assertions. Any record extension requires positive/negative contract cases and recovery coverage, not source-text inspection. Later validation runs these focused tests and the assigned full runtime-kotlin check including repoTest guards. This planning phase runs no tests, compilation, build, full checks, or workflow commands. Source audit and review inspect the criterion-to-task traces above; validation owns execution evidence.

### Constraints and handoff

Implementation may repair production wiring, test setup, formatting, and lint needed for these outcomes. Keep dependencies inward under A1/A2, one transition owner and composition-root wiring under A3, and no forwarding abstractions under A5. Use owning wire keys and enum tokens under A6, typed boundary failures and recorded degradation under A7, and injected clocks/effects under A8. Preserve cancellation. Keep tests beside their owners under A10 and narrow visibility under A11. Respect file/package ceilings without increasing baselines or exemptions under G1 through G7. Add no Kotlin comments except permitted interface KDoc. Review applies the architecture-guidelines section 5 checklist.

The subtask 5 gate/record is an authorized local prerequisite, not a claim that it has already shipped. This plan adds its loop consumer and any necessary local recovery fields together. No external lookup or unsupported service capability blocks this scope. Exact helper names remain implementation choices. Assessment failures never become a new goal-blocking budget, and unverified final-round fixes stay open. Presentation, telemetry, history, commit/push execution, PR publication, and monitoring remain with their owning subtasks or runtime phases. This plan does not execute those actions.

## Next Path

```bash
skill-bill goal SKILL-442
```
