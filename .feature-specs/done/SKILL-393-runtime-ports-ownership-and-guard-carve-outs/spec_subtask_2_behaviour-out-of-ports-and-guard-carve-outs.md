# SKILL-393 subtask 2 - Behaviour out of ports and guard carve-outs removed

Parent: [spec.md](spec.md). Findings: F-002 to F-008 in [investigation.md](investigation.md).

## Scope

Behaviour relocation (F-002):

- `ports/goalrunner/GoalParentProjectionWriter.kt` moves to `skillbill.engine.goalrunner.manifest` and becomes `internal`. Its callers and its test are in runtime-engine. It may be `@Inject` and injected instead of being hand-built in `WorkflowGoalRunnerManifestStore`, but that is not required.
- `ports/workflow/decomposition/DecompositionManifestDiscovery.kt` (`loadDecompositionManifest`, `findMatchingDecompositionManifests`, `resolveDecompositionManifest`) moves to `skillbill.application.decomposition`.
- `ports/workflow/decomposition/WorkflowStateRepositoryParentDiscovery.kt` moves to `skillbill.application.workflow.decomposition`. `listFeatureTaskWorkflowsForParentDiscovery` becomes private.
- The two functions in `ports/workflow/decomposition/DecompositionManifestProjectionFailurePersistence.kt` fold into the existing `application/decomposition/DecompositionManifestProjectionFailurePersistence.kt`. Their result enum moves to `skillbill.application.decomposition.model`.
- The three files under `ports/workflow/decomposition/runtime/model/` move to `skillbill.application.decomposition.model`, and the package is deleted.
- Engine and application call sites import the new locations. Function bodies, `error(...)` messages, and transaction extents are unchanged.

Guard carve-outs (F-002):

- Delete the `GoalParentProjectionWriter.kt` exemption in `PortsDeclarationArchitectureTest.scanPortsMainSource`.
- Delete the `DecompositionManifestProjectionFailurePersistence.kt` exemption in `RuntimeLayerBoundaryArchitectureTest`'s "public model declarations live in model packages".
- Delete the `skillbill.ports.goalrunner.GoalParentProjectionWriter.artifacts` entry from `rawMapBoundaryAccessors` in `RuntimeArchitectureTestSupport.kt`.
- Regenerate `baselines/runtime-ports-package-cycle-baseline.txt` with `RECORD_ARCHITECTURE_BASELINES=1`. Expect it to be empty.

Interface without a boundary (F-003): delete `ports/decomposition/DecompositionManifestProjectionWriter.kt` and the `decompositionManifestProjectionWriter` provider in `di/workflow/RuntimeWorkflowProvides.kt`. Delete `DecompositionManifestWriter`'s `: DecompositionManifestProjectionWriter` supertype. `WorkflowGoalRunnerManifestProjectionPersistence`, `WorkflowGoalRunnerManifestStore`, `WorkflowGoalRunnerChildRepairStore`, and the engine testFixtures take `DecompositionManifestWriter`. Remove the `override` modifier on `writeProjectionFromWorkflowState`.

Forwarders (F-004): delete `ports/taskruntime/FeatureTaskRuntimeWireArtifactValidatorExtensions.kt`. Each of the 29 main call sites, and the test call sites, calls `validate(FeatureTaskRuntimeWireArtifactKind.<KIND>, FeatureTaskRuntimeWorkflowArtifactMap.from(payload), sourceLabel)`.

Declaration guard (F-005): extend `PortsDeclarationArchitectureTest` with two rules:

- A top-level function in runtime-ports main must not declare a parameter of type `UnitOfWork`, `GoalRunnerPersistenceSession`, `DatabaseSessionFactory`, `WorkflowEngine`, or a type whose simple name ends in `Repository` or `Store`.
- A top-level function must not have a receiver whose simple name ends in `Repository`.

Add synthetic fixtures for a `Repository` receiver and for a `UnitOfWork` or `*Store` parameter (both rejected), and for a derived extension over a `*Store` receiver whose parameters are plain values (accepted). The violation list stays empty. Extend ARCHITECTURE.md Boundary Rule 4 with the rule.

Constant default (F-006): make `ReviewAttributionPort.composedLaunchPlan` abstract. `EmptyReviewAttributionPort` (runtime-ports testFixtures) returns `ReviewLaunchPlan(routedPackSlug, emptyList())`. Keep the member's KDoc.

Constants (F-007):

- Delete `REVIEW_EVIDENCE_BATCH_SIZE` from `ports/review/model/ReviewEvidenceSourceModels.kt`, and the two launcher imports of it. The launcher's `internal const val REVIEW_EVIDENCE_BATCH_SIZE` in `GovernedReviewEvidenceRequestParsing.kt` is the owner.
- Move `INSTALLER_PROCESS_OUTPUT_CAP_BYTES` and `INSTALLER_OUTPUT_TRUNCATION_SENTINEL` into `runtime-infra/host` as `internal`, beside `BoundedExternalProcessRunner`/`BoundedExternalProcessOutput`. Host tests read them from there.

