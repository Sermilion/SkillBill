---
name: bill-kotlin-code-review-performance
description: Review Kotlin blocking, allocation, Flow, serialization, persistence, queue, and measurement performance risks.
internal-for: skill-bill
---

# Performance Review Specialist

## Focus

- Dispatcher capacity, backpressure, allocation, serialization, ORM access, queues, and evidence

## Ignore

- Micro-optimizations without measurable latency, memory, throughput, or resource impact

## Applicability

Use when Kotlin execution context, data volume, I/O, or framework behavior can create observable cost.

## Project-Specific Rules

### Performance Review Rules

- Reject `runBlocking` whenever the calling execution context must remain non-blocking, including coroutine workers, `Dispatchers.Default`, and UI dispatchers; this applies even from ordinary callbacks or non-suspend functions because consuming the owned thread risks starvation or a frozen interface under concurrent load.
- Require blocking JDBC, filesystem, and legacy client boundaries to switch to an infrastructure-owned context such as `Dispatchers.IO` when they are not already governed by a thread-bound transaction; blocking the default pool causes latency failure, while an internal dispatcher hop can break transaction ownership.
- Verify measured concurrency across dispatcher parallelism, connection pools, queues, and downstream capacity; `Dispatchers.IO.limitedParallelism` can bound one call site but is not a universal substitute for admission control, and excess demand still risks resource exhaustion or timeout.
- Verify `flowOn` changes only the upstream flow execution context and leaves downstream operators and collection in the collector context; require it only when evidence shows an upstream producer or operator on the wrong context causes latency regression.
- Reject unbounded `buffer`, `Channel.UNLIMITED`, or queue accumulation because a fast producer can cause memory failure before backpressure arrives.
- Verify large `map` and `filter` chains with `async-profiler` or allocation measurements; eager intermediates can create avoidable memory pressure.
- Require `sequence` or single-pass processing only when representative benchmark, profiler, allocation, load-test, or trace evidence shows collection work affects the hot path; speculative rewrites risk slower behavior.
- Reject `Json.encodeToString` repetition for invariant payloads on a hot path when profiling shows serialization latency and allocation regression.
- Require ORM projections for count, existence, or summary queries only when traces show full `@Entity` hydration or N+1 access causes a material resource or latency regression; an unmeasured projection rewrite can introduce a different query failure.
- Verify lazy associations are not traversed during serialization; `Hibernate.initialize` cascades can cause query storms or session failure.
- Require `batchSize`, semaphore, or rate limits around queue consumers; unbounded parallel launches risk downstream timeout and connection starvation.
- Reject performance claims without reproducible `jmh`, load-test, trace, or profiler evidence because unmeasured changes can hide throughput regression.
- For Blocker or Major findings, describe the concrete latency, memory-pressure, or throughput failure scenario.
