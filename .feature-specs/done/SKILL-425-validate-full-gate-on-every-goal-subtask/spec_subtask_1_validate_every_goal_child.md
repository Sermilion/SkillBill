# SKILL-425 subtask 1 - validate every goal child

## Scope

One coherent change to goal-child quality-gate selection:

- `../../../runtime-kotlin/runtime-domain/src/main/kotlin/skillbill/goalrunner/GoalRunnerPolicy.kt`: `GoalRunnerQualityGateSelectionResolver.resolve(manifest, subtaskId)` returns `FeatureTaskRuntimeQualityGateSelection.VALIDATE` for every call. Drop the `lastOrNull { not SKIPPED }` promotion. `GoalRunnerPlanner` already never launches skipped children, so no skipped-id branch is needed. Leave `GoalRunnerPlanner`, `GoalRunnerWorkerSubtaskScheduler`, and `GoalRunnerOutcomeReconciler` alone.
- Resume fence at the three sites that re-call `resolve()` when a child row exists:
  - `../../../runtime-kotlin/runtime-engine/src/main/kotlin/skillbill/engine/goalrunner/launch/GoalRunnerSubtaskLaunchPrepare.kt`, `prepareAttemptedLaunch` / `childExecutionPlan`: first open (`priorWorkflowId == null`) uses `resolve()`; resume passes the existing child's recorded continuation or stored plan selection as the `qualityGate` given to `resolveCreation`.
  - `../../../runtime-kotlin/runtime-engine/src/main/kotlin/skillbill/engine/goalrunner/execution/core/GoalRunnerRunPreparation.kt`, `existingChildExecutionPlanAdmission`: pass the recorded selection into `FeatureTaskRuntimeExecutionPlanCreationRequest.qualityGate`.
  - `../../../runtime-kotlin/runtime-engine/src/main/kotlin/skillbill/engine/goalrunner/launch/GoalRunnerLaunchReconciler.kt`, `goalContinuationContext`: copy the recorded continuation selection into `SkillRunGoalContinuationContext.qualityGateSelection`.
  - Rule at every site: a recorded `quality_gate_selection` wins; absent means `resolve()`.
- `../../../docs/runtime-command-guidance.md`, Runtime Agent Behavior: rewrite the paragraph "Default standalone runs skip build (`review -> validate`); only goal children stamped for build use `review -> build -> write_history`" to say goal routing stamps validate on every non-skipped child (`review -> validate -> write_history`). Keep the BUILD session description for an explicit BUILD selection (pack `validation_gate.build_command` only, Kotlin `./gradlew compileKotlin`, no `collect_all_full_gate_command`, no `./gradlew check`, no `skill-bill validate`, no `skill-bill phase validation`). Keep the rule that implement and implement_fix must not run check.

Assumption for implement to confirm: the recorded selection is reachable at each site from the existing child's durable continuation artifact (`FeatureTaskRuntimeGoalContinuationArtifact.qualityGateSelection`) or its stored execution plan. If one site cannot reach it, thread the existing child record into that site rather than adding a new lookup store. Preplan treats BUILD-vs-VALIDATE descriptors as incompatible in `FeatureTaskRuntimeExecutionAdmission.requireCompatibleDescriptor`; implement confirms this when wiring the fence.

## Acceptance Criteria

1. `GoalRunnerQualityGateSelectionResolver.resolve` returns `FeatureTaskRuntimeQualityGateSelection.VALIDATE` for every subtask id and contains no last-non-skipped promotion logic.
2. `GoalRunnerSubtaskLaunchPrepare` uses `resolve()` only on first open and passes the existing child's recorded quality-gate selection into execution-plan creation on resume.
3. `GoalRunnerRunPreparation.existingChildExecutionPlanAdmission` passes the child's recorded quality-gate selection, not a fresh `resolve()` result, into `FeatureTaskRuntimeExecutionPlanCreationRequest.qualityGate`.
4. `GoalRunnerLaunchReconciler.goalContinuationContext` sets `SkillRunGoalContinuationContext.qualityGateSelection` from the recorded continuation selection when one exists and from `resolve()` otherwise.
5. `WorkflowGoalRunnerChildWorkflowPersistence.openGoalChildWorkflow` still writes the resolver's selection only for a new child, so new children persist `validate`; no code patches existing child rows.
6. `SkeletonStrategyBindings` GOAL_CHILD `QUALITY_GATE` `ByFact` mapping (BUILD to `PackBuildStrategy`, VALIDATE to `AgentValidateStrategy`, plus opus ids), standalone and VALIDATION `Fixed(AgentValidateStrategy.ID)` bindings, `FeatureTaskRuntimeQualityGateSelection`, `PackBuildStrategy`, CLI/env BUILD parsing, and the SKILL-415 fallback in `FeatureTaskRuntimeExecutionPlanResolver` are unchanged.
7. `../../../docs/runtime-command-guidance.md` states that every non-skipped goal child runs `review -> validate -> write_history`, describes BUILD as compile-only when explicitly selected, and keeps the ban on check in implement and implement_fix.
8. No detekt rule, baseline, guard, or test skip is weakened or added.

