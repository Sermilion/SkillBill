---
name: bill-generic-code-review-code-quality
description: Review changed code for concrete maintainability problems that the project tools do not already enforce.
internal-for: skill-bill
---

# Code Quality Rubric

## Focus

Review only changed code for concrete maintainability problems. Derive idioms from the detected language and its standard library. Prefer a standard library operation over a hand-rolled equivalent when it preserves behavior. In Kotlin, prefer `value.orEmpty()` to `value ?: ""` for nullable strings, and `emptyList()` to an allocated mutable list when the result is empty and immutable. Apply equivalent language-native idioms in other languages.

Use scope and helper functions when they make ownership, inputs, or results clearer. Flag chains of `let`, `run`, `also`, or `apply` when receiver changes or nested lambdas hide the operation's inputs, side effects, or return value.

Prefer one state type when related values describe one state and are read or changed together. Model mutually exclusive lifecycle flags as a sealed type or enum when a boolean cluster admits contradictory combinations. PR #3110 is calibration, not a target for findings: `CustomFreeTextViewModel` has editor fields `textContent` and `isLoaded`, and the jointly written fields `isSaved`, `isInDatabase`, `savedText`, `createdAt` and `isDeletingOwnEntry` in `onSavedEntry` and `persist`. `VisitDetailViewModel` has lifecycle fields `hasSeenVisit`, `hasCreatedVisit`, `isDeletingVisit` and `isVisitRemoved`.

Flag duplicated logic, redundant branches, dead or unused code, oversized functions or types, and names that obscure purpose. A rewrite must preserve the existing behavior and documented contracts.

Read applicable repository guidance when available, including `docs/code-quality-best-practices.md`, `AGENTS.md`, `CLAUDE.md`, and linter or formatter configuration. Follow those rules over generic taste. If the routed pack or a baseline layer has `code-quality-idioms.md` in its code-review baseline directory, use those examples. Current launch-provided rubrics do not append optional sidecar bodies, and workers cannot perform unrestricted filesystem discovery. Use the sidecar only when an authorized launch supplies it or makes it accessible; otherwise derive idioms from the detected language and its standard library.

Every finding must name a concrete smell, give its `file:line` location, and propose a concrete rewrite. Report at most five findings per review. Never auto-fix code. This lane does not require a reachable behavioral failure. Report only changed code.

## Ignore

- Ignore anything enforced by configured formatters or linters, including ktfmt, detekt, eslint, prettier, and swiftformat.
- Ignore formatting, naming preferences without a concrete cost, and changes that only express personal style.
- Ignore behavioral defects owned by other lanes, including incorrect results, security failures, contract violations, persistence defects, and lifecycle or concurrency bugs.
- Ignore unchanged code, even when it appears in surrounding context.
- Never auto-fix findings or edit the reviewed files.

## Applicability

Use this lane for changed code in any routed language when the concern is idiom choice, helper clarity, state modelling, duplication, redundant logic, dead code, unit size, or naming clarity. Use the detected language's standard library and the repository's configured tools and written guidance. Do not require a generic idiom to match the selected platform's taste.

## Project-Specific Rules

### Idiom and helper rules

- **`CQ-01` Idiomatic constructs.** Prefer a language or standard-library operation over hand-rolled code when the rewrite preserves behavior; flag duplicate conversion or empty-value logic that adds unnecessary branching, such as nullable `orEmpty()` handling or an allocated `emptyList()` result. Keep null handling and mutability unchanged.
- **`CQ-02` Scope-function clarity.** Use `let`, `run`, `also`, or `apply` only when the receiver and result stay clear; flag nested chains that hide a side effect or return value. Preserve evaluation order and the same result.
- **`CQ-03` Repository rules.** Follow applicable project guidance and configured language conventions; reject a generic-style recommendation that conflicts with `AGENTS.md` or `detekt`, because conflicting advice adds unnecessary repair work and duplicates tooling findings.

### State and lifecycle rules

- **`CQ-04` Cohesive state.** Group values into one data class or other state type when callers read or update them as one unit; flag scattered fields that make callers trace one state across unrelated declarations. Preserve valid states and `data class` serialization.
- **`CQ-05` Lifecycle states.** Replace mutually exclusive lifecycle boolean clusters with a sealed type or enum when combinations describe impossible states; flag combinations that obscure valid transitions or resource cleanup in `when` branches. Preserve all valid transitions.
- **`CQ-06` Calibration examples.** Treat the named PR #3110 fields in `CustomFreeTextViewModel` and `VisitDetailViewModel` as examples for review judgment, not changed-code findings; reporting unchanged calibration code adds unnecessary review effort. Keep their `onSavedEntry` and `persist` behavior as calibration only.

### Structure and correctness rules

- **`CQ-07` Duplication and branches.** Flag repeated logic or redundant branches only when one concrete rewrite removes divergence; describe the maintenance cost when copies drift across an `if` branch or equivalent path. Preserve branch outcomes and evaluation order.
- **`CQ-08` Dead code.** Reject unused declarations or unreachable branches when repository evidence shows they have no caller; explain how a dead `when` branch misstates supported states. Retain every reachable branch and public entry point.
- **`CQ-09` Unit size and naming.** Flag an oversized function or unclear name when it obscures a specific responsibility, data flow, resource owner, or invariant; propose a focused extraction that clarifies resource ownership or error handling around `try` and `finally`. Preserve cleanup and exceptions.
- **`CQ-10` Contract and data evidence.** Verify a suggested simplification against callers, API contracts, persisted data, and resource ownership; reject rewrites that obscure validation, change `serialized values`, or move resource ownership away from its caller.
- **`CQ-11` Tooling boundary.** Do not repeat a configured formatter or linter finding as code quality; duplicate `ktfmt` or `detekt` reports hide the tool-owned repair and add unnecessary review effort. Keep the configured tool authoritative.
- Report every code-quality finding as Minor; never Blocker or Major.
