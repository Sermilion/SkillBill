---
name: bill-php-code-review-platform-correctness
description: Use when reviewing PHP language semantics, runtime behavior, state transitions, and execution-lifetime correctness.
internal-for: skill-bill
---

# PHP Platform Correctness Review Specialist

Review PHP behavior whose failure changes program results, leaks state, or makes an execution path unsafe.

## Focus

- PHP value, error, lifetime, generator, and fiber semantics
- Request-scoped versus persistent-process state
- Composer namespace and runtime compatibility

## Ignore

- Formatting preferences without an observable runtime consequence
- Framework guidance unsupported by repository dependencies or entry points

## Applicability

Apply language rules to changed PHP. Apply worker, framework, extension, or fiber rules only when `composer.json`, runtime configuration, source types, or launch commands prove that surface exists.

## Project-Specific Rules

### PHP Runtime Correctness Rules

- Require identity-sensitive decisions to use `===` or `!==` when `0`, `"0"`, `false`, and `null` differ; coercive `==` can accept an invalid authorization or state transition.
- Reject truthiness shortcuts with `empty()` when a submitted zero is valid data; collapsing `"0"` into absence causes incorrect validation and lost updates.
- Verify `isset($row['key'])` is not used to prove array-key presence when `null` is meaningful; use `array_key_exists()` or the contract becomes invalid.
- Ensure `match` arms cover every reachable enum or scalar case without relying on coercion; an unhandled value raises `UnhandledMatchError` and crashes the request.
- Require boundary normalization before typed code consumes `json_decode(..., true)` arrays; mixed scalar shapes otherwise produce invalid offsets, comparisons, or calls.
- Verify files that depend on scalar-call strictness declare `strict_types=1` consistently at their call sites; assuming callee-owned strictness allows incorrect coercion.
- Reject catches limited to `Exception` when cleanup must also survive `Error`; uncaught `Throwable` can skip state restoration and leak a failed operation.
- Ensure custom `set_error_handler()` code respects the active `error_reporting()` mask and returns the intended boolean; mishandling warnings can hide failures or double-report them.
- Require `register_shutdown_function()` fatal handling to inspect `error_get_last()` and preserve prior telemetry; fabricated fatal reports create operationally incorrect incident data.
- Flag reference-bearing `foreach (&$value)` loops that do not `unset($value)` before reuse; the lingering reference can corrupt the final array element.
- Verify mutations of copy-on-write arrays and objects are intentional across aliases; assuming identical semantics can leak state or lose a caller-visible update.
- Require `Generator` consumers to preserve keys, return values, and single-pass behavior where contracts depend on them; eager or repeated traversal can break ordering and exhaust data.
- Reject blocking database or network calls inside a running `Fiber` on a cooperative event loop unless the selected runtime provides non-blocking integration; blocking the loop creates starvation and timeout failures.
- Ensure mutable `static` properties, singletons, and container services are reset between jobs or requests under `RoadRunner`, `Swoole`, or `FrankenPHP`; stale tenant data can leak across executions.
- Require queue workers and schedulers to clear request, locale, authentication, transaction, and error-handler state after each unit; retained lifecycle state causes cross-job regressions.
- Verify `composer.json` PHP and extension constraints match syntax and APIs used by the change; an unsupported runtime matrix produces deployment build or startup failure.
- Ensure namespaces and file paths obey the declared `autoload.psr-4` mapping, followed by `composer dump-autoload` when required; stale class maps cause production-only class-loading crashes.
- For Blocker or Major findings, describe the concrete invalid-state or ordering failure scenario.
