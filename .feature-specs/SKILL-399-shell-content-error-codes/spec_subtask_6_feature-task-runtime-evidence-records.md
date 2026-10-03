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

## Implementation Details

Source: the SKILL-399 preplan digest, subtask 6 section and shared conventions. Paths are relative to `runtime-kotlin/`. Line numbers are hints; apply each rule where the code is now. Steps marked **Confirm** record an assumption implement checks on the tree before editing. The plan runs no build, test or check; build and validate own those.

### Task 1 — code enum, message functions, predicate registration (AC-001)

- File: `runtime-contracts/src/main/kotlin/skillbill/error/shellcontent/FeatureTaskRuntimeShellContentErrors.kt`. Create `enum class FeatureTaskRuntimeFailureCode : RuntimeFailureCode` there, or extend it if subtask 5 already created it. Entries:
  - `INVALID_REPAIR_RECEIPT`, `INVALID_FINDING_VERIFICATION_RECORD`
  - `INVALID_CHECKPOINT_IDENTITY_SCHEMA`, `INVALID_CHECKPOINT_IDENTITY_VERSION`
  - `INVALID_QUARANTINE_SCHEMA`, `INVALID_IMPLEMENTATION_ATTEMPT_SCHEMA`, `INVALID_PHASE_HANDOFF_SCHEMA`, `INVALID_PERSISTENCE_SCHEMA`, `INVALID_PROJECTION_MEASUREMENT_SCHEMA`, `INVALID_SHARED_EVIDENCE_PROJECTION_SCHEMA`
  - `INVALID_BUILD_RECEIPT_SCHEMA`, `INVALID_VALIDATION_EVIDENCE_SCHEMA`, `INVALID_READINESS_EVIDENCE_SCHEMA`
  - `INVALID_EXECUTION_IDENTITY_SCHEMA`, `INVALID_WORKER_OWNERSHIP_SCHEMA`
  - family entry `FEATURE_TASK_RUNTIME_CONTRACT_REJECTED` for operator decision rejected; reuse subtask 5's family entry instead if one exists.
- Add one message function per class next to the enum, following the landed precedent (one per class, e.g. `fun invalidFeatureTaskRuntimeRepairReceipt(fieldPath, reason, cause = null): SkillBillRuntimeException`). Each takes the old constructor parameters plus `cause`, passes the enum entry, and copies the old template verbatim:
  - repair receipt keeps `fieldPath.ifBlank { "<root>" }`; checkpoint identity version keeps `actual.ifBlank { "<absent>" }` and the quarantine/regenerate sentence; quarantine, implementation attempt and execution identity keep `ifBlank { "<unknown>" }`; phase handoff, persistence, projection measurement, shared evidence projection, validation evidence and readiness evidence keep the raw `'$sourceLabel'`; worker ownership and operator decision rejected are copied from the class body as written.
  - repair receipt's function keeps no `payloadFreeReason` parameter; callers that need it carry it as a value (Task 3).
  - build receipt drops the unread `payloadFreeReason` and `failureCode` parameters.
- Add `FeatureTaskRuntimeFailureCode` to `Throwable.isShellContentContractFailure()` in `ShellContentContractFailures.kt` unless subtask 5 already did. This is the bundle's highest-impact risk: without it every guarded `rethrowUnless(isShellContentContractFailure())` site (about 55) starts propagating these failures.
- Delete the 16 classes. Leave the three subtask 5 classes (`InvalidFeatureTaskRuntimePhaseOutputSchemaError`, `InvalidFeatureTaskRuntimeHandoffProjectionError`, `FeatureTaskRuntimePhaseOrderViolationError`) in whatever state they are in.
- Widen callback types shared with subtasks 4 and 5 to `SkillBillRuntimeException` if still narrower: `ContractValidatorWireInput.kt:27` `nonObjectError`, `FeatureTaskRuntimeHandoffFoundationSchemaValidators.kt:42` `error`, and the `ClasspathContractSchemaLoader` `missingResource` / `processingFailure` / `identityFailure` callbacks used by the converted validators.

### Task 2 — workflow-state handled set (AC-003)

