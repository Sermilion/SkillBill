# SKILL-414 Subtask 2 - Quiet, accurate CLI output

Parent spec: `spec.md` (Area 2).

## Scope

Paths are under `../../../runtime-kotlin`.

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

Paths are under `../../../runtime-kotlin`. The two runtime `main` entry points are `runtime-cli/.../cli/core/Main.kt` and `runtime-mcp/.../mcp/core/Main.kt`; no other `fun main(` exists. Both modules depend in main on `runtime-core`, not on `runtime-infra:host`. No architecture rule bans `java.util.logging` outside `runtime-application/src/main` (`RuntimeLayerBoundaryArchitectureTest`) and domain (`RuntimeArchitectureTestSupport.domainEffectPuritySourceReferences`), so the shared configuration lives in `runtime-core`, the composition root both mains already import.

### Task 1 - Shared process logging configuration (AC 1, AC 2)

- New file `runtime-core/src/main/kotlin/skillbill/di/core/ProcessLogging.kt`:
  - `object ProcessLoggingEnvironmentKeys { const val SKILL_BILL_VERBOSE = "SKILL_BILL_VERBOSE" }`. This is the only declaration of the key.
  - `fun verboseLoggingRequestedByEnvironment(environment: Map<String, String>): Boolean`: true when the trimmed value equals `1` or `true`, ignoring case.
  - `fun configureProcessLogging(verbose: Boolean)`: call `LogManager.getLogManager().reset()`. Without verbose, set `Logger.getLogger("")` to `Level.OFF` and add no handler. With verbose, set the root to `Level.ALL` and add exactly one `ConsoleHandler` (stderr) at `Level.ALL` with a private single-line `Formatter` that prints `<LEVEL> <sourceClassName>.<sourceMethodName>: <formatMessage(record)>` and a newline. When `record.thrown` is non-null (only `error()` attaches one), the formatter appends the stack trace.
- Assumption for implement to confirm: `skillbill.di` has no repo-test guard rejecting a non-provider file. If one does, move the file to a non-`di` package inside `runtime-core`; do not duplicate the configuration in each main.

### Task 2 - CLI root flag and routing (AC 2, AC 3)

- `runtime-cli/.../cli/core/SkillBillCommand.kt`:
  - Add `internal fun ParameterHolder.verboseOption() = option("--verbose", help = "Print runtime diagnostics to stderr. Same as SKILL_BILL_VERBOSE=1.").flag()` beside `databasePathOption()` and `userHomeOverrideOption()`, and `registerOption(verboseOption())` in `init`.
  - Extract the leading-option scan into a pure `internal fun leadingRootOptionCount(arguments: List<String>): Int`: `--db` and `--home` advance by 2, `--db=` and `--home=` prefixes by 1, `--verbose` by 1; any other token stops the scan.
  - Extract the routing decision into a pure `internal fun routeIntakeTokens(arguments: List<String>, isCommand: (String) -> Boolean): List<String>` that uses `leadingRootOptionCount` and keeps the current goal guard verbatim: return `arguments` unchanged when the first non-option token starts with `-` or is a command, otherwise insert `"goal"` at that index. `routeIntake` delegates to it with `isCommand = { it in registeredSubcommandNames() || it in aliases() }`. Subtask 3 changes this guard later; keep every root option present in the file as found.
  - Add `internal fun resolveVerboseLogging(args: List<String>, environment: Map<String, String>): Boolean = "--verbose" in args.take(leadingRootOptionCount(args)) || verboseLoggingRequestedByEnvironment(environment)`.
- `runtime-cli/.../cli/core/CliRuntime.kt`: add `val verbose by verboseOption()` to the private `RootFlagProbeCommand`. Without it, the probe (`treatUnknownOptionsAsArgs = true`, `allowInterspersedArgs = false`) treats a leading `--verbose` as a positional and drops a following `--db` or `--home` override.
- `runtime-cli/.../cli/core/Main.kt`: after the `--check-packaged-contracts` branch and before `CliRuntime.run`, call `configureProcessLogging(resolveVerboseLogging(args.toList(), System.getenv()))`.

### Task 3 - MCP entry point (AC 2)

- `runtime-mcp/.../mcp/core/Main.kt`: right after `val environment = System.getenv()` and before the `GovernedReviewEvidenceBridge` / `McpStdioServer` branch, call `configureProcessLogging(verboseLoggingRequestedByEnvironment(environment))`. The MCP process has no root CLI options, so only the env var applies.

### Task 4 - Caller source and one-line handled conditions in `JdkRuntimeDiagnostics` (AC 4, AC 5)

