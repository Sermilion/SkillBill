---
name: bill-php-code-review-reliability
description: Use when reviewing PHP workers, queues, schedulers, retries, caches, timeouts, shutdown behavior, and production recovery.
internal-for: skill-bill
---

# PHP Reliability Review Specialist

Review operational behavior under retry, partial failure, long-lived execution, and process termination.

## Focus

- Laravel queues or Horizon and Symfony Messenger when detected
- Persistent workers, schedulers, caches, locks, and downstream timeouts
- Failure telemetry and recovery

## Ignore

- Queue or framework advice without repository-owned dependencies and configuration
- Hypothetical scale concerns lacking a reachable failure mode

## Applicability

Use Laravel rules with queue configuration, `ShouldQueue`, or Horizon evidence. Use Messenger rules with `symfony/messenger` transports or handlers. Apply persistent-lifetime rules to RoadRunner, Swoole, FrankenPHP, workers, and schedulers actually launched by the repository.

## Project-Specific Rules

### PHP Operational Reliability Rules

- Require acknowledgement only after a Laravel job or Messenger handler completes its durable effect; early `ack()` can lose work after a crash.
- Ensure visibility timeout or `retry_after` exceeds the bounded handler runtime with margin; premature redelivery creates a concurrency failure with duplicate effects.
- Reject unlimited retries and require bounded exponential backoff for transient `Throwable`, with jitter whenever multiple workers can encounter the same failure; synchronized retry storms exhaust workers and repeatedly overload recovering dependencies.
- Require poison messages to reach a failed transport, dead-letter queue, or terminal `Horizon` state with diagnostics; endless replay causes starvation.
- Verify non-retryable validation and authorization failures are not wrapped as transient `Throwable`; incorrect retry classification floods the queue.
- Require a durable outbox or equivalent atomic handoff when downstream delivery is mandatory; commit-before-`dispatch()` and after-commit dispatch both retain a crash window before broker acceptance in which required work can disappear permanently.
- Require idempotency storage keyed by a stable business-operation identifier carried unchanged through `dispatch()`, transport delivery, retry, and manual replay around externally visible effects; transport job identifiers can change and allow duplicate actions.
- Verify `DoctrineClearEntityManagerWorkerSubscriber`, Laravel container flushing, or an equivalent reset clears per-message state; stale workers leak tenant or transaction data.
- Ensure `SIGTERM` handling stops intake, finishes or safely abandons the current unit, and exits before orchestration grace expires; abrupt shutdown causes acknowledgement loss.
- Require HTTP, database, process, and lock operations to declare timeouts shorter than the job budget; an unbounded call can block worker replacement.
- Verify PHP `memory_limit` and worker recycling thresholds handle real payloads without masking growth; unchecked retention ends in fatal crashes.
- Ensure cache keys encode version, tenant, locale, and authorization dimensions that affect values; collisions serve stale or exposed data.
- Require distributed locks to have bounded leases and ownership-safe `lock-token` release; deleting another worker's renewed lock creates race failures.
- Verify Laravel `withoutOverlapping()` or Symfony `Lock` ownership protects scheduler overlap and has a recovery expiry; stuck locks can suppress all future runs.
- Ensure `register_shutdown_function()` and `error_get_last()` emit fatal context without falsely marking work successful; missing telemetry hides operational loss.
- Require `Horizon` and `Messenger` worker configuration to match deployed queue names, priorities, and transports; routing drift causes an availability failure.
- Verify replay tools and manual retry commands preserve correlation and idempotency identifiers; operational recovery can otherwise corrupt state.
- For Blocker or Major findings, describe the concrete availability, duplication, or cleanup failure scenario.