- Add `fun Throwable.isInvalidWorkflowStateFailure(): Boolean` to `ShellContentContractFailures.kt` (open decision 2: one owner in shellcontent; subtask 7 extends it). It returns true for `this is InvalidWorkflowStateSchemaError` while that class exists, for a `SkillBillRuntimeException` with code `FeatureTaskRuntimeFailureCode.INVALID_CHECKPOINT_IDENTITY_VERSION`, and for subtask 7's `WorkflowFailureCode` state entry if it exists. If subtask 7 already added the predicate, add the checkpoint-version term to it.
- Retarget every main `catch (e: InvalidWorkflowStateSchemaError)` to `catch (e: SkillBillRuntimeException) { e.rethrowUnless(e.isInvalidWorkflowStateFailure()); … }` with the body unchanged (bodies use `message.orEmpty()` only). Known sites:
  - application `WorkflowService.kt:143`, `:165` and `:173`. The `:173` catch stays outside `database.transaction` so the throw still rolls back.
  - `WorkflowStateRepositoryParentDiscovery.kt:76`, `DecompositionWorkflowContinuation.kt:438`
  - engine `VerifyWorkflowStore.kt:59`, `VerifyOperation.kt:346`, `FeatureTaskRuntimeRunPreparation.kt:39`
  - `IdeStatusService.kt:86` (state; leave the `:78` WorkListRow catch to subtask 7)
  - domain `GoalSubtaskReviewArtifactDecoder.kt:62` and `:118`
  - `PhaseLaunchPreparation.kt:205`. If `:203` is still the typed subtask 5 projection catch, keep it first and put the guarded catch after it. If subtask 5 already folded `:203` into a `SkillBillRuntimeException` catch, add a `when` branch after the projection branch.
  - `FeatureTaskRuntimeValidationGateExecutionEvidence.kt:112` (see Task 4)
  - **Confirm:** grep main for any further `catch`, `is` or `as?` on `InvalidWorkflowStateSchemaError` and convert those the same way.
- Where a guarded catch would precede a typed catch on a subclass of `SkillBillRuntimeException` belonging to another issue, order it after that typed catch so no handler gets shadowed.
- `lifecycle/remediation/FeatureTaskRuntimeRemediationBaseReconciler.kt:80`: merge `catch (_: CheckpointIdentityVersion)` → `Refused("Checkpoint identity semantics are unsupported. …")` and the following guarded catch (`code == InstallFailureCode.INVALID_GOAL_SUBTASK_REVIEW_STATE_SCHEMA` → `Absent`) into one `catch (e: SkillBillRuntimeException)` with `when (e.code)`. Put the checkpoint-version branch first, and make `else -> throw e`.

### Task 3 — move the five re-wraps to their throw sites (AC-002)

1. **`workflow/model/goalreview/FeatureTaskRuntimeRepairReceipt.kt:24–37` `anchoredToDecodePath(path, decode)`** (callers at `:96`, `:133`, `:170`, `:260`). Delete the catch. Pass the anchor `path` into the nested decode, and compute the field path at each receipt throw with a private `anchoredFieldPath(anchor, innerPath) = if (innerPath.startsWith(anchor)) innerPath else "$anchor.${innerPath.substringAfterLast('.')}"`. This keeps the current rule, which only re-anchors paths that do not already start with the anchor. The reason and cause are unchanged.
2. **`GoalSubtaskReviewState.kt:347–361` `decodeRepairReceipts`.** Delete the catch. The receipt decode takes a failure sink `onInvalid: (payloadFreeReason: String, failure: SkillBillRuntimeException) -> Nothing`, defaulting to `{ _, failure -> throw failure }`. Each receipt throw site builds the receipt failure with the message function and calls the sink with its payload-free reason. `decodeRepairReceipts` passes `{ payloadFree, failure -> reviewStateError("$sourceLabel.repair_receipts[$index]", payloadFree, failure) }`, so the outer text and the cause chain match today's.
   - `FeatureTaskRuntimeRepairReceiptSanitizer.kt:129–134` `receiptError` sets reason = payloadFreeReason; pass the same string.
   - **Confirm** whether `FeatureTaskRuntimeWorkflowArtifactWire.kt:198` `validateRepairReceiptWireEntries` reaches `decodeRepairReceipts`. Its reason is `"must be an object."` but its payload-free reason is `"$path must be an object."`. If it is reachable, it passes `"$path must be an object."` to the sink, keeping today's review-state text.
3. **engine `RuntimeGateRecordIntegrity.kt:27–36`.** Delete the catch around `requireBuildReceipt` / `requireValidationResult`. The private `invalid(phaseId, reason): Nothing` throws the phase-output schema failure directly, with `sourceLabel = phaseId` and the same reason. Use whichever form exists on the tree: the subtask 5 coded function with `FeatureTaskRuntimePhaseOutputFailureCode.SCHEMA_INVALID`, or the old class. Its `cause` is the validation-evidence failure built with the same arguments as today, so the cause chain is preserved.
   - **Confirm** that every validation-evidence failure inside the wrapped calls comes from local `invalid`. If a nested domain call can also throw one, pass a `(reason) -> Nothing` factory to that call.
