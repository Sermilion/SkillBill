# SKILL-398 Subtask 5 - collapse-remaining-errors

Parent spec: [.feature-specs/SKILL-398-runtime-exception-reduction/spec.md](spec.md)
Issue key: SKILL-398

## Scope revision (2026-10-02)

The first implement attempt blocked with no changes. Its scope was 75 classes in contracts, domain, engine, application, infra and mcp, about 60 catch-site changes, the MCP capture predicate, the telemetry and docs edits and the transition finish. That was too much for one implement phase that cannot compile. This subtask now converts only the persistence and transport failures. Those carry the riskiest behaviour (database retry, the goal-status payload, HTTP status handling and the runtime-owned fact boundary), and it adds the two shared pieces every other conversion needs.

The other areas moved to the follow-up bundle `../../SKILL-400-runtime-error-codes`:

- JSON and failure-wire codes;
- external platform-pack and add-on codes;
- durable-decode and request codes;
- phase-slot codes;
- execution-plan codes;
- domain and MCP codes;
- infra host, launcher, skills and workflow codes.

Leave their classes, throw sites, catch sites and tests unchanged here. `spec.md` was not changed, so the shared preplan's parent-spec hash stays valid.

Census on `feat/SKILL-398-runtime-exception-reduction` at `14e681f4b` (after subtask 4):

| Classes | Supertype today | Main reference lines / files | `assertFailsWith` |
|---|---|---|---|
| `DatabaseAccessError`, `DatabaseBusyError` | `RuntimeException` | 32 / 17 for all nine classes | 21 for all nine |
| `InvalidTelemetryTransportOutcomeError`, `TelemetryProxyRequestFailureError`, `TelemetryProxyInvalidResponseError`, `TelemetryRelayUrlUnconfiguredError`, `UnresolvedRemoteTransportPortError` | `SkillBillRuntimeException` | | |
| `InvalidGoalTelemetryRowError` (runtime-infra/sqlite) | `ShellContentContractException` | | |
| `RuntimeOwnedFactUnavailable` (runtime-application and runtime-engine) | `SkillBillRuntimeException` | | |

## Scope

(F-005, persistence and transport slice.) Follow the parent spec's "Target failure model". The SKILL-398 subtask 4 pieces are already on the tree: `rethrowUnless`, `failureCodeLabel` and `isShellContentContractFailure`, plus the guarded edge sites.

1. **Shared pieces.** Every later conversion relies on these. A SKILL-400 subtask that finds one missing adds it exactly as written here.
   - **Database rethrow helper.** In `skillbill.error.core`, add `fun SkillBillRuntimeException.rethrowIfDatabaseFailure()`. It throws `this` when `code is DatabaseFailureCode`.
   - **MCP capture parity.** In `McpToolDispatcher.dispatch` (runtime-mcp `skillbill.mcp.core`), the failure `when` decides whether to capture telemetry by former class, as today:
     - shell-content failures, `InvalidLearningSourceError`, `IllegalArgumentException` and `IllegalStateException` are returned without capture;
     - every other `Exception` is captured and then returned.

     Add a private `fun Throwable.uncapturedAtMcp(): Boolean` holding exactly that no-capture condition, so later conversions can add a code to it. A code whose former class was in the no-capture set joins it; a code whose former class was captured stays out. This subtask adds no code to it, because all nine classes here were captured or were already shell-content failures.
   - **Keep the handled set.** A former class that did not extend `SkillBillRuntimeException` must not be absorbed by a catch that did not catch it before. Every `catch (e: SkillBillRuntimeException)` and every `is SkillBillRuntimeException` arm without a code guard that a database failure can reach calls `rethrowIfDatabaseFailure()` first. Unguarded sites at `14e681f4b`:
     - `PlanDecompositionStop.kt:89` and `:211` (`:211` wraps `persistDecomposeTerminal` and is mandatory);
     - `FeatureTaskRuntimeRejectedOutputRecorder.kt:209,239`;
     - `InstallStaging.kt:204`, `AuthoringDiscovery.kt:43`, `AuthoringMutation.kt:55`;
     - `InstallCliCommands.kt:213`, `ScaffoldWizardRun.kt:39`, `NativeScaffoldPayloadRun.kt:37,52,111,156,172`;
     - `SkillRemove.kt:141`.

     Check reachability per site. Where no database call can run inside the `try`, leave the site unchanged. The top-level `CliRuntime` arm needs no change.
