# SKILL-390 Subtask 1 - goal-runner-collaborators-and-engine-repairs

Parent spec: [.feature-specs/SKILL-390-runtime-engine-architecture/spec.md](./spec.md)
Issue key: SKILL-390

## Scope

Covers F-001, F-004, F-005, F-006, the recovery forwarders of F-007, and F-008 from the parent overview.

Touches:
- runtime-engine main `goalrunner`: execution.core, launch, planning.context, planning.attempt, planning.outcome, planning.state, planning.sweep, status, persist, reset, repair, and the recovery importers;
- the featuretask files that import goalrunner;
- the issue-key sites in work and featuretask.lifecycle;
- runtime-domain `FeatureTaskExecutionIdentityPolicy`;
- runtime-core repoTest `RuntimeEngineBoundaryArchitectureTest` and `baselines/runtime-engine-package-cycle-baseline.txt`;
- the engine test factories that build the deleted bags (GoalRunnerTestFactory and its callers).

Changes:
- Delete the five goal-runner bags and the two status-projection bags. Each consumer takes the collaborators it reads, as private constructor parameters:
  - GoalRunner: 10.
  - GoalRunnerSubtaskLaunchPrepare: 7.
  - GoalRunnerFinalization: 8.
  - GoalRunnerPerRunLoopAssembler: unpacking gives 13 parameters. Pass GoalRunnerIterationPendingState as a call argument; it has 2 reads in each of GoalRunnerIterationOutcome and GoalRunnerSelectedSubtaskLoop. Those classes then become injectable, and the assembler shrinks or goes away.
- Turn DefaultGoalPlanningSweep's receiver and parameter functions into members, or into @Inject step classes in the same packages. Each step takes only what it reads: the shared-preplan settlement and production, the planning attempt gate and attempt recording, subtask plan production, and run progress.
- Move GoalRunnerFinalization's and GoalRunnerStatusProjectionAssembler's same-file receiver functions into their classes as private members.
- Add one non-validating canonical issue-key function to FeatureTaskExecutionIdentityPolicy. `normalizeIssueKey` calls it, and so does every engine trim-uppercase issue-key site.
- Measure the planning attempt duration with the injected Clock instead of System.nanoTime.
- Delete DurableChildRecoveryClass.kt; its importers use skillbill.engine.recovery directly.
- Remove the featuretask-to-goalrunner edge:
  - drop the 6 unused `goalrunner.status.completed` imports;
  - move protectedBranchName and its protected-branch set into featuretask.lifecycle.branch;
  - move GOAL_CHILD_REPAIR_EVIDENCE_ARTIFACT_KEY into featuretask.persist;
  - empty the engine package-cycle baseline.
- Delete the inert default-public visibility rule and its two fixtures from RuntimeEngineBoundaryArchitectureTest.

## Acceptance Criteria

1. runtime-engine main declares no type whose name ends in `Boundaries`. GoalRunnerBoundaries.kt and GoalPlanningSweepBoundaries.kt no longer exist, and neither GoalRunnerStatusProjectionDataSources nor GoalRunnerStatusProjectionValidationDependencies is declared anywhere.
2. GoalRunner, GoalRunnerSubtaskLaunchPrepare, GoalRunnerFinalization, DefaultGoalPlanningSweep, GoalRunnerStatusProjectionAssembler, GoalRunnerPerRunLoopAssembler (if it remains) and every @Inject class added under goalrunner.planning each have at most 12 constructor parameters. Each parameter is `private val` or a plain parameter. None of these classes declares a public or internal property that returns one of its constructor collaborators.
3. No function or property in runtime-engine main declares DefaultGoalPlanningSweep, GoalRunnerFinalization or GoalRunnerStatusProjectionAssembler as its receiver or as a parameter type, and no call passes `this` of those classes to a top-level function.
4. runtime-engine main contains no `.trim().uppercase()` and no `?.trim()?.uppercase()` applied to an issue key. Each former site calls one FeatureTaskExecutionIdentityPolicy function that returns the trimmed, uppercased key without validating it, and `FeatureTaskExecutionIdentityPolicy.normalizeIssueKey` returns its result through that same function.
5. runtime-engine main contains no `System.nanoTime`. The planning attempt duration is computed from the injected `Clock`, and `GoalPlanningSweepConstants.NANOS_PER_MILLI` is gone if nothing references it.
6. An engine test runs one planning attempt with a clock that advances by a fixed number of milliseconds while the launch runs, and asserts that the recorded attempt `durationMs` equals that number. This catches a start time read after the launch or from a different clock, both of which record 0.
7. `goalrunner/persist/DurableChildRecoveryClass.kt` no longer exists. GoalOperatorDecisionService, GoalRunnerResetReplanCoordinator, GoalPlanningRecoveryKind, GoalRunnerReAttemptCause, GoalRunnerRepairCoordinator and GoalPlanningRecoveryClassificationTest import their recovery symbols from `skillbill.engine.recovery`.
8. No file under runtime-engine main `skillbill/engine/featuretask` imports `skillbill.engine.goalrunner.*` or `skillbill.engine.work.*`. `protectedBranchName` and its protected-branch set are declared in `skillbill.engine.featuretask.lifecycle.branch`, and `GOAL_CHILD_REPAIR_EVIDENCE_ARTIFACT_KEY` is declared in `skillbill.engine.featuretask.persist`.
9. `runtime-core/src/repoTest/kotlin/skillbill/architecture/baselines/runtime-engine-package-cycle-baseline.txt` contains no rows.
10. RuntimeEngineBoundaryArchitectureTest.kt no longer declares the default-public top-level declaration method, its two `visibility census` fixture methods, or the private `topLevelPublicDeclarations` helper. Its run-loop acyclicity methods and the other engine boundary guard classes remain.
11. The subtask adds no architecture-test class, baseline row, detekt suppression, typealias or module, and it adds no file to a package that already holds 12 or more Kotlin files.

