# SKILL-431 subtask 2 - goal children without QA and goal-level selection

Parent spec: `.feature-specs/SKILL-431-run-qa-once-per-goal-after-all-subtasks-and-before-the-pr/spec.md`

## Scope

Remove QA from newly started goal children and move the persisted QA selection to the parent goal. After this subtask, mixed and new goals fail skip rule (a) from subtask 1 and run goal-level QA once.

Areas (from the preplan digest):

- `SkeletonDefinition.kt`: `GOAL_CHILD` semantic revision 4 = feature-run slots minus QA, PULL_REQUEST, MONITOR. Revision 3 stays in `entries` under its own constant (for example `GOAL_CHILD_REVISION_3`); `byId("goal-child")` resolves revision 4.
- Child plan plumbing with null QA selection for revision-4 children: `GoalRunnerSubtaskLaunchPrepare.prepareChild` / `childExecutionPlan`, `GoalRunnerRunPreparation.existingChildExecutionPlanAdmission`, `GoalRunnerLaunchReconciler` (continuation `qaSelection`), `GoalRunnerRunModels.childQaSelection`, `GoalRunnerChildWorkflowModels` (`qaSelection` becomes nullable) and `WorkflowGoalRunnerChildWorkflowPersistence`, `FeatureTaskRuntimeRunEntry` (the AUTO default on creation and the changed-selection refusal), `FeatureTaskRuntimeGoalContinuationPolicy.suppliedGoalContinuationConflict`, `FeatureTaskRuntimeExecutionPlanCompatibility.requireSupportedSelections`, and `PhaseStrategyLookup` selection matching.
- Goal-level persistence: a QA selection field on the parent's persisted goal policy (`GoalRunnerControlState` or a sibling), store methods on `GoalRunnerManifestStore` / `WorkflowGoalRunnerManifestStore` / the test-fixture defaults, and `GoalRunnerRunPreparation.prepareRun` modeled on the stop-after-policy mismatch path.

## Acceptance Criteria

1. `SkeletonDefinition` exposes `GOAL_CHILD` semantic revision 4 without the QA slot as the revision `byId` and `admittedForRun` select for new children, and keeps revision 3 with QA in `entries` so a recorded child resolves its recorded revision and step list unchanged.
2. Newly started goal children are planned, persisted, and continued with no QA selection across launch preparation, the launch reconciler, child workflow setup, run entry, the continuation conflict check, and the plan compatibility check, while a revision-3 child keeps receiving its recorded QA selection.
3. The parent goal persists its effective QA selection on first run (request value, else AUTO), an in-flight goal without one inherits the first child's recorded plan selection, `prepareRun` threads that value into the goal request used by the goal QA gate, and a resume requesting a different selection returns a QA-specific `PreparationBlocked` stop.

## Non-Goals

- Changing `STANDALONE` revision 3 or non-goal feature runs.
- CLI parsing (`GoalCliCommands`, `QaSelectionParsing`) and the `SKILL_BILL_QA_SELECTION` token stay unchanged; their values keep their meaning.
- Status, desktop, docs (subtask 3).

## Dependency Notes

- Depends on subtask 1: the goal QA gate must exist before children stop running QA, or new goals would ship without QA.
- Assumptions for implement to confirm:
  - `FeatureTaskRuntimeExecutionPlanCompatibility` keys its QA check on the recorded definition's slots, so a revision-4 plan with null selection passes once the definition lookup finds revision 4. Relax the check only if it does not.
  - `PhaseStrategyLookup.matchesRecordedSelection` / `singleQaSelection` accept facts without a QA selection. Adjust them if they require one.
  - Tests asserting `GOAL_CHILD` contains QA move to the revision-3 constant rather than being deleted.
  - The parent field is optional in storage; absence triggers the inheritance fallback, so no migration is needed.

## Validation Strategy

Validate runs the full repository gate. Implement and audit only read the tree.

## Test Obligations

- A new goal child's execution plan has no `qa`/`qa_fix` step and no QA selection, and a recorded revision-3 child resumes with its QA steps and selection (catches switching a recorded child's step list, or new children refused as a selection conflict). AC1, AC2.
- Goal resume with a selection different from the parent's recorded one is refused with the QA-specific message. AC3.
- A goal with no parent selection and a revision-3 child recorded with `off` resolves `off` for goal QA (catches in-flight goals silently flipping to AUTO). AC3.