2. **Database codes.** In `runtime-contracts` `skillbill.error.core`, add `DatabaseFailureCode { ACCESS, BUSY }`. `DatabaseAccessOperation` and the bounding helpers stay.
   - `databaseAccessFailure(dbPath, operation, condition)` keeps today's message, with the bounded condition after `"': "`. `databaseBusy(cause)` keeps message `cause.message`.
   - `databaseAccessCondition(failure: SkillBillRuntimeException): String` returns the bounded condition from the message suffix after the first `"': "`. It sits beside the factory that writes the format; the condition becomes part of the message (parent spec rule).
   - Throw sites: `DatabaseRuntime.kt:122,210-219` and `SQLiteDatabaseSessionFactory.kt:127`. Leave `isSqliteBusy` as subtask 3 left it.
   - Readers:
     - `GoalCliStatusCommands.kt:89` catches `SkillBillRuntimeException`, rethrows unless `code == DatabaseFailureCode.ACCESS`, and passes `databaseAccessCondition(error)` to `goalMonitorStatusText` and `databaseUnavailableGoalStatusCliMap`. The latter's parameter becomes the condition string; payload bytes are unchanged.
     - `InlineReviewPreparation.kt:75` checks `it is SkillBillRuntimeException && it.code == DatabaseFailureCode.BUSY` across the cause chain.
3. **Telemetry HTTP codes.** In `skillbill.error.core`, add `TelemetryHttpFailureCode { INVALID_TRANSPORT_OUTCOME, PROXY_REQUEST_FAILED, PROXY_INVALID_RESPONSE, RELAY_URL_UNCONFIGURED }`. It stays in the kernel because several modules throw and read it (SKILL-391 placement).
   - `TelemetryProxyRequestFailureError.statusCode` is read at `HttpTelemetryClient.kt:69-71`. `fetchProxyCapabilities` reads the status as a value instead:
     - split `requestJson` into execute-then-check;
     - when `response.statusCode` is 404 or 405, return the typed default with the identical warning before `ensureSuccessfulResponse` runs;
     - delete the `catch`.
   - `HttpInstallerScriptFetchAdapter.kt:57` catches `SkillBillRuntimeException` and rethrows unless `code == PROXY_REQUEST_FAILED`.
   - `UnresolvedRemoteTransportPortError` (`RuntimeComponent.kt:129`) is a composition defect, so it becomes `error(<same text>)`. If `AbsentOptionalPortResolutionTest` shows it is an expected runtime state, it becomes `TelemetryHttpFailureCode.REMOTE_TRANSPORT_UNRESOLVED` instead.
4. **Goal telemetry row.** `InvalidGoalTelemetryRowError` (runtime-infra/sqlite, `skillbill.infrastructure.sqlite.review.core`) extends `ShellContentContractException`. Guarded edge sites in other modules may receive it, so its code goes in runtime-contracts as `GoalTelemetryRowFailureCode.MALFORMED` in `skillbill.error.core` and joins `isShellContentContractFailure()`. `goalRowError` throws it, with the same text.
5. **Persistence boundary, one code (parent AC for `RuntimeOwnedFactUnavailable`).**
   - In `runtime-application/.../runtimepersistence/RuntimeOwnedPersistenceBoundary.kt`, declare `RuntimeOwnedPersistenceFailureCode { FACT_UNAVAILABLE }` and factory `runtimeOwnedFactUnavailable(seam, expected, cause: Exception)`. The message stays `"Runtime-owned persistence fact '$expected' could not be established at $seam: ${causeOf(cause)}"` and `cause` is preserved. Keep the `initCause` behaviour recorded in `runtime-application/agent/history.md`.
   - The engine boundary (`engine/featuretask/persist/RuntimeOwnedPersistenceBoundary.kt`) imports both; engine already depends on runtime-application. Delete both classes.
   - In both `invokeOrHandle` functions, replace `runCatching` with `try`/`catch (error: Exception)`. Keep the cooperative rethrow and its ordering, and rethrow when `error is SkillBillRuntimeException && error.code == FACT_UNAVAILABLE`.
   - Readers:
     - `FeatureTaskRuntimeRunLoopReviewCompletion.kt:59` catches `SkillBillRuntimeException` and rethrows other codes;
     - `CodeReviewStep.kt:402` uses a code arm placed before the generic `is Exception` arm.
6. **Tests.** `assertFailsWith<Former>` becomes `assertFailsWith<SkillBillRuntimeException>` plus `assertEquals(<Entry>, error.code)`. Message, payload and exit-code assertions stay byte-for-byte. Tests that construct a former class switch to its factory.
   - `DatabaseAccessErrorTest`: `.condition` becomes `databaseAccessCondition(...)`. Replace the test asserting the database error is not a `SkillBillRuntimeException` with an assertion on `DatabaseFailureCode.ACCESS`; the new planning-stop test carries its invariant.
   - `InlineReviewPreparationDispositionTest` builds through `databaseBusy(...)`.
   - A pinned class name that `failureCodeLabel()` now renders becomes the code label.
