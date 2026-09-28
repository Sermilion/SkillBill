---
name: bill-rust-code-review-reliability
description: Use when reviewing Rust cancellation, backpressure, retries, timeouts, task supervision, runtime shutdown, observability, and degradation.
internal-for: skill-bill
---

# Reliability Review Specialist

## Focus

- Cancellation safety, timeout coverage, retries, idempotency, and partial progress
- Bounded channels, backpressure, task supervision, joins, panics, and orphan work
- tokio/async-std runtime startup and graceful shutdown
- External client failure, degradation, logging, metrics, tracing, and recovery

## Ignore

- Requests for retries where failure is permanent or repetition is unsafe
- Observability preferences without an operational decision they support
- Hypothetical scale concerns outside documented workloads or limits

## Applicability

Apply to async tasks, services, workers, queues, schedulers, network clients, caches, and lifecycle code.

## Project-Specific Rules

### Rust Reliability Rules

- Require each side effect followed by `.await` to define what dropping the future leaves committed; reject cancellation that exposes partial state or loses required cleanup.
- Ensure `tokio::time::timeout` distinguishes an elapsed deadline from inner-operation errors and handles the cancelled future safely; flag ambiguous failure classification or leaked work.
- Require bounded ingress through `tokio::sync::mpsc::channel` or an equivalent capacity policy; reject overload hidden in memory growth, dropped data, or producer starvation.
- Verify concurrency limits use owned `Semaphore` permits whose release survives errors and cancellation; reject permit leaks that deadlock service progress.
- Supervise spawned work with `JoinSet`, `TaskTracker`, or retained `JoinHandle` values; reject detached task panics, silent failures, or work that outlives its owner.
- Require retry policy to classify `Result` errors, cap attempts, apply jittered backoff, and respect an overall deadline; flag retry storms, duplicate writes, or permanent-failure loops.
- Ensure mutation retries carry an idempotency key or conditional write such as `If-Match`; reject replay that duplicates externally visible state.
- Verify stream consumers handle `StreamExt::next` termination and lag from `broadcast::Receiver::recv`; reject silent data loss or busy-loop recovery.
- Require graceful shutdown to cancel intake with `CancellationToken`, close senders, drain and join workers, and flush durable state in dependency order within a repository-owned deadline, then apply an explicit abort or force-close policy and report unfinished work or flush failures; reject indefinite hangs, silent truncation, or dependency-order loss.
- Ensure synchronous destructors do not attempt async cleanup in `Drop`; require an explicit `close().await` contract and flag resource loss when shutdown is skipped.
- Preserve operational diagnosis through structured `tracing::instrument` fields and error sources without secrets; reject failure paths that cannot identify the affected request or dependency.
- Verify degraded-mode caches or circuit breakers use `Instant` and bounded staleness; reject wall-clock jumps, indefinite stale data, or recovery races.
- For Blocker or Major findings, describe the concrete availability, duplication, or cleanup failure scenario.
