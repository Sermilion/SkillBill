# SKILL-390 Subtask 3 - engine-test-packages-mirror-main

Parent spec: [.feature-specs/SKILL-390-runtime-engine-architecture/spec.md](./spec.md)
Issue key: SKILL-390

## Scope

Covers F-009 from the parent overview. This is a mechanical move inside runtime-engine/src/test, plus the PrincipleEnforcementInventory path pins that name engine test files (PrincipleEnforcementInventory.kt:144,150 pin FeatureTaskRuntimeRunnerTestSupport.kt).

Move each file in the five orphan packages to the main package whose code it exercises:
- `skillbill.engine`: 70 files;
- `skillbill.engine.featuretask.slotbaseline`: 17;
- `skillbill.engine.operation`: 3;
- `skillbill.application`: 1 (DecompositionManifestCommitProjectionTest);
- `skillbill.engine.featuretask.lifecycle`: 1 (FeatureTaskRuntimeCompletedUpstreamRepairArtifactBytesTest).

Update the package and import lines, and move any test resources that are looked up by package-relative path together with their tests.

## Acceptance Criteria

1. Every package declared by a Kotlin file under runtime-kotlin/runtime-engine/src/test/kotlin is also declared by a Kotlin file under runtime-kotlin/runtime-engine/src/main/kotlin, unless its last segment is `testsupport` or `testing`.
2. No Kotlin file under runtime-kotlin/runtime-engine/src/test/kotlin declares `package skillbill.engine`, `skillbill.engine.featuretask.slotbaseline`, `skillbill.engine.operation`, `skillbill.application` or `skillbill.engine.featuretask.lifecycle`.
3. Every engine test file path in PrincipleEnforcementInventory names a file that exists.
4. The subtask changes no file under runtime-engine/src/main. Moved test files differ from their originals only in their path, package line and import lines.

## Non-Goals

- Adding, deleting, splitting or rewriting tests.
- Adding an architecture guard for orphan test packages. The 2026-09-25 decision rejected one.
- Moving tests of other modules.

## Dependency Notes

Depends on: 2
Depends on subtask 2, which edits FeatureTaskRuntimeRunnerTestSupport and the gate-building tests that this subtask moves. It waits for no other issue: move the test files present when it runs. Whichever bundle lands second rewrites package and import lines in the files present, including the six engine/operation tests whose imports SKILL-391 points at `skillbill.engine.operation.core`. Before moving, check the slotbaseline capture and golden lookups for package-relative resource paths.

## Validation Strategy

Build compiles the runtime-engine test and testFixtures source sets and runtime-core repoTest. Validate runs the full check. The engine test count must match the count after subtask 2, and the slotbaseline captures must find their goldens at the moved paths. runtime-core repoTest guards that read PrincipleEnforcementInventory paths must still resolve files. detekt and spotless also run.

## Implementation Details

Use the upstream preplan digest as the planning evidence. Implement this mechanical subtask after subtask 2, without changing either dependency's work. The digest reports 252 engine test Kotlin files and 91 orphan files, including 69 in `skillbill.engine`. These figures supersede the older 92-file estimate for implementation; the existing Scope remains unchanged. Preserve the post-subtask-2 test census, accounting only for intentional test additions in the preceding subtasks.

In the tasks below, `T` is `runtime-kotlin/runtime-engine/src/test/kotlin/skillbill/engine/`, and destination package suffixes follow `skillbill.engine.`. Destination paths under `T` must mirror those suffixes. All destinations below are existing production packages according to preplan. Do not edit `runtime-kotlin/runtime-engine/src/main`, create production packages, add tests or guards, change test bodies, or move tests into another module. Preserve names, visibility, declarations, assertions, fixtures, resource contents, and persisted output. Only moved paths, package lines, and import lines may differ in moved Kotlin files.

1. Establish the mechanical move inventory for AC-001, AC-002, and AC-004. At implementation time, record the engine test-file census after subtask 2 and pair each orphan file with the destination below before moving it. Use the current files after the semantic repairs as the comparison baseline. The assumption for implement to confirm is that preceding subtasks preserve the digest's ownership destinations and change the census only through intentional test additions. Any such added orphan test belongs beside its existing test family in the corresponding production package. Do not rework constructors or repair semantic code in this subtask. Compare each moved file against its baseline after excluding package and import lines, and require all remaining content to match.

2. Move the small orphan families for AC-001, AC-002, and AC-004. Move `Toperation/ChecklistOperationFixtureTest.kt`, `ChecklistOperationsTest.kt`, and `ChecklistOperationHarness.kt` into `operation.core`, preserving their landed operation-error imports. Move `runtime-kotlin/runtime-engine/src/test/kotlin/skillbill/application/DecompositionManifestCommitProjectionTest.kt` into `goalrunner.manifest` under `T`, retaining runtime-engine ownership. Move `Tfeaturetask/lifecycle/FeatureTaskRuntimeCompletedUpstreamRepairArtifactBytesTest.kt` into `featuretask.lifecycle.subtask`. Later validation runs the existing checklist, manifest-projection, and upstream-repair artifact-byte tests. Their realistic risks are unresolved helper references and changed artifact expectations after package relocation; their test bodies must remain unchanged.

