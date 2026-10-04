# SKILL-398 Subtask 1 - failure-policy-and-throwable-baseline

Parent spec: [.feature-specs/SKILL-398-runtime-exception-reduction/spec.md](spec.md)
Issue key: SKILL-398

## Scope

(F-001) Replace the written policy that prescribes a typed exception class per contract with the three-tier failure model in the parent spec.

- `../../../docs/code-principles.md`, section "Failure Contracts" (lines 34-62 at the census tree): rewrite Rule, Preferred shapes, Anti-patterns and Reference examples. Rule: defects use `require`/`check`/`error()` and are never caught for control flow; expected outcomes are returned as sealed results, nullables or existing outcome types by the function that knows; failures that end the run throw `SkillBillRuntimeException` with an owner-declared `RuntimeFailureCode`; a new custom `Throwable` subclass must earn its place (a failure that crosses a boundary the runtime does not own and cannot be tier 3) and its reason is recorded in `runtime-kotlin/agent/decisions.md`. Keep the existing wire-code totality rule, the parse-boundary rule (no `error()`/`require` for untrusted input) and the cancellation rule. Anti-patterns: a class per contract or per message; throwing to report absent, refused or conflicting outcomes; catching `IllegalArgumentException`/`IllegalStateException` for control flow; branching on exception message text. Fix the stale reference path `runtime-kotlin/runtime-contracts/src/main/kotlin/skillbill/error/FailureWireCodeContract.kt` to its current location.
- `AGENTS.md:60`: a new contract gets a `RuntimeFailureCode` entry in its owner's code enum, not a typed `Invalid<Contract>SchemaError`.
- `../../../runtime-kotlin/ARCHITECTURE.md` lines that describe the `skillbill.error` "runtime exception taxonomy" (about 380, 394, 636 at the census tree): describe the failure codes and the single runtime failure type instead. Do not rewrite unrelated text.
- `../../../runtime-kotlin/agent/decisions.md`: append a dated entry "Failure model: results for expected outcomes, one runtime failure type with owner codes, defects via require/check". Context: the census in investigation.md. Decision: the three tiers and the baseline. Reason: 62% of types never discriminated; both edges discard the type. Supersedes: the "typed errors" retention of SKILL-349, SKILL-374 and SKILL-391, keeping their ownership placement. Alternatives considered: `IllegalStateException` with a code; guard only; results for everything (all rejected, see investigation.md).
- `TypedParseBoundaryArchitectureTest`: change only the wording of its failure messages from "typed contract failure" to "a result or a SkillBillRuntimeException code". The scan itself stays.

(Target type) Add `RuntimeFailureCode`, the `code` constructor parameter on `SkillBillRuntimeException`, and the transitional secondary constructor with `LegacyFailureCode.UNCLASSIFIED`, exactly as the parent spec's "Target failure model" defines them, in `../../../runtime-kotlin/runtime-contracts/src/main/kotlin/skillbill/error/core`. Existing subclasses keep compiling through the secondary constructor. Make the four `FailureWireCode` enums listed in `FailureCodeTotalityArchitectureTest` implement `RuntimeFailureCode`.

(F-002) Add a two-sided custom-throwable baseline.

- Extend `ArchitectureScanSupport` (or `ArchitectureScanGuardSupport`, wherever the other baseline drift scans live) with a scan of production main Kotlin sources under `../../../runtime-kotlin` (paths resolved from `ArchitectureScanSupport.runtimeRoot`, which is the repo root, so roots start with `runtime-kotlin/`). It collects class and object declarations and resolves supertypes by simple name transitively across all scanned files, starting from `Throwable`, `Exception`, `RuntimeException`, `Error`, `IllegalStateException`, `IllegalArgumentException`, `UnsupportedOperationException`, `IOException` and `NoSuchElementException`. Each match is one row `<gradle module path>:<SimpleName>`, for example `runtime-engine:OperationRefusalError`, so file moves inside a module do not churn the baseline.
- Record the rows in `../../../runtime-kotlin/runtime-core/src/repoTest/kotlin/skillbill/architecture/baselines/custom-throwable-baseline.txt` through `ArchitectureBaselineRecorder`, sorted.
- Add one test method to `FailureCodeTotalityArchitectureTest`: drift between the scan and the baseline fails, naming each declaration missing from the baseline ("... declares custom throwable X; return a result, use require/check, or throw SkillBillRuntimeException with a code") and each baseline row that no longer exists. Add one synthetic-fixture test for both directions, in the style of the existing synthetic tests in that class.
- Add `RuntimeFailureCode` implementers to the totality scan only where they are `FailureWireCode`; plain code enums have no wire value and need no totality check.

