# SKILL-431 subtask 1 - goal-level QA run in finalization

Parent spec: `.feature-specs/SKILL-431-run-qa-once-per-goal-after-all-subtasks-and-before-the-pr/spec.md`

## Scope

Add the goal-level QA run and wire it into goal finalization. Children still compose QA after this subtask (subtask 2 removes it), so the in-flight skip rule (a) keeps goal QA dormant for goals whose children all ran QA. The commit stands alone: no path is removed before its replacement lands.

Areas (from the preplan digest):

- `runtime-domain/.../model/skeleton/SkeletonDefinition.kt`: new in-memory `GOAL_QA` skeleton (`goal-qa`, slots `[QA, QUALITY_GATE]`, steps `[qa, qa_fix, validate]`, `standaloneInvocable = false`, `runHarness = NONE`), listed in `entries`.
- `runtime-domain/.../phase/task/FeatureTaskRuntimePhaseWorkflowTransitions.kt`, `FeatureTaskRuntimePhaseWorkflowDefinition.kt`, `SkeletonDefinitionTransitions.kt`: a new backward edge from `validate` to `qa` with its own loop id (working name `qa_fix_revalidate`). It must compose only in `GOAL_QA`. Plain `composedOnlyWithSteps` cannot express "audit absent", so add a step-absence condition (working name `composedOnlyWithoutSteps`, keyed on `audit`) or an equivalent skeleton-scoped declaration.
- `runtime-engine/.../slot/qa/QaStrategy.kt` and `QaLoopRules.kt`: `withPreparedRun` stops assuming `admittedExecution`; when it is null it reads the QA selection supplied on the in-memory run request. QA outcome recording works for the goal run, keyed by the parent workflow id.
- `runtime-engine/.../featuretask/phaserun/PhaseRunEntry.kt` and its request/facts models: carry the QA selection and the parent spec acceptance criteria into `runInvariants` for the goal run. Generalize the monitor-specific branch-mismatch message.
- `runtime-engine/.../goalrunner/...`: new `GoalRunnerQaGate` (fun interface plus default implementation modeled on `DefaultGoalRunnerCiMonitor`, with the same goal-finalization progress events), injected into `GoalRunnerFinalization`, plus DI wiring in runtime-core.
- `GoalRunnerBranchPlan.toPullRequestRequest` and the outcome store reader: a goal-level QA line in the PR body.

## Acceptance Criteria

1. `SkeletonDefinition` declares an in-memory `goal-qa` skeleton composing `qa`, `qa_fix`, `validate`, and the transition model declares a `validate -> qa` backward edge with its own loop id that composes into `goal-qa` only, never into `STANDALONE` or any `GOAL_CHILD` revision; `validate` in `goal-qa` runs only after a `qa_fix` repair, and a passing or skipped first QA ends the run.
2. `GoalRunnerFinalization.finalizePublication` calls a `GoalRunnerQaGate` after the no-change and already-published checks and before `commitAllRemainingWorktree`, running `goal-qa` through `PhaseRunEntry.runForGoal` on the branch the final PR uses, with the parent spec acceptance criteria as the run's acceptance criteria and no subtask-count special case.
3. When the goal QA run returns Blocked (findings left after the third `qa_fix`, invalid receipt, failed validate, branch mismatch), finalization returns a BLOCKED stop with the run's reason and `lastResumableStep` before any commit-all, push, or PR call.
4. `QaStrategy` resolves the QA policy from the in-memory run request when `admittedExecution` is null, so `off` yields a runtime-authored `disabled_policy` skip and a pack without `qa_capability` yields an `unsupported_target` skip through the existing `QaExecutionLifecycle`, neither launching an agent.
5. The goal QA outcome is stored keyed by the parent workflow id and the PR body reports one goal-level QA line (plus legacy per-child lines that exist), and the gate skips without launching anything when every subtask workflow ran a QA-composed plan with a recorded outcome, or when the parent's recorded outcome is `passed` or `skipped` on the current tree identity.

## Non-Goals

- Removing QA from goal children or persisting the selection on the goal (subtask 2). Until subtask 2 lands, the gate takes the selection from the goal request, falling back to the first child's recorded plan selection, then AUTO.
- Status, desktop, and docs (subtask 3).
- `QaStandalonePhaseRun`, the DEVICE_QA harness, the QA receipt schema, device leasing, and `qa_capability` resolution.

