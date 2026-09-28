---
name: bill-kotlin-code-review-testing
description: Review Kotlin coroutine, Flow, framework, persistence, serialization, failure-path, generated-code, and toolchain test evidence.
internal-for: skill-bill
---

# Testing Review Specialist

## Focus

- Behavioral evidence for concurrency, ordering, boundaries, compatibility, failures, and build variants

## Ignore

- Coverage-only demands for trivial accessors or generated mappings without behavior

## Applicability

Use for Kotlin unit, integration, contract, persistence, coroutine, and toolchain tests.

## Project-Specific Rules

### Testing Review Rules

- Require `runTest` with virtual-time control for delay, retry, and timeout behavior; real sleeps create slow flaky failure evidence.
- Require `StandardTestDispatcher` only for deterministic queued scheduling, never as evidence of production thread or event ordering; missing explicit advancement with `runCurrent`, `advanceTimeBy`, or `advanceUntilIdle` risks false-positive ordering tests and concurrency regression.
- Reject `UnconfinedTestDispatcher` when thread confinement matters; immediate resumption can make an invalid lifecycle test pass.
- Require explicit cancellation and completion assertions on child `Job` state; `runTest` reports unfinished children as a leak failure, while intentional long-lived helpers belong in `backgroundScope` so the test can cancel them at completion.
- Verify finite Flow sequences with bounded Turbine `awaitItem` assertions followed by `awaitComplete`; for `StateFlow`, `SharedFlow`, and other hot streams that normally never complete, assert the bounded items and ordering under test, then explicitly cancel the collector. A single `first()` cannot detect dropped or reordered data, while requiring hot-flow completion creates a hanging or false test contract.
- Require Spring, Ktor, or DI integration tests when interceptors and serialization boundaries change; unit mocks can miss framework contract failure.
- Reject persistence tests that assert only repository calls; verify commit, rollback, constraints, and durable rows through `Testcontainers` or an equivalent real boundary.
- Require explicit decoding fixtures for absent fields, explicit nulls, constructor defaults, unknown enum values, unknown fields, and time representations; reserve serializer round trips for encode/decode symmetry because an already-constructed object cannot exercise an omitted input.
- Verify authorization, validation, timeout, duplicate-delivery, and permanent-failure paths separately; one broad exception assertion can hide unsafe behavior.
- Reject tautological `assertEquals(stub, subject())` tests whose setup dictates the result; they provide no regression detection.
- Require generated KSP or kapt outputs to be compiled in the tested task; stale fixtures can hide generated-source build failure.
- Verify supported Java and Kotlin toolchains through Gradle matrix evidence such as `test` plus `apiCheck`; one local JDK can miss compiler or binary failure.
- Verify `runTest` completes with no unintended active child, use `backgroundScope` for intentional observers, and assert cancellation handlers when cleanup is contractual; an active child is a lifecycle leak, and merely advancing virtual time does not prove ownership.
- Reject unordered `toSet()` assertions when Flow order is contractual; lost sequencing evidence can hide a data regression.
- Require discovery of the Gradle compile, test, compiler-validation, binary-API, and generated-source tasks for the source sets actually present; do not universally require `compileTestKotlin`, which can omit custom or multiplatform tasks and preserve a build failure.
- Require discovery of the repository's configured dependency or vulnerability scanner task and controlled evidence for policy changes; requiring `dependencyCheckAnalyze` when another tool owns the contract can miss the real exposure regression.
- For Blocker or Major findings, describe the concrete undetected-regression or false-positive test scenario.
