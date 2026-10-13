---
name: bill-rust-code-review-code-quality
description: Rust ownership, enums, iterators, pattern matching, error composition, and avoiding unnecessary clones or indirection.
internal-for: skill-bill
---

## Focus

Review changed Rust code for avoidable maintenance work. Follow the workspace edition, MSRV, crate boundaries, and established error conventions. Report a concrete location, the maintenance cost, and a behavior-preserving change; no reachable failure is required.

## Ignore

- Behavioral defects owned by failure specialists; route correctness, security, persistence, reliability, and accessibility findings to those lanes.
- Issues already enforced by the repository's configured formatter, linter, compiler, or static-analysis rules.
- Style preferences without a concrete maintenance cost or a behavior-preserving rewrite.

## Applicability

Use this lane for every routed RUST pack review, regardless of whether changed code has a reachable failure. Inspect changed code only and follow repository conventions before applying general language preferences. Zero findings is valid when the code is clear.

## Project-Specific Rules

### Idioms and helper clarity rules

- Require `.clone()` only when an owned value is needed at that boundary; unnecessary clones add allocation and obscure borrowing. Preserve the same ownership and observable values within the workspace edition and MSRV.
- Require closed alternatives to use an `enum` and `match`; parallel flags permit invalid states and add state tracing. Preserve every valid transition and error value.
- Require iterator chains using `map` or `filter_map`, or a `for` loop for early exits; dense adapters add control-flow tracing. Preserve item order and borrowing.
- Require `Result` context to follow crate conventions; wrapping an error without a distinct caller action adds indirection and obscures its source. Keep validation and error values intact.

### Cohesive state and duplication rules

- Require `Box<dyn Trait>` only when runtime polymorphism is needed; unnecessary indirection adds allocation and lifetime tracing. Use the concrete type while preserving borrowing behavior.
- Require redundant `match` arms to combine only when validation, error values, and cleanup are identical; copied arms drift and add review cost. Keep distinct failures separate.
- Require repeated conversions to share code only when multiple callers use the same `From` or `TryFrom` rule; duplicated conversions drift and increase maintenance. Preserve parse errors and validation.
- Require `Drop` and lock guards to remain in the scope that owns the resource; moving them obscures cleanup and lock lifetime. Preserve acquisition and release order.

### Dead code, responsibility, and naming rules

- Require feature-gated and target-specific callers to be checked against `#[cfg(feature = "serde")]` and Cargo feature/target declarations before deleting branches; dead code misstates supported features and MSRV assumptions. Retain configured targets.
- Require functions mixing decoding, state transitions, and I/O to split by responsibility at boundaries such as `serde_json::from_str` when that crate is present or `std::io`; mixed work increases state and resource tracing. Preserve error propagation and cleanup.
- Require borrowed `&str` and owned `String` forms to have distinct names when both appear; ambiguous names make accidental cloning and lifetime reasoning harder. Keep public APIs stable.
- Require `Result` and resource rewrites to preserve validation, borrowing, `Drop`, and guard order; hidden resource ownership adds lifetime tracing during maintenance. Follow the workspace edition and MSRV.

- For every finding, identify the changed-code location, the concrete maintenance cost, and a behavior-preserving rewrite. Preserve validation, errors, resource closing, and serialized values.
- Report every code-quality finding as Minor; never Blocker or Major.