Companion census (F-008): the companion-`NONE` case of `PortNullObjectAbsenceArchitectureTest` scans runtime-ports main and runtime-engine main. Replace the single `COMPANION_VAL_MODULE` constant with a list of both.

Documentation:
- Update the runtime-ports Gradle Modules entry in `../../../runtime-kotlin/ARCHITECTURE.md` so it lists no removed item. It must also say that ports holds contracts crossing a module boundary.
- Add a `../../../runtime-kotlin/agent/decisions.md` entry. It records that 2026-09-06 (b) is superseded for `LoadedDecompositionManifest` and `ValidatedDecompositionManifestYaml` (SQLite no longer reads them; only application does), and it records the new declaration rule with its reason: SKILL-233's cleanup regressed within 18 days.

## Acceptance Criteria

1. runtime-ports main contains no `GoalParentProjectionWriter`, `DecompositionManifestDiscovery.kt`, `WorkflowStateRepositoryParentDiscovery.kt`, `DecompositionManifestProjectionFailurePersistence.kt`, or `workflow/decomposition/runtime/model` package. `GoalParentProjectionWriter` is internal to runtime-engine, and the moved functions and DTOs are declared in runtime-application.
2. `PortsDeclarationArchitectureTest` has no file exemption. `RuntimeLayerBoundaryArchitectureTest`'s model-package rule exempts no runtime-ports path. `rawMapBoundaryAccessors` names no `skillbill.ports.*` declaration. `runtime-ports-package-cycle-baseline.txt` is empty. The architecture suite passes.
3. `PortsDeclarationArchitectureTest` fails on a synthetic top-level function with a `*Repository` receiver, and on one with a `UnitOfWork` or `*Store` parameter. It passes a derived `*Store` extension with plain parameters, and reports no violation on the tree.
4. `DecompositionManifestProjectionWriter` exists in no source set, and no runtime-core provider returns it.
5. `FeatureTaskRuntimeWireArtifactValidatorExtensions.kt` does not exist, and no public runtime-ports function declares a parameter of type `Any`.
6. `ReviewAttributionPort.composedLaunchPlan` has no default body.
7. runtime-ports main declares neither `REVIEW_EVIDENCE_BATCH_SIZE`, `INSTALLER_PROCESS_OUTPUT_CAP_BYTES`, nor `INSTALLER_OUTPUT_TRUNCATION_SENTINEL`. Each value has exactly one declaration in its adapter module.
8. The companion-`NONE` census reads runtime-ports main and runtime-engine main, and passes.
9. These outputs are byte-identical to baseline under the existing suites: parent-discovery results and ambiguity messages, manifest discovery with archived bundles excluded, projection-failure artifact writes and clears, goal-parent artifact projection, the governed review evidence schema `maxItems`, the installer truncation text, and CLI/MCP workflow output.
10. `../../../runtime-kotlin/ARCHITECTURE.md` and `runtime-kotlin/agent/decisions.md` describe the landed state as listed in scope.

## Non-Goals

- Typing the `error(...)` ambiguity failures, or changing their messages.
- Moving `toSnapshot`/`toRecord`, `decodeManifest`/`encodeManifestWireMap`, or `writeTelemetryLevel`.
- The eleven domain entries in `rawMapBoundaryAccessors`.
- Exact-package cycle mode for runtime-ports.
- Any change under F-001 (subtask 1).

## Dependency Notes

Runs after subtask 1, for branch order and so the guards see a tree without the engine-owned contracts. It does not wait for another issue. If SKILL-388 has already changed `application/decomposition`, keep that package at 12 files or fewer by placing the discovery functions in an existing file of that package rather than adding one. If SKILL-389 has already edited `RuntimeRawMapArchitectureTest.kt`, the allow-list edit here is in `RuntimeArchitectureTestSupport.kt` and does not conflict. If SKILL-396 has edited the host process runner or the launcher review codec, keep both edits.

## Validation Strategy

Build compiles every module. The risks are kotlin-inject resolving `DecompositionManifestWriter` directly, and engine seeing the moved application functions. Validate runs the runtime-application, runtime-engine, runtime-core, runtime-cli, runtime-ports, and runtime-infra launcher, host, workflow, and contracts suites, `:runtime-core:repoTest`, and the routed pack quality gate. The regressions to catch:

