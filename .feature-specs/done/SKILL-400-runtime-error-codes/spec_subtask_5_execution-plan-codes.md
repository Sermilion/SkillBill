# SKILL-400 Subtask 5 - execution-plan-codes

Parent spec: [.feature-specs/SKILL-400-runtime-error-codes/spec.md](spec.md)
Issue key: SKILL-400

## Scope

Convert the execution-plan failures in runtime-contracts `skillbill.error.featuretask`:

- `FeatureTaskRuntimeExecutionPlanAdmissionError` (sealed base) with `Missing…`, `Corrupt…`, `Unsupported…` and `IncompatibleFeatureTaskRuntimeExecutionPlanError`;
- `FeatureTaskRuntimeExecutionPlanConflictError`, `FeatureTaskRuntimeSharedEvidenceFingerprintContradictionError` and `InvalidFeatureTaskRuntimeExecutionPlanSchemaError`;
- `UnsafeFeatureTaskRuntimeRegenerationError`.

All of them extend `ShellContentContractException` except `UnsafeFeatureTaskRuntimeRegenerationError`, which extends `IllegalStateException`. Engine, infra (contracts, sqlite, workflow) and application all throw these, so the enums stay in `skillbill.error.featuretask`.

- **Admission.** `FeatureTaskRuntimeExecutionPlanAdmissionCode(val wireValue: String)` replaces the base's `reasonCode`. Its entries are `MISSING_DESCRIPTOR("missing_descriptor")`, `CORRUPT_DESCRIPTOR`, `UNSUPPORTED_DESCRIPTOR` and `INCOMPATIBLE_DESCRIPTOR`, each keeping today's wire value. Factory `executionPlanRefused(code)` keeps the message `"Durable execution plan refused: ${code.wireValue}. …"` unchanged. The enum joins `isShellContentContractFailure()`.
- **Regeneration.** `FeatureTaskRuntimeRegenerationRefusal` implements `RuntimeFailureCode` and is the code. Factory `regenerationRefused(refusal)` keeps today's text.
  - The former class was ISE: the code joins `uncapturedAtMcp()`.
  - Any `IllegalStateException` handler it can reach checks the code (handled-set rule).
  - Execution-plan admission and regeneration refusal still end the run; they keep throwing.
- **Execution failures.** `FeatureTaskRuntimeExecutionFailureCode { INVALID_EXECUTION_PLAN_SCHEMA, EXECUTION_PLAN_CONFLICT, SHARED_EVIDENCE_FINGERPRINT_CONTRADICTION }` joins `isShellContentContractFailure()`. If SKILL-399 already created `FeatureTaskRuntimeFailureCode`, add these as entries there instead and create no second enum.
- **Readers.**
  - `FeatureTaskRuntimeExecutionAdmission.kt:74,80,115` and `FeatureTaskContinuationLookupService.kt:90,104` each collapse to one `catch (error: SkillBillRuntimeException)`. A `when (val code = error.code)` maps:
    - admission code → `code.wireValue`;
    - `FeatureTaskRuntimeRegenerationRefusal` → `code.wireValue`;
    - the execution-identity code, if SKILL-399 has converted it → `"invalid_route_identity"`; if the class still exists, keep its own catch;
    - anything else → rethrow without a warning.

    The warning text stays identical, and the rethrow happens after the warning.
  - `FeatureTaskRuntimeExecutionPlanCompatibility.kt:96` checks `INVALID_EXECUTION_PLAN_SCHEMA`.
- Delete the emptied files.

## Acceptance Criteria

1. No main source declares the nine classes. No main code reads `reasonCode` or `refusal` from a caught failure.
2. Each former failure throws `SkillBillRuntimeException` with an entry of `FeatureTaskRuntimeExecutionPlanAdmissionCode`, `FeatureTaskRuntimeRegenerationRefusal` or the execution-failure enum.
3. Execution-plan admission warnings (`reason=<wire>`), regeneration refusal and continuation lookup behave as before, with identical warning text.

## Non-Goals

The SKILL-399 FeatureTaskRuntime phase-output, evidence, receipt and identity classes; phase-slot classes (subtask 4).

## Test obligations

- **Admission warning (conditional).** Add these only if no existing test asserts the `reason=<wire>` warning: one test for an admission code and one for a regeneration refusal, each asserting the warning text and the rethrow. Bug it catches: the merged `when` maps a code to the wrong wire value or swallows the rethrow.
## Shared Rules

Apply `spec.md` "Conversion rules", "Shared pieces", "Transition finish" and "Execution Rule". If a shared piece is missing, add it as written there. After this subtask's edits, check the transition-finish condition and finish the transition if it holds.

## Common Acceptance Criteria

- Every user-visible message is byte-identical. No expected-output, wire-fixture or payload assertion is edited, other than replacing an exception-type assertion with a code assertion, or a pinned class name with the code label.
- MCP telemetry capture happens for exactly the failures it happened for before, and no unguarded `SkillBillRuntimeException` catch absorbs a failure it did not catch before.
- No main source declares a typealias named after a deleted class.
- `custom-throwable-baseline.txt` lists none of this subtask's deleted classes, and `FailureCodeTotalityArchitectureTest` passes.
- Classes this subtask does not own are unchanged, except for catch sites that must accept a code this subtask introduced.