3. Move the slotbaseline family for AC-001, AC-002, and AC-004. Move `Tfeaturetask/slotbaseline/GoalPlanningRunLoopPersistenceTest.kt` into `goalrunner.planning.state`. Keep the other 16 files together in `featuretask.runner`: `SlotBaselineCaptureTest.kt`, `SlotBaselineCommittedTree.kt`, `SlotBaselineDurableBundle.kt`, `SlotBaselineFixtureCompare.kt`, `SlotBaselineFixtureTest.kt`, `SlotBaselineFullRunCapture.kt`, `SlotBaselineJson.kt`, `SlotBaselineMcpLifecycleCapture.kt`, `SlotBaselineNormalizer.kt`, `SlotBaselinePaths.kt`, `SlotBaselineSqlite.kt`, `SlotBaselineTestResources.kt`, `SlotBaselineWriter.kt`, `SlotBaselineCodeReviewCapture.kt`, `SlotBaselineGoalPlanningCapture.kt`, and `SlotBaselinePhaseRunCapture.kt`. Preserve `SlotBaselinePaths.RESOURCE_ROOT` as `"featuretask/slotbaseline"` and the repository-root lookup in `SlotBaselineTestResources`. Preplan settles the resource question: leave `runtime-kotlin/runtime-engine/src/test/resources/featuretask/slotbaseline/` unchanged. Later validation runs the existing persistence and slotbaseline capture/fixture suites to catch missing golden resources or changed persisted bytes. Do not regenerate goldens.

4. Move the 69 root-package files into their existing owners for AC-001, AC-002, and AC-004. Apply this destination map to files currently directly under `T`:

   | Destination suffix | Files |
   | --- | --- |
   | `featuretask.lifecycle.continuation` | `FeatureTaskContinuationAdmissionTest.kt`, `FeatureTaskContinuationLookupServiceTest.kt`, `FeatureTaskRouterContinuationTest.kt` |
   | `featuretask.lifecycle.core` | `FeatureTaskRuntimeCrashReconcilerTest.kt`, `FeatureTaskRuntimeWorkerCoordinatorTest.kt` |
   | `featuretask.lifecycle.branch` | `FeatureTaskRuntimeBranchSetupTest.kt` |
   | `featuretask.lifecycle.execution` | `ExecutionPlanAdmissionFixture.kt`, `FeatureTaskExecutionPlanCreationTest.kt`, `FeatureTaskAdmittedRunnerReconstructionTest.kt` |
   | `featuretask.phase.prompt.compose` | `FeatureTaskRuntimeAddonBudgetTest.kt`, `FeatureTaskRuntimePhasePromptComposerTest.kt`, `FeatureTaskRuntimePhasePromptComposerContentTest.kt`, `FeatureTaskRuntimePhasePromptComposerRepairTest.kt`, `FeatureTaskRuntimePhasePromptComposerSettlementTest.kt`, `FeatureTaskRuntimePhasePromptComposerRetryTest.kt`, `FeatureTaskRuntimePhasePromptComposerTestSupport.kt`, `FeatureTaskRuntimePhasePromptComposeTestSupport.kt`, `FeatureTaskRuntimeProjectAuthoringDisciplinePromptTest.kt`, `FeatureTaskRuntimeSimplifyPhasePromptTest.kt`, `FeatureTaskRuntimeRemediationPassPromptTest.kt` |
   | `featuretask.phase.briefing` | `FeatureTaskRuntimePhaseBriefingBudgetTest.kt`, `FeatureTaskRuntimePlanningProjectionEdgeTest.kt`, `FeatureTaskRuntimeHandoffProjectionTestSupport.kt` |
   | `featuretask.model.phase` | `FeatureTaskRuntimePhaseLaunchBriefingSerializationTest.kt` |
   | `featuretask.phase.core` | `FeatureTaskRuntimePhaseSafetyPolicyTest.kt`, `RuntimeLauncherSettlement.kt` |
   | `featuretask.phase.record` | `FeatureTaskRuntimePhaseRecordOutputCarryForwardTest.kt`, `FeatureTaskRuntimePhaseTimestampTest.kt`, `RuntimeWorkflowCreationFixture.kt` |
   | `featuretask.runloop.state` | `FeatureTaskRuntimeAttemptBudgetsTest.kt`, `AmbientInputsAndLoudFailSeamsTest.kt` |
   | `featuretask.runloop.finalization` | `FeatureTaskRuntimeCommitPushCycleTest.kt` |
   | `featuretask.review.core` | `FeatureTaskRuntimeReviewFixBudgetTest.kt` |
   | `featuretask.slot` | `FeatureTaskRuntimePhaseOutputFixtures.kt` |
   | `goalrunner.persist` | `GoalRunnerDurableSequenceAllocationTest.kt`, `WorkflowGoalRunnerOutcomeStoreTaskRuntimeBlockedTest.kt`, `WorkflowGoalRunnerOutcomeStoreTaskRuntimeTest.kt`, `WorkflowGoalRunnerOutcomeStoreTaskRuntimeTestSupport.kt` |
   | `featuretask.persist` | `WorkflowIssueKeyPersistenceTest.kt`, `WorkflowStateValidationPersistenceTest.kt`, `WorkflowServiceTest.kt` |

   Place the remaining root integration tests and shared construction support in `featuretask.runner`. The digest identifies these families as audit AC-list retry, audit progress regression, audit support, corrective respawn, gate recovery, goal-start baseline, implementation convergence, measured facts, pack-gate dispatch, producer projection gate, projection rejection, quarantine regeneration, review-fix resume parity, review-pass-number derivation, run-invariants resume parity, status service, terminal failure reason, validation-gate dispatch and status projection, verify-findings body delivery, write-history settlement, audit-session resume, census phase IO runner, runner briefing support, runner test support, stateless audit boundary, stateless audit, and agent-context telemetry. This includes `FeatureTaskRuntimeRunnerTestSupport.kt`, `FeatureTaskRuntimeMeasuredFactsTest.kt`, `FeatureTaskRuntimePackGateDispatchTest.kt`, and `FeatureTaskRuntimeValidationGateDispatchTest.kt`. The digest does not spell out every remaining filename. Assume these descriptions identify the existing root integration family; implement must confirm exact filenames against that family without inventing new files or changing their responsibilities. Later validation runs the existing runner, lifecycle, prompt, phase-record, persistence, and runloop suites with their original assertions.