4. **engine `persist/FeatureTaskRuntimeHandoffEnvelopeArtifactDecoders.kt:47–70` `deliveredProjectionHistoryFrom`.** Change `validatePersistenceRecord` to return the violation reason, `String?`. The decoder throws `invalidFeatureTaskRuntimePersistenceSchema("consumer-phase:$consumerPhaseId/delivered-projection:$key", "$reason; $FEATURE_TASK_RUNTIME_INCOMPATIBLE_RECORD_GUIDANCE.")`, with no catch and no property read. Other callers that validated through the same lambda throw the same failure they threw before. The lambda comes from `FeatureTaskRuntimePhaseBriefingRecorder.kt:150–160` through the port:
   - Add an abstract `fun violation(kind, payload, sourceLabel): String?` to `skillbill.ports.taskruntime.FeatureTaskRuntimeWireArtifactValidator`. It has no default body, as `PortsDeclarationArchitectureTest` requires.
   - Implement it in infra `FeatureTaskRuntimeWireArtifactValidator.kt`. Each per-kind foundation validator gets a reason-returning core, and its throwing `validate` becomes `violation(...)?.let { throw factory(sourceLabel, it) }`.
   - The recorder lambda calls `violation`.
   - **Confirm** the cause: if the old persistence error carried a cause such as a schema-processing failure, `violation` stays a reason-only path and loader failures keep throwing unchanged. A loader failure that was persistence-coded and got re-wrapped today must keep its re-wrapped text. If that case exists, `violation` returns its reason.
5. **infra/workflow `FileSystemFeatureTaskRuntimeSharedEvidenceStoreReads.kt:120–127`.** Add `fun violation(payload, sourceLabel): String?` to `FeatureTaskRuntimeSharedEvidenceProjectionSchemaValidator` in its current module, with `validate` throwing through it. The store calls `violation` and passes the string to `degraded(seam = "stored_projection_schema", used = "re-derive", expected = …, cause = reason)`, so the degradation record is unchanged. Delete the catch.
   - **Confirm** that the validator is a concrete infra type. If it is a ports interface, declare `violation` abstract there and implement it in each implementation.
   - Loader or identity failures that were shared-evidence-coded and degraded today must still degrade, so `violation` returns their reason too.

### Task 4 — remaining producers and readers (AC-001, AC-003)

- **infra-contracts producers:**
  - `ContractValidatorWireInput.kt:50–66` (per-kind non-object factories)
  - `FeatureTaskRuntimeHandoffFoundationSchemaValidators.kt:55, 71, 86, 101, 131`
  - `BuildReceiptSchemaValidator` (same file) `:146` and loader callbacks `:175, 183, 193`. The catch at `:157–161`, which re-wraps validation evidence as build receipt using only `message`, becomes `catch (e: SkillBillRuntimeException) { e.rethrowUnless(e.code == INVALID_VALIDATION_EVIDENCE_SCHEMA) … }`, keeping message and cause.
  - `FeatureTaskRuntimeQuarantineSchemaValidator.kt:21, 45, 53, 63`, `FeatureTaskRuntimeImplementationAttemptSchemaValidator.kt` (same pattern), `FeatureTaskRuntimeCheckpointIdentitySchemaValidator.kt:22, 46, 54, 64`
- **Domain:**
  - finding verification: `FindingVerificationDisposition.kt:16, 31, 83, 89, 100` and `FeatureTaskRuntimeVerificationBoundaryHeadingProvenance.kt:52`
  - phase handoff: `FeatureTaskRuntimeHandoffModels.kt:53`, `PhaseHandoffProjectionDeclaration.kt:198`, `FeatureTaskRuntimeHandoffEnvelope.kt:63, 116`, `FeatureTaskRuntimeHandoffSharedValues.kt:17`. The `FeatureTaskRuntimeRunInvariantsPersistence.kt:137` catch becomes a code check on `INVALID_PHASE_HANDOFF_SCHEMA` that maps to `runInvariantSchemaError`. **Confirm** it reads only `message`/`cause`. If it reads a typed property, pass that value in at the throw instead.
  - checkpoint identity version: `FeatureTaskRuntimeCheckpointIdentityModels.kt:187`
  - readiness evidence: `FeatureTaskRuntimeReadinessEvidence.kt:245`
  - execution identity: `FeatureTaskExecutionIdentityPolicy.kt:31, 39` (`normalizeIssueKey`) and `:55`. The `WorkflowOpenResult.Error` → `UsageError` text stays unchanged.
