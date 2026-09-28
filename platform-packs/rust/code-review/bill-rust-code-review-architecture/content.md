---
name: bill-rust-code-review-architecture
description: Use when reviewing Rust crate boundaries, dependency direction, trait ownership, public abstractions, and Cargo feature architecture.
internal-for: skill-bill
---

# Architecture Review Specialist

## Focus

- Crate and workspace boundaries, dependency direction, and source-of-truth ownership
- Trait placement, object-safety choices, generic abstraction cost, and public type ownership
- Cargo feature topology, optional dependencies, default features, and supported combinations
- Application, domain, transport, persistence, FFI, and runtime lifecycle boundaries

## Ignore

- Module layout or naming preferences without a concrete ownership or dependency problem
- Demands for traits, generics, or extra crates where a local concrete implementation is clearer
- Formatting and compiler-enforced concerns with no architectural consequence

## Applicability

Apply to every first-party Rust diff. Infer the existing workspace architecture and MSRV before judging it. Review cross-crate changes as one dependency graph, including build scripts and feature-gated edges.

## Project-Specific Rules

### Rust Architecture Rules

- Verify `[workspace]` members and crate dependencies preserve the intended dependency direction; reject a Cargo cycle or duplicated source of truth that breaks independent builds.
- Require domain crates to avoid importing transport, database, GUI, or executor types such as `axum::Router`, `sqlx::Pool`, or `tokio::Runtime` when the repository owns an inward dependency boundary; flag coupling that blocks reuse or testing.
- Place public traits beside the consumers that define their contract and keep provider details out of `trait` signatures; reject semver-sensitive leakage that forces unrelated implementations to change.
- Ensure object-safe dispatch through `dyn Trait` is intentional and that associated types, generic methods, and `Self: Sized` constraints match callers; reject an abstraction that cannot be used at its promised boundary.
- Require public borrowed structures such as `View<'a>` to expose a usable owner and lifetime relationship; reject hidden global coupling or data that cannot safely cross the intended layer.
- Keep Cargo `[features]` additive unless exclusivity is explicitly enforced with `compile_error!`; reject default-feature leakage or combinations that select contradictory backends.
- For new or private optional dependencies, use `dep:name` and weak feature forwarding such as `name?/feature` where appropriate; for an already-published implicit feature, preserve `name = ["dep:name"]` or make a major-version migration, and reject downstream `--features name` breakage or accidental activation that changes runtime behavior.
- Ensure platform selection through `cfg(target_os)` and `cfg(feature)` has one coherent implementation per supported target; reject missing or overlapping branches that cause build failure.
- Treat `build.rs` outputs and proc-macro expansion as architectural inputs with declared ownership; reject generated interfaces that bypass crate boundaries or become environment-dependent.
- Require plugin or registry ownership through `inventory::submit!`, `OnceLock<T>`, or an explicit constructor to have deterministic initialization; flag global mutable state that creates ordering races.
- Verify callbacks and service handles using `Arc<dyn Trait + Send + Sync>` encode the actual concurrency contract; reject downcasts or shared ownership that evade layer invariants.
- Ensure public crate re-exports in `lib.rs` expose stable owned types rather than dependency internals; reject an avoidable compatibility break when an implementation crate changes.
- For Blocker or Major findings, describe the concrete dependency-cycle or ownership-boundary failure scenario.
