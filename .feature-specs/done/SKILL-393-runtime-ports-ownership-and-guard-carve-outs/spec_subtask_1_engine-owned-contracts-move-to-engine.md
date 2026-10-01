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

## Implementation Details

Planned against HEAD `2815b2064`. SKILL-389 has landed, so no `RuntimeComponent` accessor names a moved type. SKILL-390 and SKILL-392 have not landed, so `IdeStatusReadSnapshotConcurrencyTest` is still in runtime-cli. Every path below is relative to `../../../runtime-kotlin`. "Ports" means `runtime-ports/src/main/kotlin/skillbill/ports`, and "engine" means `runtime-engine/src/main/kotlin/skillbill/engine`.

### Placement of the 56 declarations

Declaration bodies, constructors, defaults, `init` blocks and `require` messages are copied byte for byte. Only the `package` line and the imports change. No declaration gains an explicit `public`, `internal` or `@Inject`.

| Engine file (new unless noted) | Package | Declarations | Source in ports |
| --- | --- | --- | --- |
| `goalrunner/manifest/GoalRunnerManifestStore.kt` | `skillbill.engine.goalrunner.manifest` | `GoalRunnerManifestQueries`, `GoalRunnerManifestExecutionCommands`, `GoalRunnerManifestControlWrites`, `GoalRunnerManifestStateWrites`, `GoalRunnerManifestPurgeCommands`, `GoalRunnerManifestStore` (6) | `goalrunner/runner/GoalRunnerPorts.kt` |
| `goalrunner/persist/GoalRunnerWorkflowOutcomeStore.kt` | `skillbill.engine.goalrunner.persist` | `GoalRunnerTerminalOutcomeStore`, `GoalRunnerReviewOutcomeStore`, `GoalRunnerWorkflowOutcomeStore`, `GoalRunnerAttemptLedgerStore` (from `GoalRunnerPorts.kt`); `GoalRunnerWorkflowOutcomeMutationStore`, `GoalRunnerWorkflowProgressStore`, `GoalRunnerWorkflowLedgerWriteStore` (from their own files) (7) | `goalrunner/runner/*` |
| `goalrunner/repair/GoalRunnerChildRepairPorts.kt` | `skillbill.engine.goalrunner.repair` | `GoalRunnerChildRepairStore`, `GoalRunnerChildRepairRunnerPort` (2) | `goalrunner/persistence/*` |
| `goalrunner/planning/hydration/GoalChildPlanningHydratorPort.kt` | `skillbill.engine.goalrunner.planning.hydration` | `GoalChildPlanningHydratorPort` (1) | `goalrunner/persistence/` |
| `goalrunner/model/GoalRunnerStoreModels.kt` | `skillbill.engine.goalrunner.model` | `GoalRunnerManifestState`, `GoalRunnerCompletionPersistenceResult`, `GoalRunnerScopedReplanWriteResult`, `GoalRunnerScopedReplanOptions`, `GoalRunnerPausePersistenceResult`, `GoalRunnerLaunchAuthorization`, `GoalRunnerReconcileGate`, `GoalRunnerWorkflowProgress` (with its secondary constructor), `GoalProgressEventDraft`, `GoalAttemptLedgerEntryDraft`, `GoalRunnerProgressEventRecordRequest`, `GoalRunnerAttemptLedgerRecordRequest`, `GoalRunnerLedgerSequenceWatermarks` (13) | `goalrunner/runner/model/GoalRunnerPortModels.kt` |
| `goalrunner/model/GoalRunnerChildWorkflowModels.kt` | `skillbill.engine.goalrunner.model` | `GoalRunnerChildExecutionPlanAdmission`, `GoalRunnerChildWorkflowSetup`, `GoalChildPlanningHydrationRequest`, `GoalChildPlanningHydrationResult` (4) | `runner/model/GoalRunnerChildExecutionPlanAdmission.kt`, `runner/model/GoalRunnerReviewPolicy.kt`, `persistence/model/GoalChildPlanningHydrationResult.kt` |
| `goalrunner/model/GoalContinuationArtifactModels.kt` | `skillbill.engine.goalrunner.model` | `GoalSubtaskIdentity`, `HistoryArtifactAppend`, `GoalContinuationCandidate`, `GoalRunnerBlockWrite` (4) | `persistence/model/GoalContinuationArtifactModels.kt` (same file name) |
| `goalrunner/model/GoalRunnerReconcileRequests.kt` | `skillbill.engine.goalrunner.model` | `CrashReconcileExpiredWorkerRequest`, `StaleRunningCandidatesBlockRequest` (2) | `persistence/model/GoalRunnerReconcileRequests.kt` (same file name) |
| `goalrunner/model/GoalRunnerRepairModels.kt` | `skillbill.engine.goalrunner.model` | `GoalRunnerWedgeClass`, `GoalRunnerRepairStatus`, `GoalRunnerRepairRequest`, `GoalRunnerWedgeFinding`, `GoalRunnerChildWedgeDiagnosis`, `GoalRunnerChildWedgeDiagnosisRequest`, `GoalRunnerChildWedgeRepairRequest`, `GoalRunnerChildRepairApplyRequest`, `GoalRunnerChildRepairApplyResult`, `GoalRunnerAppliedRepair`, `GoalRunnerRepairResult` (11) | `persistence/model/GoalRunnerRepairModels.kt` (same file name) |
| `goalrunner/model/GoalRunnerResetModels.kt` (existing, append) | `skillbill.engine.goalrunner.model` | `GoalRunnerResetSubtaskSnapshot` (1) | `goalrunner/model/GoalRunnerResetSubtaskSnapshot.kt` |
| `goalrunner/planning/model/GoalPlanningPreparationProgress.kt` | `skillbill.engine.goalrunner.planning.model` | `GoalPlanningPreparationProgress` (1) | `goalrunner/model/GoalPlanningPreparationRecord.kt` |
| `work/model/IdeStatusModels.kt` (existing, rewrite) | `skillbill.engine.work.model` | `IdeStatusSelectionTier`, `IdeStatusRepositoryResolution`, `IdeStatusRequest`, `IdeStatusResult` (4) | `idestatus/model/IdeStatusModels.kt` |

