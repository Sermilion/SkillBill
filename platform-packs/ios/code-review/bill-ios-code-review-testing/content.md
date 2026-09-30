---
name: bill-ios-code-review-testing
description: Use when reviewing iOS XCTest, Swift Testing, UI, snapshot, persistence, and relaunch evidence.
internal-for: skill-bill
---

# Testing Review Specialist

Review only material gaps or invalid test evidence.

## Focus

- XCTest and detected Swift Testing behavior coverage
- UI, snapshot, persistence, concurrency, and lifecycle tests
- Deterministic failure and relaunch evidence

## Ignore

- Coverage-count demands without meaningful behavior
- Requiring every testing framework when it is not configured or supported

## Applicability

Use `XCTest` or Swift Testing according to repository configuration, toolchain, and deployment support. Apply snapshot, UI, persistence, and concurrency guidance only when those surfaces are changed.

## Project-Specific Rules

### Behavioral Test Rules

- Async `XCTestCase` methods or Swift Testing `@Test` functions must await observable completion; reject sleeps that race and create flaky failures.
- Tests of actor-isolated state must use `@MainActor` or the owning actor correctly; reject unsafe crossings that hide concurrency bugs or cause strict-checking failure.
- Cancellation tests must prove child cleanup and stale-result rejection with a controllable `Clock`; reject tests that only assert task creation and miss lifecycle leaks.
- Continuation wrappers must test success, failure, cancellation, and duplicate-callback defense; reject missing paths that permit a production crash or timeout.
- Reducer or observable-state tests must assert state transitions and effects with `XCTAssertEqual` or `#expect`, not merely initializer values; reject tautological tests that miss regression failures.
- URL loading tests must control `URLProtocol` or an injected `URLSession`; reject live-network tests whose data and latency failures are nondeterministic.

### Platform And Recovery Test Rules

- Persistence changes must test an existing on-disk fixture upgrading through `SchemaMigrationPlan`, Core Data, or detected SQLite migrations; reject fresh-store-only evidence that misses data loss.
- Offline sync tests must cover retry, duplicate delivery, conflict, tombstone, and relaunch state; reject happy-path-only coverage that permits consistency failures.
- XCUITest flows must use `waitForExistence(timeout:)` on accessibility identifiers rather than sleep; reject timing races that create false failures.
- Snapshot tests must use intentional `UITraitCollection` device, locale, and Dynamic Type matrices; reject unexplained baseline replacement that hides rendering regression failures.
- Navigation and presentation tests must use `XCUIApplication().launch()` to verify restoration or relaunch when state is durable; reject in-process-only evidence that misses lifecycle ordering failures.
- Background-work tests must inject scheduler and expiration boundaries instead of invoking private Apple behavior; reject unbounded waits that cause test-suite timeouts.
- Performance-sensitive paths must use `measure(metrics:)` or detected benchmark tooling with a stable workload; reject noisy assertions that conceal memory or latency regressions.
- For Blocker or Major findings, describe the concrete undetected-regression or false-positive test scenario.
