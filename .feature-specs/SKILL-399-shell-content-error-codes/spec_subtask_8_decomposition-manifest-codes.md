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

## Implementation Details

The upstream preplan digest is the only source for this plan. AC-001 to AC-004 are the four numbered Acceptance Criteria above. All paths are relative to `runtime-kotlin/`. This plan adds no new decomposition and needs no dependency work.

**The digest corrects the Facts above.** Implement follows the corrections:

- `GoalPreflightLookupResolver.kt:121–124` throws `missing_manifest` ("goal continuation has no readable decomposition manifest"), not `duplicate_active`.
- `duplicate_active` is thrown by application `decomposition/DecompositionManifestDiscovery.kt:97` (`resolveDecompositionManifest`). `GoalPreflightServiceTest:207` reaches it through preflight.
- `issue_key_mismatch` has two sites: `GoalPreflightInputValidation.kt:57` and `DecompositionManifestDiscovery.kt:74`.
- `incomplete_bundle` is thrown from `DecompositionManifestDiscovery.kt:64`, which wraps `NoSuchFileException` with a cause, and from infra/workflow `DecompositionManifestBundleJournalOperations.kt:49`. The spec gives it no entry.
- The runtime-contracts thrower is `DecompositionPlanningContracts.kt:301` (`invalidPlanning`, `invalid_shape`).

**Decisions settled from the digest:**

- **One `WorkflowFailureCode` entry per journal literal** (about 25), following this spec's Codes rule.
- **Two extra manifest entries:** `DECOMPOSITION_MANIFEST_MISSING_MANIFEST` and `DECOMPOSITION_MANIFEST_INCOMPLETE_BUNDLE`. Neither literal is a `DecompositionManifestValidationFailureCode` wire value, so they duplicate nothing. Both count in the predicate, because the former catches handled the whole class.

### Ordered tasks

**1. Codes and message functions (AC-001).**

File: `runtime-contracts/src/main/kotlin/skillbill/error/shellcontent/WorkflowShellContentErrors.kt`.

- Create `enum class WorkflowFailureCode : RuntimeFailureCode`. If subtask 7 has already created it, add entries and keep its entries unchanged.
- Add these manifest entries:
  - `DECOMPOSITION_MANIFEST_INVALID_SHAPE`
  - `DECOMPOSITION_MANIFEST_ISSUE_KEY_MISMATCH`
  - `DECOMPOSITION_MANIFEST_DUPLICATE_ACTIVE`
  - `DECOMPOSITION_MANIFEST_MISSING_MANIFEST`
  - `DECOMPOSITION_MANIFEST_INCOMPLETE_BUNDLE`
- Add one `DECOMPOSITION_MANIFEST_BUNDLE_JOURNAL_<LITERAL_UPPERCASE>` entry for each journal literal:
  - From the infra-contracts journal validator: `unsupported_contract_version`, `schema_invalid`, `root_not_object`, `yaml_parse_error`, `yaml_object_error`, `schema_resource_missing`, `schema_load_error`, `schema_identity_error`.
  - From the infra/workflow `DecompositionManifestBundleJournalValidation.kt`: `journal_read_error`, `entries_missing`, `entry_not_object`, `entry_field_invalid`, `entry_incomplete`, `entry_path_invalid`, `target_outside_parent`, `target_escape`, `staged_outside_staging`, `staged_escape`, `duplicate_target`, `duplicate_staged`, `staged_digest_mismatch`, `target_digest_mismatch`, `invalid_marker_name`, `staging_directory_mismatch`, `staging_directory_escape`.
  - **Assumption:** this list is complete. Implement confirms it against the throw sites and adds an entry for any literal it finds that is not listed.
- If `WorkflowFailureCode` is new here, add `code is WorkflowFailureCode` to `Throwable.isShellContentContractFailure()` in `ShellContentContractFailures.kt`.
  - Both deleted classes extend `ShellContentContractException`, so the ~55 guarded sites absorb them today. Without this registration, propagation would change at every one of those sites.
  - Keep the `ScaffoldFailureCode` exclusion and every existing term.