## Non-Goals

- Feature-task gates, FeatureTaskRuntimeRunner visibility and the engine inject-property guard, which belong to subtask 2.
- Moving test packages, which belongs to subtask 3.
- Typing the engine's public raw-map declarations, unifying preflight add-on resolution, or renaming contracts `normalizeIssueKey`.
- Moving goal-runner declarations between modules. SKILL-393 places the ports declarations; this subtask edits whatever packages they end up in.
- Changing persisted bytes, CLI or MCP output, recovery command text, or any FeatureTaskRuntime* name.

## Dependency Notes

Depends on: none
This subtask waits for no other issue. These bundles touch the same code; if one has landed, rebase onto it first, and otherwise implement against the current tree (whichever lands second keeps both edits):
- SKILL-387 rewrites GoalPlanningPhaseAttemptGate and GoalPlanningSubtaskPlanProduction, where DefaultGoalPlanningSweep receiver functions live;
- SKILL-388;
- SKILL-389 edits ArchitectureScanSupport and the scanner inventories;
- SKILL-392 subtask 2 rewrites the issue-key derivation at FeatureTaskRuntimeRunner:69 and FeatureTaskRuntimeExecutionEntry:35. Whichever lands second routes the derivation through the one FeatureTaskExecutionIdentityPolicy canonical function;
- SKILL-393 subtask 1 deletes the engine typealiases over ports types and moves goal-runner persistence models into engine.

AC 8 and AC 9 need the featuretask-to-work edge gone. Its 18 imports all use the `IdeStatusCurrentPhaseExecution` and `IdeStatusCurrentPhaseExecutionKind` aliases in `engine/work/model/IdeStatusModels.kt`. If SKILL-393 subtask 1 has not landed, this subtask makes that bundle's edit for these two aliases only: point the featuretask imports at the runtime-ports types directly and delete the two aliases. Add no alias. Both types stay in runtime-ports under SKILL-393 (the IdeStatusSnapshot tree), so the edits agree in either order.

Before implementing, recount the receiver functions, the issue-key sites and the featuretask imports of goalrunner and work, because those bundles move lines. FeatureTaskExecutionIdentityPolicy belongs to runtime-domain (SKILL-397); the added function is this bundle's only domain edit, and whichever bundle lands second keeps it.

## Validation Strategy

Build compiles runtime-domain, runtime-engine, runtime-core, runtime-cli and runtime-mcp, which proves kotlin-inject resolution of the unpacked goal-runner and sweep constructors. Validate runs the full check:
- the goal-runner and goal-planning engine suites, including the new duration test;
- the slotbaseline goal-planning capture suites (byte-identical persisted artifacts);
- the FeatureTaskExecutionIdentityPolicy domain tests;
- runtime-core repoTest: package-cycle drift against the empty engine baseline, RuntimeEngineBoundaryArchitectureTest, RuntimeEngineInboundApiTest and ambient-clock drift;
- the runtime-cli goal suites and the runtime-mcp parity tests;
- detekt and spotless.

## Next Path

skill-bill goal SKILL-390

## Spec Path

.feature-specs/SKILL-390-runtime-engine-architecture/spec_subtask_1_goal-runner-collaborators-and-engine-repairs.md