## Dependency Notes

- First subtask, no dependencies.
- Assumptions for implement to confirm:
  - Implement's first lookup is `InMemoryPhaseRunAdapters.kt` / `PhaseRunModels.kt`, to learn how in-memory runs build `runInvariants.acceptanceCriteria`. If intake resolution cannot load the parent spec, add an explicit acceptance-criteria (or spec path) field to `PhaseRunRequest`.
  - The validate step in `goal-qa` resolves to the full project validation gate (`VALIDATE`, full depth), never `BUILD`.
  - The `validate -> qa` trigger is "validate passed after a `qa_fix` repair". Use a verdict or cross-slot rule like `QaLoopRules.crossSlotSettlement`. If the transition model cannot make `validate` reachable only from `qa_fix`, a passing first QA must still finish the run without running validate.
  - `QaExecutionLifecycle.withExecution(OFF)` returns Skipped before probing devices. If it probes, short-circuit OFF in `QaStrategy` without touching the lifecycle's device code.
  - `GoalRunnerPlanner` returns Done again on resume when every child is complete, so a blocked goal re-enters finalization and re-runs goal QA with a fresh in-memory repair budget. Losing in-memory repair progress on crash is accepted; worktree edits survive and get committed by commit-all.
  - The parent workflow has no task-runtime plan. The outcome-store reader for the parent must not answer `NotComposed`; add a dedicated method.
  - Goal QA also runs when an unmerged PR already exists (resume after a PR failure), subject to skip rule (b).

## Validation Strategy

Validate runs the full repository gate. This subtask's tests run there; implement and audit only read the tree.

## Test Obligations

- Composition: `validate -> qa` edge is present in `goal-qa` and absent from `STANDALONE` and every `GOAL_CHILD` revision (catches the edge leaking into feature runs, where validate would loop to qa forever). AC1.
- Goal finalization with findings on every QA attempt: the goal stops BLOCKED and the git/PR ports record no commit-all, push, or PR open (catches QA placed after commit_push). AC2, AC3.
- Goal finalization with a passing QA proceeds to commit-all and PR open, and the PR request carries the goal-level QA line. AC2, AC5.
- Goal QA with selection `off` settles a `disabled_policy` skip with no agent launch (catches the `checkNotNull(admittedExecution)` crash and an agent launch on off). AC4.
- Skip rule: a parent outcome `passed` on the current tree skips the gate on resume, and all-legacy-QA children skip it (catches double QA). AC5.

## Implementation Details

Settled decisions (from the preplan digest):

- `validate` in `goal-qa` is reachable only through `qa_fix`. Every `validate` pass inside `goal-qa` therefore follows a repair, so the `validate -> qa` edge fires on a plain validate pass. No cross-slot "after qa_fix" rule is needed.
- A passing or skipped first QA ends the run. `qa_fix` is already loop-only. `validate` is made loop-only only inside `goal-qa`, through a skeleton-scoped set, so the global loop-only set and feature runs stay unchanged.
- Selection source in this subtask: goal request `qaSelection`, else the first child's recorded plan `QA_SELECTION` (`outcomeStore.childQaSelection`), else `AUTO`. Subtask 2 replaces this with the persisted goal selection.
- Skip rule (a) needs at least one subtask workflow. Every subtask that has a workflow must have a QA-composed plan and a `Recorded` QA outcome. Rule (a) writes no parent outcome. The PR shows "QA ran per subtask (pre-SKILL-431 children)" plus the existing per-child lines.
- Skip rule (b) matches when the parent outcome is `passed` or `skipped` and its `testedTreeIdentity` equals `gitOperations.repositoryTreeIdentity` at gate time.
- The receipt schema, `QaStandalonePhaseRun`, the DEVICE_QA harness, device leasing, and `qa_capability` resolution are not touched.

### Task 1. `goal-qa` skeleton and transitions (AC1)

