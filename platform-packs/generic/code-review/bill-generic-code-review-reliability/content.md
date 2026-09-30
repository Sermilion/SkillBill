---
name: bill-generic-code-review-reliability
description: Technology-neutral reliability review for retries, timeouts, cancellation, recovery, and telemetry.
internal-for: skill-bill
---

# Generic Reliability Review

## Focus

Inspect partial failure, retry bounds, idempotency, timeouts, cancellation, shutdown, lease or lock expiry, restart recovery, backpressure, and terminal telemetry. Ensure failures remain visible and do not silently convert to partial success.

## Ignore

- Operational preferences without a reachable availability or recovery failure.

## Applicability

Use when changes affect retries, timeouts, cancellation, recovery, cleanup, or observability.

## Project-Specific Rules

### Reliability Rules

- Require `retry-attempt-limit` and backoff to be explicit; unbounded retries can amplify an outage and exhaust worker resources.
- Verify `retry-idempotency-key` covers every externally visible effect; repeating a partially completed operation risks duplicate data.
- Reject `timeout-without-cancel` behavior because abandoned work can retain resources and continue mutating state after the caller fails.
- Ensure `cancellation-propagation-path` reaches child operations and cleanup; detached work leaks capacity and breaks shutdown.
- Require `startup-recovery-scan` to reconcile interrupted durable work before accepting conflicting new operations; skipped recovery leaves invalid state.
- Verify `shutdown-drain-deadline` stops intake before bounded draining; closing dependencies first risks data loss and use-after-close failures.
- Reject `unbounded-work-queue` admission without backpressure because load spikes can consume memory and turn latency into process failure.
- Ensure `lease-expiry-owner` uses fencing or generation checks; stale workers can race a replacement and duplicate effects.
- Require `partial-failure-record` to preserve which steps completed; restarting from an unknown point can corrupt data or repeat unsafe work.
- Verify `terminal-event-emission` occurs exactly once for success and failure; missing or duplicate telemetry breaks operational recovery decisions.
- Reject `fallback-success-result` that hides degraded or skipped work because false success prevents retry and leaves the system unavailable.
- For Blocker or Major findings, describe the concrete availability, duplication, or cleanup failure scenario.