## Validation Strategy

Goal gates: build, unit tests, detekt and the runtime-core repoTest suite. Existing tests pass with type-to-code assertion edits only. Add only the behavioural tests listed under Test obligations, and only where no converted existing test already asserts the branch. Run nothing in implement; build, tests, detekt and repoTest belong to the build and validate phases.

## Next Path

skill-bill goal SKILL-400

## Spec Path

.feature-specs/SKILL-400-runtime-error-codes/spec_subtask_5_execution-plan-codes.md

## Implementation Details

This plan uses only the supplied preplan digest at checkout `6e512ed9ca1d0bf43619880b9f7560a58704d5f2`. Acceptance criterion references below use AC-001, AC-002 and AC-003 for the three numbered criteria above. No dependency work is required. Implement confirms current anchors while making this conversion and preserves changes already present in shared files.

1. Replace the owned throwable declarations with codes and message factories. Serves AC-001 and AC-002. Under `../../../runtime-kotlin/runtime-contracts/src/main/kotlin/skillbill/error/featuretask`, replace the sealed admission base and its four subclasses in `FeatureTaskRuntimeExecutionPlanAdmissionError.kt` with `FeatureTaskRuntimeExecutionPlanAdmissionCode` and `executionPlanRefused(code)`. Retain `missing_descriptor`, `corrupt_descriptor`, `unsupported_descriptor` and `incompatible_descriptor` exactly. Make the existing `FeatureTaskRuntimeRegenerationRefusal` implement `RuntimeFailureCode` and replace `UnsafeFeatureTaskRuntimeRegenerationError` with `regenerationRefused(refusal)`, retaining all five existing wire values. Add `INVALID_EXECUTION_PLAN_SCHEMA`, `EXECUTION_PLAN_CONFLICT` and `SHARED_EVIDENCE_FINGERPRINT_CONTRADICTION` to the existing `skillbill/error/shellcontent/FeatureTaskRuntimeFailureCode.kt`; create no competing execution-failure enum. Replace declarations in the schema, conflict and fingerprint error files with the existing enum's entries and appropriately owned factories. Preserve all constructor inputs, messages and causes, including fingerprint inputs `addressedFingerprint`, `recordedFingerprint`, `sourceLabel` and optional cause, without exception properties. Delete emptied files and name any enum-and-factory file after its enum. Existing type-to-code regression assertions cover these factories; add no standalone factory tests.

2. Convert construction and guarded handling across execution-plan owners. Serves AC-001 and AC-002, and the common propagation and message criteria. Touch engine lifecycle execution codec, decode, effective-policy, resolver, recheck and entry owners; featuretask persistence, crash reconciliation, runner, review regeneration and backward-edge owners; goalrunner manifest admission, block writes and child persistence. Also convert application `skillbill/application/workflow/persist/WorkflowServiceInputMapping.kt`, infra-contracts execution-plan schema and coherence owners, infra-sqlite `skillbill/infrastructure/sqlite/workflow/featuretask/FeatureTaskExecutionPlanWriteGuard.kt`, and infra-workflow `skillbill/infrastructure/workflow/featuretask/FileSystemFeatureTaskRuntimeSharedEvidenceStoreReads.kt`. Use factories or coded constructors at every former construction, including returned failures. In `FeatureTaskRuntimeExecutionPlanCompatibility.kt`, handle only `FeatureTaskRuntimeFailureCode.INVALID_EXECUTION_PLAN_SCHEMA` when producing corrupt admission and retain the original failure as suppressed. Replace typed checks with exact code checks and rethrow unrelated codes. Preserve rollback, immutable descriptors, stored evidence, interruption and cooperative cancellation. Assumption for implement to confirm: the digest's owner groups contain the remaining concrete construction sites; it does not enumerate every filename or factory signature. Derive those details from the existing constructors without changing their behavior.

3. Preserve admission and continuation reporting at their boundaries. Serves all three acceptance criteria. In engine `skillbill/engine/featuretask/lifecycle/execution/FeatureTaskRuntimeExecutionAdmission.kt` and `lifecycle/continuation/FeatureTaskContinuationLookupService.kt`, merge the admission, regeneration and shared-exception handlers into one `SkillBillRuntimeException` catch per boundary. Admission codes and regeneration refusals map to their existing `wireValue`; `FeatureTaskRuntimeFailureCode.INVALID_EXECUTION_IDENTITY_SCHEMA` maps to `invalid_route_identity`. Every other code rethrows without an admission warning. Keep migration before failure classification, as recorded in `runtime-kotlin/runtime-engine/src/main/kotlin/skillbill/engine/goalrunner/agent/decisions.md#b27616b4c73f`. Keep the exact warning `Execution admission refused workflow=<bounded-id> reason=<wire>`, the 128-character workflow bound and rethrow of the original failure after warning. Add one admission warning-and-rethrow case and one regeneration warning-and-rethrow case using existing engine fixtures. These are required because the digest found no warning-text coverage. Each catches the concrete regression where the merged catch emits the wrong reason or swallows the failure. Assert the full warning with a workflow ID longer than 128 characters, the exact code and rethrow identity; reuse existing continuation tests rather than duplicating the same warning branch at both boundaries.