- `runtime-domain/.../model/skeleton/SkeletonDefinition.kt`: add `GOAL_QA = SkeletonDefinition(id = "goal-qa", slots = [PhaseSlot.QA, PhaseSlot.QUALITY_GATE], runStateKind = IN_MEMORY, stepIds = [qa, qa_fix, validate], standaloneInvocable = false, runHarness = PhaseRunHarness.NONE)`. Add it to `entries`. Add an optional skeleton-scoped `loopOnlyStepIds: Set<String> = emptySet()`. `GOAL_QA` sets it to `{validate}`. Traversal uses the union of this set and the global `loopOnlyPhaseIds`. `init` requires `loopOnlyStepIds` to be a subset of `stepIds`.
- `runtime-domain/.../phase/task/FeatureTaskRuntimePhaseWorkflowDefinition.kt`: add the loop ids `QA_FIX_REVALIDATE_LOOP_ID = "qa_fix_revalidate"` (for `qa_fix -> validate`) and `QA_REQUALIFY_LOOP_ID = "qa_requalify"` (for `validate -> qa`).
- `runtime-domain/.../phase/task/FeatureTaskRuntimePhaseWorkflowTransitions.kt`, in `qaFixEdges()`:
  - Add `qa_fix --ADVANCE--> validate` (loop `qa_fix_revalidate`, `crossSlotAllowance = DECLARED`, like the existing `qa_fix -> audit` edge).
  - Add `validate --ADVANCE--> qa` (loop `qa_requalify`, perEdgeCap 3, BLOCK on exhaustion, PER_SUBTASK scope like `qa_fix`). The per-edge cap only guards against runaway loops. The existing `qa -> qa_fix` cap of 3 sets the repair budget.
  - Add a step-absence condition map `composedOnlyWithoutSteps` (loop id to step ids that must be absent). Key both new loops on `{audit}`.
- `SkeletonDefinitionTransitions.kt` `deriveTransitions()`: drop an edge when any step in its `composedOnlyWithoutSteps` entry is present in the composed steps. Then `STANDALONE`, `GOAL_CHILD` rev 3, and `GOAL_CHILD_REVISION_2` (all contain `audit`) never compose the new loops. The existing `qa_fix -> audit` and `qa_invalidation` edges fall out of `goal-qa` because `audit` and `write_history` are absent. The QA entry gate on AUDIT is filtered the same way.
- Assumption for implement to confirm: the transition model accepts a declared `qa_fix -> validate` jump. It is forward in index order, and the existing edges are backward. If the model rejects forward edges, drop the skeleton-scoped loop-only `validate`. In its place, settle `qa`'s pass or skip verdict in `goal-qa` as run completion through a `QaLoopRules` verdict rule. Then forward order `qa_fix -> validate` carries repairs, and a passing first QA still never reaches validate.
- Assumption for implement to confirm: in `goal-qa`, validate's failure route targets steps that are absent there, so the failure gets filtered and validate failure returns Blocked. If a failure edge survives, scope it out with the same absence condition.
- Confirm that the strategy-selection facts for `goal-qa` resolve `validate` to the full project gate (`VALIDATE`, full depth), never `BUILD`.
- Tests:
  - `runtime-domain/src/test/.../SkeletonDefinitionTest.kt`: one test. `GOAL_QA` derived transitions contain `qa_requalify` and `qa_fix_revalidate`. `STANDALONE` and every `GOAL_CHILD` revision in `entries` contain neither. This catches validate looping to qa forever in feature runs.
  - Update existing `entries` or `byId` assertions in `SkeletonDefinitionTest` and `FeatureTaskRuntimePhaseWorkflowDefinitionTest` only when the new entry breaks them.

### Task 2. QA policy and outcome for in-memory goal runs (AC4, AC5)