Totals are 16 store interfaces, 16 runner models, 18 persistence models, 2 others and 4 ide-status models, which makes 56. The 17 names that `GoalRunnerPersistenceModelAliases.kt` aliases now land in `skillbill.engine.goalrunner.model` under the same names. `GoalChildPlanningHydrationResult` is the 18th persistence model, and it has no alias.

These placements were chosen against the sibling ceilings and the model guards:

- **`engine/goalrunner/model`:** 14 files today. Deleting the alias file and adding 5 files gives 18, under the limit of 20. Files in this package may import only from ports, domain, contracts, `java.*` and `skillbill.engine.goalrunner.model.*`.
  - That is the reason `GoalChildPlanningHydrationRequest` stays beside `GoalRunnerChildWorkflowSetup`, which references it. Putting it in `goalrunner.planning.model` would make a `goalrunner/model` file import `skillbill.engine.goalrunner.planning.model`, and that breaks the "goal-runner model sources stay data-only" case.
- **Other packages:**
  - `manifest` goes from 6 to 7 files, `repair` from 6 to 7, `planning/hydration` from 2 to 3, and `planning/model` from 8 to 9.
  - `persist` goes from 11 to 12, which is exactly its ceiling. If a concurrent change has already added a file to `persist`, put `GoalRunnerWorkflowOutcomeStore.kt` in `goalrunner/manifest` instead. Do not add an alias.
- **`GoalPlanningPreparationProgress`:** its only producer is `GoalPlanningPreparationCheckpoint.recoveryProgress`, in `skillbill.engine.goalplanning`. That package already imports `skillbill.engine.goalrunner.planning.model`, so `planning/model` adds no new first-segment package edge.
- **Name collisions:** none of the 56 names is already declared anywhere in engine. I checked by grep.

### Ordered tasks

**T1. Create the engine declarations (AC-001, AC-002).**
- Create the new files in the table and append to `GoalRunnerResetModels.kt`.
- Import each moved file's dependencies from their staying owners. These are `skillbill.ports.goalrunner.GoalRunnerPersistenceSession`, `skillbill.ports.goalrunner.model.{GoalPlanningIdentity, GoalPlanningContractProvenance, GovernedGoalSubtaskDescriptor}`, `skillbill.ports.goalrunner.runner.model.{GoalRunnerReviewPolicy, GoalRunnerOutOfBandAcceptance}`, `skillbill.ports.taskruntime.*`, `skillbill.ports.workflow.*`, `skillbill.ports.agentrun.model.*`, the domain `skillbill.goalrunner.model.*` and `skillbill.workflow.*`, `skillbill.agentaddon.model.AgentAddonSelection`, and `skillbill.review.context.model.launch.CodeReviewExecutionMode`.
- Drop any import that now points into the declaring file's own package. For example, `GoalRunnerReconcileRequests.kt` no longer imports `GoalRunnerReconcileGate`. The two store-interface files import their models from `skillbill.engine.goalrunner.model`.