7. **Baseline, docs, decision.**
   - Remove each deleted class's row from `../../../runtime-kotlin/runtime-core/src/repoTest/kotlin/skillbill/architecture/baselines/custom-throwable-baseline.txt` by hand. Row format is `module:Class`; compare whole rows and edit no other row.
   - Add a newest-first entry to `../../../runtime-kotlin/agent/decisions.md`, "SKILL-398 subtask 5: persistence failures as codes, and the split into SKILL-400". Cover:
     - the handled-set rule and the database helper;
     - the MCP capture-parity predicate;
     - the accepted framing change: former `RuntimeException` classes that reach `CliRuntime` now print through the `SkillBillRuntimeException` arm without the `ClassName: ` prefix or the diagnostics record (known case: `DatabaseAccessError` on the non-monitor `goal status`, where `CliGoalStatusDatabaseFailureTest` still passes);
     - the split itself.

After this subtask's edits, check whether any class in main still extends `SkillBillRuntimeException` or `ShellContentContractException`, or constructs one without a code. If none does, finish the transition as `../../SKILL-400-runtime-error-codes/spec.md` "Transition finish" describes. Otherwise leave it open. SKILL-399 and SKILL-400 classes are expected to remain, so it is expected to stay open.

## Acceptance Criteria

1. `DatabaseAccessErrors.kt`, `TelemetryHttpErrors.kt`, `InvalidGoalTelemetryRowError.kt` and both `RuntimeOwnedFactUnavailable` classes are gone or hold only code enums and factories. No main source declares the nine classes.
2. Each former failure throws `SkillBillRuntimeException` with an entry of `DatabaseFailureCode`, `TelemetryHttpFailureCode`, `GoalTelemetryRowFailureCode` or `RuntimeOwnedPersistenceFailureCode`, or fails through `error()` (`UnresolvedRemoteTransportPortError` only, per task 3).
3. `rethrowIfDatabaseFailure` and the MCP `uncapturedAtMcp` predicate exist as specified. No reachable unguarded `SkillBillRuntimeException` catch absorbs a database failure.
4. Database busy retries, the goal status CLI's database-access payload, telemetry HTTP 404/405 fallback, installer-script fetch failure handling and the runtime-owned fact boundary behave as before; existing tests pass with type-to-code assertion edits only.
5. Every user-visible message is byte-identical. No expected-output, wire-fixture or payload assertion is edited other than replacing an exception-type assertion with a code assertion or a pinned class name with its code label. No typealias is named after a deleted class.
6. `custom-throwable-baseline.txt` lists none of the deleted classes, and `FailureCodeTotalityArchitectureTest` passes.
7. The classes listed in SKILL-400 are unchanged.

## Non-Goals

- Every class the SKILL-400 bundle owns (see its `spec.md`), and the SKILL-399 shell-content areas.
- The classes owned by subtasks 2, 3 and 4.
- The CLI and MCP top-level arms (F-008), beyond adding the `uncapturedAtMcp` predicate with today's condition.
- Test-source throwables.

## Dependency Notes

Depends on: none. Applies to classes wherever SKILL-391 placed them. Coordinates with SKILL-390, SKILL-396, SKILL-397, SKILL-399 and SKILL-400; the second lander keeps both edits.

## Validation Strategy

Goal gates: build, unit tests, detekt and the runtime-core repoTest suite.

## Test obligations

- **Planning stop.** A `databaseBusy(...)` failure thrown from `persistDecomposeTerminal` propagates out of `PlanDecompositionStop.apply` instead of becoming `Blocked`. Bug it catches: the unguarded catch absorbs a retryable database failure into a terminal planning block.
- **Database condition extractor.** In `DatabaseAccessErrorTest`, `databaseAccessCondition(databaseAccessFailure(path, READ, cond))` equals the bounded condition, not the full message. Bug it catches: the goal-status payload `reason` gains the path and prefix, which `CliGoalStatusDatabaseFailureTest` would not notice.
- **MCP capture parity.** In the `McpCaptureDiagnosticsTest` style, a `TelemetryHttpFailureCode.PROXY_REQUEST_FAILED` failure is captured and an `IllegalArgumentException` is not. Bug it catches: the predicate drifts and persisted telemetry rows change.
- **Covered by existing tests, no new test needed:**
  - database-busy retry: `InlineReviewPreparationDispositionTest`;
  - goal-status database payload: `CliGoalStatusDatabaseFailureTest`;
  - HTTP 404/405 fallback: the http client tests.
- Run nothing in implement. Build, unit tests, detekt and the repoTest suite (`FailureCodeTotalityArchitectureTest`, `TypedParseBoundaryArchitectureTest`) belong to the build and validate phases.

## Implementation Details

Planned on 2026-10-02 against the feature branch at `14e681f4b`, with subtasks 1–4 landed. Re-run the census for the nine classes before editing, and apply every rule to the tree as found.

