# SKILL-399 Subtask 2 - scaffold-and-review-context

Parent spec: [.feature-specs/SKILL-399-shell-content-error-codes/spec.md](./spec.md)
Issue key: SKILL-399

## Scope

Convert every class in `ScaffoldShellContentErrors.kt` (10 classes) and `ReviewContextShellContentErrors.kt` (10 classes) to coded failures.

- **Scaffold.** `ScaffoldError` is `open`, and it and its 9 subclasses extend `SkillBillRuntimeException` directly, not `ShellContentContractException`. That means `ScaffoldFailureCode` is never part of `isShellContentContractFailure()`.
  - `ScaffoldFailureCode` entries: payload version mismatch, invalid payload, retired kind, unknown skill kind, unknown pre-shell family, skill already exists.
  - Family entry: `ScaffoldError` direct throws, missing platform pack, missing supporting file target, rollback.
  - The CLI scaffold arm (`completeScaffoldError`) keeps the same text and exit code for each former class.
- **ReviewContext.** `ReviewContextFailureCode` entries: identity mismatch, review context schema, rule text too long, title too long, hunk locator missing, hunk locator unreadable, hunk integrity, spec intent unreadable, aggregation integrity. Family entry: invalid skill content identity.
  - Keep `REVIEW_HUNK_EVIDENCE_INTEGRITY` public only if code outside the file references it; otherwise make it private.
  - Known `is` site: `CodeReviewStep.kt:391-393`. Its class-name reason at `CodeReviewStep.kt:402` uses `failureCodeLabel()`.

## Acceptance Criteria

1. The two files declare no class; they hold only `ScaffoldFailureCode`, `ReviewContextFailureCode` and message functions.
2. Each former failure throws `SkillBillRuntimeException` with an entry of one of the two enums, or is a `require`/`check`/`error()` defect.
3. No main code reads a typed property from a caught failure of these areas.
4. Scaffold CLI output (stdout, stderr, exit code) is unchanged for every former scaffold class.

## Non-Goals

The other shell-content areas. SKILL-398 subtask 6's conversion of scaffold payload input sources: if it has landed, keep its edits and point them at the scaffold payload code.

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

Source: the upstream SKILL-399 preplan digest, which covers all eight subtasks. The digest says subtask 2's conversion is already on `feat/SKILL-399-shell-content-error-codes`. `ScaffoldShellContentErrors.kt` and `ReviewContextShellContentErrors.kt` now declare only code enums and message functions, and `runtime-kotlin/runtime-contracts/agent/history.md#6bd86d675578` records the landing. This plan is therefore verify-only, with a conditional repair step. It replaces the earlier conversion plan, which assumed the classes were still present. Assumption for implement to confirm: the tree still matches the digest. All paths below are relative to `runtime-kotlin/`.

1. Verify the two owned files (AC-001). In `runtime-contracts/src/main/kotlin/skillbill/error/shellcontent/ScaffoldShellContentErrors.kt` and `ReviewContextShellContentErrors.kt`, confirm there is no `class`/`object` throwable declaration, only `ScaffoldFailureCode`, `ReviewContextFailureCode` (each implementing `RuntimeFailureCode`) and functions returning `SkillBillRuntimeException`. Confirm no main source declares a typealias named after a deleted scaffold or review-context class. Tests: none to add.

2. Verify producers and readers (AC-002, AC-003). Confirm that no main source references a deleted scaffold or review-context class name. Confirm that former throw sites use the message functions or `SkillBillRuntimeException(<Code>.<ENTRY>, ...)`, and that catches at `CodeReviewStep.kt` (`launchFailure`, class-name reason through `failureCodeLabel() ?: ...`), `SpecIntentProjectionExtractor.kt`, `CodeReviewCommand.kt` (aggregation, exit code 1) and `InstallApply.kt` (identity-mismatch rethrow) discriminate by `code` with `rethrowUnless`. They must read only `code`, `message` and `cause`. Tests: none to add.

3. Verify classification and the baseline (common criteria). `ShellContentContractFailures.kt` includes `ReviewContextFailureCode` and excludes `ScaffoldFailureCode`. `runtime-core/src/repoTest/kotlin/skillbill/architecture/baselines/custom-throwable-baseline.txt` has no row for any of the twenty deleted classes. The transition-finish condition is still false: the digest lists remaining `ShellContentContractException` subclasses (PhaseSlotContractErrors, DurableExternalDecode, MalformedJsonText, ExternalPlatformPack, ExternalAddon, FailureWireCodeContract, the execution-plan errors, InvalidMcpToolArgumentError) and subtasks 4–8 classes, owned by SKILL-400/SKILL-398 and later subtasks. So the open base, the codeless constructor and `isShellContentContractFailure()` stay unchanged.

4. Verify the scaffold CLI boundary (AC-004). `runtime-cli/.../cli/scaffold/payload/NativeScaffoldPayloadRun.kt` `completeScaffoldError` maps each `ScaffoldFailureCode` entry to the same stdout, stderr and exit code the former classes produced. The existing CLI and MCP scaffold request-parser tests assert those bytes unchanged.

5. Conditional repair, only if steps 1–4 find residue. Apply the parent spec's conversion rules to the specific leftover: coded construction, a code-guarded catch with `rethrowUnless`, `assertFailsWith<SkillBillRuntimeException>` plus a code assertion, and removal of the whole baseline row. Keep messages byte-identical and keep `REVIEW_HUNK_EVIDENCE_INTEGRITY` public while `ReviewPreparationServiceTest.kt` reads it. Do not edit classes owned by subtasks 3–8. If no residue is found, implement makes no repository change for this subtask.

Constraints: no new module, dependency, typealias, exception property, `@Suppress`, `runCatching` or comments. `skillbill.error.core` must not import shellcontent. Stay within the detekt limits (ThrowsCount 2, ReturnCount 4, LongMethod 70, CyclomaticComplexMethod 15), and `ArchitectureScanSupport.kt` must not grow.

Test obligations: none. The converted existing assertions already cover the realistic regressions: changed scaffold output, wrong owner code, review-schema suffix or lane-order drift, and lost review dispositions. Validation (build, unit tests, detekt, the runtime-core repoTest suite including `FailureCodeTotalityArchitectureTest`, Spotless in a plain clone) belongs to the validate phase only. Nothing is compiled or run here, and no install step applies.

## Next Path

skill-bill goal SKILL-399

## Spec Path

.feature-specs/SKILL-399-shell-content-error-codes/spec_subtask_2_scaffold-and-review-context.md