- parent discovery choosing another parent or losing its ambiguity error;
- manifest discovery including a `..` bundle;
- projection-failure persistence leaving its artifact behind;
- a validator call using the wrong artifact kind after the forwarders go;
- the review evidence codec accepting a 33-item batch;
- the new declaration rule passing a repository-driving function (its synthetic fixtures).

Changed tests go through `skill-bill operation unit-test-value-check`.

## Implementation Details

The plan was censused at HEAD `2815b2064`, before subtask 1 landed. Implementation runs after subtask 1, so it starts by re-running each census grep named below and applies the tasks to the tree it finds. Where subtask 1 has moved a type that this plan imports, such as `GoalRunnerPersistenceSession` into runtime-engine, use subtask 1's location. Paths are relative to `../../../runtime-kotlin`. Ports main means `runtime-ports/src/main/kotlin/skillbill/ports`.

Every task follows these constraints:

- Copy moved bodies, `error(...)` strings, `InvalidDecompositionManifestSchemaError` reasons and failure codes, and transaction extents byte for byte. Change only `package` and `import` lines (AC-009).
- No `//` comments. Keep existing KDoc. Import names instead of using inline FQNs, because `InlineFqnArchitectureTest` rejects them.
- Add no module, Gradle edge, dependency bag, architecture-test class, or baseline row. Engine already has `implementation(project(":runtime-application"))`, and runtime-core, runtime-cli and the engine tests already see runtime-application.
- Do not compile or run tests here. The build phase proves compilation; the validate phase runs the suites and `:runtime-core:repoTest`.

### Task 1: move the decomposition DTOs into application (AC-001, AC-009)

- Move `workflow/decomposition/runtime/model/DecompositionManifestFileCandidate.kt`, `DecompositionManifestFileModels.kt` and `DecompositionManifestWriteModels.kt` from ports main to `runtime-application/src/main/kotlin/skillbill/application/decomposition/model/`, using the same file names. Change the package to `skillbill.application.decomposition.model`.
- `DecompositionManifestWriteModels.kt` keeps its imports of the ports `DecompositionManifestStore` and `DecompositionManifestValidator`. Those interfaces stay in ports.
- Delete the empty `workflow/decomposition/runtime/model` and `workflow/decomposition/runtime` directories.
- Rewrite every `skillbill.ports.workflow.decomposition.runtime.model.*` import to `skillbill.application.decomposition.model.*`. The census found these files:
  - application main: `decomposition/DecompositionManifestWriter.kt`, `DecompositionManifestFileWrites.kt`, `DecompositionManifestRuntimeState.kt`, `DecompositionManifestRuntimeStateDerivation.kt`, and `workflow/service/WorkflowServiceDecompositionRuntime.kt`
  - application testFixtures: `TestDecompositionManifestStore.kt`
  - application tests: `DecompositionManifestNestedProjectionTest`, `DecompositionManifestProjectionOutcomeTest`, `DecompositionManifestPayloadProjectionTest`, `DecompositionManifestWriterTest`, `DecompositionManifestRuntimeStateSupportTest`, `DecompositionDiskBootstrapTest`
  - engine main: `featuretask/prepare/FeatureSpecPreparationWriter.kt`, `decomposition/DecompositionManifestEngineEncoding.kt`
  - engine tests: `skillbill/application/DecompositionManifestCommitProjectionTest`, `WorkflowServiceTest`, `featuretask/prepare/FeatureSpecPreparationWriterTest`, `SpecSourceResolverTest`
  - core tests: `di/workflow/DecompositionManifestWriterValidationTest`
- Re-grep `LoadedDecompositionManifest|ValidatedDecompositionManifestYaml|DecompositionManifestWriteRequest|DecompositionManifestRuntimeUpdate|DecompositionPlanManifestInput|DecompositionManifestWorkflowProjectionInput|DecompositionManifestFileCandidate` before finishing. No `runtime-infra` main file may reference these names. The two `runtime-infra/contracts` tests import only `skillbill.application.decomposition.*` functions, so they need no change.

### Task 2: move manifest discovery into `skillbill.application.decomposition` (AC-001, AC-009)

