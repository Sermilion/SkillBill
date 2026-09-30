---
name: bill-python-code-review-performance
description: Review Python performance risks in hot paths, database access, network/filesystem work, memory use, imports, batching, streaming, and async/blocking boundaries.
internal-for: skill-bill
---

# Python Performance Review

Review avoidable latency, throughput loss, memory pressure, and resource exhaustion.

## Focus

- Hot paths, ORM query shape, repeated I/O, batching, streaming, memory growth, worker state, and async/blocking boundaries

## Ignore

- Micro-optimizations, small allocations, formatting, or style changes without an operator-noticeable latency, memory, throughput, or resource impact
- Cold paths with bounded inputs unless the diff supplies measurements or a realistic amplification path

## Applicability

Use this specialist for request handlers, serializers, batch jobs, workers, imports, large collections, database/network/filesystem paths, pandas or notebook workloads, and async code.

## Project-Specific Rules

### Python Performance Rules

- Verify a Django `QuerySet` is evaluated at the intended boundary and deliberately reused when its result cache is valid; distinguish cached `list`, `len`, and truthiness on the same instance from clones, reconstructed querysets, `iterator`, related-manager calls, and operations such as `count` or `exists` that may issue separate queries, because mistaking those paths can either duplicate database work or add needless count and existence round trips.
- Reject relationship access loops that create N+1 queries; require cardinality-appropriate eager loading such as Django `select_related` or SQLAlchemy `joinedload` for scalar relationships and `prefetch_related` or `selectinload` for collections, because query growth can exhaust database capacity under normal page sizes.
- Treat projection and over-fetch separately from relationship loading: require Django `values` or `values_list`, SQLAlchemy selected columns or `load_only`, or aggregates when unused columns and hydrated model state drive memory or latency rather than query multiplication.
- Verify counting and existence paths use `QuerySet.count`, `QuerySet.exists`, or SQL aggregates instead of hydrating rows; full materialization risks memory exhaustion on large tables.
- Require bounded `iterator`, `yield_per`, pagination, or streaming for large exports and dataframe pipelines; unbounded `list` accumulation can crash workers before any output is delivered.
- Reject per-item downstream requests or filesystem operations when input cardinality can amplify I/O; require batching, bounded streaming, or a cache with measured request or operation counts proving amplification stays within the repository latency and throughput budget.
- Reject synchronous `requests`, file I/O, database or ORM calls, compression, or CPU serialization inside an `asyncio` event loop; blocking work causes request starvation and latency failure. When Django async-safety boundaries or synchronous SQLAlchemy access are detected, require `sync_to_async` with appropriate `thread_sensitive` ownership or a deliberately isolated synchronous session outside the event-loop thread.
- Verify `asyncio.to_thread`, `run_in_executor`, `ThreadPoolExecutor`, or `ProcessPoolExecutor` choice reflects I/O versus GIL-bound CPU cost; the wrong executor causes throughput failure or memory exhaustion.
- Verify `ProcessPoolExecutor` and `multiprocessing` tasks avoid repeatedly pickling large pandas dataframes, tensors, or model graphs by using bounded partitions or repository-owned shared-memory or memory-mapped inputs; duplicated payloads can exhaust worker memory and cause pool failure under production cardinality.
- Require `asyncio.Semaphore`, a bounded worker pool, or queue capacity around fan-out calls; unbounded concurrency risks socket exhaustion, dependency overload, and timeout cascades.
- Require remote I/O deadlines through `asyncio.timeout` or client limits, and separately require executor work to expose cooperative cancellation or run in a terminable process with its `Future` awaited and executor shutdown owned; cancelling the await alone leaves already-running thread or process work consuming resources after callers fail.
- Verify hot-cache population uses a lock, lease, or single-flight primitive rather than parallel recomputation, and require bounded capacity with eviction or TTL plus memory evidence under realistic key cardinality; stampedes duplicate load while unbounded per-key state can exhaust worker memory.
- Reject expensive model construction, network calls, dataset loading, or plugin scanning at module import; import-time work slows every process start and can break autoscaling latency targets.
- Require long-lived Celery, notebook, or service workers to release dataframe, tensor, cache, and request references after each unit; retained state leaks memory until the process is killed.
- Require hot-path evidence from `cProfile`, `py-spy`, `tracemalloc`, query counts, or repository benchmarks before accepting a costly rewrite; unmeasured changes risk latency regression and memory failure.
- Verify producer and consumer buffer limits through `asyncio.Queue(maxsize=...)` or bounded batch sizes under overload; unlimited buffering leaks memory and causes timeout failures.
- For Blocker or Major findings, describe the concrete latency, memory-pressure, or throughput failure scenario.
