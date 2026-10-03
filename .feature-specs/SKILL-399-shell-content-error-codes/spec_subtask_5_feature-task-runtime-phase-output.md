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

## Next Path

skill-bill goal SKILL-399

## Spec Path

.feature-specs/SKILL-399-shell-content-error-codes/spec_subtask_5_feature-task-runtime-phase-output.md

## Implementation Details

Plan from the supplied preplan digest only. No source discovery, build, test execution or validation occurred during planning. AC-001 through AC-003 below refer to the numbered Acceptance Criteria above. The existing scope, dependencies, validation strategy and next path remain unchanged.

### Ordered implementation tasks

1. Replace the three owned throwable declarations with coded factories and delete the two dead shellcontent data types. Serves AC-001 and the common message and baseline criteria.

   Touch `runtime-kotlin/runtime-contracts/src/main/kotlin/skillbill/error/shellcontent/FeatureTaskRuntimeShellContentErrors.kt`, the adjacent area code owner and `ShellContentContractFailures.kt`. Use the existing `FeatureTaskRuntimePhaseOutputFailureCode`, defaulting to `SCHEMA_INVALID`, for phase-output failures. Use `InvalidFeatureTaskRuntimeHandoffProjectionContext.failureKind` directly for projection failures. Create or extend `FeatureTaskRuntimeFailureCode` with the phase-order violation entry without replacing entries landed by another slice. The three existing featuretask wire enums already implement `RuntimeFailureCode`; add no duplicate enum or marker inheritance to `FailureWireCode`.

   Preserve structured constructor inputs, optional causes and message expressions in functions returning `SkillBillRuntimeException`. Inline message-only constructions at their producers. The phase-order message retains phase ID, required phase ID, required verdict and nullable observed verdict, including `<no completed verdict>`. Drop dead structural-repair properties together with `FeatureTaskRuntimePhaseOutputStructuralRepair` and `FeatureTaskRuntimePhaseOutputStructuralRepairSource`. The digest establishes that neither shellcontent type has main or test readers. Preserve the live infrastructure object at `runtime-infra/contracts/src/main/kotlin/skillbill/infrastructure/contracts/phaseoutput/FeatureTaskRuntimePhaseOutputStructuralRepair.kt`, its decision model, repair evidence and tests.

   Convert producers and callback return types that reference these three removed classes. Relevant callbacks include `featureTaskRuntimeWireArtifactNonObjectError` and `coherenceError`. Where needed for these conversions, change shared callback failure types in `ClasspathContractSchemaLoader.kt`, `ContractValidatorWireInput.kt` and the private request in `FeatureTaskRuntimeHandoffFoundationSchemaValidators.kt` to `SkillBillRuntimeException`. Leave unrelated legacy producers and subtask 6's record conversions intact. Validation later checks existing schema assertions with exact enum codes and unchanged messages and causes.

2. Return projection rejection context at the domain boundary. Serves AC-003 and preserves projection policy.

   Touch `runtime-kotlin/runtime-domain/src/main/kotlin/skillbill/workflow/taskruntime/handoff/FeatureTaskRuntimeHandoffProjectionValidator.kt` and its owning handoff model family. Change `validate(FeatureTaskRuntimeHandoffProjectionInputs)` to a narrow typed accepted-or-rejected result. Accepted carries the existing envelope; rejected carries the existing `InvalidFeatureTaskRuntimeHandoffProjectionContext`. Retain declaration checks, field resolution, source-field checks and checkpoint policy in their current owning files. Their rejection paths construct the context once and return it rather than throwing a property-bearing exception.

   Assumption for implement to confirm: the digest does not name an existing projection-result type. Extend a suitable owning model if one exists; otherwise add the smallest sealed result beside the handoff family. Do not expose a raw-map result or move validator behavior into ports. Terminating callers may use the context-based coded factory. Measurement callers must branch on the rejection value. Convert existing domain handoff tests and `FeatureTaskRuntimeHandoffProjectionTestSupport.kt` to outcome assertions, preserving their inputs, rejection facts and messages. No additional projection test is prescribed because the digest identifies existing policy coverage.

3. Carry the projection result through briefing construction and launch preparation. Serves AC-003 and preserves measurement and producer repair behavior.

   Touch `runtime-kotlin/runtime-engine/src/main/kotlin/skillbill/engine/featuretask/phase/briefing/FeatureTaskRuntimePhaseBriefingAssembler.kt`, `FeatureTaskRuntimePhaseBriefingRecorder.kt`, `slot/attempt/PhaseLaunchPreparation.kt`, `runloop/output/FeatureTaskRuntimeRunLoopOutputVerification.kt` and `goalplanning/planning/attempt/GoalPlanningPhaseAttemptGate.kt`.

   Give `assemble(handoff, workflowId, agentAddonSelection, scope)` an accepted briefing or rejected context outcome. Change `recordProjectionRejection` to accept context rather than an exception. Both repository-checkpoint rejection and declared-launch rejection in `PhaseLaunchPreparation` obtain projection name, contract ID and failure kind from that value. Preserve repository-checkpoint fingerprints, measurement fields and dispositions. Immediate-consumer rejection in run-loop output verification retains its bounded message and the producer's re-entry behavior. Goal planning's attempt gate branches on the same context outcome. Callers that only stop execution may throw the coded context factory, without recovering fields from its message.

   Preserve the phase-generic SKILL-380 attempt boundary and accepted-step authority. Update existing engine briefing tests and `FeatureTaskRuntimePlanningProjectionEdgeTest.kt` for accepted and rejected outcomes while retaining assertions about measurement, bounded rejection and re-entry. These assertion migrations cover existing behavior; do not add parallel tests for the same branch.