## Test Obligations

- `runtime-kotlin/runtime-domain/src/test/kotlin/skillbill/goalrunner/GoalRunnerQualityGateSelectionResolverTest.kt`: rename and rewrite `three-child goal resolves build build validate` so ids 1, 2, 3 all resolve VALIDATE. Keep `single-subtask goal resolves validate`. Rewrite `ordinal-last skipped promotes validate to previous last non-skipped` so a three-child manifest with id 3 `"skipped"` resolves VALIDATE for ids 1 and 2. Catches a regression back to last-child-only promotion.
- `../../../runtime-kotlin/runtime-engine/src/test/kotlin/skillbill/engine/goalrunner/GoalRunnerTest.kt`, `GoalRunnerQualityGateSelectionTest`: rewrite `three-child goal stamps build build validate` so launched `goalContinuation.qualityGateSelection` values are VALIDATE, VALIDATE, VALIDATE. Keep `single-subtask goal stamps validate`. Rewrite the ordinal-last skipped case so launched ids stay `[1, 2]` with VALIDATE, VALIDATE. Catches launch-path stamping drift from the resolver.
- One goal-runner resume case: a child whose durable continuation records `quality_gate_selection=build` resumes without `IncompatibleFeatureTaskRuntimeExecutionPlanError` and its launch request still carries BUILD. Catches a missing resume fence that would refuse in-flight SKILL-422 BUILD children. Add only if no existing test already covers launch-path admission of a recorded BUILD child.
- Keep `FeatureTaskRuntimeGoalContinuationAdoptionPersistenceTest` `resume with recorded quality_gate_selection build preserves build on durable artifact` as is.
- Leave BUILD coverage untouched: `SlotBaselineFullRunCapture.goalChildBuild()`, `PhaseStrategyCompositionTest`, `TestPhaseStrategies`, `QuarantinedProducerRecoveryRefusalTest` GOAL_CHILD BUILD case, `GoalRunnerRepairTest` BUILD fixtures, `AuditPlanningExecutionPlanMappingTest` BUILD inputs, `FeatureTaskRuntimeQualityGateSelectionUsageTest`, `FeatureTaskRuntimeGoalContinuationProtocolTest`, `AgentRunGoalContinuationCommandTest`. `GoalRunnerTestFactory.goalRunnerExecutionPlans` follows the resolver; do not hardcode BUILD there.
- Validate may repair test setup, wiring, formatting, or lint it uncovers, as long as assertions above and BUILD coverage keep their meaning.

## Non-Goals

- No change to `GoalRunnerPlanner`, `GoalRunnerWorkerSubtaskScheduler`, `GoalRunnerOutcomeReconciler`, `FeatureTaskRuntimeRunPreparation.updateContinuationOnResume`, `WorkflowGoalRunnerChildWorkflowPersistence.requireMatchingGoalContinuation`, or GoalRunner child repair's missing-selection heal.
- No migrator or rewrite of existing child rows; no retroactive revalidation.
- No change to review, audit, commit_push, parent CI monitor, or SKILL-422 sqlite maintenance.
- BUILD does not gain full-check behavior.

## Dependency Notes

None. This is the only subtask. The resolver change and the resume fence must land together: the resolver alone would make recorded BUILD resumes fail execution-plan admission.

## Validation Strategy

Implement and audit run no commands. The validate phase runs the pack full gate (`validation_gate.collect_all_full_gate_command`, which includes `./gradlew check` and detekt) and confirms the Test Obligations pass, including BUILD CLI parsing and the standalone VALIDATE skeleton tests.

## Implementation Details