Constraints:

- Messages stay byte-identical, including placeholders, quoting and punctuation.
- No typealias named after a deleted class. No `Result`/`Either`. No new property on `SkillBillRuntimeException`. No family metadata on codes. No new `runCatching`; any `runCatching` this subtask edits becomes a narrow `try`/`catch` or uses the cooperative rethrow.
- `CancellationException`/`InterruptedException` keep propagating exactly as today. Keep `CooperativeFailurePropagation`.
- No `@Suppress`. Respect detekt `ReturnCount` 4, `ThrowsCount` 2, `LongMethod` 70, `CyclomaticComplexMethod` 15 and `MatchingDeclarationName`: a file left holding only one enum plus factories is renamed to the enum's name. Do not grow `ArchitectureScanSupport.kt`.
- `skillbill.error.core` must not import `skillbill.error.shellcontent`.
- Authored Kotlin carries no `//` or non-KDoc block comments.
- Run nothing in implement. Build, unit tests, detekt and the repoTest suite belong to the build and validate phases.

### Census at plan time (`14e681f4b`)

- Declarations: `runtime-contracts/.../error/core/DatabaseAccessErrors.kt` (`DatabaseAccessError`, `DatabaseBusyError`, plus `DatabaseAccessOperation` and the private `boundedCondition`); `runtime-contracts/.../error/core/TelemetryHttpErrors.kt` (five classes); `runtime-infra/sqlite/.../review/core/InvalidGoalTelemetryRowError.kt` (class, `goalRowError` and the `require*` row readers); `RuntimeOwnedFactUnavailable` at the top of both `RuntimeOwnedPersistenceBoundary.kt` files.
- Throw sites:
  - `DatabaseRuntime.kt:122` and the infra helper `databaseAccessError(dbPath: Path, operation, SQLException)` at `:210`. Its callers are `asTypedFailure`, `DatabaseIdentity.kt:42,53`, `ConnectionTransactions.kt:68-69` and `SQLiteDatabaseSessionFactory.kt:152`.
  - `SQLiteDatabaseSessionFactory.kt:129` (busy).
  - `HttpTelemetryStatusCodes.kt:21`, `HttpRequestUri.kt:12`, `HttpTelemetryClient.kt:173,207,246`.
  - `RuntimeComponent.kt:129`.
  - `goalRowError`.
  - Both boundaries' `fail`.
- Main readers:
  - `GoalCliStatusCommands.kt:89` reads `.condition`; `GoalCliStatusFormatting.kt:186-195` takes the error as a parameter.
  - `InlineReviewPreparation.kt:75`.
  - `HttpTelemetryClient.kt:71` reads `.statusCode`; `HttpInstallerScriptFetchAdapter.kt:57`.
  - `FeatureTaskRuntimeRunLoopReviewCompletion.kt:59` and `CodeReviewStep.kt:402` (both engine class).
  - Both `invokeOrHandle` functions.
  - `McpToolDispatcher.dispatch:27-30`.
- No main code reads `rowIdentity`, `seam`, `detail` or `dbPath`. Tests read `.operation`, `.dbPath`, `.condition`, `.statusCode` and `.detail`.
- Every remaining `catch (… : ShellContentContractException)` in main is gone (subtask 4), so the goal-row error is handled only through `isShellContentContractFailure()` guards.

### Ordered tasks

1. **Kernel codes and helpers (runtime-contracts, `skillbill.error.core`).** Serves AC-001, AC-002, AC-003.
   - Rename `DatabaseAccessErrors.kt` to `DatabaseFailureCode.kt`. Keep `DatabaseAccessOperation`, `MAX_CONDITION_CHARS`, the two regexes and `boundedCondition` unchanged. Delete both classes and add:
     - `enum class DatabaseFailureCode : RuntimeFailureCode { ACCESS, BUSY }`;
     - `fun databaseAccessFailure(dbPath: String, operation: DatabaseAccessOperation, condition: String): SkillBillRuntimeException`, with message `"Database ${operation.wireValue} failed for '$dbPath': ${boundedCondition(condition)}"`;
     - `fun databaseBusy(cause: Throwable): SkillBillRuntimeException = SkillBillRuntimeException(DatabaseFailureCode.BUSY, cause.message.orEmpty(), cause)`. The old class passed a nullable message, and `goalReviewPreparationFailure` already renders `message.orEmpty()`, so the text is unchanged;
     - `fun databaseAccessCondition(failure: SkillBillRuntimeException): String = failure.message.orEmpty().substringAfter("': ")`;
     - `fun SkillBillRuntimeException.rethrowIfDatabaseFailure()`, which throws `this` when `code is DatabaseFailureCode`, with a one-line KDoc.
   - Rename `TelemetryHttpErrors.kt` to `TelemetryHttpFailureCode.kt` (MatchingDeclarationName). It holds:
     - `enum class TelemetryHttpFailureCode : RuntimeFailureCode { INVALID_TRANSPORT_OUTCOME, PROXY_REQUEST_FAILED, PROXY_INVALID_RESPONSE, RELAY_URL_UNCONFIGURED }`;
     - one factory, `telemetryProxyRequestFailure(statusCode: Int, seam: String, detail: String, cause: Throwable? = null)`, with message `"Telemetry proxy request failed at $seam with HTTP $statusCode: $detail"`. It has two throw sites.
     - The other three messages are inlined at their single throw sites (parent rule).
   - New file `GoalTelemetryRowFailureCode.kt`: `enum class GoalTelemetryRowFailureCode : RuntimeFailureCode { MALFORMED }`.
   - In `skillbill.error.shellcontent.ShellContentContractFailures.kt`, add `|| failureCode is GoalTelemetryRowFailureCode` to `isShellContentContractFailure()`. The dependency direction shellcontent → core is already allowed.