**T2. Rewrite `engine/work/model/IdeStatusModels.kt` (AC-002, AC-003).**
- Delete the 19 aliases, the 20 aliased ports imports and the re-declared `IDE_STATUS_PAUSE_REASON_LABEL_MAX_LENGTH`. The engine copy of the constant has no reader.
- Keep `IdeStatusCandidate` byte-identical. Add the four moved declarations in ports order: `IdeStatusSelectionTier`, `IdeStatusRepositoryResolution`, `IdeStatusRequest`, `IdeStatusResult`.
- Import `skillbill.ports.idestatus.model.{IdeStatusLifecycleState, IdeStatusSnapshot, IdeStatusWorkflowFamily}`, `skillbill.workflow.model.FeatureTaskRouteScope`, `java.nio.file.Path` and `java.time.Instant`.

**T3. Delete the moved declarations from ports main (AC-001, AC-003).**
- **Whole-file deletions:**
  - all of `goalrunner/persistence/**`: 3 interface files and the 4 files under `model/`. Both packages disappear.
  - `goalrunner/runner/GoalRunnerWorkflowLedgerWriteStore.kt`, `GoalRunnerWorkflowOutcomeMutationStore.kt` and `GoalRunnerWorkflowProgressStore.kt`
  - `goalrunner/runner/model/GoalRunnerChildExecutionPlanAdmission.kt`
  - `goalrunner/model/GoalRunnerResetSubtaskSnapshot.kt`
- **Files that are split:**
  - `GoalRunnerPorts.kt` keeps only `GoalRunnerSubtaskLauncher` and `GoalPullRequestPort`. It imports only `AgentRunLaunchOutcome`, `GoalPullRequestRequest`, `GoalPullRequestResult` and `GoalRunnerSubtaskLaunchRequest`.
  - `GoalRunnerPortModels.kt` keeps `GoalRunnerSubtaskLaunchRequest`, `GoalRunnerOutOfBandAcceptance`, `GoalPullRequestRequest` and `GoalPullRequestResult`. It imports only `SkillRunRequest` and `java.nio.file.Path`.
  - `GoalRunnerReviewPolicy.kt` keeps `GoalRunnerReviewPolicy`. It imports only `AgentAddonSelection` and `CodeReviewExecutionMode`.
  - `GoalPlanningPreparationRecord.kt` loses the `GoalPlanningPreparationProgress` block, and its imports stay the same.
  - `idestatus/model/IdeStatusModels.kt` loses `IdeStatusSelectionTier`, `IdeStatusCandidate`, `IdeStatusRepositoryResolution`, `IdeStatusRequest` and `IdeStatusResult`. It also drops the now-unused `java.nio.file.Path` and `FeatureTaskRouteScope` imports. The `IdeStatusSnapshot` tree and the constant stay.

**T4. Delete `engine/goalrunner/model/GoalRunnerPersistenceModelAliases.kt` (AC-002).**

**T5. Move the two ports test fixtures (AC-003).**
- `runtime-ports/src/testFixtures/kotlin/skillbill/ports/goalrunner/runner/GoalRunnerManifestStoreDefaults.kt` moves to `runtime-engine/src/testFixtures/kotlin/skillbill/engine/goalrunner/manifest/GoalRunnerManifestStoreDefaults.kt` (package `skillbill.engine.goalrunner.manifest`). This is a new directory.
- `.../NoopGoalRunnerAttemptLedgerStore.kt` moves to `runtime-engine/src/testFixtures/kotlin/skillbill/engine/goalrunner/persist/NoopGoalRunnerAttemptLedgerStore.kt` (package `skillbill.engine.goalrunner.persist`).
- The bodies stay unchanged, and the imports are rewritten by the T6 mapping. The ports testFixtures `goalrunner/runner` directory then disappears.
- Directory and package must match, because the sibling-count test case "goal-runner test sources use the production package they exercise" enforces it.
- `PortNullObjectClassification.kt` keys entries by simple name, so its `NoopGoalRunnerAttemptLedgerStore` entry needs no edit.

