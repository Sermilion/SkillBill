---
name: bill-python-code-review-platform-correctness
description: Review Python runtime correctness, typing edge cases, resources, concurrency, serialization, and time logic.
internal-for: skill-bill
---

# Python Platform Correctness Review

Review Python behavior that can drift from the author's apparent intent.

## Focus

- Runtime semantics, typing and nullability, data models, resource lifecycle, concurrency, retries, serialization, and time logic

## Ignore

- Python style or typing preferences without a reachable runtime, state, resource, or compatibility failure
- Framework behavior already guaranteed by the exact supported version and unchanged configuration

## Applicability

Use this specialist for Python runtime logic, dataclasses, attrs, pydantic, decorators, descriptors, context managers, generators, asyncio, threads, processes, serialization, and time-sensitive behavior.

## Project-Specific Rules

### Python Correctness Rules

- Verify mutations through aliases, slices, and shallow copies such as `copy.copy` cannot corrupt shared state; require an ownership boundary when a caller expects isolation or later writes will create invalid results.
- Reject mutable function defaults such as `def collect(items=[])` and require `None` or a factory because cross-call state leakage causes order-dependent failures.
- Verify `dataclasses.field(default_factory=...)`, attrs factories, and pydantic `Field(default_factory=...)` create and validate independent model state; shared or unvalidated defaults risk corrupt records.
- Require runtime checks at untrusted boundaries even when `typing.cast`, `Protocol`, `Any`, or overloads satisfy a type checker; otherwise invalid values fail later with misleading attribute or call errors.
- Reject truthiness guards such as `if not value` when `0`, `""`, `[]`, and `None` have different contract meanings because valid state can be lost or incorrectly defaulted.
- Require exception handlers around `except Exception` to translate, recover, or re-raise with preserved cause; swallowed failures must not become false success or hide cleanup errors.
- Verify `contextlib.contextmanager`, `with`, and `async with` release files, sockets, sessions, generators, and temporary paths after success, exception, and cancellation or resource leaks will exhaust capacity.
- Require `asyncio.TaskGroup` or an equivalent retained task owner for `asyncio.create_task`; unobserved child exceptions and orphaned tasks risk silent failure and lifecycle leaks.
- Never consume `asyncio.CancelledError` inside cancelled work without cleanup and re-propagation. A task owner that initiated cancellation may await the owned task and consume the expected exception only after verifying its cleanup completed; cancellation loss elsewhere breaks timeout and shutdown ordering and can leave invalid partial state.
- Verify `concurrent.futures.ThreadPoolExecutor` versus `ProcessPoolExecutor` selection matches I/O or GIL-sensitive CPU work, and require owned shutdown because the wrong or leaked executor causes starvation and process hangs.
- Require `asyncio.Queue.task_done` and `Queue.join` accounting to match every dequeued item, with producers stopped before consumers; mismatches deadlock shutdown or discard queued work.
- Verify `json`, `Decimal`, `Enum`, and timezone-aware `datetime` serialization preserves precision, identity, and offsets; implicit conversion or naive time can corrupt contract data across DST and replay.
- Keep wall-clock timestamps separate from elapsed-time deadlines: require timezone-aware wall time for persisted or user-visible events, `time.monotonic` or the event loop's monotonic clock for durations and timeout calculations, and an injectable clock at testable boundaries; wall-clock adjustments otherwise expire work early or extend it indefinitely.
- For Blocker or Major findings, describe the concrete invalid-state or ordering failure scenario.