- Move `workflow/decomposition/DecompositionManifestDiscovery.kt` (`loadDecompositionManifest`, `findMatchingDecompositionManifests`, `resolveDecompositionManifest`, private `archivedDecompositionManifest`) to `runtime-application/src/main/kotlin/skillbill/application/decomposition/DecompositionManifestDiscovery.kt`.
- The three functions stay public, because engine main calls them. Add imports for `DecompositionManifestStore` and `DecompositionManifestValidator` from ports, and for `DecompositionManifestFileCandidate` from the application model package.
- This makes the package 12 files. If implementation finds 12 or more existing files, fold the code into `DecompositionManifestPaths.kt` instead of adding a file.
- `DecompositionManifestWriter.kt` and `DecompositionManifestRuntimeState.kt` are now in the same package, so drop their imports.
- Rewrite imports to `skillbill.application.decomposition.*` in:
  - application: `workflow/decomposition/DecompositionWorkflowContinuation.kt`, testFixtures `TestDecompositionManifestStore.kt`, test `DecompositionManifestWriterTest`
  - engine main: `goalrunner/reset/GoalRunnerPurgeCoordinator.kt`, `goalrunner/manifest/WorkflowGoalRunnerManifestLoader.kt`, `goalrunner/preflight/GoalPreflightService.kt`
  - engine tests: `DecompositionManifestCommitProjectionTest`, `goalrunner/reset/GoalRunnerPurgeCoordinatorTest`, `featuretask/prepare/FeatureSpecPreparationWriterTest`
  - core tests: `featurespec/FeatureSpecPreparationWriterValidationTest`, `di/workflow/ApplicationPersistencePortTestSupport`
  - CLI tests: `CliGoalPurgeCommandTest`

### Task 3: move parent discovery into `skillbill.application.workflow.decomposition` (AC-001, AC-009)

- Move `workflow/decomposition/WorkflowStateRepositoryParentDiscovery.kt` to `runtime-application/src/main/kotlin/skillbill/application/workflow/decomposition/WorkflowStateRepositoryParentDiscovery.kt`. That package goes from 7 to 8 files.
- Make `listFeatureTaskWorkflowsForParentDiscovery` `private`. Its only callers are in this file.
- `findDecomposedParentOrCorruptFallback`, `findDecomposedParentWorkflow` and `findDecomposedParentWorkflowForRuntime` stay public, because engine calls them.
- The private candidate types and helpers move unchanged. The imports of the ports `WorkflowStateRepository`, `WorkflowStateRecord` and `toSnapshot` stay.
- These consumers are now in the same package, so drop their imports: `PendingDecompositionProjection.kt`, `DecompositionWorkflowContinuation.kt`, `DecompositionWorkflowRuntimeLookupGoalContinuation.kt`, and the test `DecompositionDiskBootstrapTest`.
- Rewrite imports to `skillbill.application.workflow.decomposition.*` in:
  - application `workflow/service/WorkflowServiceDecompositionRuntime.kt`
  - engine main: `goalrunner/reset/WorkflowGoalRunnerChildWorkflowPersistence.kt`, `goalrunner/manifest/WorkflowGoalRunnerManifestProjectionPersistence.kt`, `WorkflowGoalRunnerManifestLoader.kt`, `goalrunner/status/GoalRunnerControlCoordinator.kt`
  - engine test `WorkflowServiceTest`

### Task 4: fold projection-failure persistence into application (AC-001, AC-009)

- Move the enum `DecompositionManifestProjectionFailurePersistence` (`PERSISTED`, `OWNER_ABSENT`) to a new `runtime-application/src/main/kotlin/skillbill/application/decomposition/model/DecompositionManifestProjectionFailurePersistence.kt`. With Task 1 the model package has 7 files.
  - detekt `MatchingDeclarationName` requires the file to be named after the enum.
  - It shares a base name with the function file in the parent package. No duplicate-basename guard exists.
- Append `persistDecompositionManifestProjectionFailure` and `clearDecompositionManifestProjectionFailure` to the existing `application/decomposition/DecompositionManifestProjectionFailurePersistence.kt`, unchanged and public, below `retryDecompositionManifestProjectionFromAuthoritativeState`. Engine calls them.
- In that file:
  - Replace the two ports imports with the application model enum import.
  - Add imports of `skillbill.ports.persistence.UnitOfWork`, `skillbill.contracts.decomposition.DecompositionManifestProjectionFailurePayloadKeys`, `skillbill.workflow.engine.WorkflowEngine`, and `DurableWorkflowArtifactFamily`, `WorkflowArtifactPatch` and `WorkflowUpdateInput` from `skillbill.workflow.engine.model`.
- Delete the ports file.
- Rewrite imports in:
  - application `workflow/service/WorkflowService.kt` and `WorkflowServiceBlockedPhaseRetry.kt`
  - engine `goalrunner/manifest/WorkflowGoalRunnerManifestProjectionPersistence.kt` and `goalrunner/repair/WorkflowGoalRunnerChildRepairStore.kt`
  - engine test `DecompositionManifestCommitProjectionTest`
- This removes the only `skillbill.ports.workflow.*` → `skillbill.ports.persistence` import in ports main. That import is the cause of the `persistence|workflow` cycle row.

### Task 5: make `GoalParentProjectionWriter` internal to engine (AC-001, AC-002)

