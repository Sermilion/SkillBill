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

## Implementation Details

Plan from the supplied preplan digest only. AC-001 through AC-004 refer to the four numbered Acceptance Criteria above. No dependency work or new decomposition is required. All paths below are relative to `runtime-kotlin/` unless stated otherwise.

1. Establish the owned codes and replace the two exception declarations. Serves AC-001 and the common message and baseline criteria. In `runtime-contracts/src/main/kotlin/skillbill/error/shellcontent/WorkflowShellContentErrors.kt`, remove `InvalidDecompositionManifestSchemaError` and `InvalidDecompositionManifestBundleJournalError`. Create or extend the sibling `WorkflowFailureCode` enum without changing entries owned by subtask 7. Add `DECOMPOSITION_MANIFEST_INVALID_SHAPE`, `DECOMPOSITION_MANIFEST_ISSUE_KEY_MISMATCH` and `DECOMPOSITION_MANIFEST_DUPLICATE_ACTIVE`. Give every distinct journal literal its own entry. The schema-validator literals are unsupported contract version, schema invalid, root not object, YAML parse error, YAML object error, schema resource missing, schema load error and schema identity error. The workflow-validator literals are journal read error, entries missing, entry not object, entry field invalid, target outside parent, target escape, staged outside staging, staged escape, entry path invalid, duplicate target, duplicate staged, entry incomplete, staged digest mismatch, target digest mismatch, invalid marker name, staging-directory mismatch and staging-directory escape. Assumption for implement to confirm at the producer sites: name these entries with a consistent `DECOMPOSITION_MANIFEST_BUNDLE_JOURNAL_` prefix and the existing literal's uppercase suffix. Use the existing domain `DecompositionManifestValidationFailureCode` directly for other manifest failures, defaulting absent codes to `SCHEMA_INVALID`. Preserve structured constructor parameters and causes in message factories beside the owning enum when multiple sites need them. Inline message-only constructions and single-site constructions. Copy every original message expression byte-for-byte; never derive a code by parsing a message. This step adds no tests. Later validation runs the existing code-totality coverage.

2. Carry the original manifest failure through rejection and acceptance. Serves AC-002 and AC-004, and prevents double-prefixing messages. In `runtime-domain/src/main/kotlin/skillbill/workflow/decomposition/model/DecompositionManifestValidationModels.kt`, add `failure: SkillBillRuntimeException? = null` to `DecompositionManifestValidationResult.Rejected`. Make `requireAccepted(sourceLabel)` rethrow that exact failure when present; otherwise construct the coded manifest failure using the result's existing code and reason. Add the pure `Throwable.isDecompositionManifestSchemaFailure()` predicate in the same model package. It handles the domain validation-code family and exactly the three Workflow manifest entries, excluding journal codes and unrelated failures. Contracts' shellcontent enum imports are compatible with the digest's model-package guard evidence. Do not add validators, filesystem behavior, ports imports or `java.nio` to domain. In `runtime-infra/contracts/src/main/kotlin/skillbill/infrastructure/contracts/workflow/decomposition/DecompositionManifestSchemaValidator.kt`, replace the typed catch with a guarded `SkillBillRuntimeException` catch and `rethrowUnless`. Preserve a domain validation code directly, map the contracts-only invalid-shape entry to `INVALID_SHAPE`, and map other recognized manifest entries to `SCHEMA_INVALID`. Store the original failure and `failure.message.orEmpty()` in `Rejected`, preserving existing source-location handling. Read only `code`, `message` and `cause` from caught failures. Preserve the whole validate/decode/repair-evidence try body and propagation of database, gate, cancellation and interruption failures. Later validation runs the existing schema-validator, validation and repair tests; retain their message and rejection assertions while converting exception assertions.

