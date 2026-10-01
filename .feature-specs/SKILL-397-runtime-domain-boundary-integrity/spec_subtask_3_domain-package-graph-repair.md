# SKILL-397 Subtask 3 - domain-package-graph-repair

Parent spec: [.feature-specs/SKILL-397-runtime-domain-boundary-integrity/spec.md](./spec.md)
Issue key: SKILL-397

## Scope

Mechanical package repair. (F-001) Empty runtime-kotlin/runtime-core/src/repoTest/kotlin/skillbill/architecture/baselines/runtime-domain-package-cycle-baseline.txt by breaking each cycle at its root. Move the 40 internal *_ARTIFACT_KEY constants into skillbill.workflow.engine.model, keeping them internal, so DurableWorkflowArtifactFamily imports no key from another package. Move selectedPlatformSlugs from install.policy into install.model. Move FeatureTaskRuntimeRequiredArtifactPresenceResolver from taskruntime.artifact and UpstreamPlanningProjectionSpec from taskruntime.phase.planning into taskruntime.phase.task. Keep one SHA-256 hex helper per input type (ByteArray, UTF-8 String) in skillbill.text, and delete the internal copy in taskruntime.model.repair and the private copy in ReviewLaneBundleAssembly. Resolve any remaining cycle edge the EXACT_PACKAGE_SCC scanner reports by moving declarations between domain packages within the sibling ceilings. (F-003) Flatten taskruntime.model.persistence.task.runtime.{checkpoint, goal, implementation, prior, run, store} into taskruntime.model.persistence. Fold handoff.envelope into handoff and repair.task into repair. (F-002) Delete the skillbill.goalrunner and skillbill.workflow.model.goalreview ceiling branches in ArchitectureScanSupport.kt (both the productionPackageSiblingCounts and packageSiblingCountViolationMessage copies), and split the two packages to at most 12 files each. Suggested: the AttemptLedger trio goes to goalrunner.ledger; the goalreview repair ledger and receipt files go to taskruntime.model.repair; the goalreview observability files go to workflow.model.goalobservability. (F-009) Refresh the ARCHITECTURE.md Package Ownership list for runtime-domain so it contains only packages that exist. Move the domain test package skillbill.workflow.goal beside its subject. Importers in other modules are rewritten to match; no alias is added.

## Acceptance Criteria

1. baselines/runtime-domain-package-cycle-baseline.txt records no SCC row.
2. All *_ARTIFACT_KEY constants of runtime-domain are declared internal in skillbill.workflow.engine.model, and DurableWorkflowArtifactFamily.kt imports no *_ARTIFACT_KEY from another package.
3. selectedPlatformSlugs is declared in skillbill.install.model. FeatureTaskRuntimeRequiredArtifactPresenceResolver and UpstreamPlanningProjectionSpec are declared in skillbill.workflow.taskruntime.phase.task. No package skillbill.workflow.taskruntime.phase.planning exists.
4. runtime-domain main declares SHA-256 hex hashing only in skillbill.text, and neither CorrectiveRepairRendering.kt nor ReviewLaneBundleAssembly.kt declares a sha256 function.
5. No runtime-domain package name contains persistence.task.runtime, handoff.envelope or repair.task.
6. ArchitectureScanSupport.kt contains no package-name-specific ceiling. skillbill.goalrunner and skillbill.workflow.model.goalreview each hold at most 12 main files, and packageSiblingCountRemainderInventory stays empty.
7. The runtime-domain rows of the ARCHITECTURE.md Package Ownership section name only packages that exist in runtime-domain main, and every runtime-domain test package matches a main package.
8. No typealias, baseline row or architecture exemption is added.

## Non-Goals

- Behaviour changes of any kind.
- Merging or splitting packages beyond what the cycle and sibling rules require.
- Resolving the skillbill.model split package shared with runtime-ports.
- Narrowing top-level visibility (SKILL-372 F-013).

## Dependency Notes

Depends on: 1, 2
Runs after subtasks 1 and 2, so the cycle and sibling scans judge the final file set, including the transitions added to skillbill.workflow.decomposition. Importer rewrites in engine, application, infra, mcp and cli follow the rule that whichever lands second rewrites the files present, with no alias.

## Validation Strategy

Goal gates: build, unit tests and the repoTest architecture suite, with the EXACT_PACKAGE_SCC scan passing against an empty runtime-domain baseline and the sibling scan passing without package-specific ceilings. No new test obligations; this is a move-only change.

## Next Path

skill-bill goal SKILL-397

## Spec Path

.feature-specs/SKILL-397-runtime-domain-boundary-integrity/spec_subtask_3_domain-package-graph-repair.md