- **`FeatureTaskRuntimeValidationGateExecutionEvidence.kt:108–117`.** Merge the validation-evidence and workflow-state catches into one `catch (e: SkillBillRuntimeException)`:
  - `e.code == INVALID_VALIDATION_EVIDENCE_SCHEMA` → `throw e` (checked first, as today);
  - `e.isInvalidWorkflowStateFailure()` → throw the validation-evidence failure with the same fixed reason and `addSuppressed(e)`;
  - else `throw e`.
  - Keep the trailing `catch (IllegalArgumentException)`. Extract a helper if ThrowsCount 2 is exceeded.
- **Engine:**
  - `FeatureTaskRuntimeRepairReceiptParser.kt:81`: the `is RepairReceipt` check inside the existing `runCatching` becomes `(it as? SkillBillRuntimeException)?.code == INVALID_REPAIR_RECEIPT`.
  - Message-only readers `phase/briefing/FeatureTaskRuntimeBriefingRendering.kt:84`, `slot/pullrequest/PullRequestReadinessGate.kt:36` and `validation/FeatureTaskRuntimeReadinessGateCoordinator.kt:321` get guarded code catches on `INVALID_READINESS_EVIDENCE_SCHEMA`, with bodies unchanged.
  - `runloop/durable/FeatureTaskRuntimeRunLoopDurableState.kt:90`: a guarded catch on `INVALID_VALIDATION_EVIDENCE_SCHEMA` that returns `PhaseSettledEnvelopeRead.Failed(e)`.
  - `runner/FeatureTaskRuntimeRunnerExecutePrepared.kt:106`: the operator-decision throw uses the family entry.
- **Execution identity throws:**
  - `FeatureTaskRuntimeExecutionAdmission.kt:49, 53, ~95`
  - `FeatureTaskContinuationLookupService.kt:66, 128, 267` and `FeatureTaskContinuationLookupExecution.kt:47`
  - `FeatureTaskRuntimeCrashReconciler.kt:120, 129, 178`
  - `AdmittedExecutionRecheck.kt:17, 29` and `FeatureTaskRuntimeRunner.kt:38`
  - `GoalPreflightGateBlockBuilder.kt:100`, `GoalPreflightInputValidation` (3) and `GoalPreflightLookupResolver` (1)
  - sqlite `FeatureTaskExecutionLookupStore` (2) and `FeatureTaskWorkflowStateStoreSql` (2)
- **Execution identity catches:**
  - `Admission:77` keeps its chain. The `FeatureTaskRuntimeExecutionPlanAdmissionError` and `UnsafeFeatureTaskRuntimeRegenerationError` typed catches stay. The identity catch becomes a guarded `SkillBillRuntimeException` catch placed after any typed catch it could shadow, and it still warns `"invalid_route_identity"` and rethrows.
  - `LookupService:97` becomes the same guarded catch, recording the warning and rethrowing.
- **Worker ownership:**
  - Throws in sqlite `FeatureTaskWorkflowStateStoreSql.kt:97, 107, 128`.
  - `WorkerLeaseInstantParsing.kt:18` becomes a guarded catch that keeps `recordDegradedValue` and the rethrow.
  - ports `FeatureTaskRuntimeWorkerOwnership.kt:35` (`parseFeatureTaskRuntimeWorkerLeaseInstant`) converts its throw in place, adding no behaviour to ports.
- Every main `catch`, `is` or `as?` on `ShellContentContractException` touched here keeps or gains the `isShellContentContractFailure()` guard. No touched catch becomes a new `runCatching`. Cancellation and interruption keep propagating.

### Task 5 — baseline and transition check (AC-001)

- Hand-delete these 16 rows from `runtime-core/src/repoTest/kotlin/skillbill/architecture/baselines/custom-throwable-baseline.txt`, and edit no other row:
  - `runtime-contracts:InvalidFeatureTaskRuntime{BuildReceipt,CheckpointIdentity,CheckpointIdentityVersion,FindingVerificationRecord,ImplementationAttempt,Persistence,PhaseHandoff,ProjectionMeasurement,Quarantine,ReadinessEvidence,RepairReceipt,SharedEvidenceProjection,ValidationEvidence,WorkerOwnership}…Error`, using the exact names on the tree;
  - `runtime-contracts:InvalidFeatureTaskExecutionIdentitySchemaError`;
  - `runtime-contracts:FeatureTaskRuntimeOperatorDecisionRejectedError`.
  - A stale or unlisted row fails `FailureCodeTotalityArchitectureTest`.