- Move `ports/goalrunner/GoalParentProjectionWriter.kt` to `runtime-engine/src/main/kotlin/skillbill/engine/goalrunner/manifest/GoalParentProjectionWriter.kt` as `internal class GoalParentProjectionWriter`. Keep the constructor and both methods unchanged.
- Import `GoalRunnerPersistenceSession` from wherever subtask 1 put it. Keep the ports imports of `DecompositionManifestValidator`, `encodeManifestWireMap`, `WorkflowFamily` and `toRecord`.
- Keep it hand-built in `WorkflowGoalRunnerManifestStore` (`private val parentProjection = GoalParentProjectionWriter(...)`). Do not inject it: a public `@Inject` constructor cannot take an internal type.
- Drop the import in the same-package `WorkflowGoalRunnerManifestStore`, `WorkflowGoalRunnerManifestLoader` and `WorkflowGoalRunnerManifestProjectionPersistence`.
- Rewrite the import in `goalrunner/reset/WorkflowGoalRunnerChildWorkflowPersistence.kt` and in the engine test `FeatureTaskExecutionPlanCreationTest`. Both are in the engine module, so `internal` is visible.
- Before finishing, grep `GoalParentProjectionWriter` to confirm that no non-internal engine signature exposes it.
- After the move:
  - `manifest` has at most 9 files, depending on what subtask 1 added.
  - The ports `goalrunner` root holds no `GoalParentProjectionWriter.kt`.
  - The raw-map guard treats the internal class as non-public, so the allow-list entry can go (Task 10).

### Task 6: delete `DecompositionManifestProjectionWriter` (AC-004)

- Delete `ports/decomposition/DecompositionManifestProjectionWriter.kt` and the empty `ports/decomposition` directory.
- In `application/decomposition/DecompositionManifestWriter.kt`, remove the `: DecompositionManifestProjectionWriter` supertype, its import, and the `override` modifier on `writeProjectionFromWorkflowState`. Keep the `@Inject` no-argument constructor.
- In `runtime-core/src/main/kotlin/skillbill/di/workflow/RuntimeWorkflowProvides.kt`, delete the `decompositionManifestProjectionWriter` provider and its import. kotlin-inject resolves the `@Inject` concrete class directly.
- Change the constructor parameter type to `skillbill.application.decomposition.DecompositionManifestWriter` in:
  - `engine/goalrunner/manifest/WorkflowGoalRunnerManifestProjectionPersistence.kt`
  - `WorkflowGoalRunnerManifestStore.kt`
  - `engine/goalrunner/repair/WorkflowGoalRunnerChildRepairStore.kt`
  - the two parameters in engine testFixtures `goalrunner/persist/WorkflowGoalRunnerStoreTestFixtures.kt`
- Grep `DecompositionManifestProjectionWriter` across every source set. It must return nothing.

### Task 7: delete the wire-artifact forwarders (AC-005)

- Delete `ports/taskruntime/FeatureTaskRuntimeWireArtifactValidatorExtensions.kt`. `validateSharedEvidenceProjection` has no caller.
- Replace each call with `validate(FeatureTaskRuntimeWireArtifactKind.<KIND>, FeatureTaskRuntimeWorkflowArtifactMap.from(<payload>), <label>)`. Import both types from `skillbill.workflow.taskruntime.model.core` and remove the `skillbill.ports.taskruntime.validate*` imports.
- Each kind must match the forwarder it replaces. The census found these sites:

  | Forwarder | Kind | Call sites |
  | --- | --- | --- |
  | `validateGoalObservabilityEvent` | `GOAL_OBSERVABILITY_EVENT` | `application/workflow/persist/WorkflowServiceInputMapping.kt:211`; `engine/goalrunner/persist/WorkflowGoalRunnerProgressRecording.kt:130` |
  | `validateGoalProgressEvent` | `GOAL_PROGRESS_EVENT` | `WorkflowGoalRunnerProgressRecording.kt:160` |
  | `validateGoalPlanningPreparationEnvelope` | `GOAL_PLANNING_PREPARATION_ENVELOPE` | `engine/goalplanning/GoalPlanningPreparationCheckpoint.kt:35, 46, 216, 223` |
  | `validateQuarantineRecord` | `QUARANTINE_RECORD` | `engine/featuretask/phase/record/FeatureTaskRuntimePhaseEvidenceRecorder.kt:95, 234` |
  | `validateImplementationAttemptRecord` | `IMPLEMENTATION_ATTEMPT` | `FeatureTaskRuntimePhaseStateRecorder.kt:279` |
  | `validateEnvelope` | `HANDOFF_ENVELOPE` | `engine/featuretask/phase/briefing/FeatureTaskRuntimePhaseBriefingRecorder.kt:50, 127, 138, 258` |
  | `validatePersistenceRecord` | `HANDOFF_PERSISTENCE_RECORD` | `FeatureTaskRuntimePhaseBriefingRecorder.kt:62, 140, 261` |
  | `validateDeclaration` | `HANDOFF_DECLARATION` | `FeatureTaskRuntimePhaseBriefingRecorder.kt:114` |
  | `validateMeasurement` | `HANDOFF_MEASUREMENT` | `FeatureTaskRuntimePhaseBriefingRecorder.kt:192, 249` |
  | `validateBuildReceipt` | `BUILD_RECEIPT` | `engine/featuretask/runloop/qualitygate/PackBuildGateCycle.kt:243` |

