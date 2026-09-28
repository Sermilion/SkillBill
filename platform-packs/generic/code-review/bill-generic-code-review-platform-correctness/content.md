---
name: bill-generic-code-review-platform-correctness
description: Technology-neutral correctness review for runtime semantics, lifecycle invariants, errors, and concurrency.
internal-for: skill-bill
---

# Generic Platform Correctness Review

## Focus

Check value and state transitions, ordering, cancellation, cleanup, error propagation, and lifecycle boundaries. Look for stale state, lost updates, duplicate effects, unsafe retries, invalid defaults, and behavior that depends on unspecified ordering.

## Ignore

- Style preferences that do not violate runtime or lifecycle behavior.

## Applicability

Use when changes affect lifecycle, concurrency, state transitions, errors, or ordering.

## Project-Specific Rules

### Correctness Rules

- Require `state-transition-table` to reject impossible source and destination pairs; permissive transitions can make invalid lifecycle state reachable.
- Verify `compare-and-update-step` is atomic when concurrent actors mutate the same value; read-modify-write gaps create lost-update races.
- Reject `callback-completion-path` implementations that can complete twice because duplicate effects break caller contracts and resource cleanup.
- Ensure `cancellation-boundary` releases owned resources and does not translate cancellation into success; swallowed cancellation causes leaks and incorrect state.
- Require `error-cause-chain` to preserve the actionable underlying failure; replacing it with a generic result can break recovery and diagnostics.
- Verify `ordering-assumption` is enforced by a queue, lock, sequence, or documented commutative operation; unspecified order creates intermittent bugs.
- Reject `mutable-alias-boundary` exposure when callers can change internal data without validation; aliasing corrupts invariants outside the owner.
- Ensure `default-value-source` distinguishes absence from an explicit zero, false, or empty value; truthiness defaults can produce incorrect behavior.
- Require `resource-finalizer` to run for success, failure, and timeout paths; missing cleanup can leak handles and poison later operations.
- Verify `retry-state-reset` retains completed effects and clears only safe transient state; broad reset can duplicate work or lose progress.
- Reject `time-window-check` based on incompatible clocks or units because boundary comparisons can accept expired data or trigger premature timeout.
- For Blocker or Major findings, describe the concrete invalid-state or ordering failure scenario.