Do the tasks in order. Tasks 2 to 4 are the resume fence and must land in the same change as task 1.

### Task 1. Collapse the resolver (AC 1, AC 5)

- File: `../../../runtime-kotlin/runtime-domain/src/main/kotlin/skillbill/goalrunner/GoalRunnerPolicy.kt`, object `GoalRunnerQualityGateSelectionResolver`.
- Keep the signature `fun resolve(manifest: DecompositionManifest, subtaskId: Int): FeatureTaskRuntimeQualityGateSelection` so callers do not change. Replace the body with a return of `FeatureTaskRuntimeQualityGateSelection.VALIDATE`. Remove the `manifest.subtasks.lastOrNull { it.status.decompositionStatus() != DecompositionStatus.SKIPPED }?.id` lookup and the BUILD branch.
- If detekt flags the unused `manifest` and `subtaskId` parameters, keep the parameters and resolve the warning the way sibling code in the module does. Do not add a baseline entry or a suppression that weakens a rule (AC 8). Assumption for implement to confirm: the module's detekt config either does not flag unused parameters on a public object function or the module already has an accepted pattern for it.
- Drop imports that become unused (`DecompositionStatus`, `decompositionStatus` if nothing else in the file uses them). Do not touch `GoalRunnerPlanner`, `GoalRunnerWorkerSubtaskScheduler`, or `GoalRunnerOutcomeReconciler`.
- `WorkflowGoalRunnerChildWorkflowPersistence.openGoalChildWorkflow` needs no edit. It already writes the resolver result only when `existingChild == null`, so new children persist `validate` (AC 5).

### Task 2. Fence `GoalRunnerSubtaskLaunchPrepare` (AC 2)

- File: `../../../runtime-kotlin/runtime-engine/src/main/kotlin/skillbill/engine/goalrunner/launch/GoalRunnerSubtaskLaunchPrepare.kt`, `prepareAttemptedLaunch` (around line 206) and `childExecutionPlan`.
- When `priorWorkflowId == null`, keep `qualityGate = GoalRunnerQualityGateSelectionResolver.resolve(manifest, subtaskId)`.
- When `priorWorkflowId != null`, read the existing child's recorded selection, first from `FeatureTaskRuntimeGoalContinuationArtifact.qualityGateSelection`, then from the stored execution plan's quality gate. Use `resolve()` only when both are absent. Pass that value as the `qualityGate` argument that reaches `resolveCreation`, so the resume request matches the stored plan.
- Keep the existing first-open-only `executionPlans.creationQualityGate(...)` assignment for `assignedQualityGate`. The only change is which value feeds `qualityGate` on resume.
- If the existing child record is not in scope here, thread it in from the caller that already loaded the child. Do not add a new store lookup.

### Task 3. Fence `GoalRunnerRunPreparation.existingChildExecutionPlanAdmission` (AC 3)

- File: `../../../runtime-kotlin/runtime-engine/src/main/kotlin/skillbill/engine/goalrunner/execution/core/GoalRunnerRunPreparation.kt`, around line 148. `GoalRunner.kt` calls this during run preparation, and it only runs when the manifest already carries a child `workflowId`.
- Replace the fresh `resolve()` passed to `FeatureTaskRuntimeExecutionPlanCreationRequest.qualityGate` with the child's recorded selection, using the same continuation-then-stored-plan order as task 2, with `resolve()` as the fallback when nothing is recorded.
- If tasks 2, 3, and 4 need the same "recorded selection or resolve()" lookup, put it in one small internal helper in the goal-runner engine package and call it from all three sites. Keep it in the engine module, not the domain resolver, because it reads child workflow state.

### Task 4. Fence `GoalRunnerLaunchReconciler.goalContinuationContext` (AC 4)

- File: `../../../runtime-kotlin/runtime-engine/src/main/kotlin/skillbill/engine/goalrunner/launch/GoalRunnerLaunchReconciler.kt`, around line 175.
- Set `SkillRunGoalContinuationContext.qualityGateSelection` from the recorded continuation selection when the child already has one, else from `resolve()`. That keeps the launch request truthful for a recorded BUILD child, so it does not say VALIDATE while sqlite says BUILD.

### Task 5. Leave the protected paths alone (AC 6, AC 8)

