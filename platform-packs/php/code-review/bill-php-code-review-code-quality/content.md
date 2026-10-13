---
name: bill-php-code-review-code-quality
description: PHP type and value handling, focused classes, generators, and Symfony or Laravel conventions.
internal-for: skill-bill
---

## Focus

Review changed PHP code for avoidable maintenance work. Follow the project's PHP version, Composer structure, and detected Symfony or Laravel conventions. Report a concrete location, the maintenance cost, and a behavior-preserving change; no reachable failure is required.

## Ignore

- Behavioral defects owned by failure specialists; route correctness, security, persistence, reliability, and accessibility findings to those lanes.
- Issues already enforced by the repository's configured formatter, linter, compiler, or static-analysis rules.
- Style preferences without a concrete maintenance cost or a behavior-preserving rewrite.

## Applicability

Use this lane for every routed PHP pack review, regardless of whether changed code has a reachable failure. Inspect changed code only and follow repository conventions before applying general language preferences. Zero findings is valid when the code is clear.

## Project-Specific Rules

### Idioms and helper clarity rules

- Require strict `===` comparisons when `0`, `false`, `null`, and `''` differ; loose truthiness hides accepted values and adds validation ambiguity. Preserve the same request outcomes.
- Require typed properties or an existing value object at a request boundary; replacing it with an `array` makes every caller reinterpret shape and increases maintenance. Keep accepted keys and validation errors unchanged.
- Require Symfony services or Laravel container resolution to follow the detected framework's `__construct` and service-scope conventions; a local service locator hides service ownership. Preserve the same service lifetime.
- Require classes to keep one domain responsibility at an `__invoke` controller boundary or the detected framework's controller method; combining controller flow, persistence mapping, and response preparation makes ownership harder to trace. Split only along existing request boundaries.

### Cohesive state and duplication rules

- Require `yield` only for genuinely streamed results; materializing a large stream adds memory use and maintenance cost, while streaming repeatable results changes caller behavior. Preserve whether callers can replay values.
- Require arrays to retain documented keys such as `$request['id']` and ordering semantics when extracting a normalizer; shape changes force repeated shape maintenance. Keep validation and serialized fields stable.
- Require `Throwable` translation branches to remain distinct from validation and absence outcomes; merging them hides error meaning and adds incident tracing. Preserve exception types and messages.
- Require request transaction state to keep `PDO::beginTransaction`, commit, rollback, and cleanup in the owning scope when PDO is used, or use the detected framework transaction API; helper wrappers obscure transaction lifetime and add state tracing. Preserve exception translation, transaction extent, and cleanup order.

### Dead code, responsibility, and naming rules

- Require repeated trimming or casing with `trim` and `strtolower` to share a normalizer only after callers have drifted; copied transformations cause behavior drift and extra maintenance. Preserve their established order.
- Require unreachable methods and imports to be deleted after `__invoke`, route, Symfony service, or Laravel container registration is traced; dead entry points misstate supported behavior. Retain registered callbacks.
- Require methods combining request validation, transaction work, and response construction to split at detected `PDO::beginTransaction` and `__invoke` controller boundaries; mixed work makes failure paths harder to trace. Keep rollback and response behavior unchanged.
- Require names to identify business values instead of `$data` or `$item`; vague names force array-shape and reference tracing. Preserve public request keys and serialized values.

- For every finding, identify the changed-code location, the concrete maintenance cost, and a behavior-preserving rewrite. Preserve validation, errors, resource closing, and serialized values.
- Report every code-quality finding as Minor; never Blocker or Major.
