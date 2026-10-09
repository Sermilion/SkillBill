# SKILL-414 Subtask 2 - Quiet, accurate CLI output

Parent spec: `.feature-specs/SKILL-414-stabilization-pass-stale-workflow-state-quiet-output-consistent-terminology-review-quality-measurement/spec.md` (Area 2).

## Scope

Paths are under `runtime-kotlin/`.

1. **Process logging configuration.** Before `CliRuntime.run`, `runtime-cli/.../cli/core/Main.kt` configures java.util.logging once.
   - By default, no JUL record reaches stderr: reset the `LogManager` and set the root logger to `OFF`.
   - When verbose, install one stderr `ConsoleHandler` at `Level.ALL` with a single-line formatter (level, source class, message).
   - Put the decision in a small pure function, for example `resolveVerboseLogging(args, environment): Boolean`. It is true when `--verbose` appears among the leading root options (the same scan `routeIntake` uses, before the first non-option token) or when `SKILL_BILL_VERBOSE` is `1` or `true` (case-insensitive).
   - Find every other runtime process entry point (`fun main`, such as a separate MCP server main) and apply the same configuration there. An MCP server's stderr must not carry routine diagnostics either.
2. **Root flag.** Declare `--verbose` as a root flag on `SkillBillCommand` so Clikt accepts it. Add `--verbose` to the `routeIntake` option-skip list. Otherwise `skill-bill --verbose APP-1 ...` would not route to goal. Keep every root option already in that list.
3. **Caller source.** `runtime-infra/host/.../host/JdkRuntimeDiagnostics.kt` resolves the caller with `StackWalker`: the first frame outside `JdkRuntimeDiagnostics` and the `RuntimeDiagnostics` helpers it is called through. Implement checks whether `RuntimeDiagnosticsBestEffortWarning.record` should also be skipped so the record names the real caller. Emit with `Logger.logp(level, callerClass, callerMethod, message)`.
4. **One line for handled conditions.** `warning(message, error)` and `info(message)` log one line that appends `ExceptionClass: exceptionMessage` when an error is given, without attaching the Throwable. `error(message, error)` keeps the Throwable at SEVERE.
5. **Gate JVM decisions.** `runtime-infra/host/.../host/jvm/GateJvmResolver.kt` `recordDecision` uses `diagnostics.info` for routine resolutions. It keeps `diagnostics.warning` only when the disposition is unresolved or image candidates were dropped. Implement reads `branchOf` and `unresolvedDetailOf` to place that line.
6. Logging boundary rules: before adding JUL configuration to `runtime-cli`, check `RuntimeArchitectureTestSupport` and the runtime-core repo-test architecture rules for any rule about where logging may be configured. Keep the change inside what they allow.

## Acceptance Criteria

1. `Main.kt` configures java.util.logging before `CliRuntime.run`. Without verbose, the root logger is `OFF` and has no console handler. With verbose, exactly one stderr handler with a single-line formatter is installed.
2. A pure function decides verbose: true when `--verbose` is among the leading root options or `SKILL_BILL_VERBOSE` is `1` or `true`, case-insensitive. Every other runtime `main` entry point found applies the same configuration.
3. `SkillBillCommand` declares a `--verbose` root flag, and the `routeIntake` option-skip list includes `--verbose`.
4. `JdkRuntimeDiagnostics` emits records whose source class and method are the caller's, not `JdkRuntimeDiagnostics`.
5. `JdkRuntimeDiagnostics.warning` and `info` attach no Throwable to the record and include a one-line exception summary when an error is supplied. `error` keeps the Throwable.
6. `GateJvmResolver.recordDecision` records routine resolutions through `info` and uses `warning` only for unresolved dispositions or dropped image candidates.
7. A test in `runtime-infra/host/src/test` attaches a capturing handler to the diagnostics logger and asserts the caller's class as the record source. It also asserts that a warning with an error has no thrown Throwable and a message containing the exception summary.
8. `GateJvmResolverTest` asserts that a routine resolution is recorded at info, not warning, and that an unresolved resolution is recorded at warning.
9. A runtime-cli test covers verbose resolution for the flag, the env var, and neither. A `routeIntake` test asserts that `--verbose APP-1 Add CSV export` routes to goal with the flag kept as a root option.

## Test Obligations

