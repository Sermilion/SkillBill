---
name: bill-kotlin-code-review-code-quality
description: Kotlin/JVM null and collection handling, sealed lifecycle state, coroutine structure, and backend framework conventions.
internal-for: skill-bill
---

## Focus

Review changed Kotlin/JVM code for avoidable maintenance work. Follow the repository's Gradle, coroutine, and detected backend framework conventions. Report a concrete location, the maintenance cost, and a behavior-preserving change; no reachable failure is required.

## Ignore

- Behavioral defects owned by failure specialists; route correctness, security, persistence, reliability, and accessibility findings to those lanes.
- Issues already enforced by the repository's configured formatter, linter, compiler, or static-analysis rules.
- Style preferences without a concrete maintenance cost or a behavior-preserving rewrite.

## Applicability

Use this lane for every routed Kotlin pack review, regardless of whether changed code has a reachable failure. Inspect changed code only and follow repository conventions before applying general language preferences. Zero findings is valid when the code is clear.

## Project-Specific Rules

### Idioms and helper clarity rules

- Require nullable-string `orEmpty()` only when null and empty have the same input contract; otherwise it hides validation distinctions and creates serialization ambiguity. Preserve null when callers encode it differently.
- Require immutable `emptyList()` for read-only empty results; mutable defaults add state ownership and make mutation tracing harder. Keep result ordering and nullability unchanged.
- Require `let`, `run`, `also`, and `apply` to make receiver and return value clear; nested scope functions add receiver ambiguity. Replace them with named locals without changing evaluation order.
- Require lifecycle alternatives to use a sealed type and changing values to share a cohesive `data class`; parallel flags allow invalid combinations and increase state maintenance. Retain wire tokens and defaults.

### Cohesive state and duplication rules

- Require `CoroutineScope` ownership and cancellation to remain visible at launch sites; hidden launches add lifetime tracing and can leak work. Extract helpers only when callers still own cancellation and exceptions.
- Require collection operations such as `map` or `mapNotNull` to match one domain transformation; hand-written repeated loops duplicate mapping rules and invite drift. Preserve iteration order and lazy versus eager behavior.
- Require Spring `@Transactional` methods to remain on the proxied boundary; private self-calls bypass proxy behavior and obscure transaction ownership. Keep the same transaction extent.
- Require Ktor pipeline phases, Exposed `transaction {}`, and Hibernate `Session` lifetimes to follow local module conventions; wrappers that hide them add resource and transaction tracing. Preserve commit, rollback, and exception translation.

### Dead code, responsibility, and naming rules

- Require equivalent branches to combine only after `when` outcomes, validation, and `CancellationException` are accounted for; duplicated branches add control-flow tracing. Keep each exception and null result distinct.
- Require dead extension functions such as `fun String.normalized()` and private adapters to be removed after production and generated callers are traced; forwarding code misstates ownership and adds navigation work. Retain any public or generated entry point.
- Require functions mixing parsing, policy, and persistence to split at meaningful parsing and resource-owning `use` or transaction boundaries; combined work makes transaction boundaries harder to see. Preserve error propagation and cleanup order.
- Require names to distinguish persisted state, requests, and projections; generic `value` names obscure which representation may change and increase tracing. Keep serialized field names stable.

- For every finding, identify the changed-code location, the concrete maintenance cost, and a behavior-preserving rewrite. Preserve validation, errors, resource closing, and serialized values.
- Report every code-quality finding as Minor; never Blocker or Major.
