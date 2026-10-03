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

## Implementation Details

This plan uses only the supplied preplan digest and this sub-spec. The digest describes discovery at `8527efaee41cfc2beb316be9b6eec4be017f9deb` on `base/SKILL-380-phase-slot-strategies`. The current FeatureTaskRuntime file has 19 classes, of which this subtask owns the 16 listed above. Keep the eight existing slices and their ownership. This subtask has no dependencies and must work with whichever shared conversions exist when implement starts.

1. Prepare the coded contracts and callback signatures. This serves AC-001 and the common code-ownership criteria. In `runtime-kotlin/runtime-contracts/src/main/kotlin/skillbill/error/shellcontent/`, create or extend `FeatureTaskRuntimeFailureCode` with the fifteen individual entries listed in Scope and the family entry for operator decision rejection. Keep checkpoint identity version distinct from checkpoint identity schema. Reuse entries already introduced by another slice and preserve the existing phase-output wire enum. Structured constructors used at multiple sites become public message factories beside the owner enum, with the original inputs and optional cause. Message-only constructors become direct coded constructions. Widen the failure callbacks in infrastructure contracts' `ClasspathContractSchemaLoader.kt`, `ContractValidatorWireInput.kt`, and the private schema-validation request in `FeatureTaskRuntimeHandoffFoundationSchemaValidators.kt` to `SkillBillRuntimeException` before passing converted factories. Leave unrelated legacy producers intact. The digest does not supply exact entry or factory spellings, so implement must follow the existing area's naming convention while retaining these distinct identities. Existing infrastructure featuretask schema tests will prove exact codes and unchanged messages during validate.

2. Preserve the complete workflow-state handled set before removing the checkpoint-version subclass. This serves AC-003. Add or extend `Throwable.isInvalidWorkflowStateFailure()` beside `ShellContentContractFailures.kt`. Recognize the legacy `InvalidWorkflowStateSchemaError` while present, the Workflow state-schema code when present, and the FeatureTaskRuntime checkpoint-identity-version code. Retarget every former workflow-state catch to a guarded coded catch using this predicate, including application workflow service, parent discovery and decomposition continuation, engine verify storage and operation, phase-launch preparation and durable run preparation, and domain `GoalSubtaskReviewArtifactDecoder`. In `FeatureTaskRuntimeRemediationBaseReconciler.kt`, keep checkpoint-version handling before broader state handling. Where an adjacent generic coded catch exists, merge by code without changing branch priority or handled outcomes. Unrelated database, gate, cancellation and interruption failures must still propagate. Existing domain checkpoint-identity and gate-execution tests, engine continuation admission and lookup tests, and `lifecycle/remediation/CheckpointHistoryRefusalTest.kt` remain the regression evidence. Preserve any existing workflow service and verify assertions affected by the catch changes.

3. Remove caught-property reads from both repair-receipt seams. This serves AC-002 and message/cause compatibility. In domain `workflow/model/goalreview/FeatureTaskRuntimeRepairReceipt.kt`, pass the anchor decode path and failure construction through nested validation instead of catching receipt failures in `anchoredToDecodePath`. Coordinate this with `workflow/taskruntime/model/repair/FeatureTaskRuntimeRepairReceiptSanitizer.kt`. The digest establishes that `receiptError` uses the payload-free reason as the reason; implement must preserve that equality. In `GoalSubtaskReviewState.decodeRepairReceipts`, supply a reason-bearing failure callback that constructs the owning review-state failure with its indexed path. Keep the previous outer message, inner cause and path anchoring, constructing the final thrown failure once. Do not parse an exception message to recover fields. Convert existing domain repair-receipt and goal-review-state assertions to exact codes while preserving malformed inputs, indexed-path text and cause assertions. Their realistic regression is a nested receipt failure losing its index or gaining a duplicate message prefix.

4. Remove caught-property reads from the remaining three seams. This serves AC-002. In engine `featuretask/validation/RuntimeGateRecordIntegrity.kt`, supply the phase ID and phase-output wrapping context to the validation-evidence boundary so the final phase-output failure retains its message and cause. In `featuretask/persist/FeatureTaskRuntimeHandoffEnvelopeArtifactDecoders.kt`, change `validatePersistenceRecord` to return `String?` for both `deliveredProjectionsFrom` and `deliveredProjectionHistoryFrom`. Build the owning persistence failure from that reason, preserving `consumer-phase:<consumerPhaseId>/delivered-projection:<key>` and the incompatible-record guidance suffix. Add `violation(payload, sourceLabel): String?` to `FeatureTaskRuntimeSharedEvidenceProjectionSchemaValidator` in its current owner; keep `validate` as the throwing wrapper. Use the reason directly in infrastructure workflow's `FileSystemFeatureTaskRuntimeSharedEvidenceStoreReads.kt`. Preserve the degraded record's `stored_projection_schema` seam, `re-derive` used value and unwrapped reason. The digest does not name the validator declaration file, so implement must confirm its existing owner without moving validation into domain or adding behavior to ports. Convert existing validation-evidence, gate-execution, shared-evidence schema/read tests and `FeatureTaskRuntimeHandoffEnvelopeArtifactDecodersTest.kt`. These tests guard against wrong projection labels, lost wrap context and changed degradation records.