5. Repair import references and inventory pins for AC-001 through AC-004. Update references to moved symbols throughout `runtime-kotlin/runtime-engine/src/test/kotlin` and `runtime-kotlin/runtime-engine/src/testFixtures/kotlin`. Add explicit imports where formerly implicit same-package references now cross packages, remove stale imports, and retain access to shared runner and slotbaseline helpers without widening visibility or changing helper bodies. This includes existing goalrunner, phaserun, slot code-review, and pull-request tests that use moved construction support. Leave testFixtures packages and paths unchanged unless their imports need updating. Do not use inline fully qualified references. In `runtime-kotlin/runtime-core/src/repoTest/kotlin/skillbill/architecture/PrincipleEnforcementInventory.kt`, change both pins for `runtime-engine/src/test/kotlin/skillbill/engine/FeatureTaskRuntimeRunnerTestSupport.kt` to `runtime-engine/src/test/kotlin/skillbill/engine/featuretask/runner/FeatureTaskRuntimeRunnerTestSupport.kt`. Preserve their exemption meaning and every other inventory entry. The digest's current line references are 144 and 150; locate the entries by their path at implementation time rather than relying on those line numbers.

6. Prepare acceptance evidence and hand off execution checks for AC-001 through AC-004. During implementation, use bounded file/package comparisons to establish that all engine test packages are declared by engine main or end in `testsupport` or `testing`, none declares the five forbidden packages, paths follow packages, and every engine test path in `PrincipleEnforcementInventory` exists. Verify that the test census matches the post-subtask-2 baseline, no engine main file changed, moved file bodies match their originals, and existing resources remain unchanged. Do not add a general orphan-package architecture guard. Preserve existing guards, baselines, and exemptions without weakening them. Later review applies A9 and A10 for package placement, A1 and A2 for ownership, and G1 through G7 for guard preservation, using the architecture-guidelines section 5 checklist.

   Build proof belongs to the later build phase. It must cover engine test and testFixtures source sets and runtime-core repoTest through the runtime-owned build strategy; compilation alone does not settle validation. The validate phase owns the full repository checks, including the suites named above, runtime-core repoTest and its inventory consumers, CLI/MCP parity, detekt, and Spotless. Existing parity coverage catches externally rendered output changes that engine capture tests cannot prove. Validation must discover all required project checks. No build, test, full check suite, or install refresh runs during planning.

No new test obligations are needed, so `test_obligations` is empty. This subtask relocates existing coverage without adding behavior. Keep regression and governed parity coverage intact and use the existing suites to prove resource loading, persisted bytes, and external output. The main implementation risks are stale imports, formerly implicit helper references, stale inventory paths, and accidental body edits. Resolve them through the mapped moves, import-only repairs, census and content comparisons, and later validation. No user decision, schema migration, feature flag, new module, or release ceremony is required.

## Next Path

skill-bill goal SKILL-390

## Spec Path

.feature-specs/SKILL-390-runtime-engine-architecture/spec_subtask_3_engine-test-packages-mirror-main.md