- `runtime-infra/host/.../host/JdkRuntimeDiagnostics.kt`: replace the private `log()` with a private emitter that resolves the caller through `StackWalker.getInstance().walk { frames -> frames.filter { !isDiagnosticsFrame(it.className) }.findFirst().orElse(null) }` and calls `log.logp(level, frame?.className ?: JdkRuntimeDiagnostics::class.java.name, frame?.methodName ?: "", text)`, with a `Throwable` overload used only by `error`.
- `isDiagnosticsFrame(className)` skips a frame whose class name starts with `JdkRuntimeDiagnostics::class.java.name`, starts with `RuntimeDiagnostics::class.java.name` (covers `$DefaultImpls` and `warning$default` / `error$default`), or whose simple name `substringAfterLast('.')` is `RuntimeDiagnosticsBestEffortWarning`. That engine helper is `internal` and unreachable from host, so the match is a string; skipping it answers the spec's question with yes. `SqliteDiagnosticRecordsKt` stays unskipped.
- `warning(message, error)`: message alone when `error` is null; otherwise `"$message (${error::class.java.simpleName}: ${error.message})"` with newlines in the exception message collapsed to spaces. No Throwable attached. `info(message)`: plain message, no Throwable. `error(message, error)`: `SEVERE` with the Throwable kept.

### Task 5 - Gate JVM decision levels (AC 6)

- `runtime-infra/host/.../host/jvm/GateJvmResolver.kt`: `recordDecision` builds the existing message once (`branchOf`, `usedValueOf`, dropped candidates, image root, `unresolvedDetailOf`). It calls `diagnostics.warning(message)` when `disposition is GateJvmDisposition.Unresolved || dropped.isNotEmpty()`, otherwise `diagnostics.info(message)`.
- Widen `recordDecision` from `private` to `internal` so the unresolved branch is testable; `resolve()` reaching `Unresolved` depends on host JDKs found by the guard script's fallback scan.

### Task 6 - Tests (AC 7, AC 8, AC 9)

- New `runtime-infra/host/src/test/kotlin/skillbill/infrastructure/host/JdkRuntimeDiagnosticsTest.kt` (AC 7): attach a capturing `Handler` to `Logger.getLogger(JdkRuntimeDiagnostics::class.java.name)`, set `useParentHandlers = false`, and restore both in `finally`. Call `JdkRuntimeDiagnostics().warning("x", IllegalStateException("boom"))` directly in the test method body, not in a non-inline lambda. Assert `sourceClassName == JdkRuntimeDiagnosticsTest::class.java.name`, `thrown == null`, and the message contains `IllegalStateException: boom`. Catches records naming `JdkRuntimeDiagnostics` and stack traces on handled conditions.
- `runtime-infra/host/src/test/.../jvm/GateJvmResolverTest.kt` (AC 8): add a private recording fake that overrides `warning`, `error` and `info` (the port's `info` default is a no-op). Routine case: `GateJvmResolver(recording, JdkHostPlatformPort).resolve(guardInput(acceptedHome, otherHome))` with an accepted `writeJdkShapedHome` records one info containing `branch=skill_bill_java_home` and no warning. Unresolved case: `recordDecision(mutableMapOf("PATH" to hostPath()), emptyList(), null, GateJvmDisposition.Unresolved("/opt/skill-bill/runtime", "21"))` records a warning containing `branch=unresolved`. Catches routine decisions drifting back to WARNING. Use the existing `PATH` key constant if the test file already references one.
- New `runtime-cli/src/test/kotlin/skillbill/cli/core/VerboseLoggingResolutionTest.kt` (AC 9), same package as the internal functions: `resolveVerboseLogging(listOf("--verbose", "APP-1"), mapOf("PATH" to "/usr/bin"))` is true; `resolveVerboseLogging(listOf("APP-1"), mapOf("SKILL_BILL_VERBOSE" to "TRUE"))` is true; `resolveVerboseLogging(listOf("APP-1"), mapOf("PATH" to "/usr/bin"))` is false; `routeIntakeTokens(listOf("--verbose", "APP-1", "Add CSV export"), isCommand = { it in setOf("goal", "phase") })` equals `listOf("--verbose", "goal", "APP-1", "Add CSV export")`. Catches a flag that breaks goal routing and an ignored env var.
- No test asserts root logger level or handler structure; audit reads `Main.kt` for AC 1. No root `--help` golden exists, so the new flag breaks none.

### Constraints

- No `//` or non-KDoc block comments in Kotlin; KDoc only on interfaces. No inline fully-qualified names.
- Hand-written fakes; any mock uses `relaxUnitFun = true`. Test environment maps are explicit and non-empty.
- Do not touch `WorkflowStateSchemaValidator` logging, stdout/JSON payloads, `CliRuntime` stderr text written outside JUL, or other direct JUL call sites.
- Implement compiles, builds and runs nothing; the validate phase runs `./gradlew check`.

## Validation Strategy

The validate phase runs `./gradlew check`: unit tests, CLI goldens (which capture stderr through `CliRuntime`, not JUL), detekt and architecture repo tests. Implement and audit run nothing.

## Next Path

```bash
skill-bill goal SKILL-414
```
