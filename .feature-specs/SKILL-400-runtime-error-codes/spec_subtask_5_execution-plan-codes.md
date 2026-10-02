# SKILL-400 Subtask 5 - execution-plan-codes

Parent spec: [.feature-specs/SKILL-400-runtime-error-codes/spec.md](./spec.md)
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
