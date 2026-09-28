---
name: bill-typescript-code-review-performance
description: Use when reviewing TypeScript event-loop work, promise fan-out, streams, browser bundles, rendering, hydration, allocation, and retained resources.
internal-for: skill-bill
---

# Performance Review Specialist

## Focus

- Node, Bun, Deno, browser, and worker scheduling or CPU consumption
- Concurrency, serialization, streams, buffering, allocation, and retention
- Bundles, rendering, hydration, effects, and framework-owned hot paths

## Ignore

- Micro-optimizations without a measured path and expected scale
- Type-only changes that do not affect emission or toolchain work
- Caching or memoization without a demonstrated repeated cost

## Applicability

Select the runtime and framework detected by repository configuration, then tie every finding to its request, job, render, bundle, or stream scale.

## Project-Specific Rules

### Scheduling and Concurrency Failure Rules

- Node, Bun, or Deno request paths must keep synchronous filesystem, crypto, compression, parsing, and CPU loops off the event loop; reject latency starvation demonstrated by `eventLoopUtilization` or profiling. Verify `performance trace` before reporting this failure.
- `Promise.all` and task creation must be bounded by the input and downstream capacity; prevent memory growth, rate-limit failure, or connection-pool starvation from unbounded fan-out. Verify `performance trace` before reporting this failure.
- Microtask recursion and immediately resolved promise chains must yield when work is large; flag a scheduling failure that delays timers, I/O callbacks, rendering, or cancellation. Verify `performance trace` before reporting this failure.
- Worker-thread or web-worker transfer must justify serialization and startup cost; reject parallelism that increases latency through copying or an unbounded worker lifecycle. Verify `performance trace` before reporting this failure.

### Data Flow and Resource Rules

- Node `Readable` and Web `ReadableStream` pipelines must honor backpressure instead of buffering the entire payload; prevent memory exhaustion and delayed first-byte delivery. Verify `performance trace` before reporting this failure.
- Repeated `JSON.stringify`, parsing, object spread, array chaining, and deep cloning must be measured at realistic cardinality; flag allocation or CPU regression in the owned hot path. Verify `performance trace` before reporting this failure.
- Database, HTTP, and filesystem calls must batch only where ordering and partial-failure contracts remain valid; reject an N+1 pattern or oversized batch that causes timeout risk. Verify `performance trace` before reporting this failure.
- Caches, memo tables, listeners, timers, and subscriptions must have bounded retention and invalidation; prevent heap leaks verified through repository profiling or lifecycle tests. Verify `performance trace` before reporting this failure.

### Browser and Rendering Failure Rules

- Client entry points must keep server-only and type-only dependencies out of `vite`, `webpack`, `rollup`, or detected bundle output; reject a bundle-size regression that delays interaction. Verify `performance trace` before reporting this failure.
- React, Vue, Svelte, Solid, Angular, or other detected views must avoid unstable dependencies and redundant derived state; flag render loops or repeated computation using framework profiling evidence. Verify `performance trace` before reporting this failure.
- Hydration and route loading must partition code and data at repository-owned boundaries; prevent duplicated fetches, long tasks, or waterfall latency visible in browser traces. Verify `performance trace` before reporting this failure.
- DOM measurement, mutation, and high-frequency event work must be scheduled to avoid layout thrash; reject frame loss or input latency supported by performance recordings. Verify `performance trace` before reporting this failure.
- For Blocker or Major findings, describe the concrete latency, memory-pressure, or throughput failure scenario.