- The two observability sites pass a method reference to a `(Any, String) -> Unit` parameter. Replace each with the lambda `{ event, sourceLabel -> <validator>.validate(GOAL_OBSERVABILITY_EVENT, FeatureTaskRuntimeWorkflowArtifactMap.from(event), sourceLabel) }`.
- `validateEnvelope` used the label `workflowId ?: "handoff-envelope"`:
  - Line 258 (`workflowId = null`) passes the literal `"handoff-envelope"`.
  - Lines 50, 127 and 138 pass `workflowId` if it is non-null at that site. Otherwise they pass `workflowId ?: "handoff-envelope"`, so the label bytes stay the same.
- The internal `validateEnvelopeWire` and `validatePersistenceWire` helpers at lines 257-261 stay. Only their bodies change.
- The test `runtime-infra/contracts/src/test/.../FeatureTaskRuntimeHandoffEnvelopeSchemaValidatorTest.kt` has 7 `validateEnvelope` calls.
  - Add one private helper in that file: `validateEnvelope(validator, envelope, label = "handoff-envelope")`, which calls `validate(HANDOFF_ENVELOPE, from(envelope), label)`.
  - Remove the ports import. The test's assertions do not change.
- Before finishing, re-grep `\.validate(QuarantineRecord|ImplementationAttemptRecord|BuildReceipt|Declaration|PersistenceRecord|Measurement|SharedEvidenceProjection|Envelope|GoalProgressEvent|GoalObservabilityEvent|GoalPlanningPreparationEnvelope)\(` and `::validate[A-Z]`. Neither may match.
- After the delete, `grep -rn ": Any" ports main` may match only the private `WorkflowRecordMapping.decodeStep` and `parseJson` and `WorkflowArtifactTimestampMapping.preserveTimestampFields`.

### Task 8: make `composedLaunchPlan` abstract (AC-006)

- In `ports/review/preparation/ReviewAttributionPort.kt`, change `fun composedLaunchPlan(routedPackSlug: String): ReviewLaunchPlan` to abstract and keep its KDoc. `knownPackSkillNames` and `knownPlatformSlugs` are derived defaults, so they stay.
- In `runtime-ports/src/testFixtures/kotlin/skillbill/ports/review/EmptyReviewAttributionPort.kt`, add `override fun composedLaunchPlan(routedPackSlug: String): ReviewLaunchPlan = ReviewLaunchPlan(routedPackSlug, emptyList())`.
- `FileSystemReviewAttribution`, `ReviewServicePreviewImportTest.PreviewImportReviewAttribution`, and the core test objects `FakePlanReviewAttributionPort` and `ThrowingPlanReviewAttributionPort` already override it.

### Task 9: give the constants one owner each (AC-007, AC-009)

- Delete `const val REVIEW_EVIDENCE_BATCH_SIZE` from `ports/review/model/ReviewEvidenceSourceModels.kt`.
- Delete the `import skillbill.ports.review.model.REVIEW_EVIDENCE_BATCH_SIZE` line from `runtime-infra/launcher/.../review/GovernedReviewEvidenceRequestParsing.kt` and `GovernedReviewEvidenceCodecWireSchemas.kt`.
  - That import currently shadows the same-package `internal const val REVIEW_EVIDENCE_BATCH_SIZE: Int = 32`. Without it, both files resolve to the launcher declaration.
  - `maxItems` stays 32.
- Delete `INSTALLER_PROCESS_OUTPUT_CAP_BYTES` and `INSTALLER_OUTPUT_TRUNCATION_SENTINEL` from `ports/process/InstallerProcessPort.kt`. Remove any import that becomes unused.
- Declare both as `internal const val` in `runtime-infra/host/src/main/kotlin/skillbill/infrastructure/host/process/BoundedExternalProcessOutput.kt`. The sentinel text stays byte-identical: `"\n...[installer output truncated at $INSTALLER_PROCESS_OUTPUT_CAP_BYTES bytes]..."`.
- Remove the ports imports from `BoundedExternalProcessRunner.kt`, `BoundedExternalProcessOutput.kt`, and the host test `InstallerProcessAdapterLifetimeTest.kt`. All three are in the same package and module, so the internal declarations resolve.
- Grep each name across all source sets. Each must have exactly one `const val`.

