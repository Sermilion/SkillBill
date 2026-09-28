---
name: bill-php-code-review-performance
description: Use when reviewing measurable PHP latency, query, hydration, rendering, allocation, autoload, cache, and worker-memory regressions.
internal-for: skill-bill
---

# PHP Performance Review Specialist

Report only evidence-backed regressions on important paths, with the input shape and resource consequence.

## Focus

- PDO, Doctrine, Eloquent, serializers, and template hot paths
- Streaming, batching, OPcache, Composer autoload, and boot cost
- Persistent-worker memory and async blocking

## Ignore

- Micro-optimizations without measured or structurally obvious impact
- ORM, template, cache, or fiber rules unsupported by repository evidence

## Applicability

Use framework and tool-specific checks only when Composer packages, configuration, profiles, queries, or changed source prove they participate in the path.

## Project-Specific Rules

### PHP Performance Failure Checks

- Require repeated PDO lookups to use one prepared `PDOStatement` where semantics permit; reparsing per row causes an operational latency failure.
- Reject Doctrine lazy association access or Eloquent property traversal inside unbounded loops; N+1 queries cause request timeouts as result size grows.
- Ensure eager loading selects only needed relations and columns; loading a full object graph can crash PHP before serialization.
- Require bulk writes to use set-based operations or bounded batches with periodic Doctrine `flush()` and `clear()`; when Eloquent processing mutates the selection predicate, use stable-key `chunkById()` or `lazyById()` instead of offset-based `chunk()`, which can skip records while an ever-growing unit of work can crash workers.
- Verify cursor, `yield`, `LazyCollection`, or streamed-response paths do not first call `all()` or `toArray()`; accidental materialization creates a memory failure.
- Reject per-item Symfony Serializer normalization or Laravel Resource expansion when one batched projection suffices; repeated reflection and queries create latency regressions.
- Ensure Blade or Twig loops do not perform service resolution, authorization queries, or database access per element; render cost creates a request-timeout failure.
- Require cache keys to have bounded cardinality and TTLs aligned with invalidation; user-input key explosions cause memory pressure and eviction failures.
- Verify OPcache preload and `opcache.validate_timestamps` assumptions match the deployment model; stale bytecode can serve incorrect code after rollout.
- Ensure optimized Composer autoload files are rebuilt after namespace changes when deployment uses `composer install --classmap-authoritative`; stale maps cause startup failures.
- Reject repeated container or configuration reconstruction inside each job when the selected worker safely supports reuse; unnecessary boot work causes a performance regression.
- Require reused services under `RoadRunner`, `Swoole`, or `FrankenPHP` to release request graphs and buffers; retained references eventually crash the worker.
- Verify large string concatenation, `json_encode()`, and copy-on-write array transformations do not duplicate full payloads on the hot path; exceeding `memory_limit` produces a fatal crash.
- Ensure pagination uses indexed deterministic predicates or keyset cursors for deep traversal; large `OFFSET` values produce database latency and timeouts.
- Reject blocking PDO, filesystem, or HTTP operations inside an event-loop `Fiber` without runtime-aware adapters; one call starves concurrent work.
- Require supported PHP and extension benchmarks to cover JIT, mbstring, intl, or driver differences relied upon by the optimization; matrix drift can erase gains or break output.
- Verify changes to `realpath_cache_size` or filesystem-heavy autoloading against production path counts; undersized caches cause a repeat-I/O latency regression.
- For Blocker or Major findings, describe the concrete latency, memory-pressure, or throughput failure scenario.