## Implementation Details

Ordered tasks. Every path is under `runtime-kotlin/` unless it says otherwise. Facts come from the preplan digest. Lines marked "Assumption" are decisions made without reading the file; implement confirms them and adjusts locally if the code disagrees.

### Task 1. Goal-child revision 4 in the skeleton catalog (AC1)

- `runtime-domain/src/main/kotlin/skillbill/workflow/taskruntime/model/skeleton/SkeletonDefinition.kt`:
  - Rename the current rev-3 `GOAL_CHILD` constant (with QA, without PULL_REQUEST and MONITOR) to `GOAL_CHILD_REVISION_3`. Keep its id `"goal-child"`, slots, step ids and `semanticRevision = 3` byte-for-byte.
  - Add a new `GOAL_CHILD`: id `"goal-child"`, `semanticRevision = 4`, slots = `LEGACY_FEATURE_RUN_SLOTS_REVISION_2` minus `PULL_REQUEST` and `MONITOR`. These are the same slots as `GOAL_CHILD_REVISION_2`, so the composed steps are intake/preplan/plan/implement/audit, review, build/validate, write_history, commit_push, with no `qa`/`qa_fix`. Copy every other constructor argument from rev 3.
  - Add `GOAL_CHILD_REVISION_3` to `entries` next to `GOAL_CHILD_REVISION_2`, so `entries` holds goal-child revisions 2, 3 and 4. `byId("goal-child")` already picks the max revision and resolves 4 with no code change. `admittedForRun(goalContinuation)` keeps returning `setOf(GOAL_CHILD)`, which is now rev 4.
  - Assumption: admission for a recorded child compares definition ids, not the `SkeletonDefinition` object. Rev-2 children are still resumable today while `admittedForRun` returns only the newest constant, which supports this. If admission turns out to compare objects, admit recorded revisions by `(id, recorded semanticRevision)` through the existing compatibility lookup. Never widen `admittedForRun` in a way that lets a new child pick rev 3.
- Transitions need no edit. `SkeletonDefinitionTransitions.deriveTransitions()` drops the `qa`→`qa_fix`, `qa_fix`→`audit` and `write_history --QA_INVALIDATED--> audit` edges by itself when `qa`/`qa_fix` are absent. `FeatureTaskRuntimePhaseWorkflowGraph.stepIds` comes from `FEATURE_RUN_SLOTS` and does not change.
- Tests: in `runtime-domain/src/test/.../phase/task/SkeletonDefinitionTest.kt`, move every assertion that `GOAL_CHILD` contains QA onto `GOAL_CHILD_REVISION_3`; don't delete them. Add one assertion that `byId("goal-child")` returns revision 4 and its step ids contain neither `qa` nor `qa_fix`. Repoint the same kind of QA-on-goal-child assertions in `runtime-engine/src/test/.../featuretask/slot/PhaseStrategyCompositionTest.kt` and `FeatureTaskRuntimeExecutionPlanResolverTest.kt` to the rev-3 constant.

### Task 2. Plan composition and compatibility accept a null selection (AC1, AC2)

- `runtime-engine/.../featuretask/lifecycle/execution/FeatureTaskRuntimeExecutionPlanResolver.kt` `resolveCreation`: new plans use `request.qaSelection ?: AUTO` today. Change it so AUTO is only defaulted in when the target definition composes `PhaseSlot.QA`. Otherwise the selection stays null and stays out of the `PhaseStrategySelectionFacts` set. The recorded path (`workflowId != null` → `compatibility.requireSupportedComposition(recorded)`) stays as it is. That path is what keeps a rev-3 child on its recorded definition and selection.
- `FeatureTaskRuntimeExecutionPlanCompatibility.requireSupportedSelections` (around line 122): the check `(PhaseSlot.QA in definition.slots) != (plan.qaSelection != null)` already uses the recorded definition found by `(id, semanticRevision)`. A rev-4 plan with null selection passes, and a rev-3 plan with a selection passes. Assumption: no edit is needed beyond confirming this. Relax the check only if a test shows otherwise.
- `PhaseStrategyLookup.kt` (`matchesRecordedSelection`, `singleQaSelection`, around lines 100-130, 220, 332): confirm that when the definition has no QA slot, an absent QA fact is accepted and `singleQaSelection` returns null instead of throwing. If either one requires a QA fact unconditionally, gate the requirement on `PhaseSlot.QA in definition.slots`. Leave behavior for QA-composing definitions (STANDALONE, GOAL_CHILD rev 3, the goal-QA skeleton from subtask 1) unchanged.

