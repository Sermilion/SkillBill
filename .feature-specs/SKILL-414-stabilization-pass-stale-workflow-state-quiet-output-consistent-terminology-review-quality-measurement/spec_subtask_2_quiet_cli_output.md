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

## Validation Strategy

The validate phase runs `./gradlew check`: unit tests, CLI goldens (which capture stderr through `CliRuntime`, not JUL), detekt and architecture repo tests. Implement and audit run nothing.

## Next Path

```bash
skill-bill goal SKILL-414
```
