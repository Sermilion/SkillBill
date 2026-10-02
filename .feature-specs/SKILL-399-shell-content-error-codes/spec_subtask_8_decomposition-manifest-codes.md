# SKILL-399 Subtask 8 - decomposition-manifest-codes

Parent spec: [.feature-specs/SKILL-399-shell-content-error-codes/spec.md](./spec.md)
Issue key: SKILL-399

## Scope

Convert `InvalidDecompositionManifestSchemaError` and `InvalidDecompositionManifestBundleJournalError` (both in `WorkflowShellContentErrors.kt`).

- **Facts.** `InvalidDecompositionManifestSchemaError.failureCode` is a free `String`.
  - Most throw sites use `DecompositionManifestValidationFailureCode` (runtime-domain) wire values.
  - `GoalPreflightInputValidation.kt:54` and `GoalPreflightLookupResolver.kt:121` use `issue_key_mismatch` and `duplicate_active`, which `GoalPreflightServiceTest:179`, `:206` assert.
  - `DecompositionPlanningContracts.kt:298`, in runtime-contracts, uses `invalid_shape`, and cannot see the domain enum.
  - `InvalidDecompositionManifestBundleJournalError.failureCode` carries the journal codes that `DecompositionManifestBundleJournalValidationTest` asserts (`duplicate_staged`, `schema_invalid`, `unsupported_contract_version`).
- **Codes.**
  - Add these `WorkflowFailureCode` entries (create the enum if subtask 7 has not): `DECOMPOSITION_MANIFEST_INVALID_SHAPE`, used only by the runtime-contracts thrower; `DECOMPOSITION_MANIFEST_ISSUE_KEY_MISMATCH`; `DECOMPOSITION_MANIFEST_DUPLICATE_ACTIVE`; and one entry per distinct bundle-journal `failureCode` literal at its throw sites.
  - Every other decomposition-manifest throw uses the `DecompositionManifestValidationFailureCode` entry for its wire value. Where no code was passed, it uses `SCHEMA_INVALID`, matching `fromWire(null)`.
  - Pass enum entries directly, not wire strings. Add no parallel entry.
- **Classification.** In runtime-domain `skillbill.workflow.decomposition.model`, add `fun Throwable.isDecompositionManifestSchemaFailure(): Boolean`. It is true for `code is DecompositionManifestValidationFailureCode`, or for one of the `WorkflowFailureCode` decomposition entries. Former `catch (e: InvalidDecompositionManifestSchemaError)` sites use it. Check the domain model-package import rule before adding the `skillbill.error.shellcontent` import.
- **`DecompositionManifestSchemaValidator.kt:235-238`.** The `try` body spans many domain and infra throwers.
  - `DecompositionManifestValidationResult.Rejected` gains `failure: SkillBillRuntimeException? = null`.
  - The catch builds `Rejected(code = <the failure's DecompositionManifestValidationFailureCode, mapping DECOMPOSITION_MANIFEST_INVALID_SHAPE → INVALID_SHAPE and anything else to SCHEMA_INVALID>, reason = failure.message.orEmpty(), failure = it)`.
  - `requireAccepted` rethrows `failure` when present, and otherwise builds the failure as before.
  - Messages stay byte-identical, because every caller passes the same label to both calls. Verify this for `DecompositionManifestDiscovery`, `DecompositionManifestFileWrites` (both functions) and `GoalRunnerPurgeCoordinator`.
- **Tests.** `error.failureCode == "x"` assertions become `error.code == <entry>`.
- **SKILL-398 subtask 6.** If it already made a validation result throw a code where this class was rethrown, keep that and use this subtask's entry.

## Acceptance Criteria

1. `WorkflowShellContentErrors.kt` declares neither class.
2. No main code reads `failureCode` or `reason` from a caught exception in `DecompositionManifestSchemaValidator`.
3. `GoalPreflightServiceTest` and `DecompositionManifestBundleJournalValidationTest` assert codes, not strings, with messages unchanged.
4. runtime-domain stays free of `java.nio` and ports imports.

## Non-Goals

The other Workflow classes (subtask 7).

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

.feature-specs/SKILL-399-shell-content-error-codes/spec_subtask_8_decomposition-manifest-codes.md
