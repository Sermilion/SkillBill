# SKILL-399 Subtask 6 - feature-task-runtime-evidence-records

Parent spec: [.feature-specs/SKILL-399-shell-content-error-codes/spec.md](./spec.md)
Issue key: SKILL-399

## Scope

Convert the other classes in `FeatureTaskRuntimeShellContentErrors.kt`:

- `InvalidFeatureTaskRuntimeRepairReceiptError`
- `InvalidFeatureTaskRuntimeFindingVerificationRecordError`
- `InvalidFeatureTaskRuntimeCheckpointIdentitySchemaError`
- `InvalidFeatureTaskRuntimeCheckpointIdentityVersionError`
- `InvalidFeatureTaskRuntimeQuarantineSchemaError`
- `InvalidFeatureTaskRuntimeImplementationAttemptSchemaError`
- `InvalidFeatureTaskRuntimePhaseHandoffSchemaError`
- `InvalidFeatureTaskRuntimePersistenceSchemaError`
- `InvalidFeatureTaskRuntimeProjectionMeasurementSchemaError`
- `InvalidFeatureTaskRuntimeSharedEvidenceProjectionSchemaError`
- `InvalidFeatureTaskRuntimeBuildReceiptSchemaError`
- `InvalidFeatureTaskRuntimeValidationEvidenceSchemaError`
- `InvalidFeatureTaskRuntimeReadinessEvidenceSchemaError`
- `FeatureTaskRuntimeOperatorDecisionRejectedError`
- `InvalidFeatureTaskExecutionIdentitySchemaError`
- `InvalidFeatureTaskRuntimeWorkerOwnershipSchemaError`

Codes and readers:

- **Codes.** `FeatureTaskRuntimeFailureCode` (create it if subtask 5 has not) gets one entry each for:
  - repair receipt, finding verification record;
  - checkpoint identity schema, checkpoint identity version (its own entry, because `FeatureTaskRuntimeRemediationBaseReconciler.kt:78` discriminates it);
  - quarantine, implementation attempt, phase handoff, persistence;
  - projection measurement (pinned by the envelope test);
  - shared evidence projection, build receipt, validation evidence, readiness evidence;
  - execution identity, worker ownership.

  Family entry: operator decision rejected. The build receipt's `failureCode` and `payloadFreeReason` have no main readers and are dropped.
- **Workflow-state subclassing.** `InvalidFeatureTaskRuntimeCheckpointIdentityVersionError` extends the `open` `InvalidWorkflowStateSchemaError` (Workflow area, subtask 7). Add `fun Throwable.isInvalidWorkflowStateFailure(): Boolean` in `skillbill.error.shellcontent` if it is missing. It is true for `is InvalidWorkflowStateSchemaError` while that class exists, or for a `SkillBillRuntimeException` whose code is the workflow-state entry or the checkpoint-identity-version entry, whichever exist. Every former `catch (e: InvalidWorkflowStateSchemaError)` uses it, so a converted checkpoint-version failure is still caught there.
- **Execution identity.** Convert SKILL-392's `FeatureTaskExecutionIdentityPolicy.normalizeIssueKey` throw to the execution-identity code. Leave the `WorkflowOpenResult.Error` → `UsageError` text unchanged.
- **Re-wrapping readers.** Move each re-wrap to the throw site by passing the wrap context in, and drop the catch. The final failure is built once, with the same text and the inner failure as `cause` where one existed.
  - `FeatureTaskRuntimeRepairReceipt.anchoredToDecodePath` (`:30-37`): pass the anchor path into the nested decode.
  - `GoalSubtaskReviewState.decodeRepairReceipts` (`:355`): pass a `(reason) -> Nothing` review-state failure factory. For receipts `payloadFreeReason == reason`; confirm this in `FeatureTaskRuntimeRepairReceiptSanitizer`. The factory throws the goal subtask review state failure in whatever form it has on the tree.
  - `RuntimeGateRecordIntegrity.kt:35`: the phase id goes into the validation-evidence check.
  - `FeatureTaskRuntimeHandoffEnvelopeArtifactDecoders.kt:61-65`: `validatePersistenceRecord` returns the violation reason (`String?`) instead of throwing.
  - `FileSystemFeatureTaskRuntimeSharedEvidenceStoreReads.kt:122-127`: the validator gains a non-throwing `violation(...)`: `String?`. `validate` throws through it, and the degraded record keeps `cause = reason`.
- **Other sites.** `FeatureTaskRuntimeRepairReceiptParser.kt:81` (`is`) becomes a code check. Lambdas typed as returning these classes (for example the `ClasspathContractSchemaLoader` `missingResource`/`processingFailure`/`identityFailure` callbacks, where they return one of them) become `SkillBillRuntimeException`.
- **Pinned test.** `FeatureTaskRuntimeHandoffEnvelopeSchemaValidatorTest` asserts `error::class.simpleName` per kind. Each kind converted here becomes a `code` assertion.

## Acceptance Criteria

1. `FeatureTaskRuntimeShellContentErrors.kt` declares none of the 16 classes.
2. No main code reads `reason`, `fieldPath` or `payloadFreeReason` from a caught exception at the five re-wrap sites.
3. A converted checkpoint-identity-version failure is still handled by every catch that handled `InvalidWorkflowStateSchemaError`.

## Non-Goals

Phase output, handoff projection and phase order (subtask 5). The Workflow classes (subtask 7).

## Test obligations

None beyond the converted assertions.

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

.feature-specs/SKILL-399-shell-content-error-codes/spec_subtask_6_feature-task-runtime-evidence-records.md
