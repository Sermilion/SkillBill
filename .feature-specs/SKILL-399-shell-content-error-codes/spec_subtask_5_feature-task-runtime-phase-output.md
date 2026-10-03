# SKILL-399 Subtask 5 - feature-task-runtime-phase-output

Parent spec: [.feature-specs/SKILL-399-shell-content-error-codes/spec.md](./spec.md)
Issue key: SKILL-399

## Scope

In `FeatureTaskRuntimeShellContentErrors.kt`, convert these classes, and drop the dead data types if they have no main or test readers:

- `InvalidFeatureTaskRuntimePhaseOutputSchemaError`
- `InvalidFeatureTaskRuntimeHandoffProjectionError`
- `FeatureTaskRuntimePhaseOrderViolationError`
- data types: `FeatureTaskRuntimePhaseOutputStructuralRepair`, `FeatureTaskRuntimePhaseOutputStructuralRepairSource`

If those data types do have readers, move them next to the reader; they are not throwables.

- **Phase output.** The code is the carried `FeatureTaskRuntimePhaseOutputFailureCode`, with default `SCHEMA_INVALID`; add no new entry. No main code reads `structuralRepair*` or the dropped properties. Pass enum entries directly, not wire strings.
- **Handoff projection rejection.** The code is `context.failureKind` (`FeatureTaskRuntimeHandoffProjectionFailureKind`). `InvalidFeatureTaskRuntimeHandoffProjectionContext` is already a value.
  - The projection build and validation entry points return the rejection context. They are used by `PhaseLaunchPreparation` (both catches, `:121` and `:221-222`) and by `FeatureTaskRuntimeRunLoopOutputVerification.kt:158` → `FeatureTaskRuntimePhaseBriefingRecorder.recordProjectionRejection` (`:101-105`).
  - Those readers build measurement rows from `context.projectionName`, `projectionContractId` and `failureKind`. Other callers throw the coded failure, built from the context by the message function.
- **Phase order violation.** `FeatureTaskRuntimeTransitionFunction.nextTransition` (runtime-domain) returns a sealed result with a violation variant carrying `phaseId` and the byte-identical message. `FeatureTaskRuntimeRunLoopDrive` (`:164-169`) branches on it. Its own throw at `:104` stays a coded failure if it ends the run. Create `FeatureTaskRuntimeFailureCode` with a phase order violation entry if subtask 6 has not.
- **Catches.** Merge the catches in `FeatureTaskRuntimeRejectedOutputRecorder.kt:189-221` into one `when (e.code)`. Lambdas typed as returning these classes (for example `featureTaskRuntimeWireArtifactNonObjectError`, `coherenceError`) become `SkillBillRuntimeException`.
- **Pinned test.** If `FeatureTaskRuntimeHandoffEnvelopeSchemaValidatorTest` asserts `error::class.simpleName` for a class converted here, that assertion becomes a `code` assertion.

## Acceptance Criteria

1. None of the three classes or two data types remains in `FeatureTaskRuntimeShellContentErrors.kt`.
2. `nextTransition` returns its violation as a value, and no main code reads `phaseId` from a caught exception.
3. No main code reads `projectionName`, `projectionContractId` or `failureKind` from a caught exception.

## Non-Goals

The other FeatureTaskRuntime classes (subtask 6).

## Test obligations

- One test: `nextTransition`'s phase-order violation blocks at the violation's `phaseId` with the same message. Realistic bug: the run blocks at the current phase instead.

## Shared Rules

Apply `spec.md` "Conversion rules", "Shared transition pieces" and "Execution Rule". If a shared transition piece is missing, add it as written there. If any main `catch`, `is` or `as?` on `ShellContentContractException` lacks the `isShellContentContractFailure()` guard, add the guard. Add each area enum this subtask creates to `isShellContentContractFailure()`, unless it is `ScaffoldFailureCode`.

After this subtask's edits, check whether any class in main still extends `SkillBillRuntimeException` or `ShellContentContractException`. If none does, finish the transition as `spec.md` "Target failure model" describes.

## Common Acceptance Criteria