- AC 7 catches the source-class regression and a stack-trace regression for handled conditions.
- AC 8 catches routine records drifting back to WARNING.
- AC 9 catches a `--verbose` flag that silently breaks goal routing, and an env var that is ignored.
- No test asserts the root logger's level or handler list as structure. AC 1 is audited by reading `Main.kt`.
- Tests that build an environment map pass an explicit non-empty map, because an empty map reads the host environment.

## Non-Goals

- No change to stdout payloads, CLI JSON output or `CliRuntime` stderr messages written outside JUL.
- No per-call-site logging edits beyond `JdkRuntimeDiagnostics` and `GateJvmResolver`. Global configuration silences the other direct JUL users.
- No change to `WorkflowStateSchemaValidator` logging, which subtask 1 owns.

## Dependency Notes

Independent of subtasks 1, 3 and 4. Subtask 3 also edits `routeIntake` in `SkillBillCommand.kt`. Apply this subtask's hunk to the file as found, and keep any goal guard or root options already present.

## Implementation Details

Ordered tasks for implement. Do not run `./gradlew check`, compile, or install. Validate owns `./gradlew check`. Audit reads `Main.kt` for AC 1. This subtask does not write a census file.

Shared-file rule: `SkillBillCommand.kt` is also edited by subtask 3. Apply the `--verbose` flag and skip-list hunk to the file as found. Keep every root option already in `routeIntake`'s skip list. Do not add `completionOption()` to that list. Do not revert a goal-guard predicate if subtask 3 landed first.

Mocks use `relaxUnitFun = true`. Tests that build an environment map pass a non-empty map.

### Task 1. Configure JUL in both process mains before run

Serves AC-001, AC-002.

Paths and symbols:
- `runtime-kotlin/runtime-cli/src/main/kotlin/skillbill/cli/core/Main.kt` (`fun main`, then `CliRuntime.run`)
- `runtime-kotlin/runtime-mcp/src/main/kotlin/skillbill/mcp/core/Main.kt` (`fun main`, then MCP stdio or the governed-review bridge)
- Pure `resolveVerboseLogging(args, environment): Boolean`

Verbose is true when `--verbose` is among the leading root options, using the same scan `routeIntake` uses (before the first non-option token), or when `SKILL_BILL_VERBOSE` is `1` or `true`, case-insensitive. Any other env value is not verbose unless the flag is present. `--verbose` is a flag (advance 1). `--db` and `--home` take a value (advance 2), including their `=` forms.

Call configuration from both mains before those runs, not from `runtime-application` or domain. `RuntimeLayerBoundaryArchitectureTest` bans `java.util.logging` in `runtime-application` main. Domain purity in `RuntimeArchitectureTestSupport` also lists it. `runtime-cli` and `runtime-infra/host` already use JUL.

Default: reset `LogManager`, set the root logger to `OFF`, leave no console handler. Verbose: exactly one stderr `ConsoleHandler` at `Level.ALL` with a single-line formatter (level, source class, message). Assumption: when verbose, set the root logger to `ALL` so that handler receives records; a root left `OFF` after reset would swallow them. Implement confirms that pairing.

