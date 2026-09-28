---
name: bill-python-code-review-testing
description: Review Python pytest/unittest quality, fixtures, monkeypatching, parametrization, async/time tests, integration boundaries, and regression value.
internal-for: skill-bill
---

# Python Testing Review

Review whether changed tests prove changed behavior and fail for the defect they claim to cover.

## Focus

- pytest and unittest structure, fixtures, monkeypatching, parametrization, async and time behavior, integration boundaries, negative paths, and regression value

## Ignore

- Test style or arrangement preferences that do not weaken regression detection, isolation, determinism, or contract coverage
- Missing tests for unreachable behavior or unchanged framework guarantees

## Applicability

Use this specialist whenever tests, fixtures, framework test clients, database or queue harnesses, async behavior, retries, time, filesystem boundaries, or bug-fix regression proof change.

## Project-Specific Rules

### Python Testing Rules

- Require a regression test to reproduce the pre-fix failure and assert returned, persisted, emitted, or user-visible behavior through `pytest`; a happy-path-only assertion can let the bug escape again.
- Reject tests that repeat implementation branches, assert only `Mock.called`, or check object construction without a failure condition; tautological coverage creates false confidence during refactors.
- Require negative contract cases through `pytest.raises`, status/error assertions, or invalid payload parametrization; missing rejection evidence allows unsafe input regressions.
- Verify `pytest.fixture` scope matches state lifetime and cleanup runs through `yield` or finalizers after assertion failure; leaked databases, environment, or globals cause order-dependent test failures.
- Require `monkeypatch` to target the symbol where the code under test looks it up and to restore environment or clock state; patching the provider module can silently exercise real dependencies.
- Require behavior-oriented `pytest.mark.parametrize` cases to name meaningful contract partitions instead of enumerating implementation branches; branch-shaped tests break on safe refactors and miss invalid states.
- Require integration evidence with the production serializer, ORM, migration, filesystem, HTTP, broker, CLI, or package entry point when those boundaries change; mocks can hide contract and data failures.
- Use `hypothesis` strategies for parsers, serializers, validators, state machines, or broad input domains when examples cannot cover invariants; untested edge values risk corruption or crashes.
- Verify `pytest-asyncio` loop scope, async fixture ownership, cancellation, and pending-task cleanup with bounded waits; leaked tasks and clients cause flaky shutdown failures.
- Require retry and idempotency tests for framework requests, webhooks, transaction retries, and worker replays to prove duplicate delivery cannot duplicate durable effects and failed attempts remain observable; one successful execution does not protect a generally retriable mutation.
- Require deterministic time through injected wall and monotonic clocks or controlled event-loop time rather than `time.sleep`; test deadline boundaries separately from persisted timestamps because timing luck and clock adjustments mask timeout regressions.
- Inject interruption on both sides of the durable checkpoint boundary, immediately before and after its associated effect, then restart from recorded progress and assert cleanup, completion of all intended effects, no skipped effects, and duplicate-safe replay with no duplicates; happy-path worker tests cannot reveal lost or repeated work after cancellation or process death.
- Require concurrency tests to coordinate with `threading.Event`, barriers, or async primitives and assert the contested invariant; uncontrolled scheduling can miss races and deadlocks.
- Verify database and migration tests use a backend that preserves relevant transaction, isolation, and DDL semantics; an incompatible fake can pass while production data fails.
- Require `caplog` or structured telemetry assertions for terminal background failures; tests that ignore observable errors allow silent operational regression.
- Verify package tests install the built wheel in an isolated environment and invoke `[project.scripts]` entry points; source-tree imports can hide missing-resource build failures.
- Require cleanup assertions for `AsyncClient`, SQLAlchemy sessions, temporary paths, and worker tasks after injected exceptions; absent lifecycle evidence lets resource leaks escape.
- For Blocker or Major findings, describe the concrete escaped-regression or false-confidence scenario.
- For Blocker or Major findings, describe the concrete undetected-regression or false-positive test scenario.