- Add message functions next to the enum. Both classes have many throw sites.
  - `invalidDecompositionManifestSchema(sourceLabel, reason, code: RuntimeFailureCode, cause: Throwable? = null): SkillBillRuntimeException`. Copy the deleted class's message template verbatim; the digest does not quote it.
  - `invalidDecompositionManifestBundleJournal(sourceLabel, reason, code: RuntimeFailureCode, cause: Throwable? = null): SkillBillRuntimeException`, with the message `"Decomposition manifest bundle journal '${sourceLabel.ifBlank { "<unknown>" }}' is invalid: $reason"`.
- Delete both classes from the file.
- Delete exactly two rows from `runtime-core/src/repoTest/kotlin/skillbill/architecture/baselines/custom-throwable-baseline.txt`: `runtime-contracts:InvalidDecompositionManifestBundleJournalError` and `runtime-contracts:InvalidDecompositionManifestSchemaError`. Edit no other row.
- Confirm that `DecompositionManifestValidationFailureCode` implements `RuntimeFailureCode`. The digest says the `FailureWireCode` enums already do. Do not make `FailureWireCode` extend it.

**2. Domain predicate and result carrier (AC-002, AC-004).**

File: runtime-domain `skillbill/workflow/decomposition/model/DecompositionManifestValidationModels.kt`. It already imports `skillbill.error.shellcontent` and `skillbill.error.core.*`, and the package-cycle baseline stays empty.

- Add `fun Throwable.isDecompositionManifestSchemaFailure(): Boolean`.
  - True when the code `is DecompositionManifestValidationFailureCode`.
  - True when the code is in a private `setOf` of the five `WorkflowFailureCode.DECOMPOSITION_MANIFEST_*` manifest entries. Reference entries, not wire strings.
  - Journal entries are excluded.
- Add `failure: SkillBillRuntimeException? = null` to `DecompositionManifestValidationResult.Rejected(code, reason, sourceLocation?)`.
- Change `requireAccepted` (around :121–125):
  - If `failure` is present, rethrow it.
  - Otherwise throw `invalidDecompositionManifestSchema(label, reason, code)`, passing the `code` entry itself instead of `code.wireValue`.
- Add no `java.nio`, no ports import and no new dependency.

**3. The only main reader of `failureCode`/`reason` (AC-002).**

File: infra-contracts `DecompositionManifestSchemaValidator.kt:237–240`, inside `validateYamlTextResult`.

- Replace the typed catch with `catch (error: SkillBillRuntimeException) { error.rethrowUnless(error.isDecompositionManifestSchemaFailure()) ... }`.
- Build `Rejected(code = rejectedCode(error.code), reason = error.message.orEmpty(), failure = error)`, keeping the existing `sourceLocation` handling.
- Add a private `rejectedCode(code)` that maps:
  - a `DecompositionManifestValidationFailureCode` to itself;
  - `WorkflowFailureCode.DECOMPOSITION_MANIFEST_INVALID_SHAPE` to `INVALID_SHAPE`;
  - anything else to `SCHEMA_INVALID`.
- Never call `fromWire` on Workflow entries: it would throw `UnrecognizedFailureWireCodeError`.
- Keep the try body, and propagation for everything else, unchanged.
- **The `reason` change.** `reason` changes from the raw reason to the full message. Because `requireAccepted` rethrows the carried failure, the thrown text stays byte-identical. This only holds if each caller passes the same label to both calls.
  - Callers to check: `DecompositionManifestDiscovery`, both `DecompositionManifestFileWrites` functions and `GoalRunnerPurgeCoordinator`.
  - **Assumption:** they do. If a caller differs, it keeps the rebuild path.
  - Check every other main reader of `Rejected.reason`. If any renders it into a payload or message, its output would change. **Assumption:** the digest names no such reader. If implement finds one, it keeps that output byte-identical, for example by storing the raw text from the throw site in a value; it must not read a property back from the exception.

