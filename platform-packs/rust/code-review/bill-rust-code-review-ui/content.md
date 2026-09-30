---
name: bill-rust-code-review-ui
description: Use when reviewing Rust wasm, server-rendered, desktop GUI, terminal UI, and interactive CLI behavior.
internal-for: skill-bill
---

# UI Review Specialist

## Focus

- State, event, navigation, validation, loading, error, and empty-state behavior
- wasm browser integration, hydration, server rendering, desktop GUI, and terminal UI lifecycle
- Async UI work, cancellation, thread affinity, rendering cost, and stale updates
- User-controlled content, escaping, command output stability, and interaction consistency

## Ignore

- Visual taste without a repository design or usability contract
- Framework preference across Leptos, Yew, Dioxus, egui, iced, Slint, ratatui, or server templates
- Styling details unrelated to behavior, accessibility, or regression
- Accessibility-only findings that belong to the `ux-accessibility` specialist
- Security-only findings that belong to the `security` specialist

## Applicability

Apply when changed Rust code affects UI state, rendering, events, hydration, terminal interaction, or desktop lifecycle.

## Project-Specific Rules

### Rust UI Rules

- For wasm surfaces using `wasm_bindgen` or `web_sys`, require event listeners and `Closure` ownership to survive exactly as long as the DOM node; reject leaked handlers or callbacks invoked after state disposal.
- For Leptos, Yew, or Dioxus hydration, ensure server markup and initial client state are deterministic; flag `hydrate` mismatches that discard input, duplicate events, or break rendering.
- For server-rendered templates such as `askama::Template`, require escaped user content and stable form state across validation errors; reject script exposure or lost user input.
- For native GUI code using `egui`, `iced`, or Slint, require state updates on the framework-owned UI thread; reject cross-thread mutation, frozen windows, or lifecycle races.
- For terminal interfaces using `ratatui` and `crossterm`, require raw mode, alternate-screen, and cursor restoration on success, error, and panic hooks; reject a corrupted terminal session.
- Require one owner for visible state using `RwSignal`, `UseStateHandle`, or framework messages; flag duplicated caches that render stale or contradictory values.
- Ensure async responses carry a `CancellationToken`, request generation, or identity check before committing state; reject older completion overwriting a newer user action.
- Require `Loading`, `Disabled`, `Error`, and empty view states to be reachable and recoverable; flag interactions that silently stall or trap the user.
- Verify event propagation and default prevention through `web_sys::Event`, GUI messages, or terminal key events; reject double submission, ignored input, or accidental navigation.
- Keep non-blocking async I/O responsive through `spawn_local` or framework tasks where applicable, but move blocking or CPU-heavy work to Web Workers on wasm or `spawn_blocking`/bounded workers on native targets; reject UI-thread stalls, input latency, or deadlock.
- Ensure list and canvas rendering uses `ScrollArea::show_rows` or the detected framework's bounded draw path when workload evidence requires it; flag memory or frame-time regression on documented data sizes.
- Require shutdown to cancel UI-owned `JoinHandle` tasks, release subscriptions, and persist intended state before window or terminal teardown; reject orphan work or lost updates.
- For Blocker or Major findings, describe the concrete user-visible interaction or rendering failure scenario.