Assumption: a small JUL configure helper may live in `runtime-infra/host` because that module already uses JUL and both mains can call it. Keep `resolveVerboseLogging` beside CLI `Main.kt` (same package as `routeIntake`'s scan). If `runtime-mcp` cannot depend on `runtime-cli`, duplicate the resolver and configure call in MCP `Main.kt`. MCP uses the same flag scan and the same env var.

test_obligations: none. Do not assert the root logger's level or handler list as structure. Audit AC 1 by reading both `Main.kt` files.

### Task 2. Declare `--verbose` and skip it in `routeIntake`

Serves AC-003.

Path: `runtime-kotlin/runtime-cli/src/main/kotlin/skillbill/cli/core/SkillBillCommand.kt`.

Root options today are `--db` (value, skip +2), `--home` (value, skip +2), and `completionOption()`. The `routeIntake` skip list is only `--db` / `--home` and their `=` forms. Declare `--verbose` as a Clikt flag. Skip `token == "--verbose"` with +1. Keep `--db` and `--home`. Do not add completion to the skip list. Leave `aliases()` unchanged (`feature-verify-stats` -> `verify-stats`, `feature-task-runtime-stats` -> `runtime-stats`).

test_obligations: none in this task. The routing assertion lives in Task 5.

### Task 3. Caller source and one-line handled diagnostics

Serves AC-004, AC-005, AC-007.

Paths and symbols:
- `JdkRuntimeDiagnostics` in `runtime-infra/host` (today `Logger.getLogger(JdkRuntimeDiagnostics::class.java.name)` and `log.log(level, message, error)`)
- `RuntimeDiagnosticsBestEffortWarning` at `runtime-kotlin/runtime-engine/src/main/kotlin/skillbill/engine/diagnostics/RuntimeDiagnosticsBestEffortWarning.kt` (`record(diagnostics, message, cause)`)
- `RuntimeDiagnostics` in `runtime-ports` (`info` is an empty default; the JDK adapter already overrides it). Do not change the interface.

Settled from the digest: skip `JdkRuntimeDiagnostics` and `RuntimeDiagnosticsBestEffortWarning` when walking `StackWalker`. Take the first remaining frame. Emit with `Logger.logp(level, callerClass, callerMethod, message)`. Logger identity may stay `JdkRuntimeDiagnostics`.

`warning` and `info` append `ExceptionClass: exceptionMessage` when an error is supplied and attach no Throwable. `error` keeps the Throwable at SEVERE.

test_obligations:
- New test in `runtime-infra/host/src/test`. Realistic bug: every record still shows source `JdkRuntimeDiagnostics` because the adapter logged through `log()` instead of `logp` with a caller frame. Attach a capturing handler to the diagnostics logger, emit a record from a named caller, assert the record's source class and method are that caller.
- Same test (or the same capturing handler). Realistic bug: `warning(message, error)` still attaches the Throwable, so JUL prints a stack trace for a handled condition. Assert `thrown` is null and the message contains the one-line exception summary.

### Task 4. Routine gate-JVM decisions at info

Serves AC-006, AC-008.

Path: `GateJvmResolver.recordDecision` in `runtime-infra/host` (`.../host/jvm/GateJvmResolver.kt`). `branchOf` and `unresolvedDetailOf` already distinguish the cases.

Today `recordDecision` always calls `diagnostics.warning`. Use `diagnostics.info` for routine `Export` and `LeaveUnset`. Keep `warning` for `GateJvmDisposition.Unresolved` or when `dropped` is non-empty.

test_obligations: extend `GateJvmResolverTest` (disposition-only today, no log assertions). Use a recording `RuntimeDiagnostics` collaborator, or a mock with `relaxUnitFun = true` that stores emitted level and message as data. Do not assert call order.
- Realistic bug: a successful resolve still lands at WARNING and leaks to stderr on every agent launch. Assert a routine resolution is recorded at info, not warning.
- Realistic bug: an unresolved disposition is recorded at info and operators never see the failure. Assert unresolved is recorded at warning.
- Realistic bug: dropped image candidates stay at info because disposition is still `Export`. Assert a resolve with non-empty `dropped` is recorded at warning. Tied to AC-006.

### Task 5. CLI verbose resolution and `routeIntake` with the flag

Serves AC-009.

Cover `resolveVerboseLogging` in a runtime-cli test:
- `--verbose` among leading root options is true
- non-empty env with `SKILL_BILL_VERBOSE` true (use `1` or `true`; one env case is enough, pick a mixed-case value if covering case-insensitivity in that same case)
- neither flag nor env is false

Drive `routeIntake` through `CliRuntime.run`, same harness as `CliRuntimeShellCommandsTest`. Assert `--verbose APP-1 Add CSV export` routes to goal and keeps `--verbose` as a root option. Realistic bug: `--verbose` is omitted from the skip list, so that argv never reaches goal.

test_obligations: those two tests. No extra sibling literals for the same branch. No test that `--verbose` after a non-option is ignored; implement still scans only leading root options.

### Constraints

- No JUL configuration in `runtime-application` or domain.
- No per-call-site logging edits beyond `JdkRuntimeDiagnostics` and `GateJvmResolver`.
- No change to stdout payloads, CLI JSON, or `CliRuntime` stderr written outside JUL.
- No change to `WorkflowStateSchemaValidator` logging (subtask 1).
- No `WORKFLOW_STATE_CONTRACT_VERSION` bump.
- No installer, uninstall, or install-sync commands.
- No census file for this subtask.

## Validation Strategy

The validate phase runs `./gradlew check`: unit tests, CLI goldens (which capture stderr through `CliRuntime`, not JUL), detekt and architecture repo tests. Implement and audit run nothing.

## Next Path

```bash
skill-bill goal SKILL-414
```