## Acceptance Criteria

1. `../../../docs/code-principles.md` "Failure Contracts" states the three tiers, the earns-its-place rule with its decisions.md requirement, and the four anti-patterns above; it no longer names `Invalid*SchemaError` as a preferred shape, and every reference example path exists.
2. `../../../AGENTS.md` no longer requires a typed `Invalid<Contract>SchemaError` for a new contract and names the owner code enum instead.
3. `../../../runtime-kotlin/ARCHITECTURE.md` describes `skillbill.error` as the failure codes plus the single runtime failure type, not a "runtime exception taxonomy".
4. `../../../runtime-kotlin/agent/decisions.md` has the dated failure-model entry with Context, Decision, Reason, Supersedes and Alternatives considered.
5. `skillbill.error.core` declares `RuntimeFailureCode` and `SkillBillRuntimeException(code, message, cause)`; while subclasses exist, the class is `open` and has the `(message, cause)` constructor that sets `LegacyFailureCode.UNCLASSIFIED`. The four `FailureWireCode` enums implement `RuntimeFailureCode`.
6. `custom-throwable-baseline.txt` exists, lists exactly the custom `Throwable` declarations in production main at the time this subtask lands, and is written by `ArchitectureBaselineRecorder`.
7. Adding a custom `Throwable` subclass to any production main file fails `FailureCodeTotalityArchitectureTest`, and so does a baseline row whose class no longer exists; the synthetic-fixture test proves both.
8. Production behaviour is unchanged: no CLI, MCP or wire-fixture assertion is edited.

## Non-Goals

- Removing or converting any exception class (subtasks 2-6).
- Changing `TypedParseBoundaryArchitectureTest`'s scan or the parse-boundary inventory.
- Adding a detekt rule.

## Dependency Notes

Depends on: none.
If subtasks 2-6 landed first, record the baseline from the tree as it is, and keep any target-type pieces they already added. If another subtask already deleted `LegacyFailureCode` because no subclass remained, do not re-add it. Coordinates with SKILL-389/393/397, which edit other regions of `ArchitectureScanSupport` and the baselines directory; the second lander keeps both edits.

## Validation Strategy

Goal gates: build, unit tests, detekt and the runtime-core repoTest suite. Test obligations: the synthetic new-declaration and stale-row cases of the baseline guard.

## Implementation Details

Planned against `base/SKILL-380-phase-slot-strategies` at `432d427c8`. Subtask 1 lands first in this goal, so no target-type piece exists yet and none of the "if subtasks 2-6 landed first" branches apply. Anchors were checked on that tree. Re-check each one before editing, because SKILL-389/393/397 also edit `ArchitectureScanSupport` and the baselines directory.

### Task 1: Target type (AC-005, AC-008)

- `../../../runtime-kotlin/runtime-contracts/src/main/kotlin/skillbill/error/core/RuntimeExceptionBases.kt`: add the following to the existing file, so the error core package gains no new file.
  - `/** Marker for owner-declared failure code enums. */ interface RuntimeFailureCode`. KDoc is allowed only on interfaces (`CommentAndInterfaceKdocArchitectureTest`).
  - `enum class LegacyFailureCode : RuntimeFailureCode { UNCLASSIFIED }`.
  - Make `SkillBillRuntimeException` `open class SkillBillRuntimeException(val code: RuntimeFailureCode, message: String, cause: Throwable? = null) : RuntimeException(message, cause)` with the secondary constructor `constructor(message: String, cause: Throwable? = null) : this(LegacyFailureCode.UNCLASSIFIED, message, cause)`.
  - Leave `ShellContentContractException` unchanged. It and every existing subclass call `(message[, cause])`, which resolves to the secondary constructor because the first parameter types differ, so no subclass or throw site changes.
  - Add no other property or member (constraint).
