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
- Update the runtime-ports Gradle Modules entry in `runtime-kotlin/ARCHITECTURE.md` so it lists no removed item. It must also say that ports holds contracts crossing a module boundary.
- Add a `runtime-kotlin/agent/decisions.md` entry. It records that 2026-09-06 (b) is superseded for `LoadedDecompositionManifest` and `ValidatedDecompositionManifestYaml` (SQLite no longer reads them; only application does), and it records the new declaration rule with its reason: SKILL-233's cleanup regressed within 18 days.

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
10. `runtime-kotlin/ARCHITECTURE.md` and `runtime-kotlin/agent/decisions.md` describe the landed state as listed in scope.

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
- manifest discovery including a `.feature-specs/done/` bundle;
- projection-failure persistence leaving its artifact behind;
- a validator call using the wrong artifact kind after the forwarders go;
- the review evidence codec accepting a 33-item batch;
- the new declaration rule passing a repository-driving function (its synthetic fixtures).

Changed tests go through `skill-bill operation unit-test-value-check`.