**4. Schema-error producers (AC-001).**

Pass domain enum entries directly, never wire strings:

- `DecompositionManifestSchemaValidator.kt`: :73 `SCHEMA_INVALID`, :104 `ROOT_NOT_OBJECT`, :140 `MALFORMED`, :155 `INVALID_SHAPE`. :126 `DUPLICATE_KEY` and :133 `MALFORMED` already use the entry, so drop their `.wireValue`.
- `DecompositionManifestCoherenceValidator.kt:172`: `COHERENCE_INVALID`.
- Domain `DecompositionManifestWireCodec.kt:240`: `INVALID_SHAPE`.
- Domain `runtime/DecompositionManifestWriterErrors.kt` `invalidManifest(sourceLabel, reason)`: `SCHEMA_INVALID`.
- Application `DecompositionManifestFileWrites.kt:44`: `REPAIR_LIMIT_EXCEEDED`.
- **Assumption:** each literal has an entry in the domain enum. If one does not, use `SCHEMA_INVALID`, which matches `fromWire(null)`. Add no domain entry, because that would change the totality-checked wire enum.

Use the Workflow entries at these sites:

- `DecompositionPlanningContracts.kt:301`: `DECOMPOSITION_MANIFEST_INVALID_SHAPE`. This is runtime-contracts, so it does not import the domain enum.
- `GoalPreflightInputValidation.kt:57` and `DecompositionManifestDiscovery.kt:74`: `ISSUE_KEY_MISMATCH`.
- `DecompositionManifestDiscovery.kt:97`: `DUPLICATE_ACTIVE`.
- `GoalPreflightLookupResolver.kt:121–124`: `MISSING_MANIFEST`.
- `DecompositionManifestDiscovery.kt:64` (keep the cause) and `DecompositionManifestBundleJournalOperations.kt:49`: `INCOMPLETE_BUNDLE`.

Any lambda or function type returning the deleted class now returns `SkillBillRuntimeException`.

**5. Journal producers (AC-001).**

- Infra-contracts `DecompositionManifestBundleJournalSchemaValidator.kt`: each literal becomes its journal entry through the message function.
- Delete the redundant `catch (journal) { throw error }` at :113 that sits before `catch (IllegalArgumentException)`. `SkillBillRuntimeException` is not an `IllegalArgumentException`, so the passthrough is a no-op.
- Infra/workflow `DecompositionManifestBundleJournalValidation.kt`:
  - The private `journalError(sourceLabel, reason, failureCode)` at :300 takes `code: WorkflowFailureCode` and delegates to the message function.
  - The `.also { it.initCause(error) }` calls at :28/:34 (`journal_read_error`) become the constructor `cause`.
- Leave containment, digest, marker and recovery logic unchanged.

**6. Catch sites (AC-002).**

These become `catch (error: SkillBillRuntimeException) { error.rethrowUnless(error.isDecompositionManifestSchemaFailure()) ... }`, with each branch body and its order relative to other catches kept:

- Application `decomposition/DecompositionManifestWriter.kt:284`
- Infra/workflow `FileSystemFeatureTaskRuntimeRunInvariantsSource.kt:64`
- Application `review/spec/SpecIntentProjectionResolver.kt:136`

Implement confirms there are no other `catch`, `is` or `as?` sites on either class and converts any it finds the same way. A journal-class site would use `code` membership in the journal entries. Add no new `runCatching`, and let cancellation and interruption keep propagating.

**7. Rendered labels.** Touched sites that render a caught throwable's class name use `failureCodeLabel() ?: <existing expression>`. A test that pins either deleted class name switches to the code label.

**8. Test conversions (AC-003 and common criteria).**

