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

## Next Path

skill-bill goal SKILL-399

## Spec Path

.feature-specs/SKILL-399-shell-content-error-codes/spec_subtask_2_scaffold-and-review-context.md