4. Return phase-order violations as transition values and block at their target. Serves AC-002 and AC-001.

   Touch `runtime-kotlin/runtime-domain/src/main/kotlin/skillbill/workflow/taskruntime/model/phase/FeatureTaskRuntimeTransitionModels.kt`, `validation/FeatureTaskRuntimeTransitionFunction.kt` and `runtime-kotlin/runtime-engine/src/main/kotlin/skillbill/engine/featuretask/runloop/core/FeatureTaskRuntimeRunLoopDrive.kt`. Add a violation variant to the existing sealed transition model, carrying the target `phaseId` and unchanged message. Use an equally narrow owning result only if the existing model cannot express both accepted transitions and violations. `nextTransition` returns that outcome instead of throwing the removed class.

   Replace `resolveNextTransition`'s exception-based `runCatching` discrimination with an explicit result branch. Its violation branch calls `blockAt` with the violation target and message. Preserve `entryGateBlockReason` message construction. A separate terminating phase-order failure remains coded; do not turn unrelated failures into violation outcomes.

   Convert `FeatureTaskRuntimeTransitionFunctionShippedTest.kt`, `FeatureTaskRuntimeTransitionFunctionTest.kt` and `FeatureTaskRuntimeTransitionFunctionTestSupport.kt`, including `shippedTransition`'s explicit return type. Preserve target-ID assertions for missing audit, gaps-found audit and invalid verify verdicts as value assertions. Add or adapt one engine runner regression using `runtime-engine/src/test/kotlin/skillbill/engine/featuretask/runner/FeatureTaskRuntimeRunnerTestSupport.kt` and its existing harness. Its realistic bug is blocking at the current phase instead of the violation target. Assert the observable blocked target and unchanged reason. Reuse a converted existing test only if it proves both outcomes at the runner boundary.

5. Complete owned consumer migration and shared transition maintenance. Serves all three criteria and the common compatibility criteria.

   Replace remaining references to the three removed classes at their current producers and consumers with factories, result branches or narrow guarded code checks. Caught failures expose only `code`, `message` and `cause`; do not parse messages to recover removed fields. Preserve handled sets, catch priority, guarded rethrows and database, gate, cancellation and interruption propagation. Retain `failureCodeLabel() ?: existingExpression` rendering, changing only labels for converted failures.

   The digest corrects the scope's historical catch anchor: the grouped diagnostic catches in `FeatureTaskRuntimeRejectedOutputRecorder.kt` belong to subtask 7. Leave that merge to its owner unless a compatibility adjustment is necessary for a code introduced here. Likewise, subtask 6 owns the remaining featuretask evidence, receipt and identity classes, its property-rewrap seams and the full pinned artifact-kind assertion migration. Update the pinned `FeatureTaskRuntimeHandoffEnvelopeSchemaValidatorTest` only for kinds converted by this subtask.

   Remove only whole baseline rows for the three deleted throwable classes from `runtime-kotlin/runtime-core/src/repoTest/kotlin/skillbill/architecture/baselines/custom-throwable-baseline.txt`. Preserve all other rows and the synthetic totality fixtures. Add the newly introduced area enum to shell-content classification while retaining existing coded families, especially `GoalTelemetryRowFailureCode`, and every guarded rethrow. Apply the digest's rule to remove the legacy classifier term while retaining transitional base declarations if still used.

   Implement checks the final transition condition against both subclasses and codeless constructions. The digest proves external legacy uses currently remain, so assume the open exception, codeless constructor and legacy base must stay. Confirm that condition during implement. Finish the parent transition only if intervening work has removed every remaining use; do not convert another slice's classes to force completion.

6. Prepare acceptance evidence for later audit and validation. Serves all three criteria and the existing Validation Strategy.

   Implement leaves an end state with no owned declarations or caught-property readers, preserved messages and causes, typed projection and transition outcomes, and corrected baseline rows. Audit inspects each criterion and common rule. Architecture review applies A1 through A12 and G1 through G7 from the digest, especially handled sets, wire ownership, package cycles and test placement. Preserve value-based parse boundaries, existing test source sets, slotbaseline resource paths and persisted bytes.

   Build proof belongs only to a runtime-owned build phase. Test execution and full checks belong to validate. Validate runs the affected domain transition and handoff suites, engine runner and briefing suites, relevant infrastructure phase-output and handoff schema suites, and `FailureCodeTotalityArchitectureTest`. It also covers detekt, formatting and repository architecture guards for totality, ports declarations, typed parse boundaries, package cycles, wire vocabulary and comments through the full gate `./gradlew check --continue --parallel -q --warning-mode none` under JDK 21. Spotless runs in a plain clone. Validation and review may repair production wiring, test setup, formatting or lint as needed without weakening behavior, assertions or architecture rules.

### Test obligations and constraints

The single new behavioral obligation is the engine runner target-phase regression in task 4, tied to AC-002. Preserve existing regression and governed parity coverage. Other tasks convert existing assertions and helpers; add no tests that mirror implementation or merely repeat another branch assertion. No tests were executed in this phase.

Keep messages, payloads, recovery, quarantine, diagnostics and measurement records unchanged apart from coded labels for converted failures. No persisted schema, payload, dependency or feature-flag change is needed. Do not add modules, aliases for removed classes, exception properties, dependency bags, locator accessors, raw-map public results, suppressions or new `runCatching`. Core must not import shellcontent; domain must not import ports or `java.nio`; ports retain declarations only. Keep the specified detekt limits, do not grow `ArchitectureScanSupport.kt`, and add no authored Kotlin line comments, block comments or non-interface KDoc.

No installer work or authored skill changes are required. History, commit, push and PR actions remain with their owning phases. The digest settles all design questions; the narrow projection-result model choice and final legacy-use condition above are implement-time confirmations, not planning blockers.