- Every user-visible message is byte-identical. No expected-output, wire-fixture or payload assertion is edited, other than replacing an exception-type assertion with a code assertion, or a pinned class name with the code label.
- No main source declares a typealias named after a deleted class.
- `custom-throwable-baseline.txt` lists none of this subtask's deleted classes, and `FailureCodeTotalityArchitectureTest` passes.
- Classes this subtask does not own are unchanged, except for catch sites that must accept a code this subtask introduced.

## Validation Strategy

Goal gates: build, unit tests, detekt and the runtime-core repoTest suite. Existing tests pass with type-to-code assertion edits only. Add only the behavioural tests listed under Test obligations, and only where no converted existing test already asserts the branch.

## Implementation Details

This plan comes from the shared preplan digest. Paths are relative to `runtime-kotlin/`. Line numbers are digest anchors; implement applies each step to the code wherever it is now. Earlier plan drafts in this file are replaced by this section.

### Corrections to the Scope anchors (settled from the digest)

- `recordProjectionRejection` has no main caller. Its only caller is `runtime-core/src/test/.../di/workflow/ApplicationPersistencePortWorkflowTest.kt:241`. The `FeatureTaskRuntimeRunLoopOutputVerification.kt` catch (around `:159`) reads only `error.message` through `boundedSchemaGateDetail`. That catch becomes a code check; it does not gain a new measurement call.
- The `FeatureTaskRuntimeRejectedOutputRecorder.kt` catch pairs (`:205/207`, `:235/237`) catch `InvalidProducerOutputEvidenceSchemaError` and `InvalidRejectedOutputDiagnosticSchemaError`. Both belong to subtask 7, which merges them. This subtask touches that file only if implement finds an arm catching one of the three classes converted here.
- `FeatureTaskRuntimeRunLoopDrive.kt:103` (`entryGateBlockReason`) builds the violation only to read `.message`; it does not throw. With no remaining throw that ends the run, no `FeatureTaskRuntimeFailureCode` entry is needed here. That enum stays subtask 6's to create. Implement confirms this. If a run-ending throw remains, create the enum (or add to subtask 6's), add `PHASE_ORDER_VIOLATION`, and register the enum in `isShellContentContractFailure()`.
- The `PhaseLaunchPreparation.kt:118` catch around `resolveRepositoryCheckpoint` is dead: `buildRepositoryCheckpoint` (OutputVerification `:282`) has no projection producer. Implement confirms there is no producer and deletes the catch. If a producer exists, that path branches on the projection result from task 3 instead.

### Ordered tasks

1. **Phase-output message function (AC-001).** In `runtime-contracts/src/main/kotlin/skillbill/error/shellcontent/FeatureTaskRuntimeShellContentErrors.kt`:
   - Delete `InvalidFeatureTaskRuntimePhaseOutputSchemaError`.
   - Add `fun invalidFeatureTaskRuntimePhaseOutputSchema(sourceLabel: String, reason: String, code: FeatureTaskRuntimePhaseOutputFailureCode = FeatureTaskRuntimePhaseOutputFailureCode.SCHEMA_INVALID, cause: Throwable? = null): SkillBillRuntimeException`. It returns the text `"Feature-task-runtime phase output '${sourceLabel.ifBlank { "<unknown>" }}' fails schema validation: $reason"` byte for byte.
   - Drop `payloadFreeReason`, the `String` `failureCode` and `structuralRepair`. Their only main reader is the classifier, which task 2 moves to `message`.
   - Delete the `FeatureTaskRuntimePhaseOutputStructuralRepairSource` and `FeatureTaskRuntimePhaseOutputStructuralRepair` data classes; they have no main readers. Keep the unrelated infra `internal object FeatureTaskRuntimePhaseOutputStructuralRepair` in `skillbill.infrastructure.contracts.phaseoutput` and its tests.
   - Confirm that `FeatureTaskRuntimePhaseOutputFailureCode`, `FeatureTaskRuntimePhaseOutputFailureKind` and `FeatureTaskRuntimeHandoffProjectionFailureKind` implement `RuntimeFailureCode`, and add the marker where it is missing. Do not make `FailureWireCode` extend it. They are `FailureWireCode`s, so `isShellContentContractFailure()` needs no new term.
   - Throw sites, each passing the enum entry its old wire string named:
     - domain `model/handoff/task/FeatureTaskRuntimeHandoffModels.kt:151, 156`;
     - domain `model/phase/FeatureTaskRuntimePhaseOutputValidationModels.kt:20, 39, 134`, where the payload-free argument is dropped;
     - engine `RuntimeGateRecordIntegrity.kt:35`. The `error.reason` read there comes from subtask 6's ValidationEvidence class: keep that read, or use whatever subtask 6 put in its place if it has landed;
     - `GoalChildPlanningHydrator.kt:229` (`PreparedPlanningPayloadValidator.requireValid`).