**T6. Rewrite imports everywhere (AC-001, AC-002, AC-005).**

Apply this mapping to every importer. The census at HEAD found 2 runtime-core main files, 2 runtime-cli main files, 1 runtime-cli test, about 75 engine main files, about 25 engine test files and 5 engine testFixtures. No application, mcp, infra or core test file imports a moved name. No file uses a wildcard import or an inline fully qualified reference to a moved type.

| Old import | New import |
| --- | --- |
| `skillbill.ports.goalrunner.persistence.model.X` | `skillbill.engine.goalrunner.model.X` |
| `skillbill.ports.goalrunner.persistence.GoalChildPlanningHydratorPort` | `skillbill.engine.goalrunner.planning.hydration.GoalChildPlanningHydratorPort` |
| `skillbill.ports.goalrunner.persistence.GoalRunnerChildRepair{Store,RunnerPort}` | `skillbill.engine.goalrunner.repair.*` |
| `skillbill.ports.goalrunner.runner.GoalRunnerManifest*` and `GoalRunnerManifestStoreDefaults` | `skillbill.engine.goalrunner.manifest.*` |
| `skillbill.ports.goalrunner.runner.{GoalRunnerTerminalOutcomeStore, GoalRunnerReviewOutcomeStore, GoalRunnerWorkflowOutcomeStore, GoalRunnerWorkflowOutcomeMutationStore, GoalRunnerWorkflowProgressStore, GoalRunnerWorkflowLedgerWriteStore, GoalRunnerAttemptLedgerStore, NoopGoalRunnerAttemptLedgerStore}` | `skillbill.engine.goalrunner.persist.*` |
| `skillbill.ports.goalrunner.runner.model.<the 16 moved runner models>` | `skillbill.engine.goalrunner.model.*` |
| `skillbill.ports.goalrunner.model.GoalRunnerResetSubtaskSnapshot` | `skillbill.engine.goalrunner.model.GoalRunnerResetSubtaskSnapshot` |
| `skillbill.ports.goalrunner.model.GoalPlanningPreparationProgress` | `skillbill.engine.goalrunner.planning.model.GoalPlanningPreparationProgress` |
| `skillbill.ports.idestatus.model.{IdeStatusRequest, IdeStatusResult, IdeStatusRepositoryResolution, IdeStatusSelectionTier}` | `skillbill.engine.work.model.*` |
| `skillbill.engine.work.model.<any of the 15 staying types or IDE_STATUS_PAUSE_REASON_LABEL_MAX_LENGTH>` | `skillbill.ports.idestatus.model.*` |

Rules:
- **Same-package imports:** drop any import that would point into the file's own package. Examples are the `goalrunner/model` files (`GoalRunnerReplanModels.kt`, `GoalRunnerResetModels.kt`, `GoalRunPreparation.kt`, `GoalPreflightModels.kt`, `GoalRunnerLaunchModels.kt`), `manifest/*`, `persist/*`, `repair/*`, `planning/hydration/*`, and the engine tests and testFixtures that sit in those packages, such as `test/.../goalrunner/manifest/TestNoopGoalPlanningManifestStore.kt`.
- **Engine alias imports stay:** keep every existing `skillbill.engine.goalrunner.model.*` and `skillbill.engine.work.model.{IdeStatusCandidate, IdeStatusRequest, IdeStatusResult, IdeStatusSelectionTier, IdeStatusRepositoryResolution}` import. Those names now resolve to real declarations.
- **Staying ide-status names switch to ports:**
  - engine main `work/IdeStatus{Projector, ProblemSnapshots, Service, RepositoryCorrelation, FreshnessClassifier, ProjectorMapping, SelectionPolicy, LivenessAnchors}.kt`
  - `featuretask/phase/core/FeatureTaskRuntimeCurrentPhaseExecutionContext.kt` and `featuretask/model/core/FeatureTaskRuntimeStatusModels.kt`
  - under `featuretask/slot/`: `PhaseStrategy.kt`, `codereview/{DelegatedReviewStrategy, InlineReviewStrategy, CodeReviewSlot}.kt`, `codereview/history/CodeReviewHistory.kt`, `qualitygate/QualityGateSteps.kt`, `qualitygate/{packbuild/PackBuildStrategy, agentvalidate/AgentValidateStrategy, packvalidation/PackValidationStrategy}.kt`, `audit/{AcceptanceAuditStrategy, AcceptanceAuditHistory}.kt` and `state/PhaseHistoricalInterpreter.kt`
  - engine tests: `FeatureTaskRuntimeStatusServiceTest`, `work/IdeStatusService{GoalProjection,BranchScoping,}Test`, `IdeStatusSelectionPolicyTest`, `IdeStatusFreshnessTest`, `IdeStatusTimestampTest`, `featuretask/slot/PhaseHistoricalInterpreterTest`
  - runtime-cli tests: `CliWorkStatusTest` and the `IdeStatusProblemCode` import in `IdeStatusReadSnapshotConcurrencyTest`
