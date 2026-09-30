---
name: bill-ios-code-review-reliability
description: Use when reviewing iOS background work, retries, relaunch, and degradation risks.
internal-for: skill-bill
---

# Reliability Review Specialist

Review only availability, recovery, and cleanup failures.

## Focus

- BGTaskScheduler and background URLSession lifecycle
- Expiration, retry, idempotency, and relaunch
- Degradation and operational observability

## Ignore

- Hypothetical availability concerns with no reachable lifecycle
- Background APIs not enabled or used by the detected target

## Applicability

Apply background rules only when capabilities, identifiers, session configuration, or lifecycle hooks are detected. Verify behavior against the deployment target and Apple API availability.

## Project-Specific Rules

### Background Lifecycle Rules

- Every `BGTaskScheduler.register` call must be registered during application launch before submission; reject late registration that causes the background task lifecycle to fail.
- `BGTaskSchedulerPermittedIdentifiers` and submitted `BGTaskRequest.identifier` values must exactly match the project configuration and permitted identifiers; reject drift that makes scheduling fail operationally.
- `BGTaskScheduler.shared.submit` submission failures must remain visible and actionable through logging and retry policy; never discard the error because background work then silently disappears.
- A `BGProcessingTask` expiration handler must be installed before work proceeds and cancel all tracked work on expiration; reject cleanup races that leak resources after expiration.
- Each `BGTask` must call `setTaskCompleted` exactly once through `setTaskCompleted(success:)`; reject missing or duplicate completion that damages future scheduling reliability.
- `beginBackgroundTask` tokens must end through `endBackgroundTask` on success, failure, cancellation, and expiration; reject leaked assertions that trigger termination.
- Background `URLSession` configuration identifiers must be stable and uniquely owned in `URLSessionConfiguration.background(withIdentifier:)`; reject collisions that route lifecycle events to incorrect state.

### Recovery And Degradation Rules

- `application(_:handleEventsForBackgroundURLSession:completionHandler:)` must reconnect delegates and invoke the completion handler after event draining; reject relaunch loss that leaves transfers stuck.
- Retry policy must classify transient failures, honor `Retry-After`, add bounded jitter, and cap attempts; reject retry storms that create resource starvation or timeouts.
- Retried writes must carry an idempotency key or durable operation identity; reject replay that duplicates remote or local data after relaunch.
- Background progress must be persisted before suspension in a termination-safe data contract and restored on next launch; reject memory-only state that loses user work when iOS terminates the process.
- Unsupported capability, offline state, or denied permission must degrade to an explicit foreground path or user-visible state; reject silent availability failure.
- Reliability paths must emit privacy-safe `Logger` events or configured telemetry for submission, expiration, retry, completion, and data recovery; reject unobservable failures that operators cannot diagnose.
- For Blocker or Major findings, describe the concrete availability, duplication, or cleanup failure scenario.
