---
name: bill-go-code-review-testing
description: Use when reviewing Go regression proof, concurrency tests, subtests, race-sensitive behavior, fuzzing, cleanup, integration boundaries, and test-value failures.
internal-for: skill-bill
---

# Go Testing Review Specialist

Own whether tests can detect the changed behavior failing. Do not reward line coverage, duplicated implementation, or assertions disconnected from externally meaningful outcomes.

## Focus

- Behavioral regression proof, concurrency and race coverage, deterministic time, fuzzing, cleanup, and integration boundaries
- Tautological, flaky, coverage-padding, or otherwise false-positive tests

## Ignore

- Test style preferences that do not weaken regression detection
- Missing tests for generated or trivial behavior without a meaningful contract

## Applicability

Apply to changed production behavior and `_test.go` files. Prefer the narrowest real boundary that proves the contract while keeping concurrency and time deterministic.

## Project-Specific Rules

### Go Testing Rules

- Require `go test ./path/...` coverage for the changed package contract; absent regression proof risks shipping the original failure again.
- Verify table cases passed to `t.Run` assert distinct behavior rather than repeating identical inputs; decorative cases create false confidence and miss invalid data.
- Ensure `t.Parallel` subtests do not share mutable fixtures, environment, ports, or loop variables; shared state produces races and flaky order-dependent failures.
- Require concurrent code to be exercised by `go test -race` where the target supports it; synchronization bugs otherwise remain invisible until production load.
- Verify goroutine tests wait through `errgroup.Group`, channels, or explicit joins rather than `time.Sleep`; timing guesses cause flaky timeout regressions.
- Require clock-sensitive logic to accept an owned clock or timer seam instead of depending directly on `time.Now`; wall-clock tests fail nondeterministically.
- Require `t.Cleanup` or an equivalent cleanup registration immediately after successful resource acquisition and before any assertion or helper can abort; delayed registration leaks servers, files, and goroutines that poison later tests.
- Reject assertions that only check non-nil, boolean success, call count, or collection length when exact values matter; weak checks allow contract corruption to pass.
- Require HTTP tests using `httptest.NewRecorder` or `httptest.NewServer` to assert status, headers, body, and side effects relevant to the change; partial assertions miss client regressions.
- Verify persistence integration tests use the real `database/sql` transaction and constraint behavior when mocks cannot expose locking or rollback failures.
- Require negative cases for malformed input, cancellation, dependency errors, and authorization when those paths changed; happy-path-only tests miss reachable security failures.
- Ensure fuzz targets with `F.Fuzz` preserve a stable invariant and retain discovered seeds; crash-only fuzzing without a contract misses semantic corruption.
- Reject tests that reimplement the production algorithm step for step; mirrored bugs can make incorrect behavior appear valid.
- Require golden files updated through an intentional `-update` path to be reviewed for semantic changes; blind snapshots can normalize broken output.
- Verify generated mocks or `go:generate` fixtures do not make expectations tautological; asserting configured return values alone does not prove behavior.
- Ensure coverage increases from `go test -cover` correspond to branch or contract assertions; execution-only padding can hide an undetected regression.
- For Blocker or Major findings, describe the concrete undetected-regression or false-positive test scenario.