### Task 3. Goal-runner child plumbing hands new children no selection (AC2)

- `runtime-engine/.../goalrunner/execution/.../GoalRunnerRunModels.kt`: `GoalRunnerRunRequest.qaSelection` stays the goal-level value, set by Task 5. Remove the `childQaSelection` getter (`qaSelection ?: AUTO`) and its call sites. A goal-level selection must never flow into a new child's plan.
- `runtime-engine/.../goalrunner/launch/GoalRunnerSubtaskLaunchPrepare.kt`:
  - `prepareChild` (around line 210): a newly created child gets `qaSelection = null`, which goes both to `childExecutionPlan(...)` and to `GoalRunnerChildWorkflowSetup.qaSelection`.
  - `childExecutionPlan(...)` (around lines 312-345): keep `definition = SkeletonDefinition.GOAL_CHILD`, which is now rev 4. Drop the selection argument, or make it nullable and always pass null, so the facts are `setOfNotNull(reviewMode, assignedQualityGate)`.
- `GoalRunnerChildWorkflowModels.kt:29`: change `qaSelection: FeatureTaskRuntimeQaSelection = AUTO` to `FeatureTaskRuntimeQaSelection? = null`. `WorkflowGoalRunnerChildWorkflowPersistence.kt:330` writes the continuation artifact. `FeatureTaskRuntimeGoalContinuationArtifact.qaSelection` is already optional and is written only when non-null, so the writer passes the nullable value through.
- `GoalRunnerLaunchReconciler.kt` (around line 179): continuation `qaSelection` for a new child (no workflow yet) becomes `null` instead of `request.qaSelection`. An existing child keeps `outcomeStore.childQaSelection(workflowId, ...)`, but the fallback argument becomes `null` instead of `request.qaSelection`. A rev-3 child gets its recorded value, and a rev-4 or pre-QA child gets null. Assumption: the second parameter of `childQaSelection` is a fallback used when nothing is recorded. If it is not a fallback, pass whatever produces "recorded value or null".
- `GoalRunnerRunPreparation.existingChildExecutionPlanAdmission`: it already calls `resolveCreation` with a workflow id and no selection. Confirm it resolves the recorded plan and passes nothing new.

### Task 4. Run entry and continuation conflict accept null for goal children (AC2)

- `runtime-engine/.../featuretask/.../FeatureTaskRuntimeRunEntry.kt`, lines 101-103 and 272-300: when `goalContinuation != null`, the requested selection is `goalContinuation.qaSelection` alone and is nullable. Do not default it to AUTO on creation (line 272). The changed-selection refusal (`changedQaSelectionRefusal`) compares nullable values against `admittedExecution.plan.qaSelection`, so rev-4 null vs null passes and rev-3 recorded vs recorded passes. Non-goal runs keep `input.qaSelection ?: AUTO` and the STANDALONE rev-3 behavior byte-for-byte. Assumption: goal children never get `input.qaSelection` from the goal runner. If they do, the goal runner stops passing it for children.
- `FeatureTaskRuntimeGoalContinuationPolicy.suppliedGoalContinuationConflict`: keep `supplied.qaSelection != durable.qaSelection`. Tasks 3 and 4 make sure both sides are null for new children and the recorded value for rev-3 children. Confirm that no AUTO normalization happens on either side. If one does, remove it for the goal route only.

### Task 5. Goal-level QA selection persistence and resume refusal (AC3)

