---
name: bill-typescript-code-review-testing
description: Use when reviewing TypeScript type assertions, unit and integration behavior, browser or worker evidence, package entry points, async races, and regressions.
internal-for: skill-bill
---

# Testing Review Specialist

## Focus

- Compile-time type assertions and runtime behavioral evidence
- Unit, integration, contract, browser, worker, and package-consumer tests
- Scheduling, cancellation, module matrices, regression proof, and test validity

## Ignore

- Coverage percentages without an identified missing behavior
- Tests that restate implementation or static types without a failure mode
- Snapshot churn without a semantic assertion

## Applicability

Choose test layers from the changed contract and the repository's actual runner, runtime, framework, package, and module support matrix.

## Project-Specific Rules

### Type and Behavioral Contract Rules

- Type-level changes must use `tsd`, `expectTypeOf`, `@ts-expect-error`, or the repository equivalent for accepted and rejected calls; flag a declaration regression that ordinary unit tests cannot detect. Verify the accepted-call inference and rejected-call `tsc --noEmit` diagnostic before reporting this failure.
- Compile-time assertions must be paired with runtime validation tests at JSON, JavaScript, database, network, DOM, or message seams; reject a false-safety claim based only on erased types. Verify the compiled `dist/*.js` path with malformed boundary input before reporting this failure.
- Unit tests must assert outputs, state transitions, errors, and side effects for the changed branch; prevent a regression hidden by tests that only await successful completion. Verify branch-specific values, `expect.objectContaining` state snapshots, thrown errors, and collaborator calls before reporting this failure.
- Contract fixtures must include optional, nullable, malformed, extra-field, and version-skew inputs where parsing or narrowing changed; flag invalid data paths without evidence. Verify the `safeParse` result or stable rejection for each boundary fixture before reporting this failure.

### Runtime and Integration Failure Rules

- Integration tests must exercise the actual database, transport, filesystem, queue, or framework adapter when its semantics changed; reject mocks that conceal transaction or serialization failures. Verify committed `SELECT` rows, wire bytes, filesystem entries, or acknowledged messages from the real adapter before reporting this failure.
- Browser behavior must run in Playwright, Cypress, Web Test Runner, or the detected harness for rendering, hydration, focus, routing, and network consequences; prevent DOM-emulator false positives. Verify `page.on('console')` output, DOM state, focus target, route, and intercepted requests before reporting this failure.
- Worker and process behavior must test message transfer, cancellation, errors, and termination in the affected runtime; flag lifecycle failures unobservable in an in-process fake. Verify `postMessage` payloads, exit status, termination events, and open handles before reporting this failure.
- Package tests must import declared `exports` from built tarballs under supported ESM and CommonJS modes; reject source-path tests that miss broken declarations or entry points. Verify `npm pack` contents and consumer import results for each declared condition before reporting this failure.

### Concurrency and Regression Evidence Rules

- Promise tests must await completion and make rejection explicit; prevent a false pass when the test runner exits before asynchronous assertions execute. Verify `expect.assertions` counts and the awaited resolve or reject path before reporting this failure.
- Races, retries, cancellation, and stale completions must use controlled promises, fake timers, barriers, or deterministic events; reject scheduler-dependent tests that intermittently miss the bug. Verify the forced event order and terminal state under `vi.useFakeTimers()` or the detected scheduler before reporting this failure.
- The changed failure mode must fail against the pre-fix behavior and pass after the fix through a focused regression assertion; flag tautological coverage that cannot detect recurrence. Verify the same focused `test()` assertion against both pre-fix and corrected behavior before reporting this failure.
- Test toolchain configuration and CI selection must include the affected workspace, target, module format, browser, and generated output; prevent a green suite that silently omits the changed consumer. Verify `--listTests` output and CI matrix jobs for the affected consumer before reporting this failure.
- For Blocker or Major findings, describe the concrete undetected-regression or false-positive test scenario.
