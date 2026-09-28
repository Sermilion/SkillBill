---
name: bill-kotlin-code-review-ui
description: Review standalone Kotlin Compose Desktop, Swing or JavaFX interop, server-rendered views, and CLI or TUI interface correctness.
internal-for: skill-bill
---

# UI Review Specialist

## Focus

- State, effects, lifecycle, rendering, thread ownership, input, resize, recovery, and cleanup

## Ignore

- Android and Compose Multiplatform source-set or target-specific behavior, which belongs to KMP; standalone Compose Desktop remains Kotlin-owned. ux-accessibility and security findings belong to their specialist lanes; ignore visual taste without a user-visible failure

## Applicability

Run only when the diff contains Compose Desktop, Swing, JavaFX, server-rendered Kotlin, CLI, or TUI surfaces.

## Project-Specific Rules

### UI Review Rules

- Require Compose Desktop state that survives recomposition to use `remember` or owned observable state; recreating it can reset user input and break interaction state.
- Reject side effects launched directly from a composable body; select the effect primitive that matches ownership: `LaunchedEffect` for suspend work, `DisposableEffect` for acquire/dispose, `SideEffect` for post-commit synchronization, and `rememberUpdatedState` when a long-lived effect needs the latest callback.
- When `LaunchedEffect` is appropriate, verify its keys identify the actual lifecycle owner; a stale key risks lifecycle failure by retaining obsolete work, while a broad key can cause performance regression through repeated launches.
- Use `derivedStateOf` only when inputs change more often than the derived result and suppressing redundant recomposition is useful; use keyed `remember` for ordinary cached calculation or move measured expensive work off the UI thread, because treating `derivedStateOf` as a general cache can still block rendering or retain stale inputs.
- Reject blocking I/O on the Compose Desktop UI thread; its UI execution context owns snapshot and rendering work and is not a generic dispatcher for application I/O, so `runBlocking` can freeze painting and cause input timeout.
- Require Swing mutations on the EDT, using `SwingUtilities.invokeLater` only when the current execution is off that thread; cross-thread access risks a race, while always enqueueing can introduce stale ordering.
- Require JavaFX scene-graph mutations on the JavaFX Application Thread, using `Platform.runLater` only to cross from another thread; cross-thread access can fail, while unnecessary re-enqueueing can produce invalid update ordering.
- Require Swing or JavaFX bridges to dispose listeners and windows with the owning Compose lifecycle; retained callbacks can leak resources after close.
- Reject server-rendered Kotlin templates that derive visible state from hidden mutable singletons; concurrent requests can expose another user's data.
- Verify `kotlinx.html`, Thymeleaf, or FreeMarker conditionals render validation and recovery states; omitted errors can break form completion after invalid input.
- Require CLI commands built with `Clikt` or `kotlinx-cli` to separate parsing, state transition, and rendering; mixed partial output can report success after failure.
- Verify TUI loops handle resize, interrupted reads, redraw recovery, and terminal restoration in `finally`; a crash can leave corrupted rendering or raw terminal state.
- Require `Window` close handling to cancel owned effects; missing lifecycle cleanup can leak concurrent state and resources.
- Require targeted rendering, interaction, golden, or UI-harness evidence for the changed surface; a generic Gradle test run unrelated to that surface cannot establish layout, state, lifecycle, or interaction correctness.
- For Blocker or Major findings, describe the concrete user-visible interaction or rendering failure scenario.

Android views and Compose Multiplatform source-set or target-specific behavior must route to the KMP UI lane; standalone Compose Desktop remains in this Kotlin baseline.