- Census result: no throwable in production main declares a `code` property today. The only `val code:` declarations are data classes and result variants. So the new base property neither clashes nor shadows. Re-grep `val code` in main before compiling.
- Make three files declare `RuntimeFailureCode` beside `FailureWireCode`, keeping the existing `FailureWireCode` supertype (`enum class X(...) : FailureWireCode, RuntimeFailureCode`):
  - `runtime-contracts/.../skillbill/error/featuretask/FeatureTaskRuntimePhaseOutputFailureCode.kt` (`FeatureTaskRuntimePhaseOutputFailureCode`).
  - `runtime-contracts/.../skillbill/error/featuretask/FeatureTaskRuntimeFailureKinds.kt` (`FeatureTaskRuntimePhaseOutputFailureKind` and `FeatureTaskRuntimeHandoffProjectionFailureKind`).
  - `runtime-domain/.../skillbill/workflow/decomposition/model/DecompositionManifestValidationModels.kt` (`DecompositionManifestValidationFailureCode`). It already imports `skillbill.error.core.FailureWireCode`. `RuntimeModuleCatalog` allows the `skillbill.error` prefix for domain model packages, so importing `skillbill.error.core.RuntimeFailureCode` is allowed.
  - Do not make `FailureWireCode` extend `RuntimeFailureCode`.
- The totality scan in `FailureCodeTotalityArchitectureTest.inScopeFailureWireCodeViolations` already lists exactly these four `FailureWireCode` enums, and no other `RuntimeFailureCode` implementer exists, so the list doesn't change. `LegacyFailureCode` has no wire value and gets no totality row.
- No catch site, message, edge arm or telemetry `error_type` changes: class names and messages stay the same.

### Task 2: Throwable scan (AC-006, AC-007)

- Put the scan in `../../../runtime-kotlin/runtime-core/src/repoTest/kotlin/skillbill/architecture/ArchitectureScanGuardSupport.kt` (803 lines; the drift helpers and the private `sourceWithoutCommentsOrLiterals` live there). Do not grow `ArchitectureScanSupport.kt` (1,369 lines). Add no comments: the comment policy scans repoTest.
- Add `const val CUSTOM_THROWABLE_BASELINE: String = "custom-throwable-baseline.txt"` to `PrincipleEnforcementInventory`, next to `SPILLOVER_FILE_NAME_BASELINE`.
- `internal fun ArchitectureScanSupport.customThrowableRows(scanRoot: Path = runtimeRoot): Set<String>`:
  - Walk `ArchitectureScanSupport.kotlinFilesUnder(scanRoot.resolve(scanCase.mainScanRoot))` for every `PrincipleEnforcementInventory.moduleArchitectureScanCases` entry. That covers production main under `../../../runtime-kotlin` only, and the aggregate `runtime-infra` is already excluded. Don't scan test, repoTest or intellij-plugin.
  - Strip each file with `sourceWithoutCommentsOrLiterals`, so declaration text inside comments or string literals never counts.
  - Collect every `class` and `object` declaration at any nesting depth, which covers sealed-family variants such as `RejectedOutputDiagnosticError` children. Skip `interface`, `enum class`, `annotation class` and `fun interface`.
  - Record each declaration's module, file, nesting path (`Outer.Inner`) and supertype references. Take the supertype list after the `:` that follows the name, any type parameters and the balanced-paren primary constructor, and allow it to span lines. The list ends at `{` or at a blank line or new declaration at paren and angle depth 0.
  - Split the list on top-level commas. Drop generic arguments, constructor-call arguments and `by` delegation. Keep the dotted reference as written.
- Supertype resolution, applied transitively with a visited set:
  1. A dotted or simple reference that matches a declaration's nesting path in the same file resolves to it.
  2. Otherwise, a simple name declared anywhere in the scanned sources resolves to every declaration with that simple name. The class counts as throwable if any of them is throwable. A local declaration named `Error` or `Failure` shadows the root name, so result variants such as `X : Outcome.Error` are not counted.
  3. Otherwise, the reference is a root when its last segment is one of the spec's roots (`Throwable`, `Exception`, `RuntimeException`, `Error`, `IllegalStateException`, `IllegalArgumentException`, `UnsupportedOperationException`, `IOException`, `NoSuchElementException`), when it is qualified as `kotlin.*` or `java.*` with a root last segment, or when it is unresolved and ends in `Exception` or `Error`. The last rule catches library-rooted subclasses such as `CliktError` or `SerializationException`.
