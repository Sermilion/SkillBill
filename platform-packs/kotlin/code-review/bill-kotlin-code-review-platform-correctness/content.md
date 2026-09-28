---
name: bill-kotlin-code-review-platform-correctness
description: Review Kotlin type, interop, coroutine, synchronization, and Flow correctness. Use for Kotlin runtime and language-semantics failures.
internal-for: skill-bill
---

# Platform and Correctness Review Specialist

## Focus

- Kotlin types, Java interop, cancellation, supervision, dispatchers, synchronization, and Flow delivery

## Ignore

- Readability feedback without a concrete incorrect state or runtime failure

## Applicability

Use across Kotlin/JVM libraries and services. Android and Compose Multiplatform target behavior belongs to KMP.

## Project-Specific Rules

### Correctness Review Rules

- Require platform values from Java such as `String!` to be validated before dereference; unchecked nullability can crash at `value.length`.
- Reject unsafe generic casts around `List<*>` or erased Java collections because incorrect variance can corrupt data or throw `ClassCastException`.
- Verify `data class` equality excludes mutable identity-sensitive state; unstable `equals` can break map membership and Flow state delivery.
- Require exhaustive handling for `sealed interface` variants; an `else` branch can hide a new contract state and cause incorrect behavior.
- For APIs actually published to Java callers, verify default parameters have the intended Java surface through explicit overloads, a bridge, or selectively applied `@JvmOverloads`; Kotlin-only and non-overloaded Java APIs do not require it.
- Require broad `catch` blocks and `runCatching` recovery paths to propagate parent or scope `CancellationException` while handling ordinary failures separately; a `TimeoutCancellationException` created and owned by the current boundary may be translated to its declared timeout result, but swallowing external cancellation keeps abandoned work alive and breaks parent-child completion ordering.
- Verify `SupervisorJob` is used only where a child failure must not cancel siblings, and that failures are still observed; it isolates child failure propagation but does not make shared mutable state or a failed dependency safe.
- Reject blocking calls on `Dispatchers.Default` and UI dispatchers; `Thread.sleep` or JDBC there risks thread starvation and timeout.
- Require `Mutex` ownership paths to avoid reentrant acquisition and keep suspension or external calls outside the critical section where possible; reacquiring with the same non-null owner token throws `IllegalStateException`, while ownerless nested acquisition can suspend indefinitely.
- Verify shared mutation uses an atomic operation, `Mutex`, actor, or single-dispatcher confinement whose lifetime is explicit; choosing a dispatcher alone does not protect a plain `var` when other contexts can reach it, risking races and lost updates.
- Require `StateFlow` only for a current-state contract where equality-based conflation is acceptable; using it for distinct repeated events risks delivery loss unless replay and ordering are defined elsewhere.
- Require `SharedFlow` replay and extra-buffer capacity, channel capacity, and overflow behavior to match explicit producer/consumer guarantees; `BufferOverflow.SUSPEND` applies backpressure only while subscribers are active, because without subscribers emission does not suspend and values beyond replay are discarded. Require replay, subscriber-readiness coordination, or durable delivery for subscriber gaps; drop policies accept data loss and rendezvous channels provide no buffer.
- For Blocker or Major findings, describe the concrete invalid-state or ordering failure scenario.