- Transition check: legacy subclasses remain outside this subtask, in `PhaseSlotContractErrors.kt`, the `error/core` decode/platform/addon errors and `InvalidMcpToolArgumentError`, all owned by SKILL-400/SKILL-398. Keep the open bases, the codeless constructor and the predicate. Do the transition finish only if implement finds none left.

### Task 6 — tests (type-to-code edits only)

- Convert `assertFailsWith<FormerClass>` to `assertFailsWith<SkillBillRuntimeException>` plus `assertEquals(FeatureTaskRuntimeFailureCode.<ENTRY>, error.code)`. Keep message, `contains`, payload and exit-code assertions byte-for-byte. Fixtures that construct former classes switch to the message functions; build-receipt fixtures drop the two removed arguments.
- Files listed by the digest:
  - FeatureTaskRuntime validation and schema tests: `FeatureTaskRuntimeValidationGateExecutionEvidenceTest`, `FeatureTaskRuntimeValidationEvidenceTest`, `FeatureTaskRuntimeQuarantineSchemaValidatorTest`, `…ImplementationAttemptSchemaValidatorTest`, `…CheckpointIdentitySchemaValidatorTest`, `…ReadinessEvidenceSchemaTest`, `FeatureTaskRuntimeRepairReceiptTest`, `…SharedEvidenceProjectionSchemaContractVersionTest`, `…HandoffFoundationSchemaValidatorTest`, `…BuildReceiptSchemaContractVersionTest`
  - execution identity and continuation: `FeatureTaskExecutionIdentityPolicyTest`, `GoalPreflightServiceTest`, `FeatureTaskContinuationLookupServiceTest`, `FeatureTaskContinuationAdmissionTest`, `WorkflowIssueKeyPersistenceTest`
  - stores, workers and checkpoints: `WorkflowStateStoreTest`, `FeatureTaskRuntimeWorkerOwnershipTest` (ports), `WorkerTakeoverFencingTest`, `SqliteDegradationDiagnosticsTest`, `CheckpointHistoryRefusalTest`, `FeatureTaskRuntimeCheckpointIdentityModelsTest`, `GitReadinessTreeIdentityOperationsTest`
  - evidence, verification and handoff: `…ValidationEvidenceSettlementTest`, `…FindingVerificationOutputTest`, `…FindingVerificationDurableDecodeTest`, `…HandoffEnvelopeArtifactDecodersTest`, `FileSystemFeatureTaskRuntimeRunInvariantsSourceTest`, `…PersistenceReviewPassParityTest`
- `FeatureTaskRuntimeHandoffEnvelopeSchemaValidatorTest` pinned map: QUARANTINE_RECORD, IMPLEMENTATION_ATTEMPT, BUILD_RECEIPT, HANDOFF_DECLARATION, HANDOFF_PERSISTENCE_RECORD, HANDOFF_MEASUREMENT and HANDOFF_SHARED_EVIDENCE_PROJECTION become `"FeatureTaskRuntimeFailureCode.<ENTRY>"`. Inputs and the `failureCodeLabel() ?: simpleName` rendering stay unchanged.
- Fakes implementing `FeatureTaskRuntimeWireArtifactValidator` or the shared-evidence validator get the new `violation` member, mirroring each fake's existing `validate` behaviour. Do not use relaxed mocks or a new helper module.
- test_obligations: none new. The converted `RepairReceipt`, goal-review-state, handoff-decoder, shared-evidence-read and gate-integrity tests guard against the realistic regressions: lost index or anchor in re-anchored paths, a doubled prefix, a missing guidance suffix, and a changed degradation `cause`. `CheckpointHistoryRefusalTest` and the reconciler tests guard AC-003. Audit inspects each catch for AC-002 and AC-003.

### Constraints

- Messages, payloads, degradation records and rendered labels stay byte-identical.
- Do not add a typealias, a property on `SkillBillRuntimeException`, `@Suppress`, a new `runCatching`, a new module, a `Result`/`Either` library, or a `//` or non-KDoc block comment.
- `skillbill.error.core` must not import shellcontent. runtime-domain must not import `java.nio` or ports. Ports get only abstract declarations.
- detekt limits: ThrowsCount 2, ReturnCount 4, LongMethod 70, CyclomaticComplexMethod 15. Extract small private helpers when needed. `ArchitectureScanSupport.kt` must not grow.
- Leave classes owned by other subtasks unchanged, except catch sites that must accept the new codes.
- Implement runs no build, test, install or check. Build and validate own them. Spotless runs in a plain clone during validate.

## Next Path

skill-bill goal SKILL-399

## Spec Path

.feature-specs/SKILL-399-shell-content-error-codes/spec_subtask_6_feature-task-runtime-evidence-records.md