- `runtime-domain/src/main/kotlin/skillbill/goalrunner/model/GoalRunnerControlModels.kt:81`: add `qaSelection: FeatureTaskRuntimeQaSelection? = null` to `GoalRunnerControlState`. It is optional in storage, and absence means "not recorded yet", so no migration. Assumption: `FeatureTaskRuntimeQaSelection` can be seen from runtime-domain. If it can't, store the wire string and map it in the engine.
- `runtime-engine/.../goalrunner/manifest/GoalRunnerManifestStore.kt` (next to `persistStopAfterSubtask`, line 148): add `persistQaSelection(parentWorkflowId, selection): GoalRunnerControlState`. Implement it in `WorkflowGoalRunnerManifestStore.kt` the same way stop-after is persisted. Give it a default in `runtime-engine/src/testFixtures/.../goalrunner/manifest/GoalRunnerManifestStoreDefaults.kt` so existing fakes keep compiling.
- `runtime-engine/.../goalrunner/execution/core/GoalRunnerRunPreparation.kt` `prepareRun`, placed right after the stop-after mismatch / `persistEffectiveStopAfterPolicy` step:
  1. `recorded = state.control.qaSelection`.
  2. If `recorded == null`, inherit. Walk the manifest subtasks in order and take the first that has a child workflow whose `outcomeStore.childQaSelection(workflowId, null)` is non-null. That covers in-flight goals whose rev-3 children carry the selection.
  3. Mismatch: if `request.qaSelection != null` and a recorded or inherited value exists and differs from it, return `GoalRunPreparation.PreparationBlocked(stopped(StoppedReportArgs(reason = GoalRunnerStopReason.BLOCKED, blockedReason = "Cannot change QA selection on goal resume: parent workflow '<id>' is pinned to qa:<recorded>.", workflowId = parentWorkflowId, lastResumableStep = <same value the stop-after path uses>)))`.
  4. Effective value = recorded ?: inherited ?: request.qaSelection ?: AUTO. When `recorded` was null, persist it through `persistQaSelection`. Then thread it into the request with `request.copy(qaSelection = effective)`, the same way `codeReviewMode` is threaded. The goal QA gate from subtask 1 reads this value. Task 3 makes sure children never read it.
- CLI sources (`GoalCliCommands.kt`, `QaSelectionParsing.kt`) and `SKILL_BILL_QA_SELECTION` stay unchanged.

### Test obligations to implement (and existing tests to update)

- New-child vs recorded-child plan (AC1, AC2). Put it in `runtime-engine/src/test/.../goalrunner/GoalRunnerTest.kt`, or in `featuretask/lifecycle/execution/FeatureTaskExecutionPlanCreationTest.kt` if that file has the goal-child fixture. A newly launched child's plan has no `qa`/`qa_fix` step and a null selection. A child recorded on rev 3 with selection `off` resumes with its QA steps and `off`, and is not refused as a selection conflict. One test covers both sides.
- Resume refusal (AC3), in `GoalRunnerTest.kt`: parent recorded `auto`, resume with `qa:off` → `PreparationBlocked` with the QA-specific message.
- In-flight inheritance (AC3), in `GoalRunnerTest.kt`: no parent selection, a rev-3 child recorded `off`, resume without a requested selection → the effective goal request carries `off` and the parent now persists `off`.
- Expected churn, updated rather than deleted: tests asserting that new goal children get AUTO or compose QA (`FeatureTaskContinuationAdmissionTest.kt`, `FeatureTaskRuntimeGoalContinuationAdoptionPersistenceTest.kt`, `goalrunner/persist/WorkflowGoalRunnerOutcomeStoreTaskRuntimeTest.kt`, `runtime-cli/.../featuretask/FeatureTaskRuntimeGoalContinuationProtocolTest.kt`) move their QA expectations to rev-3 fixtures or expect null for rev-4 children. Store fakes pick up the new default from `GoalRunnerManifestStoreDefaults.kt`.
- No other new tests. Renaming the constant and threading a nullable value are glue, and the three tests above cover them.

### Constraints

- Leave `STANDALONE` rev 3, non-goal feature runs, `operation:verify`, standalone `phase:qa`, the QA receipt schema, device leasing, and `qa_capability` resolution unchanged.
- Leave the unrelated uncommitted edits alone (`ValidationGateResolver.kt`, `ValidationGateRoutingTest.kt`, the staged `QaCapabilityResolverTest.kt`). Do not revert them or fold them in.
- Status, desktop and docs belong to subtask 3. The goal QA gate, the GOAL_QA skeleton and the skip rules belong to subtask 1, which this subtask depends on.
- No compile, test or check runs in implement. The validate phase runs the full repository gate.

## Next Path

```bash
skill-bill goal SKILL-431
```