- Row format:
  - `<moduleName>:<SimpleName>` for top-level declarations, using `moduleName` as declared (`runtime-engine`, `runtime-infra:host`).
  - `<moduleName>:<Outer.Inner>` for nested declarations. Keying nested variants by their nesting path keeps two same-named variants (for example `Absent`) in one module from collapsing into one row, which would hide a stale row. File moves inside a module still don't churn rows.
  - Compare rows as whole strings and never split them on `:`.
- `internal fun ArchitectureScanSupport.customThrowableDrift(scanRoot: Path = runtimeRoot, readBaseline: (String) -> String = ArchitectureBaselineSupport::readBaseline): List<String>`:
  - Use one repo-wide baseline. `moduleBaselineDrift` is per module, so don't use it, but copy its shape and its `parseStringSetBaseline`.
  - Each `current - baseline` row, sorted, produces `"<module> declares custom throwable <name>; return a result, use require/check, or throw SkillBillRuntimeException with a code."`. The module and name are the row's two parts, kept as built rather than split.
  - Each `baseline - current` row, sorted, produces `"<row> is listed in custom-throwable-baseline.txt but no longer exists; re-record the baseline."`.
- Keep each function under detekt's `LongMethod` (70), `ReturnCount` (4) and `CyclomaticComplexMethod` (15) limits by splitting header parsing, supertype splitting and resolution into small private functions. No `@Suppress`.

### Task 3: Recorder and baseline file (AC-006)

- `ArchitectureBaselineRecorder.recordBaselinesWhenRequested`: add `recordCustomThrowableBaseline(baselineDir)`. It writes `customThrowableRows().sorted().joinToString("\n") + "\n"` to `baselineDir.resolve(PrincipleEnforcementInventory.CUSTOM_THROWABLE_BASELINE)`.
- Generate `../../../runtime-kotlin/runtime-core/src/repoTest/kotlin/skillbill/architecture/baselines/custom-throwable-baseline.txt` with the recorder (`RECORD_ARCHITECTURE_BASELINES=1`). Don't hand-write it. Recording is a repoTest invocation, which belongs to the build/validate phases; implement may hand the recording to them, but the committed file must be the recorder's output.
- Before committing, sanity-check the file:
  - The row count should be near the census figure of 231, after SKILL-391 to 397.
  - `runtime-contracts:SkillBillRuntimeException`, `runtime-contracts:ShellContentContractException` and the `RejectedOutputDiagnosticError` variants are present.
  - No non-throwable result variant named `Error` or `Failure` appears.
  - No test-source class appears.
- Re-record the other baselines only if the scan changes them. It shouldn't.

### Task 4: Guard tests in `FailureCodeTotalityArchitectureTest` (AC-007)

Add only these two tests, and no new test class:

- `every custom throwable in production main is listed in the throwable baseline`:
  - `val drift = ArchitectureScanSupport.customThrowableDrift()`, then `assertEquals(emptyList(), drift, drift.joinToString("\n"))`.
  - Also assert that `customThrowableRows()` is non-empty, so a scanner that finds nothing can't pass. This follows the decision at `runtime-core/agent/decisions.md#5d1e4a85e4be`: a real, non-empty repository scan.
- `throwable baseline guard reports an unlisted declaration and a stale row`. This is the synthetic fixture covering both directions.
  - Build a temp tree with `seedModuleScanTreeWithEngineViolation` (writes `SYNTHETIC_ENGINE_VIOLATION_PATH`) and one source file with:
    - a direct `IllegalStateException` subclass;
    - a subclass of it with a multi-line constructor header (transitive and multi-line);
    - a `sealed class` rooted at `kotlin.RuntimeException` with a nested `data class` variant (qualified root, nested path row);
    - a non-throwable `sealed interface Outcome { data class Error(...) : Outcome }` plus a class extending `Outcome.Error` (shadowing: must not appear);
    - a triple-quoted string containing `class Fake : Exception()` (literal stripping: must not appear).
  - Call `customThrowableDrift(scanRoot = root, readBaseline = { "runtime-engine:SyntheticRemovedFailure\n" })`.
  - Assert the exact list: the four `declares custom throwable` lines in sorted order, then the stale line for `runtime-engine:SyntheticRemovedFailure`.
  - Exact equality also proves the shadowing and literal cases.
  - Realistic bugs it catches: a scanner that misses transitive, multi-line or nested throwables; one that counts result variants or literal text; or a drift check that ignores stale rows.
