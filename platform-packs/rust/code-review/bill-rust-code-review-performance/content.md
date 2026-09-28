---
name: bill-rust-code-review-performance
description: Use when reviewing Rust allocation, cloning, locking, atomics, async scheduling, serialization, I/O, and hot-path regressions.
internal-for: skill-bill
---

# Performance Review Specialist

## Focus

- Unnecessary allocation, cloning, boxing, collection growth, and serialization copies
- Lock scope, contention, atomics, channels, task spawning, and async executor pressure
- Blocking I/O or CPU work on tokio/async-std executor threads
- Repeated database/network/filesystem work and algorithmic regressions on measured hot paths

## Ignore

- Micro-optimizations without evidence that the path matters
- Replacing clear safe code with `unsafe` for speculative speed
- Mechanical preference for borrowing when ownership makes the API clearer and cost is immaterial

## Applicability

Apply when changed Rust code affects throughput, latency, memory, startup, binary size, or resource bounds. Use repository benchmarks, profiles, limits, and workload assumptions as evidence.

## Project-Specific Rules

### Rust Performance Rules

- Require hot-path allocation through `Vec::with_capacity`, `String::with_capacity`, or borrowed slices when measured cardinality is known; flag repeated growth that causes latency or memory regression.
- Verify `.clone()` and `.to_owned()` copy frequency and retained size against the actual workload; reject material allocation amplification or stale duplicated state rather than the syntax alone.
- Ensure iterator chains do not introduce accidental `collect::<Vec<_>>()`, repeated traversal, or per-item serialization; flag lost streaming that creates peak-memory failure.
- Require `MutexGuard` and `RwLockReadGuard` scopes to end before `.await` unless the lock protocol proves otherwise; reject contention, starvation, or deadlock on executor threads.
- Verify atomics such as `AtomicUsize::fetch_add` use an ordering no stronger than required and no weaker than correctness permits; reject race risk or needless synchronization cost.
- Bound work submitted through `tokio::task::spawn_blocking` or `rayon::ThreadPool`; reject unbounded blocking tasks that exhaust threads, delay shutdown, or inflate memory.
- Ensure CPU-heavy loops do not run directly inside `tokio::spawn`; require measured chunking or an owned worker pool and flag executor latency regression.
- Require channels such as `tokio::sync::mpsc::channel` to have capacity and backpressure matched to producers; reject unbounded buffering or producer starvation under load.
- Verify async fan-out through `buffer_unordered`, `FuturesUnordered`, or `JoinSet` has a concurrency ceiling; flag connection-pool exhaustion or timeout cascades.
- Preserve batching across `read_vectored`, database bulk operations, and `serde_json::to_writer`; reject repeated I/O or temporary buffers that create throughput failure.
- Require benchmark or profile evidence from configured `criterion`, `cargo bench`, or repository telemetry before complex hot-path rewrites; reject speculative unsafe code that adds correctness risk without demonstrated gain.
- Verify Cargo feature and generic expansion with `cargo bloat` or repository size checks when binary size is a stated constraint; flag monomorphization that breaks deployment limits.
- For Blocker or Major findings, describe the concrete latency, memory-pressure, or throughput failure scenario.