2. **Phase-output catches (AC-001).** Each becomes `catch (error: SkillBillRuntimeException)` with `error.rethrowUnless(error.code is FeatureTaskRuntimePhaseOutputFailureCode)` and keeps its branch logic:
   - `runloop/state/FeatureTaskRuntimeRunStateValidation.kt:88`: rethrow when `requiresValidCompletedOutput`, otherwise null.
   - `runloop/state/FeatureTaskRuntimeRunState.kt:215`: rethrow when status is COMPLETED, otherwise null.
   - `GoalPlanningPreparationCheckpoint.planningRecordRejection` (~`:300`) and `GoalChildPlanningHydrator.requireImportedPayloadValid` (~`:303`). If subtask 4 has already merged the pair into one guarded catch, swap the phase-output term for `error.code is FeatureTaskRuntimePhaseOutputFailureCode`. If not, swap only the phase-output catch and keep the schema-error catch ahead of it, unchanged. The handled set stays the same and the text `"stored record failed its durable contract: ${error.message}"` is unchanged.
   - `goalrunner/planning/recovery/GoalPlanningRecoveryKind.kt:67`: the phase-output cause match becomes `code is FeatureTaskRuntimePhaseOutputFailureCode -> reasonIndicatesContractVersionHardReset(message)`. Leave the schema-error and conflict terms unchanged unless subtask 4 has already converted them. Messages embed the reason, so `GoalPlanningRecoveryClassificationTest` case 3 (reason `"contract_version: must be the constant value '0.4'"`) still classifies as HARD_RESET. The untyped `IllegalStateException` case stays SCOPED_REPLAN, because message matching applies only to coded families.

