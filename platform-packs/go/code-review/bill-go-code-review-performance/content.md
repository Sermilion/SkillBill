---
name: bill-go-code-review-performance
description: Use when reviewing measured Go latency, CPU, allocation, retention, lock, scheduler, batching, and resource regressions.
internal-for: skill-bill
---

# Go Performance Review Specialist

Own measurable efficiency consequences. Do not report stylistic micro-optimizations without a hot-path precondition and observable impact.

## Focus

- Measured latency, CPU, allocations, retention, contention, scheduler pressure, batching, and bounded resources
- Reproducible benchmark, profile, trace, or workload evidence for performance findings

## Ignore

- Speculative micro-optimizations without an observable hot-path consequence
- Concurrency correctness defects without measured performance impact

## Applicability

Use repository benchmarks, production profiles, traces, or a reproducible workload. Ask whether the changed behavior alters latency, throughput, allocation rate, retained memory, or bounded resource use.

## Project-Specific Rules

### Go Performance Rules

- Require a `go test -bench` comparison or production measurement for a claimed hot-path regression; intuition-only rewrites risk needless complexity without latency benefit.
- Verify CPU claims against `runtime/pprof` or `go tool pprof` call stacks; optimizing outside sampled work fails to improve operator-visible throughput.
- Require scheduler or blocking claims to be reproduced with `runtime/trace`, `go tool trace`, or block profiles; source inspection alone can misattribute goroutine latency and approve an ineffective regression fix.
- Require heap investigations to distinguish `alloc_space` from `inuse_space`; confusing allocation churn with retention leads to incorrect memory fixes.
- Ensure repeated conversions between `string` and `[]byte` are removed only on measured paths; uncontrolled conversions can raise allocation rate and garbage-collection latency.
- Verify values returned across interfaces do not escape unexpectedly using `go build -gcflags=-m`; avoidable heap escape risks throughput regression under load.
- Reject retention of oversized slice capacity after parsing or pooling; a small `[]byte` view can pin a large backing array and cause memory growth.
- Require `sync.Pool` only for reusable, allocation-heavy objects with reset invariants; pooling tiny or stateful values risks stale-data exposure and worse performance.
- Verify `sync.Mutex` contention with mutex or block profiles before sharding locks; speculative synchronization changes risk races without reducing latency.
- Ensure blocking calls are not made while holding `sync.RWMutex`; serialized I/O can cause starvation and tail-latency failures.
- Reject unbounded or workload-scaled one-goroutine-per-item fan-out without a worker pool, semaphore, `errgroup.Group.SetLimit`, or an equivalent cap; a small statically bounded fan-out can be valid, while uncapped `go func()` storms create scheduler pressure and resource exhaustion.
- Require channel buffer sizes such as `make(chan Job, n)` to follow a measured burst and backpressure model; arbitrary capacity hides overload until memory or latency fails.
- Verify batching with `time.Timer` and size thresholds flushes on cancellation and low traffic; throughput gains must not cause data loss or unbounded delay.
- Ensure `encoding/json` reflection or repeated marshaling is not duplicated on a confirmed hot response path; redundant serialization increases CPU and allocation cost.
- Require database and network loops to batch or parallelize only within dependency limits; uncontrolled concurrency risks pool starvation and timeout cascades.
- Verify benchmark setup uses version-appropriate `testing.B` loops and timer controls such as `b.ResetTimer`, `b.StopTimer`, or `b.ReportAllocs` only when setup, allocation evidence, or the repository's Go version requires them; treating their mere absence as a defect creates invalid review findings while distorted measurements can approve a real regression.
- Reject caching with `sync.Map` when invalidation, cardinality, and ownership are undefined; an unbounded cache leaks memory and serves stale data.
- For Blocker or Major findings, describe the concrete latency, memory-pressure, or throughput failure scenario.
