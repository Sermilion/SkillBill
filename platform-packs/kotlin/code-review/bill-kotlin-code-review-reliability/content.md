---
name: bill-kotlin-code-review-reliability
description: Review Kotlin retries, timeouts, supervision, replay, shutdown, cleanup, ordering, and operational evidence.
internal-for: skill-bill
---

# Reliability Review Specialist

## Focus

- Failure classification, bounded recovery, consumer lifecycle, durable ordering, cleanup, and telemetry

## Ignore

- Observability polish without an outage, loss, duplication, or recovery consequence

## Applicability

Use for Kotlin services, workers, schedulers, queues, caches, and downstream integrations.

## Project-Specific Rules

### Reliability Review Rules

- Retry `HttpClient.request` only when the operation is eligible under its caller-owned latency and attempt budget, the failure is transient, and replay is safe; non-idempotent, caller-budgeted, or latency-sensitive calls may prohibit retries, while retrying permanent or authorization failures risks amplification and lockout.
- Reject unbounded `retryWhen` loops; require attempt limits, backoff, jitter, and a terminal outcome to prevent resource exhaustion.
- Require a locally owned `TimeoutCancellationException`, which may become a domain timeout at that boundary, to remain distinct from parent or sibling `CancellationException`, which must propagate; broad recovery otherwise risks cancellation failure, abandoned work, and duplicate effects.
- Require every retried write such as `repository.insert` to have an effective deduplication strategy that is atomically enforced with the mutation, such as an idempotency key, unique message constraint, conditional update, or natural idempotency; absent replay protection risks duplicate durable state.
- For the detected broker, verify `acknowledge` or offset commit occurs only after the corresponding durable state reaches its required commit point; exact ack, transaction, redelivery, and offset semantics are framework-specific, but a crash must not turn incomplete work into permanent loss.
- Require `SupervisorJob` only for independently recoverable partitions; hiding a shared fatal dependency failure can leave invalid partial service state.
- Verify the `readiness` signal follows the migrations, clients, subscriptions, and warmup actually required by the detected service; publishing readiness before required dependencies risks request failure or consumption in an invalid partial state.
- Require shutdown hooks to stop intake, apply the broker's drain or rebalance contract, await bounded in-flight work, cancel remaining scopes, and `close` resources in dependency order; skipped stages risk loss, duplication, or use-after-close failure.
- Require bounded `withContext(NonCancellable)` only for cleanup that must finish despite cancellation, and keep that region minimal; an unbounded non-cancellable region risks shutdown timeout, while ordinary cleanup should remain cancellable.
- Require outbox records and business state to share one atomic transaction owned by the detected framework; separate commits risk missing events. Separately verify the relay's claiming, retry, and publication behavior preserves the ordering required per aggregate or partition, because atomic insertion alone does not establish publication order.
- When a circuit-breaker library such as `resilience4j` is detected, verify its configured failure predicate distinguishes dependency failures and timeouts from caller cancellation and locally excluded outcomes; counting the wrong signals can open or hold a circuit incorrectly.
- Require metrics and structured logs to expose attempts, terminal outcome, queue depth, and correlation ID; missing `retry_count` evidence makes operational failure invisible.
- Require replay and rebuild jobs to bound concurrency, persist checkpoints, make repeated work duplication-safe, support safe reruns, and report a terminal outcome; an interrupted or concurrent replay without these controls can overload dependencies, duplicate effects, or leave recovery completeness unknowable.
- For Blocker or Major findings, describe the concrete availability, duplication, or cleanup failure scenario.
