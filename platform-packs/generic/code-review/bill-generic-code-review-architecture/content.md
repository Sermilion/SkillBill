---
name: bill-generic-code-review-architecture
description: Technology-neutral architecture review for boundary ownership, dependency direction, state lifetime, and change isolation.
internal-for: skill-bill
---

# Generic Architecture Review

## Focus

Trace responsibility across modules, processes, data owners, and external systems. Flag changes that create circular dependencies, duplicate sources of truth, bypass an owning boundary, or couple unrelated lifecycle and transaction concerns.

## Ignore

- Naming or layout preferences without a reachable architectural failure.

## Applicability

Use when changes affect responsibility, dependencies, lifecycle ownership, or boundaries.

## Project-Specific Rules

### Boundary Rules

- Require `module-dependency-graph` edges to point toward the owning abstraction; cycles or reversed edges break build isolation and change containment.
- Reject `service-lifecycle-owner` ambiguity because unowned startup, shutdown, or background work can leak resources and leave invalid state.
- Verify `boundary-contract-map` has one authority for each write and invariant; competing owners risk data divergence and inconsistent validation.
- Require `transaction-owner` to encompass the complete atomic use case; repository-local commits can expose partial state after a failure.
- Reject `cross-boundary-global` state shared by unrelated components because concurrency and test ordering can create races and hidden coupling.
- Ensure `composition-root` performs concrete dependency selection; scattering construction across domain code breaks replacement and can create lifecycle bugs.
- Verify `public-module-surface` exposes only dependencies consumers must own; leaking implementation types creates contract and build regressions.
- Require `external-effect-adapter` to isolate network, process, clock, and filesystem behavior; direct access from policy code risks unsafe retries and invalid tests.
- Reject `request-context-capture` by longer-lived services because identity or authorization data can leak across operations.
- Ensure `shutdown-sequence` stops intake, drains owned work, and closes resources in order; reversed teardown risks loss, timeout, or use-after-close failures.
- Verify `failure-propagation-path` crosses module boundaries without translating actionable errors into false success; swallowed failures break operational recovery.
- For Blocker or Major findings, describe the concrete dependency-cycle or ownership-boundary failure scenario.