- First lookup: `InMemoryPhaseRunAdapters.kt` and `PhaseRunModels.kt`, to learn how `InMemoryPhaseRunFacts` builds `runInvariants.acceptanceCriteria` from intake.
- `PhaseRunRequest`: add `qaSelection: FeatureTaskRuntimeQaSelection? = null` and `acceptanceCriteria: List<String>? = null`. Thread both into `InMemoryPhaseRunFacts`. When non-null, `acceptanceCriteria` overrides the intake-derived `runInvariants.acceptanceCriteria`. If intake resolution can already load the parent spec by path, pass the path as intake and skip the override field. Record the choice in the code comment that sits next to the field.
- `runtime-engine/.../slot/qa/QaStrategy.kt` `withPreparedRun`: replace `checkNotNull(run.request.admittedExecution?.plan?.qaSelection)` with `admittedExecution?.plan?.qaSelection ?: run.request.qaSelection`. When both are null, fail with a clear error, never an NPE. Map AUTO and OFF to `QaPolicy` as today.
- Confirm that `QaExecutionLifecycle.withExecution(OFF)` returns `QaExecutionTarget.Skipped(disabled_policy)` before probing devices. If it probes, short-circuit OFF in `QaStrategy` to the existing `receiptValidator.skipped(...)` path without touching the lifecycle's device code. A pack without `qa_capability` keeps yielding `unsupported_target` through the lifecycle unchanged.
- Outcome recording: when `admittedExecution == null` and `run.request.qaSelection != null` (the goal run), `QaStrategy` upserts the settled `FeatureTaskQaOutcome` for the run's workflow id (the parent workflow id, via `runForGoal`) at each QA receipt settlement. The last attempt wins. The record holds the outcome mapped from the receipt verdict, `qaFixAttempts` from `QaRepairState.consumedAttempts`, and `testedTreeIdentity` from the receipt. For runtime-authored skips with no tested tree, use the current repository tree identity. If `FeatureTaskQaOutcomeRecorder` has only `recordAttempted`, add one `recordSettled` method that reuses the `FeatureTaskQaOutcomeRecords.settled` mapping. Standalone `phase:qa` passes no `qaSelection` on the request, so it keeps not recording.
- `PhaseRunEntry.runInternal`: change the branch-mismatch message from monitor wording to "Goal finalization requires branch '<expected>' ...". Update `PhaseMonitorRunTest` only if it asserts the old text.
- Tests:
  - New `runtime-engine/src/test/.../featuretask/phaserun/PhaseGoalQaRunTest.kt`, modeled on `PhaseMonitorRunTest`. `runForGoal(goal-qa, qaSelection = OFF)` returns a completed run whose QA receipt is a runtime-authored `disabled_policy` skip, and the fake agent launcher records no launch. This catches the `checkNotNull` crash and an agent launch on off.
  - No separate `unsupported_target` test. That path is existing lifecycle behavior with existing coverage.

### Task 3. Goal QA gate and finalization wiring (AC2, AC3, AC5)

- New `runtime-engine/.../goalrunner/qa/GoalRunnerQaGate.kt`:
  - `fun interface GoalRunnerQaGate { fun run(state, request): GoalQaGateResult }`, where `GoalQaGateResult` is `Passed | Skipped(reason: SkippedPerSubtask | AlreadySettledOnTree) | Blocked(reason, stepId)`.
  - `DefaultGoalRunnerQaGate(phaseRunEntry, outcomeStore, gitOperations, clock)` is modeled on `DefaultGoalRunnerCiMonitor`. It applies skip rule (a), then rule (b). Otherwise it calls `phaseRunEntry.runForGoal(PhaseRunRequest(definitionId = SkeletonDefinition.GOAL_QA.id, repoRoot, invokedAgentId = configuredAgentOverrideId ?: invokedAgentId, intake = issueKey, timeout, agentAddonSelection, modelAssignment, qaSelection = effective selection, acceptanceCriteria = parent spec ACs, eventSink, activityOutput), parentWorkflowId, expectedBranch = the final PR head branch)`.
  - The final PR head branch is the same one `GoalRunnerBranchPlan.toPullRequestRequest` uses (`branchForFinalPullRequest()` or `featureBranch`).
  - Parent spec ACs come from `manifest.parentSpecPath` / `resolvedParentSpecPath`, parsed with the same AC parser intake uses.
  - Progress goes through `outcomeStore.recordProgressEvent` with `operationKind = GOAL_FINALIZATION_OPERATION_KIND`, the same as the CI monitor. Extracting a shared progress helper from `DefaultGoalRunnerCiMonitor` is optional. Do it only if it removes duplication without changing monitor events.
  - There is no subtask-count special case.
