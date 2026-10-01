# SKILL-397 Subtask 1 - domain-wire-boundary-honesty

Parent spec: [.feature-specs/SKILL-397-runtime-domain-boundary-integrity/spec.md](./spec.md)
Issue key: SKILL-397

## Scope

Remove the three guard evasions on the domain wire boundary. (F-004) Change the 33 public top-level domain functions whose declared return type is `Any`. They are in taskruntime/artifact FeatureTaskRuntimeWorkflowArtifactWire.kt, FeatureTaskRuntimeWorkflowArtifactWireMappings.kt and FeatureTaskRuntimeWorkflowArtifactWirePhaseMappings.kt; goalrunner GoalWorkerSubtaskRequestArtifactCodec.kt; goalrunner/model GoalRunnerStatusProjectionModels.kt, FeatureTaskRuntimeGoalContinuationOutcome.kt and GoalRunnerAccountingModels.kt; and workflow/model/goalreview GoalSubtaskCommitFocusedAccounting.kt, GoalSubtaskReviewState.kt and GoalObservabilityModels.kt. Each returns FeatureTaskRuntimeWorkflowArtifactMap or a concrete type, or is deleted where a typed wire map already exists (for example presentationWireMap). Extend the existing inner-layer raw-map scan in runtime-core repoTest (findRawMapViolations, used by RuntimeRawMapArchitectureTest) to reject public declarations typed exactly `Any` in runtime-application, runtime-domain and runtime-ports main. Add a synthetic-fixture test beside the existing application fixture test. (F-005) Domain stops accepting validators. Delete the FeatureTaskRuntimeWireArtifactValidation fun interface and the validator parameters or field at PhaseHandoffProjectionDeclaration.fromArtifactMap, FeatureTaskRuntimeHandoffProjectionInputs, the phase-mapping wrapper, GoalObservabilityArtifacts (3 sites), InstallPlanWireMap and InstallPlanPolicy. Each engine or application caller validates with its ports validator before calling the domain decoder. Delete the domain testFixtures file AcceptingFeatureTaskRuntimeWireArtifactValidator, the java-test-fixtures plugin in runtime-domain/build.gradle.kts, and the testFixtures(project(:runtime-domain)) dependency lines in application, engine and core. The three tests that use the fixture switch to the engine fixture or to direct calls. (F-006) Make decodeStrictKeyedArtifactMap, FeatureTaskRuntimeGoalContinuationArtifact.toWorkflowArtifactPatch, goalParentArtifactProjection, missingResultPrefixTerminalOutcomeArtifact, goalReviewArtifacts and validatedGoalReviewPasses internal, or type them with the existing DurableWorkflowArtifacts or WorkflowArtifactPatch carriers. Delete their rawMapBoundaryAccessors entries in RuntimeArchitectureTestSupport.kt. Amend ARCHITECTURE.md Boundary Rule 11 to name the four DurableWorkflowArtifactFamily members as the only allow-listed raw-map members, and give the reason.

## Acceptance Criteria

1. No public declaration in runtime-domain, runtime-application or runtime-ports main declares a return or property type of exactly `Any`. Each of the 33 former wrappers returns FeatureTaskRuntimeWorkflowArtifactMap or a concrete type, or no longer exists.
2. The inner-layer raw-map scan used by RuntimeRawMapArchitectureTest reports a public declaration typed exactly `Any` in those three modules, and a synthetic-fixture test in RuntimeRawMapArchitectureTest asserts that rejection.
3. runtime-domain main declares no FeatureTaskRuntimeWireArtifactValidation, and GoalObservabilityArtifacts, InstallPlanWireMap, InstallPlanPolicy, PhaseHandoffProjectionDeclaration, FeatureTaskRuntimeHandoffProjectionInputs and the phase-mapping wrappers have no validator parameter or property.
4. runtime-domain has no src/testFixtures directory, its build file applies no java-test-fixtures plugin, and no build file references testFixtures(project(:runtime-domain)).
5. rawMapBoundaryAccessors contains no skillbill.workflow.* or skillbill.goalrunner.* entry other than DurableWorkflowArtifactFamily.contains, value, putInto and removeFrom. The six former entries are internal or expose no Map<String, Any?> in a public signature.
6. ARCHITECTURE.md Boundary Rule 11 names the four DurableWorkflowArtifactFamily members as the only allow-listed raw-map members and states why.
7. No baseline file gains a row, no exemption or architecture-test class is added, and no existing wire-fixture or expected-payload assertion is edited.

## Non-Goals

- Unifying the domain and engine add-on selection decoders (F-008).
- Changing the DurableWorkflowArtifactFamily accessor signatures.
- Deleting the ports validateX forwarders (SKILL-393 owns them).
- Changing the ports FeatureTaskRuntimeWireArtifactValidator interface or its adapter.

## Dependency Notes

Depends on: none
Coordinates with SKILL-393, which deletes the ports allow-list entry and the 12 ports forwarders, and with SKILL-389, which edits other regions of ArchitectureScanSupport and RuntimeRawMapArchitectureTest. Whichever lands second keeps both edits and leaves only the four family entries in the allow-list.

## Validation Strategy

Goal gates: build, unit tests and the runtime-core repoTest architecture suite. Test obligation: the synthetic-fixture test for the `Any`-typed public declaration rule. Existing projection, handoff, install-plan and observability tests must pass unchanged, which shows that validation order and wire output are unchanged.

## Next Path

skill-bill goal SKILL-397

## Spec Path

.feature-specs/SKILL-397-runtime-domain-boundary-integrity/spec_subtask_1_domain-wire-boundary-honesty.md
