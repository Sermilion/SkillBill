---
name: bill-kmp-code-review-code-quality
description: Android and multiplatform Kotlin idioms, Compose state cohesion, readable Flow operations, and expect/actual organization.
internal-for: skill-bill
---

## Focus

Review Android and Kotlin Multiplatform code for avoidable maintenance work. Follow the project's source-set, Android, Compose, and Kotlin conventions. Report a concrete location, the maintenance cost, and a behavior-preserving change; no reachable failure is required.

## Ignore

- Behavioral defects owned by failure specialists; route correctness, security, persistence, reliability, and accessibility findings to those lanes.
- Issues already enforced by the repository's configured formatter, linter, compiler, or static-analysis rules.
- Style preferences without a concrete maintenance cost or a behavior-preserving rewrite.

## Applicability

Use this lane for every routed KMP pack review, regardless of whether changed code has a reachable failure. Inspect changed code only and follow repository conventions before applying general language preferences. Zero findings is valid when the code is clear.

## Project-Specific Rules

### Idioms and helper clarity rules

- Require platform APIs to stay in their owning `androidMain` or `iosMain` source set and shared behavior in `commonMain`; misplaced APIs multiply target maintenance. Keep `expect`/`actual` declarations limited to genuine platform differences.
- Require Compose screen state to live in one cohesive state holder such as `mutableStateOf`; independent mutable fields permit unsupported combinations and make lifecycle ownership ambiguous. Preserve each rendered state transition when combining them.
- Require each meaningful `Flow` transformation to have a readable operator boundary; dense `combine` chains add receiver tracing. Name intermediate values without changing emission order or errors.
- Require nested `flatMapLatest` only when the inner source must cancel with the outer value; otherwise it hides cancellation ownership and complicates flow tracing. Preserve cancellation and upstream error behavior when simplifying.

### Cohesive state and duplication rules

- Require nullable-string `orEmpty()` only when null and empty text have the same contract; otherwise it hides input distinctions and adds validation ambiguity. Preserve accepted serialized values.
- Require `emptyList()` for immutable no-result defaults; mutable empty collections add state ownership without value. Keep callers' read-only behavior and ordering.
- Require `let`, `run`, `also`, or `apply` only when the receiver and returned value stay clear; nested scope functions create receiver ambiguity. Replace them with named locals without changing null handling.
- Require related fields to move in one cohesive `data class` transition; separate updates allow invalid combinations and duplicate state maintenance. Preserve serializer field names and defaults.

### Dead code, responsibility, and naming rules

- Require lifecycle alternatives to use a `sealed interface` or `sealed class` when alternatives are closed; parallel booleans permit invalid states and add transition tracing. Keep wire tokens and every valid transition.
- Require duplicated source-set mappings to share a common helper only when `commonMain` and `expect`/`actual` target behavior is identical; copied mappings drift and multiply maintenance. Preserve platform-specific validation and errors.
- Require removal of unused `expect`/`actual` members only after checking every target implementation; stale declarations add target work and hide supported behavior. Retain members used by any configured target.
- Require methods mixing persistence, `Flow` operators, and Compose `LaunchedEffect` effects to split by responsibility where Compose is detected; mixed ordering obscures resource ownership. Preserve transaction bounds, resource closing, and emission order.

- For every finding, identify the changed-code location, the concrete maintenance cost, and a behavior-preserving rewrite. Preserve validation, errors, resource closing, and serialized values.
- Report every code-quality finding as Minor; never Blocker or Major.