3. Convert manifest and journal producers and consumers together. Serves AC-001, AC-002 and AC-004. Update `runtime-contracts/src/main/kotlin/skillbill/contracts/decomposition/DecompositionPlanningContracts.kt` to use the Workflow invalid-shape entry without importing the domain enum. Update engine `goalrunner/preflight/GoalPreflightInputValidation.kt` and `GoalPreflightLookupResolver.kt` to use the issue-key-mismatch and duplicate-active entries. Convert all other owned manifest producers to the domain enum and all former manifest catches or type checks to the narrow domain predicate. Convert journal constructions in `runtime-infra/contracts/src/main/kotlin/skillbill/infrastructure/contracts/workflow/decomposition/DecompositionManifestBundleJournalSchemaValidator.kt` and `runtime-infra/workflow/src/main/kotlin/skillbill/infrastructure/workflow/decomposition/DecompositionManifestBundleJournalValidation.kt`, plus their consumers, to the owning Workflow entries. Keep path containment, marker identity, digest checks, attached causes and atomic bundle recovery unchanged. The digest establishes matching validation and acceptance labels in application `DecompositionManifestDiscovery`, both paths in `DecompositionManifestFileWrites`, and engine `GoalRunnerPurgeCoordinator`; preserve those labels without adding normalization or extra wrapping. Keep any value-based parse repairs already present. Later validation runs `DecompositionPlanningContractsTest`, `DecompositionManifestCodecTest`, application manifest writer tests and core `DecompositionManifestWriterValidationTest`, as well as the journal and file-store coverage named below.

4. Convert the existing regression assertions without adding duplicate tests. Serves AC-003 and the common unchanged-message criteria. Update engine `GoalPreflightServiceTest` and infrastructure workflow `DecompositionManifestBundleJournalValidationTest` to assert `SkillBillRuntimeException` and exact enum entries instead of former classes or `failureCode` strings. Apply the same conversion to existing manifest schema, validation, repair, codec, planning-contract, application writer, core writer-validation and `FileSystemDecompositionManifestFileStoreTest` assertions and fixtures affected by the removed types. Preserve message, payload, wire-fixture, expected-output and recovery assertions exactly. Preserve existing test source sets and resource paths. The realistic bugs covered by these existing tests are selecting the wrong preflight or journal code, losing the original validation message through wrapping, and accepting an unsafe or incomplete journal during recovery. `test_obligations` is empty for new tests, matching the digest and the Test obligations section; retain all existing regression and governed parity coverage. Test execution belongs to validate, not plan or implement.

5. Complete shared classification and baseline maintenance, then hand off verification. Serves AC-001 through AC-004 and all common criteria. In contracts' `ShellContentContractFailures.kt`, include `WorkflowFailureCode` when newly introduced, preserving `GoalTelemetryRowFailureCode`, existing coded families and the Scaffold exclusion. Retain `failureCodeLabel() ?: existingExpression` at touched rendering seams; converted failures may render their enum label while uncoded labels and payloads stay unchanged. Remove only the two deleted classes' whole rows from `runtime-core/src/repoTest/kotlin/skillbill/architecture/baselines/custom-throwable-baseline.txt`. During implement, check remaining subclasses and codeless constructions before deciding whether to finish the shared transition. The digest establishes external uses, so retain the open exception, legacy base and codeless constructor unless intervening work has removed every use. If the condition becomes true, apply the parent rule supplied in this briefing, retaining coded classification and every guarded rethrow. Do not change unrelated classes, weaken baselines or fixtures, or grow `ArchitectureScanSupport.kt`. Audit and review inspect the resulting end states and apply A1 through A12 and G1 through G7, especially handled sets, wire ownership, package cycles and test placement. Validate owns unit tests, detekt, formatting and repository architecture suites, including `FailureCodeTotalityArchitectureTest`, package-cycle, typed-parse, wire-vocabulary, ports-declaration and comment guards. It also owns the full gate `./gradlew check --continue --parallel -q --warning-mode none` under JDK 21 and strict agnix checks required by CI. Spotless must run in a plain clone. Any compile/build proof remains with the runtime's build owner. Review and validation may repair required production wiring, test setup, formatting or lint while preserving behavior and architecture rules.

Across these tasks, preserve recovery, quarantine, measurement and diagnostic records. No persisted schema, payload, feature flag or dependency change is planned. Add no module, alias, public raw-map result, dependency bag, locator accessor, exception property, suppression or new `runCatching`. Core must not import shellcontent, contracts must not import engine, infra or MCP, and ports remain declaration-only. Keep the SKILL-380 attempt boundary phase-generic and accepted-step authority intact. Respect the existing detekt limits, add no authored Kotlin line or block comments, and permit KDoc only on interfaces and their members. The plan does not restrict later required repairs beyond these contracts and the explicit operator constraints. History, commit, PR and installation work remain with their owners; this conversion needs no skill or generated-artifact changes. No command execution, builds or tests are part of this plan phase.
