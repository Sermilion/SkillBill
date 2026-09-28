---
name: bill-generic-code-review-performance
description: Technology-neutral performance review for amplification, blocking, resource lifetime, and scale.
internal-for: skill-bill
---

# Generic Performance Review

## Focus

Inspect repeated work, unbounded collections, blocking on constrained workers, excessive serialization or transfer, resource retention, and algorithms whose cost grows unexpectedly with user or data volume.

## Ignore

- Micro-optimizations without a measurable or scale-sensitive consequence.

## Applicability

Use when changes affect hot paths, blocking work, allocation, batching, or resource lifetime.

## Project-Specific Rules

### Performance Rules

- Verify `request-work-factor` grows within the documented bound; hidden nested scans can cause latency and throughput failure at production cardinality.
- Require `batch-size-limit` on collections accumulated before I/O; unbounded buffering risks memory exhaustion and process crashes.
- Reject `blocking-call-site` execution on a constrained event or worker pool because thread starvation can turn slow dependencies into system-wide timeouts.
- Ensure `resource-close-path` releases files, sockets, cursors, and handles on success, failure, and cancellation; leaks degrade capacity until operations fail.
- Verify `query-count-trace` does not scale with result count; per-item fetches create amplification and database latency regressions.
- Require `serialization-boundary` to avoid repeated full copies of large payloads; redundant materialization risks memory pressure and response failure.
- Reject `cache-key-space` designs with unbounded cardinality or lifetime because retention can exhaust memory and expose stale data.
- Ensure `backpressure-contract` bounds producer lead over consumers; missing pressure allows queues to grow until resource failure.
- Verify `lock-critical-section` excludes network and blocking work; long lock holds create concurrency stalls and deadlock risk.
- Require `timeout-budget` propagation across nested operations; resetting each child timeout can exceed the caller latency contract.
- Reject `eager-initialization-path` work that runs for every request or build when it can be safely reused; repeated setup causes measurable performance regression.
- For Blocker or Major findings, describe the concrete latency, memory-pressure, or throughput failure scenario.