- The existing totality tests stay unchanged.

### Task 5: Parse-boundary wording (scope item; AC-008 unaffected)

- `ArchitectureScanSupport.kt:430`: change `"use a typed contract failure instead."` to `"use a result or a SkillBillRuntimeException code instead."`.
- `TypedParseBoundaryArchitectureTest.kt`:
  - `:16`: the assertion message `"…through typed contract failures, not error,…"` becomes `"…through a result or a SkillBillRuntimeException code, not error,…"`.
  - `:41` and `:90`: the two expected strings get the same new suffix.
- Scan logic and inventory stay unchanged. These are architecture-test strings, not CLI, MCP or wire-fixture assertions.

### Task 6: `../../../docs/code-principles.md` "Failure Contracts" (AC-001)

Rewrite lines 34 to 60, from the heading through the Amendment, keeping the Amendment paragraph. Follow `../../../docs/architecture-guidelines.md` A7 and link to it rather than restating it differently.

- **Rule.** State the three tiers:
  - defects use `require`/`check`/`error()` and are never caught for control flow;
  - expected outcomes are returned as sealed results, nullables or existing outcome types by the function that knows;
  - failures that end the run throw `SkillBillRuntimeException(code, message, cause)` with an owner-declared `RuntimeFailureCode`.

  Then:
  - A new custom `Throwable` subclass earns its place only for a failure that crosses a boundary the runtime does not own and cannot be tier 3. Its reason goes in a dated `../../../runtime-kotlin/agent/decisions.md` entry. `custom-throwable-baseline.txt` enforces this.
  - Keep the wire-code totality sentence, the parse-boundary sentence (untrusted input becomes a result or a tier 3 failure, never `error()`/`require`/bare `throw`/`runCatching` classifiers) and the cancellation sentence.
- **Preferred shapes.**
  - `enum class … : FailureWireCode, RuntimeFailureCode` with `wireValue`, and `failureWireByValue` at decode sites.
  - Plain owner `enum class … : RuntimeFailureCode` for codes without a wire value.
  - Sealed results or nullables for tier 2.
  - Remove `Invalid*SchemaError` and "domain-specific typed failures".
- **Anti-patterns.** Keep the three existing ones (collapsing unknown tokens to `SCHEMA_INVALID`, a parallel kind enum, `error("bad json")` in durable decoders) and add the four:
  - a class per contract or per message;
  - throwing to report absent, refused or conflicting outcomes;
  - catching `IllegalArgumentException`/`IllegalStateException` for control flow;
  - branching on exception message text.
- **Reference examples.** Three of the four current paths are stale or moved. Use paths verified on this tree:
  - `../../../runtime-kotlin/runtime-contracts/src/main/kotlin/skillbill/error/core/FailureWireCodeContract.kt`;
  - `../../../runtime-kotlin/runtime-contracts/src/main/kotlin/skillbill/error/core/RuntimeExceptionBases.kt`;
  - `../../../runtime-kotlin/runtime-domain/src/test/kotlin/skillbill/workflow/decomposition/model/FailureWireCodeConformanceTest.kt`, which moved from `workflow/failureidentity/`;
  - `../../../runtime-kotlin/runtime-infra/sqlite/src/main/kotlin/skillbill/infrastructure/sqlite/workflow/goalrunner/runner/GoalRunnerControlStoreDecodeState.kt` (`decodeControlState`), which moved from `db/workflow/GoalRunnerControlStore.kt`;
  - `../../../runtime-kotlin/runtime-core/src/repoTest/kotlin/skillbill/architecture/TypedParseBoundaryArchitectureTest.kt`;
  - `../../../runtime-kotlin/runtime-core/src/repoTest/kotlin/skillbill/architecture/FailureCodeTotalityArchitectureTest.kt`.

  Check that each path exists right before writing.

