---
name: bill-go-code-review-reliability
description: Use when reviewing Go service shutdown, deadlines, retries, backpressure, workers, external clients, observability, partial failure, and leak-free termination.
internal-for: skill-bill
---

# Go Reliability Review Specialist

Own operational behavior after components are composed: graceful stop, bounded waiting, degradation, recovery, and evidence operators can observe.

## Focus

- Graceful server, worker, queue, and connection shutdown with bounded operational recovery
- Deadlines, retries, backpressure, partial failure, and operator-visible evidence

## Ignore

- Statement-level channel ownership and synchronization correctness owned by platform correctness
- Component placement and package ownership decisions owned by architecture

## Applicability

Apply to servers, workers, queues, scheduled jobs, and outbound clients. Check actual process lifetime and failure paths rather than only successful request flow.

## Project-Specific Rules

### Go Reliability Rules

- Require process cancellation to originate from `signal.NotifyContext` or an equivalent owned signal path; unmanaged signals create a lifecycle failure through abrupt shutdown and lost work.
- Verify `http.Server.Shutdown` receives a bounded fresh context, its returned error is handled, and timeout paths use `http.Server.Close` when forced termination is the declared fallback; ignored shutdown failures leave listeners or requests alive while the process exits.
- Require owners of connections taken through `http.Hijacker` to track and close or drain them because `http.Server.Shutdown` does not manage hijacked connections; otherwise deploys leak sessions or hang termination.
- Ensure errors from `http.Server.Serve` distinguish `http.ErrServerClosed`; treating normal closure as a crash breaks operational health reporting.
- Require shutdown orchestration to stop intake, cancel worker scopes, wait for in-flight work, and release queue resources in the component's declared operational order; skipped or reversed drain stages risk termination hangs or lost work. Leave individual `errgroup` return and channel-close correctness to platform correctness.
- Require the lifecycle owner to account for all admitted work before shutdown begins and wait for that work only within the bounded drain deadline; untracked work or an unbounded wait can lose jobs or hang termination. Leave `sync.WaitGroup` statement correctness to platform correctness.
- Require outbound `http.Client` use to set request deadlines and reuse a configured `Transport`; default or per-call clients risk indefinite timeouts and connection leaks.
- Ensure response bodies from `Client.Do` are closed and drained when reuse matters; abandoned `resp.Body` values cause connection-resource failure.
- Reject retries that ignore `context.Context`, idempotency, exponential backoff, or a finite budget; retry storms amplify partial failures and latency.
- Require queue and worker admission to use bounded `chan T`, semaphore, or explicit rejection policy; unbounded goroutines cause memory exhaustion and scheduler starvation.
- Require queue consumers to define acknowledgement timing and at-most-once or at-least-once delivery semantics before `Ack`, `Nack`, or visibility-timeout changes; acknowledging too early loses work while retrying after side effects duplicates data.
- Verify worker draining stops intake before waiting for in-flight `sync.WaitGroup` work and respects a bounded deadline; reversed lifecycle ordering accepts jobs that cannot finish and causes shutdown timeout or data loss.
- Verify overload paths return a deliberate `http.StatusServiceUnavailable` or equivalent signal; silently accepting discarded work creates a client contract failure.
- Ensure periodic work created by `time.NewTicker` calls `Stop` and exits with component cancellation; orphaned tickers and goroutines leak after reload.
- Require external clients such as `grpc.ClientConn` or `sql.DB` to be shared and closed by their lifecycle owner when applicable; per-request construction risks resource exhaustion.
- Verify partial-result aggregation records which operation failed and does not publish invalid success state; swallowed errors cause a data-contract failure and misleading responses.
- Require structured `slog` records or metrics to include operation, duration, outcome, and stable identifiers without secrets; missing evidence makes timeout regressions operationally invisible.
- Ensure `http.Handler` health and readiness paths distinguish process liveness from dependency readiness; conflation causes an operational failure through restart loops or traffic sent to invalid state.
- Reject shutdown paths that call `os.Exit` before deferred flush or close work completes; buffered telemetry and persisted state may be lost.
- For Blocker or Major findings, describe the concrete availability, duplication, or cleanup failure scenario.