- **The hidden case:** `runtime-engine/src/test/kotlin/skillbill/engine/work/model/IdeStatusModelsTest.kt` uses the staying names through its own package without importing them. Those names are `IdeStatusPlanning`, `IdeStatusCurrentPhaseExecution`, `IdeStatusCurrentPhaseExecutionKind`, `IdeStatusCurrentModel`, `IdeStatusSnapshot`, `IdeStatusWorkflowFamily`, `IdeStatusLifecycleState`, `IdeStatusStep` and `IdeStatusFreshness`. Add explicit `skillbill.ports.idestatus.model.*` imports for each. The sibling `IdeStatusWireTestSupport.kt` already imports from ports. `engine/work/model` has no other main file.
- **runtime-core main:**
  - `di/goal/RuntimeGoalRunnerStoreProvides.kt` imports `GoalRunnerManifestStore`, `GoalRunnerWorkflowOutcomeStore`, `GoalRunnerChildRepairRunnerPort` and `GoalChildPlanningHydratorPort` from their engine packages.
  - `di/goal/RuntimeGoalRunnerLaunchProvides.kt` imports `GoalRunnerAttemptLedgerStore` and `GoalRunnerChildRepairStore` from engine, and keeps `GoalRunnerSubtaskLauncher` from ports.
  - Each `@Provides` keeps its body and returns the same implementation, so the kotlin-inject binding keys change only their package.
- **runtime-cli main:**
  - `cli/goal/core/GoalCliExitCodes.kt:15-16` imports `GoalRunnerRepairResult` and `GoalRunnerRepairStatus` from `skillbill.engine.goalrunner.model`.
  - `cli/goal/core/GoalCliFormatting.kt:6-9` imports `GoalRunnerResetSubtaskSnapshot`, `GoalRunnerAppliedRepair`, `GoalRunnerChildWedgeDiagnosis` and `GoalRunnerWedgeFinding` from `skillbill.engine.goalrunner.model`.
  - `GoalCliControlCommands.kt` and `WorkCliCommands.kt` already import engine names, which now resolve to declarations, so they need no edit. AC-005 then holds because no CLI main file imports any `skillbill.ports.goalrunner.*persistence*`, moved runner or moved ide-status name.
- **Import order:** keep imports in ktlint order (`*`, `java.**`, `javax.**`, `kotlin.**`, then aliases), and remove any import left unused by a split.

**T7. Give runtime-cli tests the engine test fixtures (AC-003).**
- Add `testImplementation(testFixtures(project(":runtime-engine")))` to `runtime-cli/build.gradle.kts`, next to the existing `testFixtures(project(":runtime-ports"))` line.
- `IdeStatusReadSnapshotConcurrencyTest` extends `GoalRunnerManifestStoreDefaults` and names `GoalRunnerManifestState`, and without this line it does not compile.
- `RuntimeAdapterDependencyAllowlistTest` counts only main configurations and `testFixturesImplementation`/`testFixturesApi` lines, so this test-scope edge changes no curated list.
- Relocating the test to runtime-core is SKILL-392's job, and it is out of scope here. If SKILL-392 has already moved the test, skip T7 and apply the import rewrite in the test's new location.

**T8. Update the pinned inbound API (AC-004).**
- In `runtime-core/src/repoTest/kotlin/skillbill/architecture/RuntimeEngineInboundApiTest.kt` `PINNED_ENGINE_INBOUND_API_TYPES`, add:
  - `skillbill.engine.goalrunner.model.GoalRunnerAppliedRepair`
  - `skillbill.engine.goalrunner.model.GoalRunnerChildWedgeDiagnosis`
  - `skillbill.engine.goalrunner.model.GoalRunnerWedgeFinding`
  - `skillbill.engine.goalrunner.model.GoalRunnerResetSubtaskSnapshot`