### Task 7: `AGENTS.md:60` (AC-002)

- Replace `→ typed \`Invalid<Contract>SchemaError\` →` with `→ a failure-code entry in the owner's \`RuntimeFailureCode\` enum, thrown as \`SkillBillRuntimeException\` →`, keeping the rest of the line.
- Leave line 52 (`InvalidManifestSchemaError`) alone: it describes a class that still exists, and subtask 4 owns it.

### Task 8: `../../../runtime-kotlin/ARCHITECTURE.md` (AC-003)

- `:380-381`: "the `skillbill.error` runtime exception taxonomy" becomes "the `skillbill.error` failure codes (owner `RuntimeFailureCode` enums) and the single runtime failure type `SkillBillRuntimeException`".
- `:394-397`: name `SkillBillRuntimeException`, `RuntimeFailureCode`, the transitional `LegacyFailureCode`, `ShellContentContractException` and the `FailureWireCode` contract as the contents of `skillbill.error.core`, and keep the acyclicity and package sentences.
- `:636`: `- \`skillbill.error\`: failure codes (\`RuntimeFailureCode\` enums) and the single runtime failure type \`SkillBillRuntimeException\`.`
- Lines 1554, 1879 and 1932 describe current `Invalid*SchemaError` behaviour, which is unrelated text, so leave them.
- Grep repoTest for the phrase "runtime exception taxonomy" to confirm no doc-parity test pins it.

### Task 9: `../../../runtime-kotlin/agent/decisions.md` entry (AC-004)

Insert at the top (newest first, before the 2026-10-01 entries), dated on the implement day:

`## [2026-10-02] SKILL-398: Failure model: results for expected outcomes, one runtime failure type with owner codes, defects via require/check`

Write the entry as `Context:`, `Decision:`, `Reason:`, `Supersedes:`, `Alternatives considered:` and `Revisit when:` lines:

- **Context:** the census in `investigation.md`: 231 custom throwables in main, and 62% are never discriminated by type.
- **Decision:** the three tiers, the earns-its-place rule, the transitional `LegacyFailureCode.UNCLASSIFIED` secondary constructor, and the two-sided `custom-throwable-baseline.txt` guard in `FailureCodeTotalityArchitectureTest`.
- **Reason:** 62% of types are never discriminated, and both the CLI and MCP edges discard the type and print the message.
- **Supersedes:** the "typed errors" retention of SKILL-349, SKILL-374 and SKILL-391, and the class-per-failure form of the SKILL-351/352/353 typed durable failures. Their ownership placement stands.
- **Alternatives considered:**
  - `IllegalStateException` with a code: the edges would catch real bugs as user errors.
  - Guard only: it freezes 231 classes.
  - Results for everything: it pushes unrecoverable failures through every signature.

  All three are rejected; see investigation.md.

### Constraints

- No new module, dependency, test class, typealias, `Result`/`Either`, or `runCatching`.
- No CLI, MCP or wire-fixture assertion is edited.
- No exception class is removed or converted (subtasks 2-6).
- No change to `../../../docs/architecture-guidelines.md`. A7's "SKILL-398 adds" wording is left for a later subtask; it is outside this spec's scope.
- No installer commands.
- Spotless and repoTest run in a plain clone, not a linked worktree. If a stale configuration cache appears, use `--no-configuration-cache`.

### Tests to add or run

- Add the two `FailureCodeTotalityArchitectureTest` methods in Task 4.
- The build and validate phases run build, unit tests, detekt and the runtime-core repoTest suite, including `FailureCodeTotalityArchitectureTest`, `TypedParseBoundaryArchitectureTest`, `CommentAndInterfaceKdocArchitectureTest`, `SuppressionBanArchitectureTest`, the import-rule tests and the line-ceiling tests.
- Existing CLI, MCP and wire tests must pass unedited, which proves AC-008.

## Next Path

skill-bill goal SKILL-398

## Spec Path

.feature-specs/SKILL-398-runtime-exception-reduction/spec_subtask_1_failure-policy-and-throwable-baseline.md