2. **Database throw sites (runtime-infra/sqlite).** Serves AC-002, AC-004, AC-005.
   - `DatabaseRuntime.kt`:
     - `:122` throws `databaseAccessFailure(path, READ, "database schema is missing")`;
     - the infra helper `databaseAccessError` keeps its name and parameters, returns `SkillBillRuntimeException` and delegates to `databaseAccessFailure` with the same condition text.
   - `SQLiteDatabaseSessionFactory.kt:129`: `throw databaseBusy(error)`. Leave `translatingBusyFailures`' `runCatching` as it is: it already does the cooperative rethrow, so the bundle constraint holds. Leave `isSqliteBusy` unchanged.
   - Update imports in `DatabaseIdentity`, `ConnectionTransactions` and `DatabaseMigrations` only where a deleted name was imported.
3. **Database readers.** Serves AC-002, AC-003, AC-004.
   - `GoalCliStatusCommands.kt:89`: `catch (error: SkillBillRuntimeException)`, then `error.rethrowUnless(error.code == DatabaseFailureCode.ACCESS)`. Keep `if (!options.monitorOnly) throw error` next. Pass `val reason = databaseAccessCondition(error)` to `goalMonitorStatusText(…, databaseUnavailableReason = reason)` and to `databaseUnavailableGoalStatusCliMap(issueKey, reason)`.
   - `GoalCliStatusFormatting.kt:186`: the parameter becomes `reason: String` and the body uses `singleLineBounded(reason)`. Payload keys and order stay the same.
   - `InlineReviewPreparation.kt:75`: `any { it is SkillBillRuntimeException && it.code == DatabaseFailureCode.BUSY }`.