- `GoalPreflightServiceTest`:
  - The `assertFailsWith` at :150, :173 and :200 becomes `assertFailsWith<SkillBillRuntimeException>`.
  - At :180, `failureCode == "issue_key_mismatch"` becomes `assertEquals(WorkflowFailureCode.DECOMPOSITION_MANIFEST_ISSUE_KEY_MISMATCH, error.code)`.
  - At :207, `failureCode == "duplicate_active"` becomes `assertEquals(WorkflowFailureCode.DECOMPOSITION_MANIFEST_DUPLICATE_ACTIVE, error.code)`.
  - If a case asserts the missing-manifest literal, it uses `DECOMPOSITION_MANIFEST_MISSING_MANIFEST`.
- `DecompositionManifestBundleJournalValidationTest` (11 references): :138 `duplicate_staged`, :251 `schema_invalid` and :274 `unsupported_contract_version` become the matching `DECOMPOSITION_MANIFEST_BUNDLE_JOURNAL_*` entries.
- Apply the same type-to-code edit wherever either class appears:
  - `DecompositionManifestValidationTest` (13), `SchemaValidatorPortLoudFailTest` (8) and `FeatureSpecPreparationWriterTest` (8)
  - `DecompositionManifestCodecTest`, `DecompositionManifestWriterTest`, `DecompositionManifestSchemaValidatorTest` and `WorkflowServiceTest`
  - `DecompositionManifestNestedProjectionTest`, `RuntimeFilesystemArchitectureProbeTest`, `DecompositionManifestWriterValidationTest` and `DecompositionManifestWriterSelectorTest`
  - `FileSystemDecompositionManifestFileStoreTest`, `DecompositionManifestValidationRepairTest` (:65 becomes `MALFORMED`), `DecompositionPlanningContractsTest` and `DecompositionPlanningIngressTest`
- Wherever an old assertion compared a wire string, assert the domain enum entry.
- Message, `contains`, payload and exit-code assertions stay byte-for-byte. Tests that construct either class switch to the message function.
- No new tests: the Test obligations section asks for none (`test_obligations: []`). The converted assertions already catch the realistic bugs: the wrong preflight or journal code, and a message changed by wrapping.

**9. Transition check.** Subclasses of `ShellContentContractException` remain outside shellcontent: `PhaseSlotContractErrors.kt`, the core decode, JSON, platform-pack and add-on errors, the execution-plan errors and `InvalidMcpToolArgumentError`. These belong to SKILL-400 and SKILL-398. So keep the open base classes, `LegacyFailureCode`, the codeless constructor and the predicate. Implement only reconfirms this.

### Constraints

- No typealias for a deleted class.
- No property on `SkillBillRuntimeException`.
- No `@Suppress`, new `runCatching`, module, dependency or relaxed mock.
- No `//` or non-KDoc block comments.
- `skillbill.error.core` must not import `shellcontent`.
- runtime-contracts declares no engine, infra or MCP code.
- runtime-domain imports no `java.nio` and no ports.
- Wire strings stay on the enums.
- detekt limits: `ThrowsCount` 2, `ReturnCount` 4, `LongMethod` 70, `CyclomaticComplexMethod` 15. Splitting the `rejectedCode` helper out of the catch keeps the catch within these limits.
- `ArchitectureScanSupport.kt` must not grow.
- Classes owned by subtask 7 stay as they are.

### Validation

Validation belongs to the validate phase only; implement runs nothing. It covers build, unit tests and detekt, plus the runtime-core repoTest suite. That suite includes `FailureCodeTotalityArchitectureTest` (stale and unlisted baseline rows), `PortsDeclarationArchitectureTest`, `WireVocabularyArchitectureTest`, and the package-cycle, typed-parse and comment guards. The validate phase also runs the converted tests named in task 8.

## Next Path

skill-bill goal SKILL-399

## Spec Path

.feature-specs/SKILL-399-shell-content-error-codes/spec_subtask_8_decomposition-manifest-codes.md
