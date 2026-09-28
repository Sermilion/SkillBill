---
name: bill-php-code-review-testing
description: Use when reviewing PHP test evidence, isolation, static analysis, framework kernels, fixtures, and supported runtime matrices.
internal-for: skill-bill
---

# PHP Testing Review Specialist

Review whether PHP tests and analyzers can fail for the real regression and remain trustworthy across execution environments.

## Focus

- PHPUnit and Pest behavior assertions
- Framework, database, queue, cache, and persistent-worker isolation
- PHPStan, Psalm, and supported PHP/extension matrices

## Ignore

- Coverage-percentage demands without missing behavioral proof
- Tool-specific expectations unless configuration, Composer scripts, or dependencies own the tool

## Applicability

Apply PHPUnit, Pest, Laravel, Symfony, PHPStan, and Psalm rules only when their packages, configuration, base classes, or repository commands are present.

## Project-Specific Rules

### PHP Test Confidence Rules

- Require PHPUnit or Pest assertions on externally observable results rather than only `assertInstanceOf()`; shallow checks allow incorrect behavior to pass.
- Reject tests that compute expected values with the same production `helper()` under test; shared bugs create false-positive confidence.
- Ensure PHPUnit data providers include `0`, `"0"`, `false`, `null`, missing keys, and boundary values when coercion matters; omitted cases hide validation regressions.
- Require exception tests to assert the relevant `Throwable` type and state consequence; accepting any failure can conceal a different crash.
- Verify global state, `ini_set()`, error handlers, clocks, environment variables, and mutable statics are restored in `tearDown()`; leaked lifecycle state makes order-dependent failures.
- Ensure Symfony `KernelTestCase` or Laravel application refreshes do not reuse a contaminated container between tests; stale services can mask worker leaks.
- Require database tests around `DB::transaction()` to prove commit, rollback, locking, tenant scope, and migration behavior; fake persistence misses data-loss races.
- Reject fixtures that bypass model `factory()` events, casts, or constraints when those mechanisms are under review; unrealistic rows yield invalid test evidence.
- Verify `Queue::fake()` assertions cover payload, routing, delay, and stable business idempotency identifiers, then retain a transport integration test; call counts alone miss delivery-contract failures.
- Verify cache fakes assert tenant-aware key scope, `TTL`, tags, and invalidation behavior, then retain a cache integration test; an in-memory fake can hide stale or cross-tenant values.
- Require a multi-unit process test for `RoadRunner`, Swoole, FrankenPHP, Horizon, or Messenger state reset when supported; one-request tests cannot detect cross-tenant leakage.
- Ensure retry tests use deterministic `Clock` instances and explicit attempt state; wall-clock sleeps create flaky timeout regressions.
- Require `RunInSeparateProcess` coverage when code mutates constants, extensions, shutdown handlers, or fatal behavior; in-process tests cannot safely observe those failures.
- Verify PHPStan or Psalm configuration analyzes changed source at the repository's declared level; an excluded path or expanded baseline creates false static-analysis confidence.
- Reject new PHPStan/Psalm baseline entries used to silence changed-code errors; suppression converts a detectable type failure into latent risk.
- Ensure `Serializer` contract or golden-file tests fail when fields, status codes, or template escaping change; snapshot churn without semantic assertions misses client breakage.
- Require CI to exercise every supported PHP version and required extension set declared by `composer.json`; untested matrices can fail installation or runtime behavior.
- Verify parallel PHPUnit/Pest execution isolates `TEST_TOKEN` database names, caches, queues, and temp paths; shared resources create races and corrupt results.
- For Blocker or Major findings, describe the concrete undetected-regression or false-positive test scenario.
