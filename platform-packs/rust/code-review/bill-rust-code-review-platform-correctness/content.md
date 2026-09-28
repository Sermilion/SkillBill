---
name: bill-rust-code-review-platform-correctness
description: Use when reviewing Rust ownership, borrowing, lifetimes, error semantics, panic behavior, concurrency, and async runtime correctness.
internal-for: skill-bill
---

# Platform Correctness Review Specialist

## Focus

- Ownership, borrowing, lifetimes, interior mutability, pinning, and drop order
- `Result` propagation, error conversion, panic boundaries, `unwrap`/`expect`, and invariant enforcement
- `Send`/`Sync`, locks, atomics, channels, races, task lifetime, and cancellation
- tokio/async-std runtime semantics, blocking boundaries, time, and feature-gated behavior

## Ignore

- Compiler errors the changed code cannot build with
- `unwrap`, `expect`, `panic!`, or cloning solely by keyword without a reachable failure
- Idiom preferences that do not change correctness, diagnostics, or maintainability

## Applicability

Apply when changed Rust code affects ownership, borrowing, error behavior, unsafe invariants, concurrency, feature combinations, or runtime semantics.

## Project-Specific Rules

### Rust Platform Correctness Rules

- Treat accepted safe-Rust borrows and drop glue as compiler guarantees; inspect runtime lifetime or destruction hazards only where `*const T`, `*mut T`, `MaybeUninit<T>`, `ManuallyDrop<T>`, erased lifetimes, self-reference, or FFI bypasses those guarantees, and reject a task, callback, or stored pointer that can observe invalid data after its owner is dropped.
- Require ownership transfers through `Box<T>`, `Arc<T>`, and `Cow<'a, T>` to match mutation and sharing needs; flag cloning or reference cycles that cause stale state or memory leaks.
- Ensure interior mutability through `Cell<T>`, `RefCell<T>`, `Mutex<T>`, or `RwLock<T>` has a single defensible access protocol; reject reachable borrow panics, poisoned-state loss, races, or deadlocks.
- Verify every `unsafe` block establishes pointer provenance, alignment, initialization, aliasing, and lifetime obligations before dereference; reject undefined-behavior risk hidden behind a safe function.
- Require `unsafe impl Send` and `unsafe impl Sync` to prove all reachable fields and callbacks are thread-safe; flag cross-thread access that can race or expose invalid state.
- Ensure pinned values using `Pin<&mut T>` are never moved unless their type is `Unpin`, and verify projection and self-reference invariants; reject memory-unsafety or corrupted lifecycle ordering.
- Verify `Drop::drop` order, guard release, and partial initialization for normal returns, `?`, and unwinding; reject resource leaks, double cleanup, or state observed after dependent fields are destroyed.
- Preserve typed failure identity across `Result<T, E>`, `?`, and `map_err`; reject conversions that turn actionable validation or I/O failures into panics or indistinguishable errors.
- Require `unwrap`, `expect`, and `panic!` to depend only on a local proven invariant; reject user input, remote data, configuration, concurrency, or filesystem failures that can crash the process.
- Ensure guards, permits, and borrowed state are not unintentionally retained across `.await`; reject cancellation leaks, lock starvation, or deadlock when another future needs the same resource.
- Verify `tokio::spawn`, `spawn_local`, and `async_std::task::spawn` satisfy their actual `Send + 'static` and executor-lifecycle contracts; reject orphan work or target-specific build failures.
- Require affected `cfg` and Cargo feature paths to compile with supported combinations; reject an invalid conditional `impl`, missing dependency, or runtime selection that breaks a documented build.
- For Blocker or Major findings, describe the concrete invalid-state or ordering failure scenario.