5. Convert the remaining producers and discriminators, then delete the sixteen classes. This serves AC-001 and AC-003. Replace constructions and imports throughout the current consumers of the owned classes with the coded factories or constructors. This includes domain repair, finding-verification, checkpoint, quarantine, implementation-attempt, phase-handoff, persistence, projection-measurement, shared-evidence, build, validation, readiness, execution-identity and worker-ownership records. Convert `FeatureTaskExecutionIdentityPolicy.normalizeIssueKey` to the execution-identity code while preserving the `WorkflowOpenResult.Error` to `UsageError` text. Drop the unread build-receipt `failureCode` and `payloadFreeReason` properties. Change `FeatureTaskRuntimeRepairReceiptParser.kt`'s catch-to-null discriminator to the receipt code with a narrow guard. Preserve the complete nested message and cause where `FeatureTaskRuntimeHandoffFoundationSchemaValidators.kt` wraps validation evidence as build receipt using `message`. Merge adjacent validation-evidence and workflow-state catches in `FeatureTaskRuntimeValidationGateExecutionEvidence.kt`, retaining its fixed reason and suppressed workflow-state failure. A caught failure exposes only code, message and cause. Keep other catches narrow when they only render a message or rethrow. Existing finding verification decode/output and infrastructure schema tests cover these boundaries; no new glue tests are needed.

6. Update regression assertions and the shared transition bookkeeping. This serves all three acceptance criteria and the common baseline criterion. Convert former-class assertions to `SkillBillRuntimeException` plus exact owner-code assertions. In `runtime-infra/contracts/src/test/kotlin/skillbill/infrastructure/contracts/workflow/featuretask/FeatureTaskRuntimeHandoffEnvelopeSchemaValidatorTest.kt`, replace class-simple-name assertions only for converted artifact kinds, preserving every validation input. Keep expected messages, exit codes, wire fixtures and payload assertions unchanged, apart from converted code labels. Preserve `failureCodeLabel() ?: existingExpression` rendering for uncoded failures. Add the area enum to `isShellContentContractFailure()` without removing `GoalTelemetryRowFailureCode` or other existing coded classifications. Guard any touched legacy shell-content catches and rethrow unrelated codes. Remove only the sixteen deleted classes' complete rows from `runtime-kotlin/runtime-core/src/repoTest/kotlin/skillbill/architecture/baselines/custom-throwable-baseline.txt`. Check the transition condition against the implementation tree, including codeless constructions. The digest establishes remaining external legacy subclasses, so retain the open coded exception, codeless constructor and legacy base unless intervening work removes every remaining use. Finish the shared transition only if that full condition holds, preserving coded classifications and guarded rethrows. Leave other slices' classes and behavior unchanged.

7. Prepare evidence for the owning later phases. Audit must inspect AC-001 through AC-003 individually, including every former workflow-state catch and all five re-wrap seams. Review must apply A1 through A12 and G1 through G7, especially A7 handled sets, A4 declaration-only ports, A6 wire ownership, A9 package cycles and A10 test placement. Build proof belongs to build. Validate owns execution of the existing tests named above, unit tests, detekt, formatting and repository architecture suites. Required architecture coverage includes `FailureCodeTotalityArchitectureTest`, ports declarations, typed parse boundaries, package cycles, wire vocabulary and comment guards. The later collect-all gate is `./gradlew check --continue --parallel -q --warning-mode none` under JDK 21, with strict agnix as required by CI. Spotless must run in a plain clone. Required production-wiring, test-setup, formatting and lint repairs remain available to their owning phases within the feature requirements. This plan runs no commands to prove implementation and records no executed tests.

Test obligations remain empty for new tests. The digest prescribes converted existing coverage for this slice. Preserve all regression and governed parity coverage, including failure-totality synthetic fixtures; do not replace boundary assertions with mock call-order or implementation-structure checks.

Across all tasks, preserve message expressions byte-for-byte, including blank substitutions and suffixes, as well as recovery, quarantine, measurement and diagnostic records. The intended observable change is the code label for converted failures. Add no persisted schema or payload change, feature flag, dependency, module, typealias, exception property, raw-map public result, locator accessor, dependency bag or new `runCatching`. Core must not import shellcontent; domain must not import ports or `java.nio`; contracts must not import engine, infrastructure or MCP. Keep the SKILL-380 attempt boundary phase-generic and preserve accepted-step authority. Keep test setup in its existing source sets and slotbaseline resources and persisted bytes at their current paths. Meet existing detekt limits without suppressions, authored line comments or non-interface KDoc, and do not grow `ArchitectureScanSupport.kt` or weaken guards and baselines.

Planning changes only this sub-spec. Implementation, audit, review, finding verification, build proof, full validation, history, commit and PR work remain with their owning later phases or parent runtime. No installer refresh is part of this child plan. No repository-answerable design question remains open; implement must confirm only the naming and validator-file assumptions identified above against its current tree.