### Task 10: extend the declaration guard and delete the carve-outs (AC-002, AC-003)

Edit `runtime-core/src/repoTest/kotlin/skillbill/architecture/PortsDeclarationArchitectureTest.kt`.

**Remove the exemption.** Delete the `GoalParentProjectionWriter.kt` `if` in `scanPortsMainSource`, so `forbiddenTopLevelClassViolations` runs on every file. Add `addAll(repositoryDrivingFunctionViolations(fileName, source))`.

**Add `private fun repositoryDrivingFunctionViolations(fileName: String, source: String): List<String>`.** It follows the column-0 convention:

- A top-level function starts at column 0 and matches `^(?:(?:public|internal|private|inline|suspend|operator|infix|tailrec)\s+)*fun\s+`. Skip `fun interface` lines.
- Collect the header from `fun` through the `)` that closes the parameter list, counting paren depth across lines so multi-line signatures parse.
- Parse the header with `fun\s+(?:<[^>]*>\s+)?(?:(receiver)\.)?(\w+)\s*\(`.
- Split the parameter list on commas at depth 0, counting `<>` and `()`. A parameter's type is the text after its first `:`, up to a depth-0 `=`.
- A type's simple name drops generic arguments and `?`, then takes the last `.` segment. Skip function-typed parameters, which start with `(`.

It reports two violations:

- `"$fileName: fun <name> has a *Repository receiver"` when the receiver's simple name ends with `Repository`.
- `"$fileName: fun <name> takes <param>: <Type>"` when a parameter's simple name is `UnitOfWork`, `GoalRunnerPersistenceSession`, `DatabaseSessionFactory` or `WorkflowEngine`, or ends with `Repository` or `Store`.

A `*Store` receiver is allowed. On the tree, after Tasks 2 to 4 and 7, the remaining top-level receivers are:

- `FileLocation`, `Path`, `WorkflowStateSnapshot`, `WorkflowStateRecord`
- `SkillRunRequest`, `AgentRunLaunchFacts`, `ProducerOutputEvidence`
- `FeatureTaskRuntimeProcessInspection`, `ReviewFinishedTelemetry`
- `DecompositionManifestValidator`, `TelemetryConfigStore`

`parseFeatureTaskRuntimeWorkerLeaseInstant` has no receiver. None of these trips a rule, so the live test keeps an empty violation list.

**Add one `@Test`: `the repository-driving function check rejects repository receivers and store parameters`.** Assert exact lists:

- (a) `fun WorkflowStateRepository.findParent(issueKey: String): String? = null` is rejected for its receiver.
- (b) A multi-line `fun persistFailure(\n  engine: WorkflowEngine,\n  unitOfWork: UnitOfWork,\n  workflowId: String,\n): Boolean` is rejected once for each forbidden parameter.
- (c) `fun loadManifest(path: Path, fileStore: DecompositionManifestStore): String` is rejected for the `*Store` parameter.
- (d) `fun TelemetryConfigStore.writeTelemetryLevel(level: String): Boolean {` together with a `fun interface ProbeStore {` line returns `emptyList()`.

Each fixture catches a realistic bug:

- (a) the SKILL-233 regression shape
- (b) a scanner that reads single lines only and misses multi-line signatures
- (c) a store-driving function returning to ports
- (d) a rule over-broad enough to reject derived `*Store` extensions or `fun interface` declarations

**Delete the other carve-outs.**

- In `RuntimeLayerBoundaryArchitectureTest.kt`'s `public model declarations live in model packages`, delete the `DecompositionManifestProjectionFailurePersistence.kt` exemption at lines 338-340. Keep the domain `FeatureTaskRuntimeCommitPushResultArtifact.kt` exemption.
- In `RuntimeArchitectureTestSupport.kt`'s `rawMapBoundaryAccessors`, delete the `"skillbill.ports.goalrunner.GoalParentProjectionWriter.artifacts"` entry. The 10 non-ports entries stay. Domain owns them, and they are a Non-Goal.
- Hand-edit `runtime-core/src/repoTest/kotlin/skillbill/architecture/baselines/runtime-ports-package-cycle-baseline.txt` to a single `\n`. That is the 1-byte empty form the other empty baselines use.
  - This replaces the scope's `RECORD_ARCHITECTURE_BASELINES=1` step, which runs the repo suite. That suite belongs to the validate phase.
  - Validate's `:runtime-core:repoTest` proves the empty baseline.

### Task 11: extend the companion census to ports (AC-008)

In `PortNullObjectAbsenceArchitectureTest.kt`:

- Replace `const val COMPANION_VAL_MODULE` with `val COMPANION_VAL_MODULES: List<String> = listOf("runtime-kotlin/runtime-ports", "runtime-kotlin/runtime-engine")`.
- `testOnlyCompanionNullObjects()` builds `declaringSources` from each module's `src/main`, using `kotlinFilesUnderWithArchitectureAsserts` so a mistyped root fails loudly instead of scanning nothing. `mainSources` is unchanged.
- The 7 ports `NONE` vals (`AgentRunWorktreeEditObserver`, `AgentRunProgressProbe`, `AgentRunDeclaredProgressProbe`, `AgentRunMcpStartupProbe`, `AgentRunProgressEmitter`, `AgentRunOutputSink`, `AgentRunActivityStampSink`) each have main-source references, so the census is expected to pass.

### Task 12: documentation (AC-010)

`../../../runtime-kotlin/ARCHITECTURE.md`:

- **Gradle Modules runtime-ports entry (~L403-411).**
  - State that runtime-ports holds only contracts that cross a module boundary: interfaces and DTOs implemented or consumed in more than one module, plus derived extensions on its own types.
  - List no removed item: no goal-parent projection, decomposition discovery, parent discovery or projection-failure persistence, and no goal-runner store contracts if subtask 1 has not already reworded the entry.
  - Say that repository-driving behaviour lives in runtime-application or runtime-engine.
- **Boundary Rule 4 (~L920-926).** Add the function rule: no top-level function with a `*Repository` receiver, or with a `UnitOfWork`, `GoalRunnerPersistenceSession`, `DatabaseSessionFactory`, `WorkflowEngine`, `*Repository` or `*Store` parameter. `PortsDeclarationArchitectureTest` enforces it.
- **Validator paragraph (~L1531-1539).** Correct it:
  - The validator ports are `skillbill.ports.taskruntime.FeatureTaskRuntimeWireArtifactValidator` and `skillbill.ports.workflow.decomposition.DecompositionManifestValidator` in runtime-ports.
  - Callers name the closed `FeatureTaskRuntimeWireArtifactKind` explicitly.
  - Drop the type-alias and extension-helper sentences.
- **Installer sentinel mention (~L849).** If it names a ports owner, say that the cap and sentinel are `internal` to `runtime-infra/host` `skillbill.infrastructure.host.process`.

`../../../runtime-kotlin/agent/decisions.md`: add one dated entry, in the existing "supersedes X only for Y" form. Recheck the date at write time. The entry records:

- **The supersession.** It supersedes `[2026-09-06] SKILL-233 subtask 2 audit round 3` decision (b) only for `LoadedDecompositionManifest` and `ValidatedDecompositionManifestYaml`. The evidence: no `runtime-infra/sqlite` file reads them. Their readers are application main and tests, engine main (`FeatureSpecPreparationWriter`, `DecompositionManifestEngineEncoding`), and core and engine tests. They now live in `skillbill.application.decomposition.model`, and the `WorkflowRecordMapping` half of (b) stands.
- **The new declaration rule and its reason.** SKILL-233's ports cleanup regressed within 18 days, when SKILL-372 moved repository-driving functions back into ports behind file exemptions.
- **What else moved.** `GoalParentProjectionWriter` is engine-internal, the projection-writer interface and the wire-artifact forwarders are deleted, and the adapter constants have single owners.

### Self-checks for implement (greps only)

- **AC-001:** ports main has none of `GoalParentProjectionWriter`, `DecompositionManifestDiscovery.kt`, `WorkflowStateRepositoryParentDiscovery.kt`, `DecompositionManifestProjectionFailurePersistence.kt`, or `workflow/decomposition/runtime`.
- **AC-002:** no `skillbill.ports.` entry in `rawMapBoundaryAccessors`, and no `runtime-ports` or `skillbill/ports/` path literal in the two guards' exemption conditions.
- **AC-004 and AC-005:** greps of every source set.
- **AC-007:** one `const val` per constant.
- Leave `unit-test-value-check` and all test execution to validate.

### Test obligations

- **Add:** only the synthetic fixtures in Task 10 (AC-003).
- **Mechanical edits:** the test changes in Tasks 1-9 are import rewrites, two method-reference-to-lambda changes, one private test helper, and one fixture override. They change no assertion.
- **No new tests:**
  - F-006, F-007 and F-008 change no behaviour.
  - The 32-item `maxItems` and 33-item rejection, the installer truncation text, parent-discovery ambiguity and stale lineage, archived `..` exclusion, projection-failure clear, and wrong-kind validation all have coverage in existing launcher, host, application, engine and contracts suites. Validate runs those suites.
- **No test is removed or weakened.**
- **Parent AC 4 wording ("no top-level non-DTO class"):** the private `ReviewFinishedTelemetryPayloadContract` class in `ports/telemetry/model/ReviewFinishedTelemetryPayload.kt` is a SKILL-358/377 retention. The class guard skips private classes, so it stays and is not a violation.
