# SKILL-393 subtask 1 - Engine-owned contracts move to engine

Parent: [spec.md](spec.md). Finding: F-001 in [investigation.md](investigation.md).

## Scope

This is a mechanical move. No function body, signature shape, or behaviour changes.

- Move the 56 declarations F-001 lists from runtime-ports main into runtime-engine main, and delete them from ports:
  - the 16 goal-runner store interfaces, including the manifest and outcome role interfaces;
  - their 16 runner models and 18 persistence models;
  - `GoalRunnerResetSubtaskSnapshot` and `GoalPlanningPreparationProgress`;
  - `IdeStatusRequest`, `IdeStatusResult`, `IdeStatusRepositoryResolution`, and `IdeStatusSelectionTier`.
- Declare the 17 persistence and repair models that `engine/goalrunner/model/GoalRunnerPersistenceModelAliases.kt` aliases under those same names in `skillbill.engine.goalrunner.model`. Declare the four ide-status models in `skillbill.engine.work.model`. Engine files importing the alias names then need no change.
- Put the store interfaces beside their implementations (`engine/goalrunner/manifest`, `persist`, `repair`, `planning/hydration`), or in another existing goal-runner package. The remaining runner models go in an engine `model` package. Every package must stay within `PackageSiblingCountArchitectureTest` limits (12 files, 20 for `model` packages).
- Delete `GoalRunnerPersistenceModelAliases.kt`.
- In `engine/work/model/IdeStatusModels.kt`, delete the 19 aliases and the re-declared `IDE_STATUS_PAUSE_REASON_LABEL_MAX_LENGTH`. For the 16 aliased types and the constant that stay in ports (the `IdeStatusSnapshot` tree that `IdeStatusValidator` takes), import the ports declaration directly.
- Delete ports' `IdeStatusCandidate`, which duplicates engine's.
- Move `GoalRunnerManifestStoreDefaults` and `NoopGoalRunnerAttemptLedgerStore` from runtime-ports testFixtures to runtime-engine testFixtures, under the package of the interface each implements.
- Update imports in engine main, test, and testFixtures, in runtime-core main (store providers) and tests, and in runtime-cli main (`GoalCliFormatting.kt`, `GoalCliExitCodes.kt`, `GoalCliControlCommands.kt`, `WorkCliCommands.kt`) and tests.
- Update `RuntimeEngineInboundApiTest.PINNED_ENGINE_INBOUND_API_TYPES`:
  - keep the three repair entries and `IdeStatusRequest`/`IdeStatusResult`, which now name declarations;
  - add `GoalRunnerAppliedRepair`, `GoalRunnerChildWedgeDiagnosis`, `GoalRunnerWedgeFinding`, and `GoalRunnerResetSubtaskSnapshot` at their engine paths;
  - remove `skillbill.engine.work.model.IdeStatusSnapshot` and `IdeStatusProblemCode`, which will name no engine declaration.

## Acceptance Criteria

1. runtime-ports main declares none of the 56 declarations listed in investigation F-001, and runtime-engine main declares each of them.
2. runtime-engine main declares no typealias whose target is a `skillbill.ports.*` type, and it does not re-declare a ports constant.
3. runtime-ports main declares no `IdeStatusCandidate`. runtime-ports testFixtures declares neither `GoalRunnerManifestStoreDefaults` nor `NoopGoalRunnerAttemptLedgerStore`, and runtime-engine testFixtures declares both.
4. Every `PINNED_ENGINE_INBOUND_API_TYPES` entry names an engine declaration. The inbound API test, the engine boundary test, and the package sibling-count test pass.
5. No runtime-cli main file imports a type both from runtime-ports and from runtime-engine under the same simple name.
6. Goal-runner status, repair, reset, and replan output, and `work status` output, are byte-identical to baseline under the existing CLI and engine suites.

## Non-Goals

- Renaming any moved type or dropping the `Port` suffix.
- Collapsing role interfaces or replacing store interfaces with concrete classes.
- Moving `GoalRunnerSubtaskLauncher`, `GoalPullRequestPort`, `PullRequestIdentityLookup`, `PullRequestTemplateFiles`, `GoalRunnerControlRepository`, `GoalPlanningPreparationRepository`, `GoalRunnerPersistenceSession`, `GoalRunnerReviewPolicy`, `GoalRunnerOutOfBandAcceptance`, `GoalRunnerSubtaskLaunchRequest`, `GoalPullRequestRequest`/`Result`, or the `IdeStatusSnapshot` tree. Each has a consumer or implementer outside engine.
- Behaviour relocation and guard edits (subtask 2).

## Dependency Notes

No dependency within this bundle, and no wait on another issue. If SKILL-389 has already removed the `goalRunnerManifestStore`/`goalRunnerWorkflowOutcomeStore` accessors, there is nothing to re-import there. If SKILL-392 has already moved `IdeStatusReadSnapshotConcurrencyTest` into runtime-core, rewrite its imports: `IdeStatusProblemCode` from ports, and `IdeStatusRequest`/`IdeStatusResult` from `skillbill.engine.work.model`. Keep SKILL-392's own pin edits. If a SKILL-390 engine change has already moved goal-runner packages, place the declarations in the current packages. Add no alias.

## Validation Strategy

Build compiles every module; kotlin-inject resolution of the store providers is the risk. Validate runs the runtime-engine, runtime-cli, runtime-core, and runtime-ports suites and `:runtime-core:repoTest`. The regressions to catch:

- a store provider binding the wrong type after the move;
- an ide-status projection changing when read through engine types;
- the pinned list naming a declaration that no longer exists, which the inbound API test's second case catches.
