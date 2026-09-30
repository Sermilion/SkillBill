---
name: bill-rust-code-review
description: Use when conducting a Rust PR review across crates, workspaces, libraries, services, CLIs, async code, FFI, persistence, tests, and Rust-owned UI surfaces.
internal-for: skill-bill
---

# Adaptive Rust PR Review

Review Rust changes against the repository's edition, MSRV, Cargo workspace, feature policy, async runtime, error conventions, safety documentation, and public API compatibility. Treat ownership and type-system success as a starting point, not proof that runtime behavior is correct.

## Classification Rules

- If Cargo workspace metadata or first-party Rust source dominates the changed product surface, classify the owned diff as `rust`.
- Otherwise keep Rust ownership only for first-party Rust files when another stack owns the changed product surface.
- Always select `bill-rust-code-review-architecture`.
- Select at least two and at most ten specialists.
- Use `bill-rust-code-review-platform-correctness` as the default second lane when no narrower signal applies.
- Select `bill-rust-code-review-testing` for material changes to tests, fixtures, feature matrices, concurrency, or regressions.
- Review `unsafe`, FFI, raw pointers, auth, parsing, and dependency trust through the security lane as well as the owning behavior lane.
- Check ownership and borrowing choices for semantic bugs, accidental cloning, invalid lifetime assumptions, lock scope, and API constraints rather than asking whether the code compiles.

## Diff-Signal Routing Table

- Crate/workspace layout, dependency direction, trait ownership, public abstractions, or Cargo feature topology -> `architecture` specialist.
- Allocation, cloning, boxing, locking, atomics, serialization, async scheduling, or repeated I/O -> `performance` specialist.
- Borrowing, lifetimes, pinning, `Send`/`Sync`, interior mutability, error semantics, or async runtime behavior -> `platform-correctness` specialist.
- `unsafe`, FFI, raw memory, auth, secrets, untrusted parsing, process/file/network boundaries, or Cargo supply chain -> `security` specialist.
- Unit, integration, doc, property, fuzz, compile-fail, concurrency, or feature-combination tests -> `testing` specialist.
- Traits, public types, serde shapes, HTTP/RPC, FFI ABI, or semver compatibility -> `api-contracts` specialist.
- SQLx, Diesel, SeaORM, migrations, transactions, locking, or durable writes -> `persistence` specialist.
- Cancellation, backpressure, retries, channels, tasks, timeouts, shutdown, or observability -> `reliability` specialist.
- Wasm, server-rendered HTML, desktop GUI, terminal UI, or interactive CLI output -> `ui` specialist.
- Semantics, keyboard/focus flow, accessible names, feedback, or localization -> `ux-accessibility` specialist.

## Mixed Diffs

- Keep baseline specialists for the whole review, add only area-relevant lanes, and use lightweight file-level classification from paths, imports, Cargo metadata, and runtime markers to build each specialist scope.
- Architecture receives every first-party Rust-owned file; other specialists receive only matching files.
- Exclude generated, vendored, build-output, and non-stack files from specialist scope and dominance scoring, including `target/`, generated clients, bindings, and vendored crates.
- Rust used only for FFI bindings or wasm build support does not make the whole repository Rust-owned.
- After exclusions, drop empty lanes and restore the minimum by assigning all Rust-owned files to platform-correctness when architecture would otherwise stand alone.
- Load each selected specialist rubric so every selected specialist result is retained and attributed.
- Launch selected specialists as subagents in this harness in a deterministic order and retain every selected specialist result.

## Finding Discipline

- Calibrate severity to concrete production, operator, client, or user impact using only the governed severity vocabulary.
- Verify every triggering precondition and reachable failure path before reporting a finding.
- Keep findings attributed to their specialist lane through collection and merge.
- Deduplicate overlapping findings without losing the strongest evidence, consequence, or ownership attribution.
- Name the violated Rust, repository, API, or operational contract and the concrete failure; do not report rustfmt output, subjective style, speculative micro-optimizations, or `unwrap`/`unsafe` merely by keyword.