3. **Handoff projection rejection as a value (AC-001, AC-003).**
   - **Message function.** In the shellcontent file, delete `InvalidFeatureTaskRuntimeHandoffProjectionError`. Add `fun invalidFeatureTaskRuntimeHandoffProjection(context: InvalidFeatureTaskRuntimeHandoffProjectionContext, cause: Throwable? = null): SkillBillRuntimeException`, which uses code `context.failureKind` and the old message byte for byte. Each blank field still renders as `<unknown>`, plus `[failureKind]` and the reason.
   - **Context stays put.** `InvalidFeatureTaskRuntimeHandoffProjectionContext` stays where it is: AC-001 does not name it, and moving it would ripple through domain, infra and ports. That leaves one data class in shellcontent, in tension with parent AC 1. The final subtask's audit or a follow-up should settle where it lives.
   - **Domain validator.** In `workflow/taskruntime/handoff/FeatureTaskRuntimeHandoffProjectionValidator.kt`:
     - Add a sealed `FeatureTaskRuntimeHandoffProjectionResult` beside the validator, with nested data variants `Accepted(envelope: FeatureTaskRuntimeHandoffEnvelope)` and `Rejected(context: InvalidFeatureTaskRuntimeHandoffProjectionContext)`.
     - Add `validateToResult(inputs)` and build the throwing `validate(inputs)` on top of it: on `Rejected` it throws `invalidFeatureTaskRuntimeHandoffProjection(context)`.
     - `rejectFeatureTaskRuntimeHandoffProjection(...): Nothing` becomes a context builder. Its 13 callers in DeclarationChecks (8), EnvelopeWire (1), FieldResolver (1), Validator (1) and ValueBuilder (2) return the rejection as a value: checks return `InvalidFeatureTaskRuntimeHandoffProjectionContext?` (first failure wins, in today's order), and resolver and builder steps return a small internal sealed step result. The exact internal shape is implement's call, within the detekt limits. No internal throw-and-catch may carry the context.
   - **Infra envelope schema validator.** In `workflow/featuretask/FeatureTaskRuntimeHandoffEnvelopeSchemaValidator.kt`:
     - Add `rejection(envelope, workflowId): InvalidFeatureTaskRuntimeHandoffProjectionContext?`, with `projectionName = firstProjectionLocation(errors)` and SCHEMA_INVALID. `validate` throws the message function on top of it.
     - `featureTaskRuntimeHandoffEnvelopeIdentityMismatchError` and the loader callbacks return `SkillBillRuntimeException`.
     - `ContractValidatorWireInput.kt:68` uses the message function. Widen its `nonObjectError` type (`:27`) and the `error` type in `FeatureTaskRuntimeHandoffFoundationSchemaValidators.kt:42` to `SkillBillRuntimeException`, so subtasks 4 and 6 producers still fit. `featureTaskRuntimeWireArtifactNonObjectError` and `coherenceError` likewise return `SkillBillRuntimeException`.
   - **Port and recording path (settles open decision 3).** `rejectedHandoffLaunch` needs `projectionName` for its measurement. For a schema rejection raised inside `recordPhaseBriefing`, no value path exists today, and AC-003 forbids reading it from the exception. So:
     - Add one declaration to `skillbill.ports.taskruntime.FeatureTaskRuntimeWireArtifactValidator`: `fun handoffEnvelopeRejection(payload, sourceLabel): InvalidFeatureTaskRuntimeHandoffProjectionContext?`. Use the same parameters the current `HANDOFF_ENVELOPE` branch passes at infra `:42–43`; implement mirrors how that branch derives `workflowId`. The declaration has no default body, per `PortsDeclarationArchitectureTest`.
     - Implement it in infra `FeatureTaskRuntimeWireArtifactValidator.kt` through `rejection(...)`.
     - Fakes implementing the port return `null`, or delegate where they already validate.
     - `FeatureTaskRuntimePhaseBriefingRecorder.recordPhaseBriefing` (`:50`) calls it and returns the context to its caller instead of throwing. The `validate(HANDOFF_ENVELOPE, ...)` port path and its other callers keep throwing.
     - This deviates from the digest's recommendation against a result-returning port. It is the only path that keeps the measurement's projection name intact without reading a caught property.
   - **Assembler.** `phase/briefing/FeatureTaskRuntimePhaseBriefingAssembler.assemble` (`:71`) returns an accepted-briefing-or-`Rejected(context)` result, built from `validateToResult`.
   - **`slot/attempt/PhaseLaunchPreparation.kt`.**
     - `prepareLaunch` branches on the assembler result, then on the recording result. Each rejection goes to `rejectedHandoffLaunch(context)`, which reads `failureKind.toMeasurementFailureClassification()` and `projectionName` from the context.
     - The `:203` projection catch is removed. The `:205` `InvalidWorkflowStateSchemaError` catch (`rejectedDurableBriefingLaunch`) belongs to subtask 7 and stays in place and in order.
     - Delete the dead `:118` catch (see the corrections above).
     - The file is 429 lines: extract a small helper if the change pushes it past detekt or the line-ceiling guard.
   - **Message-only catches.** `FeatureTaskRuntimeRunLoopOutputVerification.kt:159` and `goalplanning/.../GoalPlanningPhaseAttemptGate.kt:130` (reached through `GoalPlanningPhaseAttemptGateLaunch.kt:78`) become `catch (error: SkillBillRuntimeException) { error.rethrowUnless(error.code is FeatureTaskRuntimeHandoffProjectionFailureKind) ... }`. They keep `boundedSchemaGateDetail` and `projectionRejectedReason` on `error.message`.
   - **`recordProjectionRejection`.** Its parameter becomes `context: InvalidFeatureTaskRuntimeHandoffProjectionContext`, through `PhaseRunRecords.kt:177` (interface; keep its KDoc accurate), `FeatureTaskRuntimePhaseRecorder.kt:236–242`, `DurablePhaseRunAdapters.kt:149–154` and `InMemoryPhaseRunRecords.kt:180`. The measurement row is built from the same three fields.

4. **Phase-order violation as a value (AC-001, AC-002).**
   - Delete `FeatureTaskRuntimePhaseOrderViolationError`.
   - In domain `model/phase/FeatureTaskRuntimeTransitionModels.kt`, add the sealed `FeatureTaskRuntimeTransitionResult` with nested data variants `Resolved(next: FeatureTaskRuntimeNextPhase)` and `PhaseOrderViolation(phaseId, message: String)`. `phaseId` keeps the old class's type. Using a wrapper leaves `FeatureTaskRuntimeNextPhase` and the exhaustive `when` in `runloop/core/FeatureTaskRuntimeRunLoopTransitions.kt:46–51` untouched.
   - Add a domain message builder `featureTaskRuntimePhaseOrderViolationMessage(phaseId, requiredPhaseId, requiredVerdict, observedVerdict): String` with the old text byte for byte, including `${observedVerdict ?: "<no completed verdict>"}` and the trailing sentence.
   - `validation/FeatureTaskRuntimeTransitionFunction.nextTransition` returns `FeatureTaskRuntimeTransitionResult`. `guardEntryGate` (`:29`) returns the violation instead of throwing.
   - `FeatureTaskRuntimeRunLoopDrive.resolveNextTransition` (`:152`) drops its `runCatching` for a `when`. `PhaseOrderViolation` calls `blockAt(request, state, coupledSession(), violation.phaseId, violation.message)` and returns null; `Resolved` returns `next`. Other throwables now propagate without the explicit rethrow.
   - `entryGateBlockReason` (`:103`) calls the message builder.

5. **Remaining references (AC-001, AC-002, AC-003).**
   - Convert any leftover `is` or `as?` on the three classes to `(error as? SkillBillRuntimeException)?.code` checks.
   - Sites that render a caught class name use `failureCodeLabel() ?: <existing expression>`.
   - Add no new `runCatching`. Cancellation and interruption keep propagating. Leave subtask 6 and subtask 7 classes alone.

6. **Tests (common criteria).**
   - **Domain projection.** In `FeatureTaskRuntimeHandoffProjectionValidatorTest` (11 `assertFailsWith`), throwing-entry tests become `assertFailsWith<SkillBillRuntimeException>` plus `assertEquals(FeatureTaskRuntimeHandoffProjectionFailureKind.<ENTRY>, error.code)`, with messages unchanged.
   - **Infra envelope.** In `FeatureTaskRuntimeHandoffEnvelopeSchemaValidatorTest`, the 8 `assertFailsWith` sites become code assertions. In the pinned map, `HANDOFF_ENVELOPE` becomes `"FeatureTaskRuntimeHandoffProjectionFailureKind.SCHEMA_INVALID"`, and any kind pinned to the phase-output class becomes its `FeatureTaskRuntimePhaseOutputFailureCode` label. Edit no other rows.
   - **`ApplicationPersistencePortWorkflowTest`.**
     - `:210–218`: the `failureKind` assertion becomes a `code` assertion. The `consumerPhaseId` assertion becomes a message-substring assertion carrying the same value (open decision 4). This is the one deviation from the "property → code only" rule, because `consumerPhaseId` has no code equivalent.
     - `:241`: passes a CHECKPOINT_POLICY_VIOLATION context and still asserts that a STALE_CHECKPOINT measurement is recorded.
     - `:262` and `:278`: become code assertions.
   - **Phase-output tests.** Convert to the message function and code assertions: `FeatureTaskRuntimePhaseOutputValidationModelsTest`, `FeatureTaskRuntimePlanningProjectionEdgeTest:146, 168`, `FeatureTaskRuntimeGateRecoveryTest`, `AmbientInputsAndLoudFailSeamsTest`, `WorkflowServiceTest` and `GoalPlanningRecoveryClassificationTest` (case 3 construction only; its HARD_RESET assertion stays).
   - **Transition tests.** The TestSupport helpers and `shippedTransition` unwrap `Resolved`, which covers about 25 call sites in `FeatureTaskRuntimeTransitionFunctionTest`, `SemanticLoopWarningThresholdDeclarationTest` and `ReviewFixCapReconciliationTest`. The `assertFailsWith` sites at `UnboundedRemediationLoopRegressionTest:37` and `FeatureTaskRuntimeTransitionFunctionShippedTest:83, 98, 115` assert a `PhaseOrderViolation` with the same `phaseId` and message.
   - **Fakes.** Port fakes from task 3 implement `handoffEnvelopeRejection`.
   - **Hygiene.** Add no relaxed mocks, no `environment = emptyMap()` and no new helper module.

7. **Required regression (AC-002; the Test obligation).**
   - Add one engine test using `runtime-engine/src/test/kotlin/skillbill/engine/featuretask/runner/FeatureTaskRuntimeRunnerTestSupport.kt` (or the existing drive harness). It drives a run whose next transition is a phase-order violation targeting a phase other than the current one. It asserts that the run blocks at the violation's `phaseId` with a blocked reason equal to the violation message.
   - The realistic bug it catches: blocking at the current phase.
   - Skip it if a converted existing test already asserts that boundary. No other new tests: the projection and phase-output branches are covered by the converted existing tests.

8. **Baseline (AC-001).** In `runtime-core/src/repoTest/kotlin/skillbill/architecture/baselines/custom-throwable-baseline.txt`, delete exactly these three rows by whole-row match:
   - `runtime-contracts:FeatureTaskRuntimePhaseOrderViolationError`
   - `runtime-contracts:InvalidFeatureTaskRuntimeHandoffProjectionError`
   - `runtime-contracts:InvalidFeatureTaskRuntimePhaseOutputSchemaError`

9. **Transition check.** After the edits, check for remaining `ShellContentContractException` or `SkillBillRuntimeException` subclasses and codeless constructions. The digest shows many remain (subtasks 6 and 7, `PhaseSlotContractErrors`, `DurableExternalDecodeErrors`, `InvalidMcpToolArgumentError` and others), so keep the open base, the codeless constructor and the predicate unchanged.

### Constraints

- Messages, payloads, measurement rows and blocked reasons stay byte-identical. Only the labels of converted failures change.
- No typealias for a deleted class, no property on `SkillBillRuntimeException`, no `@Suppress`, no new `runCatching`, module or dependency.
- `skillbill.error.core` imports nothing from `shellcontent`. runtime-domain gains no `java.nio` or ports import. Ports gain one declaration with no body and no behaviour.
- detekt limits apply: ThrowsCount 2, ReturnCount 4, LongMethod 70, CyclomaticComplexMethod 15. Extract helpers where the result branching pushes a function over a limit, especially in `PhaseLaunchPreparation` and the hydrator. `ArchitectureScanSupport.kt` must not grow.
- Authored Kotlin gets no `//` or non-KDoc block comments.
- The SKILL-380 attempt boundary stays phase-generic.
- This phase and implement run no build, tests, check or installer. Build proof belongs to the build phase. Unit tests, detekt, the runtime-core repoTest suite (`FailureCodeTotalityArchitectureTest`, `PortsDeclarationArchitectureTest`, package-cycle, wire-vocabulary and comment guards) and `./gradlew check` belong to validate. Validate and review may repair wiring, test setup, formatting or lint without weakening behaviour or assertions.

### Overlaps with other subtasks

- **Subtask 4:** shares `GoalPlanningRecoveryKind`, the Checkpoint and Hydrator catches, and the pinned map. Whichever lands second keeps both edits.
- **Subtask 6:** owns `RuntimeGateRecordIntegrity`'s ValidationEvidence read and `FeatureTaskRuntimeFailureCode`.
- **Subtask 7:** owns the RejectedOutputRecorder catch pairs and the `PhaseLaunchPreparation:205` workflow-state catch.

## Next Path

skill-bill goal SKILL-399

## Spec Path

.feature-specs/SKILL-399-shell-content-error-codes/spec_subtask_5_feature-task-runtime-phase-output.md