- `runtime-engine/.../goalrunner/execution/core/GoalRunnerFinalization.kt`: inject `GoalRunnerQaGate`. In `finalizePublication`, after `noChangeFinalization` and `publishedGoalResult` and before `commitAllRemainingWorktree`, call the gate.
  - On `Blocked`, return `stopped(StoppedReportArgs(reason = GoalRunnerStopReason.BLOCKED, blockedReason = result.reason, workflowId = parentWorkflowId, lastResumableStep = result.stepId))`.
  - On `Passed` or `Skipped`, continue.
  - The gate also runs when an unmerged PR already exists. Rule (b) prevents a re-run on the same tree.
- Confirm that `GoalRunnerPlanner` returns `Done` on resume when every child is complete, so a blocked goal re-enters finalization with a fresh in-memory repair budget. If the plan step goes missing on a crash, nothing needs to happen. Worktree edits from `qa_fix` stay uncommitted until `commitAllRemainingWorktree`.
- DI: provide `DefaultGoalRunnerQaGate` in runtime-core next to the `GoalRunnerCiMonitor` binding. Update `RuntimeFeatureTaskSlotProvidesTest` (or the module test that covers the CI monitor binding) only if it enumerates bindings. Add a no-op `GoalRunnerQaGate` default (returns `Skipped`) in the runtime-engine testFixtures. Existing `GoalRunnerFinalization` constructions in tests pick it up without behavior change.
- Tests:
  - `goalrunner/GoalRunnerTest.kt`, first test: a fake gate returns `Blocked`. The goal stops BLOCKED with that reason and step, and the fake git and PR ports record no commit-all, push, or PR open. This catches QA placed after commit_push.
  - Second test: a fake gate returns `Passed` and the parent outcome is recorded `passed`. The run reaches commit-all and PR open, and the PR request body contains the goal-level QA line.
  - New `goalrunner/qa/DefaultGoalRunnerQaGateTest.kt`, first test (rule b): the parent outcome is `passed` on the current tree identity, so the gate returns `Skipped(AlreadySettledOnTree)` and the counting `PhaseRunEntry` fake sees no run.
  - Second test (rule a): every child has a QA-composed plan with a `Recorded` outcome, so the gate returns `Skipped(SkippedPerSubtask)` and no run happens. Together these catch double QA.

### Task 4. Outcome reader and PR body line (AC5)

- `GoalRunnerWorkflowOutcomeStore`: add `recordedGoalQaOutcome(parentWorkflowId): FeatureTaskQaOutcome?`, implemented in `WorkflowGoalRunnerOutcomeStore` as a direct `SqliteFeatureTaskQaOutcomeStore` lookup by workflow id. It never consults a task-runtime plan, so it never answers `NotComposed`. Add the default in `GoalRunnerManifestStoreDefaults.kt` or the outcome-store test fixture, whichever holds the store fakes.
- `runtime-engine/.../goalrunner/execution/support/GoalRunnerBranchPlan.kt` `toPullRequestRequest`: add a `goalQaSummary: GoalQaSummary` parameter. `GoalQaSummary` is `Recorded(outcome) | RanPerSubtask | NotRecorded` and lives in `engine/goalrunner/model`. Render one goal-level "Mobile QA" line ahead of the per-subtask lines, reusing the `pullRequestText()` wording for outcomes. Per-child lines keep their current rendering. `GoalRunnerFinalization` builds the summary from `recordedGoalQaOutcome(parentWorkflowId)`, or `RanPerSubtask` when the gate skipped by rule (a).
- Keep the sentence "Every subtask completed the configured review, audit, validation, and history gates" unchanged.
- Tests: covered by the second `GoalRunnerTest` case in Task 3. Update existing PR-body assertions only where the new line breaks them.

### Constraints

- Do not touch the unrelated uncommitted edits (`ValidationGateResolver.kt`, `ValidationGateRoutingTest.kt`, the staged `QaCapabilityResolverTest.kt`), and do not fold them into this work.
- Children keep composing QA in this subtask. `GOAL_CHILD` rev 3, `STANDALONE`, child selection plumbing, status, desktop, and docs stay unchanged.
- No receipt schema, device-leasing, `qa_capability`, `QaStandalonePhaseRun`, or `operation:verify` changes.
- Implement and audit do not run builds or tests. The validate phase runs the full repository gate, including the new tests.

## Next Path

```bash
skill-bill goal SKILL-431
```
