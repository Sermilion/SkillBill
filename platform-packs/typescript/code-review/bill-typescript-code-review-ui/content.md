---
name: bill-typescript-code-review-ui
description: Use when reviewing DOM or framework-owned TypeScript UI state, effects, events, rendering, routing, hydration, cleanup, and recovery behavior.
internal-for: skill-bill
---

# UI Review Specialist

## Focus

- State ownership, derivation, effects, subscriptions, events, forms, and async work
- Rendering, routing, server/client boundaries, hydration, and browser lifecycle
- Loading, empty, validation, failure, retry, and recovery behavior

## Ignore

- Visual taste without a product or interaction contract
- Framework preference when repository conventions preserve behavior
- Defer accessibility concerns to the `ux-accessibility` specialist and security concerns to the `security` specialist

## Applicability

Detect DOM usage and repository-owned React, Vue, Svelte, Solid, Angular, Lit, vanilla, or other framework conventions; TSX alone must never be treated as proof of React.

## Project-Specific Rules

### State and Event Contract Rules

- TSX alone must never be proof of React behavior; verify the repository's actual JSX transform and framework owner or reject an invalid effect, event, and lifecycle model. Verify the configured JSX factory or runtime and the owning framework's rendered lifecycle behavior before reporting this failure.
- User-visible state must have one owner and derived values must remain derived through the detected store or component model; reject duplicated state that produces invalid rendering after updates. Verify `browser trace` before reporting this failure.
- Effects, watchers, signals, stores, and subscriptions must declare accurate dependencies and cleanup for their framework; prevent stale closures, loops, or retained lifecycle resources. Verify `browser trace` before reporting this failure.
- Typed DOM and component events must match the actual element, payload, and propagation path; flag an assertion that hides invalid form data or a handler that never receives the claimed event. Verify `browser trace` before reporting this failure.
- Asynchronous completions must be cancelled, versioned, or compared with current intent; reject a race where an older search, save, or route result overwrites newer state. Verify `browser trace` before reporting this failure.

### Rendering and Boundary Failure Rules

- React, Vue, Svelte, Solid, Angular, Lit, or repository render code must use stable identity and keys; prevent state migration, duplicate nodes, or incorrect reuse after list changes. Verify `browser trace` before reporting this failure.
- Server-rendered and client-rendered initial output must agree for time, locale, generated IDs, and data availability; flag hydration failure or lost interaction after takeover. Verify `browser trace` before reporting this failure.
- Client graphs must exclude server modules, credentials, and Node-only dependencies under the detected bundler; reject a browser crash or sensitive-data exposure. Verify `browser trace` before reporting this failure.
- Routing and nested layouts must preserve parameter parsing, loader ownership, cancellation, scroll, and state restoration; prevent invalid views or stale navigation commits. Verify `browser trace` before reporting this failure.

### Interaction and Recovery Rules

- Loading, empty, validation, disabled, optimistic, partial, error, retry, and success states must be reachable and mutually coherent; reject a user task that becomes stuck after failure. Verify `browser trace` before reporting this failure.
- Forms must preserve typed values, native submission behavior, server errors, and double-submit protection; prevent data loss or duplicate mutation across client and server handlers. Verify `browser trace` before reporting this failure.
- Observers, listeners, timers, portals, streams, and external widgets must be released on dependency change, unmount, or route exit; flag memory leaks and updates to destroyed views. Verify `browser trace` before reporting this failure.
- UI evidence must exercise the affected framework runtime and supported browser behavior through component or browser tests; reject a rendering claim supported only by TypeScript compilation. Verify `browser trace` before reporting this failure.
- For Blocker or Major findings, describe the concrete user-visible interaction or rendering failure scenario.
