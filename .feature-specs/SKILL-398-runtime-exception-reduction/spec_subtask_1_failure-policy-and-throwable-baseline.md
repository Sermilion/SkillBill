# SKILL-398 Subtask 1 - failure-policy-and-throwable-baseline

Parent spec: [.feature-specs/SKILL-398-runtime-exception-reduction/spec.md](./spec.md)
Issue key: SKILL-398

## Scope

(F-001) Replace the written policy that prescribes a typed exception class per contract with the three-tier failure model in the parent spec.

- `docs/code-principles.md`, section "Failure Contracts" (lines 34-62 at the census tree): rewrite Rule, Preferred shapes, Anti-patterns and Reference examples. Rule: defects use `require`/`check`/`error()` and are never caught for control flow; expected outcomes are returned as sealed results, nullables or existing outcome types by the function that knows; failures that end the run throw `SkillBillRuntimeException` with an owner-declared `RuntimeFailureCode`; a new custom `Throwable` subclass must earn its place (a failure that crosses a boundary the runtime does not own and cannot be tier 3) and its reason is recorded in `runtime-kotlin/agent/decisions.md`. Keep the existing wire-code totality rule, the parse-boundary rule (no `error()`/`require` for untrusted input) and the cancellation rule. Anti-patterns: a class per contract or per message; throwing to report absent, refused or conflicting outcomes; catching `IllegalArgumentException`/`IllegalStateException` for control flow; branching on exception message text. Fix the stale reference path `runtime-kotlin/runtime-contracts/src/main/kotlin/skillbill/error/FailureWireCodeContract.kt` to its current location.
- `AGENTS.md:60`: a new contract gets a `RuntimeFailureCode` entry in its owner's code enum, not a typed `Invalid<Contract>SchemaError`.
- `runtime-kotlin/ARCHITECTURE.md` lines that describe the `skillbill.error` "runtime exception taxonomy" (about 380, 394, 636 at the census tree): describe the failure codes and the single runtime failure type instead. Do not rewrite unrelated text.
- `runtime-kotlin/agent/decisions.md`: append a dated entry "Failure model: results for expected outcomes, one runtime failure type with owner codes, defects via require/check". Context: the census in investigation.md. Decision: the three tiers and the baseline. Reason: 62% of types never discriminated; both edges discard the type. Supersedes: the "typed errors" retention of SKILL-349, SKILL-374 and SKILL-391, keeping their ownership placement. Alternatives considered: `IllegalStateException` with a code; guard only; results for everything (all rejected, see investigation.md).
- `TypedParseBoundaryArchitectureTest`: change only the wording of its failure messages from "typed contract failure" to "a result or a SkillBillRuntimeException code". The scan itself stays.

(Target type) Add `RuntimeFailureCode`, the `code` constructor parameter on `SkillBillRuntimeException`, and the transitional secondary constructor with `LegacyFailureCode.UNCLASSIFIED`, exactly as the parent spec's "Target failure model" defines them, in `runtime-kotlin/runtime-contracts/src/main/kotlin/skillbill/error/core/`. Existing subclasses keep compiling through the secondary constructor. Make the four `FailureWireCode` enums listed in `FailureCodeTotalityArchitectureTest` implement `RuntimeFailureCode`.

(F-002) Add a two-sided custom-throwable baseline.

- Extend `ArchitectureScanSupport` (or `ArchitectureScanGuardSupport`, wherever the other baseline drift scans live) with a scan of production main Kotlin sources under `runtime-kotlin/` (paths resolved from `ArchitectureScanSupport.runtimeRoot`, which is the repo root, so roots start with `runtime-kotlin/`). It collects class and object declarations and resolves supertypes by simple name transitively across all scanned files, starting from `Throwable`, `Exception`, `RuntimeException`, `Error`, `IllegalStateException`, `IllegalArgumentException`, `UnsupportedOperationException`, `IOException` and `NoSuchElementException`. Each match is one row `<gradle module path>:<SimpleName>`, for example `runtime-engine:OperationRefusalError`, so file moves inside a module do not churn the baseline.
- Record the rows in `runtime-kotlin/runtime-core/src/repoTest/kotlin/skillbill/architecture/baselines/custom-throwable-baseline.txt` through `ArchitectureBaselineRecorder`, sorted.
- Add one test method to `FailureCodeTotalityArchitectureTest`: drift between the scan and the baseline fails, naming each declaration missing from the baseline ("... declares custom throwable X; return a result, use require/check, or throw SkillBillRuntimeException with a code") and each baseline row that no longer exists. Add one synthetic-fixture test for both directions, in the style of the existing synthetic tests in that class.
- Add `RuntimeFailureCode` implementers to the totality scan only where they are `FailureWireCode`; plain code enums have no wire value and need no totality check.

## Acceptance Criteria

1. `docs/code-principles.md` "Failure Contracts" states the three tiers, the earns-its-place rule with its decisions.md requirement, and the four anti-patterns above; it no longer names `Invalid*SchemaError` as a preferred shape, and every reference example path exists.
2. `AGENTS.md` no longer requires a typed `Invalid<Contract>SchemaError` for a new contract and names the owner code enum instead.
3. `runtime-kotlin/ARCHITECTURE.md` describes `skillbill.error` as the failure codes plus the single runtime failure type, not a "runtime exception taxonomy".
4. `runtime-kotlin/agent/decisions.md` has the dated failure-model entry with Context, Decision, Reason, Supersedes and Alternatives considered.
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

## Next Path

skill-bill goal SKILL-398

## Spec Path

.feature-specs/SKILL-398-runtime-exception-reduction/spec_subtask_1_failure-policy-and-throwable-baseline.md
