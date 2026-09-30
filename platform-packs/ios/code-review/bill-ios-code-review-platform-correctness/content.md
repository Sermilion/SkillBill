---
name: bill-ios-code-review-platform-correctness
description: Use when reviewing Swift concurrency, state, effect, and iOS lifecycle correctness.
internal-for: skill-bill
---

# Platform Correctness Review Specialist

Review only reachable Swift and iOS correctness failures.

## Focus

- Actor isolation, Sendable crossings, cancellation, and continuations
- Observable state and effect ordering
- Application, scene, view, and extension lifecycles

## Ignore

- Syntax or architecture preferences without incorrect behavior
- Strict-concurrency demands not applicable to the detected Swift language mode

## Applicability

Use concurrency rules according to the target's Swift language mode and compiler settings. Apply framework and lifecycle guidance only to detected targets and supported OS versions.

## Project-Specific Rules

### Concurrency Correctness Rules

- UI mutation must be isolated to `@MainActor` or transferred with `MainActor.run`; reject arbitrary callback delivery that races and renders invalid state.
- Values crossing actors must satisfy `Sendable` or remain isolated behind an actor; reject mutable reference types that create data races under strict concurrency.
- `@unchecked Sendable` must have a concrete thread-safety invariant enforced by synchronization; reject convention-only safety that can corrupt concurrent state.
- Child work must use `async let` or `withTaskGroup` when lifetime follows the caller; reject detached tasks that leak beyond cancellation.
- After every relevant `await`, code must revalidate identity and state; reject stale pre-suspension decisions that cause ordering failures under actor reentrancy.
- `withCheckedThrowingContinuation` must resume exactly once for success, failure, cancellation, and early exits; reject paths that crash or hang indefinitely.
- Cancellation must propagate and run cleanup through `withTaskCancellationHandler`; reject swallowed `CancellationError` that leaks resources or commits stale data.

### State And Lifecycle Correctness Rules

- Observation `@Observable` and `ObservableObject` state must have one owner appropriate to the deployment target; reject duplicate owners that race and display incorrect data.
- SwiftUI `.task(id:)` work must use stable identity and stop when the view lifecycle ends; reject task accumulation that repeats network or persistence effects.
- UIKit callbacks must update the currently owned `UIViewController` hierarchy; reject presentation from a detached controller because it fails at runtime.
- App and scene transitions must order persistence and restoration through `scenePhase` or delegate contracts; reject background assumptions that lose state on termination.
- Extension code must honor its shorter lifecycle and call `completeRequest` or detected equivalent exactly once; reject unfinished work that times out operationally.
- State effects must return through the established store action path; reject direct async mutation that bypasses ordering and creates invalid reducer state.
- For Blocker or Major findings, describe the concrete invalid-state or ordering failure scenario.