- Remove `skillbill.engine.work.model.IdeStatusSnapshot` and `skillbill.engine.work.model.IdeStatusProblemCode`. No application, CLI or MCP main file imports them.
- Keep `GoalRunnerRepairRequest`, `GoalRunnerRepairResult`, `GoalRunnerRepairStatus`, `IdeStatusRequest` and `IdeStatusResult`. Each now names a declaration.
- Make no other guard edit. `RuntimeEnginePublicTopLevelDeclarationArchitectureTest` flags only explicit `public` declarations outside model packages. The moved interfaces are default-public, and every moved model lands in an exempt model prefix.

**T9. Shrink the engine cycle baseline (supports AC-002).**
- After T6, no `skillbill.engine.featuretask` main file imports `skillbill.engine.work`. Its only such imports were the `IdeStatusCurrentPhaseExecution` aliases. That breaks the `featuretask|work` first-segment pair.
- Delete that one line from `runtime-core/src/repoTest/kotlin/skillbill/architecture/baselines/runtime-engine-package-cycle-baseline.txt`, so the pair cannot quietly return. Keep `featuretask|goalrunner`.
- The edit only shrinks the baseline. If validate reports `featuretask <-> work` as new, a featuretask main file still imports `skillbill.engine.work`, and that import must be found and fixed. Do not restore the row.

### Constraints

- **Mechanical change only.** No body, signature, default, `require` text, visibility or name changes, and no `Port` suffix is renamed. The design rules are: no `//` comments, KDoc only on interfaces (none of the moved declarations has KDoc), no new alias anywhere, no new module, dependency bag or architecture-test class, and no change to wire keys, persisted bytes, schemas or contract versions.
- **Every interface and role split stays.** The roles must stay split, because flattening them would need a detekt suppression that the gate forbids.
- **File names avoid spillover suffixes,** such as `Support`, `Helpers`, `Extras`, `Continued` or a digit suffix.
- **No new tests.** Each test file edit in this subtask is an import rewrite, the fixture relocation, or the pinned-list edit. No assertion changes. Under the test-value bar this subtask needs no new test, because the existing guards already pin the outcome:
  - the inbound API test's second case rejects a pinned name with no engine declaration
  - its first case rejects an unpinned engine type that CLI references
  - the package sibling-count test enforces the ceilings and the data-only and test-placement cases
  - the existing goal-runner and ide-status suites pin the output bytes required by AC-006
- **No added scanner.** The bundle's constraints allow scanner changes only in subtask 2, so AC-001 to AC-003 are checked by audit greps, not by a new guard.

### Verification hand-off (no commands run in this phase)

- **Audit checks:**
  - **AC-001:** grep runtime-ports main for each of the 56 names as a declaration and expect none. Grep runtime-engine main and expect one declaration of each, at the paths in the table.
  - **AC-002:** `grep -rn "^typealias" runtime-engine/src/main` finds no `skillbill.ports` target. No `const val IDE_STATUS_` exists in engine main.
  - **AC-003:** ports main does not declare `IdeStatusCandidate`. Ports testFixtures declares neither fixture, and engine testFixtures declares both.
  - **AC-005:** no runtime-cli main file imports the same simple name from both `skillbill.ports.` and `skillbill.engine.`.
- **The build phase** proves compilation and kotlin-inject resolution of the two core provider interfaces.
- **The validate phase** runs:
  - the runtime-ports, runtime-engine, runtime-cli and runtime-core suites, plus `:runtime-core:repoTest`
  - those suites cover AC-004 (inbound API, engine boundary and sibling-count tests) and AC-006 (`GoalRunnerRepairTest`, `GoalRunnerStopVerbTest`, `GoalHardResetCommitSpanRecoveryTest`, `GoalRunnerPurgeCoordinatorTest`, the `GoalRunnerTest` replan and reset cases, `IdeStatusService*Test`, `IdeStatusModelsTest`, `CliWorkStatusTest`, `IdeStatusReadSnapshotConcurrencyTest`).
- **Docs:** none of `../../../runtime-kotlin/ARCHITECTURE.md` or `docs/` names a moved file. The documentation update is subtask 2 (parent AC-12).