4. **Handled-set guards (`rethrowIfDatabaseFailure()` as the first statement of the catch body).** Serves AC-003. Per-site reachability decisions from the plan-time census:
   - **Guard:**
     - `PlanDecompositionStop.kt:211` (mandatory: `persistDecomposeTerminal` writes the database).
     - `PlanDecompositionStop.kt:89`, unless implement confirms `verifyAuthoredBundle` → `preparationRuntime.prepareForFeatureSpec` can't reach a `DatabaseSessionFactory`. Guard by default.
     - `InstallCliCommands.kt:213`: `installSelectionPersistencePort.readLatestSuccessfulSelection` is a persisted read.
     - `NativeScaffoldPayloadRun.kt:111` (`scaffoldGateway` with `registerExternalSources = true`) and `:156` (`completeAuthoring`'s arbitrary block), unless tracing shows no database call.
   - **Leave unchanged:**
     - `FeatureTaskRuntimeRejectedOutputRecorder.kt:209,239`: already code-guarded, because `degradableFailureClass()` returns null for non-`RejectedOutputDiagnosticFailureCode` codes and the site rethrows.
     - `InstallStaging.kt:204`, `AuthoringDiscovery.kt:43`, `AuthoringMutation.kt:55`: filesystem only; they rethrow after logging or rollback.
     - `ScaffoldWizardRun.kt:39`, `NativeScaffoldPayloadRun.kt:37,52,172`: payload read, JSON encode and render; no database call.
     - `SkillRemove.kt:141`: filesystem removal port.
     - `CliRuntime`.
   - If tracing shows a "leave" site does reach the database, guard it. Record each final decision in the implement summary.
5. **Telemetry HTTP (runtime-infra/http, runtime-core).** Serves AC-002, AC-004, AC-005.
   - `HttpTelemetryStatusCodes.kt:21`: `throw SkillBillRuntimeException(TelemetryHttpFailureCode.INVALID_TRANSPORT_OUTCOME, "Telemetry transport returned $statusCode, which is not a valid HTTP status code.")`.
   - `HttpRequestUri.kt:12` and `ensureSuccessfulResponse` throw `telemetryProxyRequestFailure(...)` with the same arguments.
   - `invalidJsonResponse` inlines `"Telemetry proxy response invalid at $errorContext: $detail"` with `PROXY_INVALID_RESPONSE`.
   - `requireConfiguredRelayUrl` inlines `"Telemetry relay URL is not configured."` with `RELAY_URL_UNCONFIGURED`.
   - `fetchProxyCapabilities`:
     - split `requestJson` into `executeJsonRequest(request, requester): RemoteTransportResponse` and `successfulJsonBody(response, errorContext): Map<String, Any?>` (`ensureSuccessfulResponse` + `decodeJsonObject`). `requestJson` stays as their composition for `fetchRemoteStats`.
     - In `fetchProxyCapabilities`, when `response.statusCode` is `HTTP_NOT_FOUND` or `HTTP_METHOD_NOT_ALLOWED`, emit the identical warning string with `response.statusCode` and return `defaultProxyCapabilities(...)`. Otherwise decode, map and validate as today.
     - Delete the `try`/`catch`. This is equivalent: the only other producer of the failure, `httpRequestUri`, uses status 0. Two returns keep ReturnCount within its limit.
   - `HttpInstallerScriptFetchAdapter.kt:57`: `catch (error: SkillBillRuntimeException) { error.rethrowUnless(error.code == TelemetryHttpFailureCode.PROXY_REQUEST_FAILED); result = Failed(errorMessage(error)) }`. The `finally` teardown is unchanged.
   - `RuntimeComponent.kt:129`: `ctx.requester ?: error("RemoteTransportPort is unresolved; provide it from the composition root after bootstrap resolution.")`. `AbsentOptionalPortResolutionTest` reaches it only by calling the component directly with an unresolved context and bypassing bootstrap, which is a composition defect. So `error()` applies and no `REMOTE_TRANSPORT_UNRESOLVED` entry is added.
6. **Goal telemetry row (runtime-infra/sqlite).** Serves AC-001, AC-002.
   - Delete the class.
   - `goalRowError` throws `SkillBillRuntimeException(GoalTelemetryRowFailureCode.MALFORMED, "Goal telemetry row $identity is malformed: $reason")`.
   - Rename the file to `GoalTelemetryRowColumns.kt`, since it now holds only the internal `require*` readers. Check that the name is free in the package first.
7. **Runtime-owned persistence boundary.** Serves AC-001, AC-002, AC-004.
   - **Deviation: two entries, not one.** `CodeReviewStep.launch` runs `reviewPass.review(...)`, which runs the application `ParallelCodeReviewRunner`. That runner throws the application `RuntimeOwnedFactUnavailable` from `requiredRead`/`requiredWrite` in `ParallelCodeReviewRunnerResultAssembly`, `ParallelCodeReviewRunnerLanePlanRecording` and `ParallelCodeReviewRunnerVerificationStages`. Today `launchFailure` matches only the engine class, so the application failure takes the generic arm: `"Runtime-owned review failed: <label>: …"` with `RETRYABLE`. With one shared code it would move to the `PROCESS_FAILURE` arm, which breaks AC-004 and AC-005. Main code therefore discriminates the two classes, and the parent rule ("one entry when main code discriminates it") gives each its own entry.
   - In `runtime-application/.../runtimepersistence/RuntimeOwnedPersistenceBoundary.kt`:
     - declare `enum class RuntimeOwnedPersistenceFailureCode : RuntimeFailureCode { FACT_UNAVAILABLE, REVIEW_FACT_UNAVAILABLE }`. `FACT_UNAVAILABLE` is thrown by the engine feature-task boundary; `REVIEW_FACT_UNAVAILABLE` by the application boundary, whose only callers are the review runner;
     - add the factory `fun runtimeOwnedFactUnavailable(code: RuntimeOwnedPersistenceFailureCode, seam: String, expected: String, cause: Exception): SkillBillRuntimeException`, with message `"Runtime-owned persistence fact '$expected' could not be established at $seam: ${causeOf(cause)}"` and `cause` passed through. Pass `cause` through the constructor, as the `initCause` note in `runtime-application/agent/history.md` requires. Move `causeOf` beside it as a private file-level function, and keep the boundary's `recordFailure` using it.
     - Delete the class.
   - Application `invokeOrHandle`: keep `runCatching`, which already does the cooperative rethrow and so meets the bundle constraint. Keep the order: cooperative rethrow first, then `return onFailure(error)` when `error is Exception && !error.isOwnedFactUnavailable()`, else `throw error`. A private `fun Throwable.isOwnedFactUnavailable(): Boolean = (this as? SkillBillRuntimeException)?.code == RuntimeOwnedPersistenceFailureCode.REVIEW_FACT_UNAVAILABLE` keeps ComplexCondition within its limit. `fail` throws the factory with `REVIEW_FACT_UNAVAILABLE`.
   - Engine `persist/RuntimeOwnedPersistenceBoundary.kt`: delete the class and import the code and factory from application. Keep `runCatching` and its explicit `CancellationException` exclusion, which lets `InterruptedException` reach `onFailure`. Replace `error !is RuntimeOwnedFactUnavailable` with a private predicate on `FACT_UNAVAILABLE`. `fail` throws the factory with `FACT_UNAVAILABLE`. The engine keeps its own private `causeOf` for `recordFailure`.
   - **Deviation from task 5's "replace `runCatching` with `try`/`catch (error: Exception)`":** detekt's default `TooGenericExceptionCaught` is active (`buildUponDefaultConfig = true`), `@Suppress` is banned, and no engine or application main site catches `Exception`. The only main catches of `Exception` use `_`. Keeping `runCatching` with the existing cooperative handling meets the bundle constraint and leaves cancellation and interruption propagation exactly as today.
   - Readers, both against `FACT_UNAVAILABLE` only:
     - `FeatureTaskRuntimeRunLoopReviewCompletion.kt:59`: `catch (error: SkillBillRuntimeException) { error.rethrowUnless(error.code == RuntimeOwnedPersistenceFailureCode.FACT_UNAVAILABLE); return …blockInPhase(…) }`, with the same reason text. A database failure is not `FACT_UNAVAILABLE`, so it is rethrown and the handled set is preserved.
     - `CodeReviewStep.kt:402`: the arm becomes `error is SkillBillRuntimeException && error.code == RuntimeOwnedPersistenceFailureCode.FACT_UNAVAILABLE ->`. Convert the `when (error)` subject form to a subjectless `when` only if the guard needs it. Keep it before `is Exception`, and keep the arm order and texts.
8. **MCP capture parity (runtime-mcp `McpToolDispatcher`).** Serves AC-003.
   - Add `private fun Throwable.uncapturedAtMcp(): Boolean = isShellContentContractFailure() || this is InvalidLearningSourceError || this is IllegalArgumentException || this is IllegalStateException`.
   - The arm becomes `error.uncapturedAtMcp() -> mcpToolErrorResult(toolName, error)`.
   - No code is added to the predicate. The goal-row code stays uncaptured through `isShellContentContractFailure()`, as `InvalidGoalTelemetryRowError` was. Every other former class here was captured and stays captured.
9. **Tests (type-to-code edits, plus the three obligations).** Serves AC-004, AC-005.
   - **Type-to-code:** each `assertFailsWith<Former>` becomes `assertFailsWith<SkillBillRuntimeException>` plus `assertEquals(<Entry>, error.code)`. Files:
     - `SqliteDegradationDiagnosticsTest`, `SQLiteDatabaseSessionFactoryBusyTranslationTest`, `SQLiteDatabaseSessionFactoryTypedFailureTest`, `DatabaseWriteReadinessTest`, `DatabaseAccessFailureTest`, `SQLiteDatabaseSessionFactoryTest`;
     - `GoalTelemetryStoreTest:235`;
     - `HttpTelemetryTypedErrorsTest`;
     - `ApplicationCooperativeFailureBoundaryTest:183,206` (expect `REVIEW_FACT_UNAVAILABLE`).
     - `thrown is DatabaseAccessError` becomes `(thrown as? SkillBillRuntimeException)?.code == DatabaseFailureCode.ACCESS` with the same failure text.
   - **Removed properties:** an assertion on a property that no longer exists asserts the same fact from the message that property fed:
     - `.operation` / `.dbPath`: `assertTrue(message.startsWith("Database <wire> failed for '<path>': "))`;
     - `.condition`: `databaseAccessCondition(error)`;
     - `.statusCode` / `.detail`: the exact message, or the `"with HTTP <n>: "` / `": <detail>"` substring.

     Record this in the decisions entry as the one class of edit beyond type-to-code. No expected CLI, MCP, wire or payload value changes.
   - **`DatabaseAccessErrorTest`:** construct through `databaseAccessFailure`, and replace `.condition` with `databaseAccessCondition(...)`. Replace `the typed error is not absorbed by supertype catches…` with an assertion that `code == DatabaseFailureCode.ACCESS`. Add obligation 2: `databaseAccessCondition(databaseAccessFailure(path, READ, cond)) == cond` (bounded), not the full message.
   - **`InlineReviewPreparationDispositionTest`:** build through `databaseBusy(IllegalStateException(BUSY_MESSAGE))`; assertions unchanged.
   - **`AbsentOptionalPortResolutionTest`:** `assertFailsWith<IllegalStateException>` with the same unresolved-port message; rename the test to say "fails as a composition defect".
   - **Obligation 1, new test in runtime-engine:** a `FeatureTaskRuntimePlanningStopper` whose transition owner's `persistDecomposeTerminal` throws `databaseBusy(...)`. Assert that `resolve(...)` propagates that exact failure (`assertSame`) instead of returning `Blocked`. Reuse the existing planning-stop test support. If none exists, use the smallest fake `FeatureTaskRuntimeRunTransitionOwner`, with `relaxUnitFun = true` if MockK is used, never `relaxed = true`.
   - **Obligation 3, new test in `McpCaptureDiagnosticsTest`:** a `telemetryProxyRequestFailure(500, …)` failure is captured as `TelemetryHttpFailureCode.PROXY_REQUEST_FAILED`, and an `IllegalArgumentException` returns its error without a telemetry row. Use the existing `failingRequester` and `capturedErrorTypes` helpers in a new method; leave the existing methods unedited.
   - **Expected label changes:**
     - `CodeReviewStep`'s generic arm and `RuntimeExceptionTelemetry.error_type` now render `TelemetryHttpFailureCode.*`, `DatabaseFailureCode.*` or `RuntimeOwnedPersistenceFailureCode.REVIEW_FACT_UNAVAILABLE` where the class simple name used to appear. That is the allowed "pinned class name → code label" change. Plan-time grep found no test pinning those names.
     - Re-grep for them in tests and docs before finishing.
10. **Baseline, docs, decision.** Serves AC-006, AC-007.
    - Delete exactly these rows from `custom-throwable-baseline.txt`, matching whole lines:
      - `runtime-application:RuntimeOwnedFactUnavailable`
      - `runtime-contracts:DatabaseAccessError`
      - `runtime-contracts:DatabaseBusyError`
      - `runtime-contracts:InvalidTelemetryTransportOutcomeError`
      - `runtime-contracts:TelemetryProxyInvalidResponseError`
      - `runtime-contracts:TelemetryProxyRequestFailureError`
      - `runtime-contracts:TelemetryRelayUrlUnconfiguredError`
      - `runtime-contracts:UnresolvedRemoteTransportPortError`
      - `runtime-engine:RuntimeOwnedFactUnavailable`
      - `runtime-infra:sqlite:InvalidGoalTelemetryRowError`
    - `runtime-kotlin/ARCHITECTURE.md:251`: `DatabaseAccessError(READ)` becomes "a `DatabaseFailureCode.ACCESS` failure for `READ`".
    - Prepend the decisions entry `## [2026-10-02] SKILL-398 subtask 5: persistence failures as codes, and the split into SKILL-400`, with Context / Decision / Reason / Alternatives considered. It covers:
      - the handled-set rule and `rethrowIfDatabaseFailure`, with the per-site reachability outcomes;
      - the `uncapturedAtMcp` parity predicate;
      - the accepted framing change: former `RuntimeException` database failures that reach `CliRuntime` print through the `SkillBillRuntimeException` arm, without the `ClassName: ` prefix or the diagnostics record (known case: non-monitor `goal status`);
      - the factory frame in `goalReviewPreparationFailure`'s location suffix: the first `skillbill.` stack frame of a factory-built failure is now the factory;
      - the two persistence entries and why;
      - kept `runCatching` versus `TooGenericExceptionCaught`;
      - the property-to-message test edits;
      - the split into SKILL-400.
    - Leave SKILL-399 and SKILL-400 classes untouched (AC-007). Before finishing, re-grep that no file named in the SKILL-400 bundle changed.
11. **Transition check.** Main still declares many `SkillBillRuntimeException` and `ShellContentContractException` subclasses (SKILL-399 and SKILL-400 areas), so the transition stays open: no change to `RuntimeExceptionBases.kt`. State this in the implement summary.

### Test obligations (final)

- Planning-stop propagation of `databaseBusy` from `persistDecomposeTerminal` (AC-003). Realistic bug: the unguarded catch absorbs a retryable database failure into a terminal planning block.
- `databaseAccessCondition` returns the bounded condition, not the full message (AC-004). Realistic bug: the goal-status `reason` gains the path and prefix.
- MCP captures `PROXY_REQUEST_FAILED` and not `IllegalArgumentException` (AC-003). Realistic bug: the predicate drifts and changes persisted telemetry rows.
- No other new tests. Busy retry, the goal-status database payload and the HTTP 404/405 fallback are covered by the existing `InlineReviewPreparationDispositionTest`, `CliGoalStatusDatabaseFailureTest` and http client tests.

## Next Path

skill-bill goal SKILL-398

## Spec Path

.feature-specs/SKILL-398-runtime-exception-reduction/spec_subtask_5_collapse-remaining-errors.md