4. Preserve shell-content and MCP handled sets. Serves AC-002 and AC-003 and the common capture-parity criterion. Register `FeatureTaskRuntimeExecutionPlanAdmissionCode` in contracts `skillbill/error/shellcontent/ShellContentContractFailures.kt`; the reused `FeatureTaskRuntimeFailureCode` family is already registered. Add `FeatureTaskRuntimeRegenerationRefusal` to runtime-mcp `skillbill/mcp/core/McpToolDispatcher.kt`'s `uncapturedAtMcp()` predicate because its former class was an `IllegalStateException`. Do not add regeneration refusal to shell-content classification. Update reachable former ISE handlers to discriminate that code, and guard reachable shared-exception handlers so the newly shared regeneration failure retains its previous route. Preserve cancellation-first dispatch, the unchanged tool error message and capture behavior for unrelated failures. Keep existing guarded database and shell-content boundaries. No new telemetry test is planned for this slice; its only new behavioral test obligations are the two warning cases above, while the existing MCP diagnostics suite supplies regression coverage during validate.

5. Retarget existing regression coverage and shrink the throwable baseline. Serves all acceptance criteria and the common baseline criterion. In runtime-engine tests, update `FeatureTaskContinuationAdmissionTest`, `WorkerTakeoverFencingTest`, `FeatureTaskExecutionPlanCreationTest`, `AuditPlanningExecutionPlanMappingTest`, `QuarantinedProducerRecoveryRefusalTest`, `FeatureTaskRuntimeQuarantineRegenerateTest`, slot `FeatureTaskRuntimeExecutionPlanResolverTest`, `FeatureTaskRuntimeEffectivePoliciesTest`, and review `FeatureTaskRuntimeSharedReviewEvidenceResolverTest`. In the corresponding infra modules, update `FeatureTaskRuntimeExecutionPlanRawBoundaryTest`, `FeatureTaskRuntimeExecutionPlanCoherenceTest`, `FeatureTaskExecutionPlanWriteGuardTest`, `FileSystemFeatureTaskRuntimeSharedEvidenceStoreTest`, and `FeatureTaskRuntimeSharedEvidenceProjectionReadValidationTest`. Update application `ReviewPreparationServiceTest`'s constructed fingerprint failure. Replace subclass tables with exact code tables, assert the shared exception type plus code, and replace `error.refusal` assertions with `error.code`. Keep existing messages, payloads, refusal-before-claim, immutable-descriptor, recovery, fingerprint and write-conflict assertions intact. Remove only the nine deleted classes' whole rows by hand from `../../../runtime-kotlin/runtime-core/src/repoTest/kotlin/skillbill/architecture/baselines/custom-throwable-baseline.txt`. Preserve `FailureCodeTotalityArchitectureTest`'s nonempty scan and synthetic rejection fixture; leave `ArchitectureScanSupport.kt` unchanged.

6. Confirm the repository end state and hand off command proof. Serves AC-001 through AC-003 and the common criteria. Implement checks for surviving owned declarations, aliases, old constructions and caught `reasonCode` or `refusal` reads, and confirms every touched boundary retains its handled set. Apply A1 and A2 ownership, A4 declaration-only ports, A7 classification and propagation, A9 and A10 placement, and G7 baseline reduction. Add no module, dependency, exception property, family metadata, typealias, suppression, relaxed mock or new `runCatching`; respect package direction, centralized wire keys, interface-only KDoc and detekt limits. The digest establishes that other subtasks still own remaining legacy classes, so this subtask is expected to leave the transition open. Implement confirms the transition condition across source sets; only if no subclasses or codeless callers remain may it remove the legacy bases and constructor, make the shared exception final and update the required documentation under the existing guards. Otherwise report the remaining owner and leave unrelated classes unchanged. This conditional check creates no dependency on another subtask.

Build owns compile/buildability proof. Validate owns execution of the affected unit tests listed above, existing MCP diagnostics, detekt, formatting and runtime-core repoTest checks including `FailureCodeTotalityArchitectureTest`, followed by the installed runtime's full validation gate. Spotless runs in a plain clone. Plan and implement execute no build, test or check commands. Audit inspects each criterion; review and validate may repair production wiring, test setup, formatting or lint while preserving behavior, assertions and architecture rules. History, commit and PR actions remain with their owning phases. No schema version, persisted wire-format change or installation refresh is part of this plan.

The digest settles enum reuse, the warning-test obligation and migration ordering. No external decision remains open. Implement confirms the assumed concrete site names and test fixture placement from the current tree; this does not require another preplan or decomposition.
