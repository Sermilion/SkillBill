---
name: bill-kmp-code-review-reliability
description: Use when reviewing WorkManager and CoroutineWorker scheduling, process-death and foreground-service recovery, collector supervision, and connectivity-aware retry on Android and KMP.
internal-for: skill-bill
---

# KMP Reliability Review Specialist

Review only availability, recovery, and cleanup failures in background and long-running work.

## Focus

- WorkManager and `CoroutineWorker` retry, backoff, constraint, and uniqueness correctness
- Foreground-service and process-death recovery for long-running sync
- Coroutine collector supervision and connectivity-aware retry with failure telemetry

## Ignore

- Hypothetical availability concerns with no reachable trigger in the changed code
- Background APIs the module does not schedule or observe
- Data-shape and transaction concerns owned by the persistence specialist

## Applicability

Use this specialist when a diff touches `WorkManager` enqueue or `Worker` implementations, a foreground `Service`, a `BroadcastReceiver` that starts work, a long-lived `Flow` collector on a repeating trigger, or retry and error-classification code. Evaluate every rule against process death, Doze and App Standby, and a device that loses connectivity mid-operation.

## Project-Specific Rules

### WorkManager Scheduling And Retry Rules

- A transient failure must return `Result.retry()` rather than `Result.failure()`; reject collapsing every exception into failure because WorkManager then drops the work permanently and the user's queued action never runs.
- Every retried worker must declare an explicit `BackoffPolicy` and initial delay; reject the implicit default on a worker that retries against a rate-limited endpoint because the retry cadence then amplifies the outage.
- `enqueueUniqueWork` and `enqueueUniquePeriodicWork` must pass an intentional `ExistingWorkPolicy`; reject `REPLACE` on a sync worker because it cancels an in-flight upload and re-runs it from the start, duplicating side effects.
- `Constraints` must match what the work actually requires, and a worker gated on `setRequiresCharging` or `setRequiredNetworkType` must have a user-visible degraded path; reject constraints that can never be satisfied on the target device because the work stays pending forever with no signal.
- Worker input must be small identifiers read from durable storage, never the payload itself; reject large `Data` inputs because exceeding the `Data` size limit throws at enqueue and the operation is lost before it ever starts.
- `CoroutineWorker.doWork` must honor cancellation and clean up partial state in a `finally` block; reject uncancellable blocking calls because a stopped worker then leaks its connection and leaves a half-written batch behind.
- A `CoroutineWorker` performing user-visible long work must implement `getForegroundInfo` and call `setForeground`; reject its absence on an expedited request because the system quietly demotes or drops the work.

### Foreground Service And Process-Death Recovery Rules

- A started foreground service must call `startForeground` with a matching declared `foregroundServiceType` within the platform time limit; reject a delayed or missing call because the system throws and terminates the process mid-operation.
- Long-running sync must checkpoint its progress durably before each suspension point that can outlive the process; reject memory-only progress because a process death restarts the whole transfer and re-applies work already performed.
- Work restarted after process death must be idempotent against its persisted operation identity; reject an unguarded replay because it duplicates the remote or local effect the first attempt already committed.
- A service or receiver that schedules follow-up work must re-establish it after reboot through a `BOOT_COMPLETED` receiver or a persisted periodic worker; reject in-memory-only scheduling because a reboot silently stops all recurring sync.
- Wake locks, notifications, and network callbacks acquired for background work must be released on every exit path including cancellation and failure; reject unmatched acquisition because the leak drains the battery and the system eventually kills the process.

### Collector Supervision And Trigger-Death Rules

- A recurring trigger collected in `viewModelScope` or an injected scope must catch and classify exceptions inside the flow via `catch` or a per-emission `runCatching`; reject an uncaught exception in the collector body because it cancels the scope and silently disables every later trigger with no crash and no log.
- A scope hosting independent long-lived collectors must be built with `SupervisorJob`; reject a plain `Job` because one child's failure cancels its siblings and disables unrelated recurring work.
- `CancellationException` must be rethrown rather than swallowed by a broad `catch (e: Exception)`; reject swallowing it because the coroutine keeps running after cancellation and continues writing after its owner is gone.
- Retry operators must be bounded and must exclude non-transient errors; reject an unconditional `retry()` because a permanent 4xx or serialization error then spins an unbreakable loop that never surfaces the failure.
- Collectors restarted on lifecycle events must be attached through `repeatOnLifecycle` or an equivalent owner-bound launch; reject a raw `launch` in `onStart` because each restart adds another collector and multiplies every triggered operation.

### Connectivity-Aware Retry And Observability Rules

- Retry against a network dependency must wait on an actual connectivity or constraint signal rather than a fixed sleep; reject blind timed retries because they burn attempts and the retry budget while the device is offline.
- Every network operation must carry an explicit timeout; reject an unbounded call because a stalled connection holds the worker slot until the system stops it, and the operation reports neither success nor failure.
- Degraded state from a denied permission, an unmet constraint, or an exhausted retry budget must reach a user-visible or operator-visible surface; reject silent degradation because the user believes queued work completed.
- Enqueue, retry, permanent failure, and recovery must emit structured telemetry with the operation identity and no user content; reject unobservable background paths because an operator cannot distinguish work that never ran from work that failed.
- For Blocker or Major findings, describe the concrete availability, duplication, or cleanup failure scenario.