- No edits to `SkeletonStrategyBindings`, `FeatureTaskRuntimeQualityGateSelection`, `PackBuildStrategy`, CLI/env `--quality-gate-selection` parsing, `FeatureTaskRuntimeExecutionPlanResolver` (`plannedQualityGate`, `creationQualityGate`, SKILL-415 fallback), `FeatureTaskRuntimeRunPreparation.updateContinuationOnResume`, `WorkflowGoalRunnerChildWorkflowPersistence.requireMatchingGoalContinuation`, or the GoalRunner child repair heal.
- No detekt config, baseline, or guard edits, and no `@Ignore` or skipped tests.

### Task 6. Docs (AC 7)

- File: `../../../docs/runtime-command-guidance.md`, Runtime Agent Behavior section.
- Replace the sentence "Default standalone runs skip build (`review -> validate`); only goal children stamped for build use `review -> build -> write_history`." with text saying standalone runs use `review -> validate`, and goal routing stamps validate on every non-skipped child, so each child runs `review -> validate -> write_history` and the validate slot owns the full gate on every child.
- Keep the BUILD session description, scoped to an explicit BUILD selection: pack `validation_gate.build_command` only (Kotlin `./gradlew compileKotlin`), with no `collect_all_full_gate_command`, no `./gradlew check`, no `skill-bill validate`, and no `skill-bill phase validation`.
- Keep the rule that implement and implement_fix must not run check. No other authoring briefing in `../../../docs` or `skills/` states the last-child rule, so no other doc edits.

### Task 7. Tests (Test Obligations)

- `runtime-kotlin/runtime-domain/src/test/kotlin/skillbill/goalrunner/GoalRunnerQualityGateSelectionResolverTest.kt`:
  - Rename `three-child goal resolves build build validate` to something like `three-child goal resolves validate for every child`. Assert ids 1, 2, 3 all return VALIDATE.
  - Keep `single-subtask goal resolves validate`.
  - Rename `ordinal-last skipped promotes validate to previous last non-skipped` to something like `children before a skipped last child resolve validate`. Use the three-child `manifest(subtaskCount)` helper with id 3 status `"skipped"` and assert ids 1 and 2 return VALIDATE.
- `../../../runtime-kotlin/runtime-engine/src/test/kotlin/skillbill/engine/goalrunner/GoalRunnerTest.kt`, class `GoalRunnerQualityGateSelectionTest`:
  - Rewrite `three-child goal stamps build build validate` to expect `launcher.requests.map { it.goalContinuation.qualityGateSelection }` equal to VALIDATE, VALIDATE, VALIDATE and rename it to match.
  - Keep `single-subtask goal stamps validate`.
  - Rewrite the ordinal-last skipped case: launched ids stay `[1, 2]`, selections VALIDATE, VALIDATE. Same pattern as today: `InMemoryGoalManifestStore`, `RecordingSubtaskLauncher` completing each child, `testGoalRunner`, assert `GoalRunnerRunReport.Completed`.
- Resume BUILD case: first check whether any existing goal-runner test resumes a child with a recorded BUILD selection through `existingChildExecutionPlanAdmission` or `prepareAttemptedLaunch`. If none does, add one test in `GoalRunnerTest.kt` (or the existing goal-runner resume test file, if implement finds a better-fitting one). It seeds a manifest child with a `workflowId`, a durable continuation recording `quality_gate_selection=build`, and a stored BUILD execution plan, then runs the goal runner. It asserts the run does not throw `IncompatibleFeatureTaskRuntimeExecutionPlanError` and the launch request's `goalContinuation.qualityGateSelection` is BUILD. One test covers all three fence sites because they sit on the same resume path. The realistic bug it catches is a missing fence that refuses in-flight SKILL-422 BUILD children.
- Leave every BUILD fixture listed under Test Obligations unchanged. `GoalRunnerTestFactory.goalRunnerExecutionPlans` follows the resolver on its own, so no edit there.
- No other new tests. The docs paragraph and the unchanged bindings need no test.

### Constraints

- No `//` comments in Kotlin. Use `FeatureTaskRuntimeQualityGateSelection` values or `wireValue`, never a local `"build"` or `"validate"` string.
- Tests use `kotlin.test` `assertEquals` and backtick names.
- Implement and implement_fix do not compile, test, or run check. The validate phase owns the full gate, and validate may repair wiring, test setup, formatting, or lint without changing the assertions above or removing BUILD coverage.
- No installer or install-sync commands.

## Next Path

```bash
skill-bill goal SKILL-425
```
