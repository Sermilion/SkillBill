---
name: bill-generic-code-review-testing
description: Technology-neutral testing review for behavioral coverage, failures, isolation, determinism, and regression value.
internal-for: skill-bill
---

# Generic Testing Review

## Focus

Check that tests observe public behavior or durable effects, cover the changed failure and boundary conditions, and fail when the implementation regresses. Flag tautological assertions, mocks that replace the behavior under test, shared-state leakage, and timing-dependent expectations.

## Ignore

- Coverage-only assertions that do not establish behavior or a failure path.

## Applicability

Use when changes affect tests, fixtures, determinism, isolation, or regression evidence.

## Project-Specific Rules

### Testing Rules

- Require `production-effect-assertion` to observe the public result or durable side effect; asserting a mock call alone can hide a behavioral regression.
- Verify `failure-path-fixture` reaches the real rejecting boundary; stubbing the failure after that boundary creates invalid confidence.
- Reject `implementation-copy-oracle` expectations that reproduce the same algorithm because the test and code can share the same bug.
- Ensure `concurrency-test-barrier` controls ordering explicitly; sleeps and timing guesses create flaky races rather than deterministic evidence.
- Require `shared-state-reset` between cases and parameter sets; leaked fixtures make results order-dependent and can mask data corruption.
- Verify `negative-authorization-case` uses a distinct actor or tenant and asserts no mutation; happy-path identity reuse misses security exposure.
- Reject `exception-type-only-check` when message, error identity, or recovery contract changed because clients can still receive an incorrect failure.
- Ensure `boundary-value-matrix` covers absence, zero, maximum, overflow, and malformed inputs relevant to the change; interior-only cases miss validation bugs.
- Require `retry-behavior-test` to assert attempt bounds and effect uniqueness; checking eventual success alone can miss duplicate operations and timeout risk.
- Verify `migration-roundtrip-fixture` starts from the previous durable shape and reads the result through production code; synthetic final-state setup can hide data loss.
- Reject `coverage-without-assertion` cases that execute lines but cannot fail on the claimed regression; such tests add maintenance cost without behavioral protection.
- For Blocker or Major findings, describe the concrete undetected-regression or false-positive test scenario.
