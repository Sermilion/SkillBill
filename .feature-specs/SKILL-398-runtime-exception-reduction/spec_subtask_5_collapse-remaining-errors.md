# SKILL-398 Subtask 5 - collapse-remaining-errors

Parent spec: [.feature-specs/SKILL-398-runtime-exception-reduction/spec.md](./spec.md)
Issue key: SKILL-398

## Scope

(F-005) Replace every other custom `Throwable` declared in production main with `SkillBillRuntimeException` and an owner code, or with `require`/`check`/`error()` for code defects, following the parent spec's "Target failure model" and the same rules as subtask 4 (code enum per owner area, existing `FailureWireCode` reused, message functions, no reading of typed exception properties, catch sites check `code`, tests assert `code`).

Anchors at the census tree (76 declarations):

- `runtime-contracts/.../error/core/`: `ExternalAddonErrors.kt`, `ExternalPlatformPackErrors.kt`, `DurableExternalDecodeErrors.kt`, `MalformedJsonTextError.kt` (incl. `JsonWrongRootTypeError`, `UnsupportedJsonValueError`), `DatabaseAccessErrors.kt` (`DatabaseAccessError`, `DatabaseBusyError`), `TelemetryHttpErrors.kt`, `InvalidFeatureSpecPreparationRequestError.kt`, `UnresolvedEnvironmentContextFieldError.kt`, `FailureWireCodeContract.kt` (`UnrecognizedFailureWireCodeError`).
- `runtime-contracts/.../error/featuretask/`: `PhaseSlotContractErrors.kt` (17 classes), `FeatureTaskRuntimeExecutionPlanAdmissionError.kt` (base plus 4; `reasonCode` becomes the code), `FeatureTaskRuntimeExecutionPlanConflictError.kt`, `FeatureTaskRuntimeSharedEvidenceFingerprintContradictionError.kt`, `InvalidFeatureTaskRuntimeExecutionPlanSchemaError.kt`, `InvalidPhaseStrategyCompositionError.kt` (extends `IllegalArgumentException`), `UnsafeFeatureTaskRuntimeRegenerationError.kt` (extends `IllegalStateException`; its `FeatureTaskRuntimeRegenerationRefusal` enum becomes the code).
- `runtime-contracts/.../error/goalrunner/`, `.../error/learning/` (`InvalidLearningSourceError`; update the `McpToolDispatcher.kt` arm that names it to the code, output unchanged).
- runtime-engine: `RuntimeOwnedFactUnavailable` (`featuretask/persist/RuntimeOwnedPersistenceBoundary.kt`).
- runtime-application: `RuntimeOwnedFactUnavailable` (`runtimepersistence/RuntimeOwnedPersistenceBoundary.kt`) — one code serves both copies; keep the `initCause` behaviour recorded in `runtime-application/agent/history.md`.
- runtime-domain: `ReviewAttributionResolutionError` and `MalformedVocabulary` (`review/model/ReviewAttributionModels.kt`), `SkillBillRollbackException` (`skillremove/`).
- runtime-infra: `GateJvmResolutionErrors.kt` (6, host/jvm), `CursorReviewStreamErrors.kt` (base plus 5, launcher/review; delete the never-constructed `CursorReviewStreamEmptyError`; the base extends `Exception` and becomes a code), `InstallSymlinkException` (skills/install/apply), `ReleaseLicensePolicyError` (skills/scaffold/runtime/validation), `InvalidGoalTelemetryRowError` (sqlite/review/core), `ValidationGateProcessException` (workflow/validation).
- runtime-mcp: `InvalidMcpToolArgumentError` (`skillbill/mcp/shared/`): an MCP-owned code enum in runtime-mcp, per SKILL-391.
- Any other custom `Throwable` in production main not named in subtasks 2, 3 or 4, including ones added by concurrent bundles (for example SKILL-396's typed infra errors) if present.

Specific rules:

- `PhaseSlotContractErrors.kt`, `InvalidPhaseStrategyCompositionError` and other registry or composition wiring failures are defects when the condition can only arise from how the runtime is composed; they become `require`/`check`/`error()`. Selection failures that reach users (`UnknownPhaseReviewTargetError`, `UnknownQualityGateSelectionError`, `PhaseIntakeRequiredError`, `PullRequestBranchRefusedError`) stay tier 3 codes, or become returned values where the CLI already maps them to a usage error (`PhaseCommand.kt:93`, `FeatureTaskRuntimeRunRequestAssembly.kt:92` reads `allowedValues`).
- `DatabaseBusyError` and `DatabaseAccessError` keep their retry and CLI classification behaviour (`InlineReviewPreparation.kt:75` cause-chain check, `GoalCliStatusCommands.kt:94` reads `condition`): the readers check the code; `condition` becomes part of the code (one entry per condition) or of the message.
- `TelemetryProxyRequestFailureError.statusCode` (read at `HttpTelemetryClient.kt:69`): the HTTP client returns the status as a value where it branches on it.
- Execution-plan admission and regeneration refusal end the run: keep throwing, as codes; `FeatureTaskRuntimeExecutionAdmission.kt` and `FeatureTaskContinuationLookupService.kt` read `code` instead of `reasonCode` / `refusal`.

When no class in main extends `SkillBillRuntimeException` or `ShellContentContractException` afterwards, finish the transition as the parent spec describes. If the custom-throwable baseline exists, remove the rows of every deleted class; after this subtask and subtasks 2-4 have landed, it lists only `SkillBillRuntimeException` unless a decisions.md entry records a reason for another class.

## Acceptance Criteria

1. No production main source declares a custom `Throwable` other than `SkillBillRuntimeException`, the classes owned by subtasks 2, 3 and 4 that are still present, and classes whose retention reason is recorded in `runtime-kotlin/agent/decisions.md`.
2. Each former failure throws `SkillBillRuntimeException` with a code from an enum implementing `RuntimeFailureCode`, declared by the module and package that owned the class, or fails through `require`/`check`/`error()` where only a code defect can trigger it. runtime-contracts declares no MCP-, infra- or engine-only code.
3. `RuntimeOwnedFactUnavailable` is gone from both runtime-application and runtime-engine, and both boundaries use one code.
4. Database busy retries, the goal status CLI's database-access payload, telemetry HTTP status handling, execution-plan admission warnings and MCP learning-source handling behave as before; existing tests pass with type-to-code assertion edits only.
5. Every user-visible message is byte-identical; no expected-output, wire-fixture or payload assertion is edited other than replacing an exception-type assertion with a code assertion. No typealias is named after a deleted class.
6. If the custom-throwable baseline exists, it lists none of the deleted classes and `FailureCodeTotalityArchitectureTest` passes.

## Non-Goals

- Classes owned by subtasks 2, 3 and 4.
- The CLI and MCP top-level arms (F-008), beyond renaming the `InvalidLearningSourceError` arm to its code.
- Test-source throwables.

## Dependency Notes

Depends on: none.
If subtask 1 has not landed, add the target-type pieces as the parent spec defines them. Applies to classes wherever SKILL-391 placed them. If subtask 4 finished first, this subtask may be the one that finishes the transition. Coordinates with SKILL-390, SKILL-396 and SKILL-397; the second lander keeps both edits and converts any custom throwable they added.

## Validation Strategy

Goal gates: build, unit tests, detekt and the runtime-core repoTest suite. Test obligations: one test for the database-busy retry and one for the goal status database-access payload, each driven by the code, if no existing test covers them after the conversion.

## Implementation Details

Planned on 2026-10-02 against `base/SKILL-380-phase-slot-strategies` at `432d427c8`. Subtasks 1–4 had not landed then. This subtask runs fifth on the feature branch, so expect `RuntimeFailureCode`, `LegacyFailureCode`, the coded `SkillBillRuntimeException`, the custom-throwable baseline, the shell-content code enums and subtasks 2–3's results to be present. Re-run the census in task 0 before editing, and apply every rule to the tree as found.

### Constraints that apply to every task

- Messages stay byte-identical, including `<root>`/`<unknown>` placeholders, quoting and punctuation. A message format thrown from two or more sites moves to one factory function beside its code enum; a single-site message is inlined.
- No typealias named after a deleted class. No `Result`/`Either`. No new property on `SkillBillRuntimeException`. No family metadata on codes; catch sites check `error.code is <OwnerEnum>` or `== <Entry>`. No new `runCatching`. Any `runCatching` this subtask edits becomes a narrow `try`/`catch` or uses the cooperative rethrow.
- `CancellationException`/`InterruptedException` keep propagating exactly as today. Keep `CooperativeFailurePropagation`.
- No `@Suppress`. Respect detekt `ReturnCount` 4, `ThrowsCount` 2, `LongMethod` 70, `CyclomaticComplexMethod` 15 and `MatchingDeclarationName`: a file left holding only one enum plus factories is renamed to the enum's name. Do not grow `ArchitectureScanSupport.kt`.
- **Where a code enum lives.** Put it in the module and package that declared the replaced class, except in two cases:
  - If only one non-contracts module throws and discriminates it, it moves to that module's package (AC-002: no MCP-, infra- or engine-only code in runtime-contracts).
  - If another module must discriminate it, it lives in runtime-contracts `skillbill.error.core` (or `.featuretask`). That covers an edge narrowing a catch or the MCP capture predicate, and then the code is not module-only.
- **Defects.** A class becomes `require`/`check`/`error()` only when nothing but runtime composition or a code bug can trigger it, and no existing CLI/MCP test drives it through an edge. When unsure, keep a code.
- **Tests.** `assertFailsWith<Former>` becomes `assertFailsWith<SkillBillRuntimeException>` plus `assertEquals(<Entry>, error.code)`. For classes that became defects it becomes `assertFailsWith<IllegalArgumentException>`/`<IllegalStateException>`. Message assertions are untouched.
  - Test constructions of a deleted class switch to its factory.
  - The only other test edits allowed are the ones named in tasks 3, 4, 7 and 9. Do not change test-source-declared throwables.

### Tasks

**0. Census and preconditions (AC-001, AC-006).**
- List every `class`/`object` in production main whose supertype chain reaches `Throwable`. Match multi-line headers, nested variants and qualified supertypes; the baseline file is the cross-check.
- Subtract `SkillBillRuntimeException` and any class subtasks 2–4 left behind. Those classes are left alone; if one still exists, the transition in task 10 must not finish.
- At planning time, besides the spec anchors, the census found:
  - `ExternalAddonErrors.kt`, `ExternalPlatformPackErrors.kt`, `DurableExternalDecodeErrors.kt` (6), `MalformedJsonTextError.kt` (3), `DatabaseAccessErrors.kt` (2), `TelemetryHttpErrors.kt` (5), `InvalidFeatureSpecPreparationRequestError`, `UnresolvedEnvironmentContextFieldError`, `UnrecognizedFailureWireCodeError`;
  - `PhaseSlotContractErrors.kt` (17), the execution-plan admission family (base + 4), `FeatureTaskRuntimeExecutionPlanConflictError`, `FeatureTaskRuntimeSharedEvidenceFingerprintContradictionError`, `InvalidFeatureTaskRuntimeExecutionPlanSchemaError`, `InvalidPhaseStrategyCompositionError` (IAE), `UnsafeFeatureTaskRuntimeRegenerationError` (ISE), `InvalidLearningSourceError`;
  - both `RuntimeOwnedFactUnavailable`, `ReviewAttributionResolutionError`/`MalformedVocabulary` (IAE), `SkillBillRollbackException`;
  - `GateJvm*` (6), `CursorReviewStream*` (base + 5), `InstallSymlinkException`, `ReleaseLicensePolicyError` (IAE), `InvalidGoalTelemetryRowError`, `ValidationGateProcessException`;
  - `InvalidMcpToolArgumentError`.
- Also list every codeless `SkillBillRuntimeException(message[, cause])` construction, meaning the `LegacyFailureCode` secondary constructor. At planning time there were 12 in `runtime-infra/skills/.../scaffold/{authoring,rendering}/`, and subtasks 2–4 may have added more.
- **Edge census.** List every site that catches or `is`-checks `ShellContentContractException`, `SkillBillRuntimeException` or `LegacyFailureCode`. At planning time:
  - about 60 `ShellContentContractException` sites in cli, mcp, engine, application and infra (sqlite, skills, launcher, contracts, http);
  - `SkillBillRuntimeException` catches in `InstallCliCommands`, `ScaffoldWizardRun`, `NativeScaffoldPayloadRun`, `PlanDecompositionStop:89,211`, `SkillRemove:127`, `InstallStaging:204`, `AuthoringDiscovery:43`, `AuthoringMutation:54`.
- List every `error::class.simpleName`/`javaClass.name` sink that formats user-visible or persisted text:
  - `CliRuntime.unexpectedErrorResult`, `CodeReviewStep.launchFailure`, `RuntimeExceptionTelemetry`, `ExternalPlatformPackTelemetryPolicy`;
  - the `errorType=` diagnostic lines and `ValidationGateResolver`.

**1. Contracts kernel codes (AC-002, AC-004, AC-005).** In `runtime-contracts/.../skillbill/error/core/`:
- `JsonFailureCode { MALFORMED_TEXT, WRONG_ROOT_TYPE, UNSUPPORTED_VALUE }` and factories (`malformedJsonText(cause)`, `jsonWrongRootType(expectedRoot)`). Catch sites switch to code checks:
  - `WorkflowRecordMapping.kt:116` (ports), `ReviewRunLaneSegmentAccountingJson.kt:32-34` (domain), `WorkflowServiceFeatureTaskAbandon.kt:91`, `JsonCodec` throw sites.
  - Where a handler maps the failure to null or empty, prefer a returning decode only if it is a local change.
- `UnrecognizedFailureWireCodeError` becomes an entry in a `FailureWireCodeContract`-local enum (for example `FailureWireDecodeCode.UNRECOGNIZED`) thrown by `failureWireByValue`.
- `DatabaseFailureCode { ACCESS, BUSY }`. `DatabaseAccessOperation` and the bounding helpers stay.
  - Factories: `databaseAccessFailure(dbPath, operation, condition)` keeps today's message, with the bounded condition after `"': "`. `databaseBusy(cause)` uses message `cause.message`.
  - `databaseAccessCondition(failure: SkillBillRuntimeException): String` recovers the bounded condition from the message suffix after the first `"': "`. The extractor sits beside the factory that writes the format, which follows the spec rule that the condition becomes part of the message.
  - Add `fun SkillBillRuntimeException.rethrowIfDatabaseFailure()` (throws when `code is DatabaseFailureCode`) for the catch sites in task 9.
- `TelemetryHttpFailureCode` (kept in the kernel: SKILL-391 placed it there, and the MCP capture predicate in task 8 reads it). Entries: `INVALID_TRANSPORT_OUTCOME`, `PROXY_REQUEST_FAILED`, `PROXY_INVALID_RESPONSE`, `RELAY_URL_UNCONFIGURED`, and `REMOTE_TRANSPORT_UNRESOLVED` unless task 7 classifies it as a defect.
- `ExternalPlatformPackFailureCode { CONFIG, AMBIGUOUS, OVERLAY, PUBLISH }` and `ExternalAddonFailureCode { CONFIG, OVERLAY }`. The CLI config and install catch sites discriminate these, so they stay in the kernel.
- `FeatureSpecPreparationFailureCode.INVALID_REQUEST` (domain and engine throw it) with factory `invalidFeatureSpecPreparationRequest(fieldPath, reason, cause)`.
- `AgentAddonAgentIdFailureCode.INVALID` (domain `SupportedAgent` throws it and infra-skills catches it in `ScaffoldServicePlanningPayloadMerge.kt:128`).
- `UnresolvedEnvironmentContextFieldError` (thrown only in `sqlite/core/schema/DatabasePaths.kt`): a composition defect. Use `error("EnvironmentContext.$fieldName is unresolved; …")` with the same text, unless a test pins its CLI output. In that case it becomes an infra-sqlite code.
- In `skillbill.error.learning`, `InvalidLearningSourceReason` implements `RuntimeFailureCode` and is the code. Add factory `invalidLearningSource(reason, reviewRunId, findingId)` with today's `when` text. Delete the class.

**2. Featuretask codes (AC-002, AC-004).**
- `FeatureTaskRuntimeExecutionPlanAdmissionCode(val wireValue)` with `MISSING_DESCRIPTOR("missing_descriptor")`, `CORRUPT_DESCRIPTOR`, `UNSUPPORTED_DESCRIPTOR`, `INCOMPATIBLE_DESCRIPTOR`, plus factory `executionPlanRefused(code)` (message `"Durable execution plan refused: ${code.wireValue}. …"` unchanged). It goes in `skillbill.error.featuretask`, since engine, infra (contracts, sqlite, workflow) and application all throw these.
- `FeatureTaskRuntimeRegenerationRefusal` implements `RuntimeFailureCode`; factory `regenerationRefused(refusal)`.
- `FeatureTaskRuntimeExecutionFailureCode { INVALID_EXECUTION_PLAN_SCHEMA, EXECUTION_PLAN_CONFLICT, SHARED_EVIDENCE_FINGERPRINT_CONTRADICTION }`. Move an entry to its thrower's package if only one module throws and reads it.
- `FeatureTaskRuntimeExecutionAdmission.kt:74-83,115` and `FeatureTaskContinuationLookupService.kt:90-111` each collapse to one `catch (error: SkillBillRuntimeException)`. A `when (val code = error.code)` maps:
  - admission code → `code.wireValue`;
  - `FeatureTaskRuntimeRegenerationRefusal` → `code.wireValue`;
  - subtask 4's execution-identity code → `"invalid_route_identity"`;
  - anything else → rethrow without a warning.

  The warning text stays identical, and rethrow happens after the warning. `FeatureTaskRuntimeExecutionPlanCompatibility.kt:96` checks `INVALID_EXECUTION_PLAN_SCHEMA`.
- `InvalidPhaseStrategyCompositionError` becomes a defect: `throw IllegalArgumentException("Invalid phase strategy composition: $reason")` via one private helper per throwing file, or `require`.
  - Throw sites: `PhaseStrategySelection`, `PhaseStrategyLookup:295`, `PhaseStrategyRegistry:62`, `ResolvedPhaseTraversalValidation`, `SkeletonDefinition:34`.
  - Exception: `PhaseHistoricalInterpreter.kt:63` keeps a code if a persisted step id or policy can trigger it. The edge output is identical either way, because the CLI's IAE and runtime-exception arms print the same line.
- `PhaseSlotContractErrors.kt`:
  - **Defects:** `UnknownPhaseStep`, `DuplicatePhaseStrategy`, `PhaseStrategyStepOutsideSlot`, `UnknownPhaseStrategy`, `InvalidSkeletonDefinition`, `InMemorySkeletonDefinitionRequired`, `InMemoryPhaseRunUnsupported`, `PhaseRunFanOutUnsupported`, `GoalPlanningPhaseGatesUnsupported`, `PhaseStrategySelectionSlotMismatch`, `UnregisteredPhaseStrategySelection`. Each becomes `require`/`check`/`error()` with the same text after a per-site check against the defect rule.
  - **Kept as codes:** `UnknownPhaseReviewTarget`, `PhaseIntakeRequired`, `PullRequestBranchRefused`, `PhaseValidationScope` (git I/O), and `UnknownSkeletonDefinition` if a user-supplied definition id reaches it. They go in one `PhaseSlotFailureCode`, in `skillbill.error.featuretask` if domain throws a kept entry, else in `skillbill.engine.featuretask.slot`.
  - `UnknownQualityGateSelectionError` becomes a returned value: `FeatureTaskRuntimeQualityGateSelection.fromWire` returns `null` (its only caller is `FeatureTaskRuntimeRunRequestAssembly.kt:92`). That caller builds the same `UsageError` text from `entries.map { it.wireValue }` and drops the `initCause`/`runCatching`.
  - `PhaseCommand.runPhase` handles `UNKNOWN_REVIEW_TARGET` through `usageError(error)` inside the merged `SkillBillRuntimeException` catch (task 9).
- Delete the emptied featuretask files. Keep `FeatureTaskRuntimePhaseOutputFailureCode`, `FeatureTaskRuntimeFailureKinds` and `InvalidFeatureTaskRuntimeHandoffProjectionContext`.

**3. Persistence boundary, one code (AC-003).**
- In `runtime-application/.../runtimepersistence/RuntimeOwnedPersistenceBoundary.kt`, declare `RuntimeOwnedPersistenceFailureCode { FACT_UNAVAILABLE }` and factory `runtimeOwnedFactUnavailable(seam, expected, cause: Exception)` with message `"Runtime-owned persistence fact '$expected' could not be established at $seam: ${causeOf(cause)}"` and `cause` preserved.
- The engine boundary (`engine/featuretask/persist/RuntimeOwnedPersistenceBoundary.kt`) imports both; engine already depends on runtime-application. Delete both classes.
- In both `invokeOrHandle` functions, replace `runCatching` with `try`/`catch (error: Exception)`. Keep the cooperative rethrow and its ordering, and rethrow when `error is SkillBillRuntimeException && error.code == FACT_UNAVAILABLE`.
- Readers:
  - `FeatureTaskRuntimeRunLoopReviewCompletion.kt:59`: catch `SkillBillRuntimeException`, rethrow other codes.
  - `CodeReviewStep.kt:395`: code arm placed before the generic `is Exception` arm.

**4. Domain codes.**
- `SkillBillRollbackException` becomes `SkillRemoveFailureCode.ROLLBACK_INCOMPLETE` in `skillbill.skillremove`. Inline the messages at the two infra-skills throw sites. `SkillRemove.kt:126-127` becomes one `is SkillBillRuntimeException` branch with `rollbackComplete = error.code != ROLLBACK_INCOMPLETE`.
- `ReviewAttributionResolutionError.MalformedVocabulary`: if the vocabulary at `ReviewAttributionCanonicalization.kt:161` is code-owned, it becomes `throw IllegalArgumentException(<same text>)`. Otherwise use a `ReviewAttributionFailureCode.MALFORMED_VOCABULARY`. Update `ReviewAttributionCanonicalizationTest` and the MCP `ReviewAttributionResolutionParityTest` type assertions.

**5. Infra-owned codes.**
- **host** `skillbill.infrastructure.host.jvm`: `GateJvmFailureCode { GUARD_RESOURCE_MISSING, GUARD_EXECUTION, GUARD_OUTPUT, GUARD_TIMEOUT, UNRESOLVED, STARTUP_FAILURE }`. The `FileSystemValidationGateRunner` factories keep their text. `rejectedCandidate`, `requiredMajor` and `resolvedJvm` only feed messages.
- **launcher** `skillbill.infrastructure.launcher.review`: `CursorReviewStreamFailureCode { MALFORMED, FORBIDDEN_OPERATION, PROVIDER_FAILURE, TERMINATION, UNKNOWN }`. Delete the never-constructed `CursorReviewStreamEmptyError`. `AgentRunAdapters.kt:219` becomes `error is SkillBillRuntimeException && error.code == MALFORMED`. `CursorStreamParse.error` keeps holding the throwable value.
  - Also in launcher: `InvalidGovernedReviewEvidenceRequestError` moves to a launcher-owned code; only the launcher's governed-review files throw and catch it.
- **skills:**
  - `InstallSymlinkException` becomes `InstallApplyFailureCode.SYMLINK`; the existing `symbolicLinkFailure` factory returns the coded exception.
  - `NativeAgentLinkInventory{Decode,Write,Reconcile}` and `InvalidInstallStagingError` become a skills-owned install code.
  - `ReleaseLicensePolicyError` becomes a code that `RepoValidationCliCommands.kt:116` handles beside its IAE catch, with identical payload and text. The CLI discriminates it, so its enum lives in `skillbill.error.core`; if subtask 6 already made `validateReleaseRef` return a value, use that instead.
  - The 12 codeless authoring/rendering throws get `ScaffoldAuthoringFailureCode` entries: one family entry, plus entries only where main code or a test discriminates.
- **sqlite:** `InvalidGoalTelemetryRowError` becomes `GoalTelemetryRowFailureCode.MALFORMED` in `skillbill.infrastructure.sqlite.review.core`; `goalRowError` throws it. Throw sites use `databaseAccessFailure`/`databaseBusy`: `DatabaseRuntime.kt:122,210-219` and `SQLiteDatabaseSessionFactory.kt:127`. Leave `isSqliteBusy` to subtask 3.
- **workflow:** `ValidationGateProcessException` becomes `ValidationGateProcessFailureCode { TIMED_OUT, LAUNCH_FAILED }`.
- **http:** use `TelemetryHttpFailureCode`. `HttpTelemetryClient.fetchProxyCapabilities` reads the status as a value:
  - Split `requestJson` into execute-then-check.
  - When `response.statusCode` is 404/405, return the typed default with the identical warning before `ensureSuccessfulResponse` runs.
  - Delete the `catch`. `HttpInstallerScriptFetchAdapter.kt:56` catches `SkillBillRuntimeException` with `code == PROXY_REQUEST_FAILED`.
- **runtime-core:** `UnresolvedRemoteTransportPortError` (`RuntimeComponent.kt:129`) is a composition defect, so it becomes `error(<same text>)`, unless `AbsentOptionalPortResolutionTest` shows it is an expected runtime state. Then it keeps `REMOTE_TRANSPORT_UNRESOLVED`.

**6. MCP-owned code (AC-002).** `InvalidMcpToolArgumentError` becomes `McpToolArgumentFailureCode.INVALID` in `skillbill.mcp.shared`, with factory `invalidMcpToolArgument(toolName, argumentKey, detail, cause)`. Use it at the dispatcher and argument-reader throw sites.

**7. CLI readers (AC-004, AC-005).**
- `GoalCliStatusCommands.kt:89` catches `SkillBillRuntimeException`, rethrows unless `code == DatabaseFailureCode.ACCESS`, and passes `databaseAccessCondition(error)` to `goalMonitorStatusText` and `databaseUnavailableGoalStatusCliMap`. The latter's parameter becomes the condition string; payload bytes are unchanged.
- `FeatureTaskRuntimeRunRequestAssembly` is handled in task 2.
- Test edits:
  - `DatabaseAccessErrorTest`: `.condition` becomes `databaseAccessCondition(...)`. Replace the test asserting the database error is not a `SkillBillRuntimeException` with an assertion on `DatabaseFailureCode.ACCESS`; task 9's new test carries that test's invariant.
  - `InlineReviewPreparationDispositionTest` builds via `databaseBusy(...)`.
- `InlineReviewPreparation.kt:75` checks `it is SkillBillRuntimeException && it.code == DatabaseFailureCode.BUSY` across the cause chain.

**8. MCP dispatcher arm (AC-004).** Rewrite the failure `when` in `McpToolDispatcher.dispatch`:
- `CancellationException` → throw.
- `is SkillBillRuntimeException`:
  - capture first only when `capturedAtMcp(error.code)`;
  - always return `mcpToolErrorResult`.
- IAE/ISE → result.
- `Exception` → capture plus result.
- `capturedAtMcp` is a private `when (code) { is … -> true; else -> false }` over the former direct-subclass and `RuntimeException` codes MCP can see: `TelemetryHttpFailureCode`, `DatabaseFailureCode`, `RuntimeOwnedPersistenceFailureCode`, `FeatureSpecPreparationFailureCode`, `SkillRemoveFailureCode`, subtask 2's `RejectedOutputDiagnosticFailureCode`.

Former shell-content, learning-source, ISE- and IAE-based codes stay uncaptured, which matches today's no-capture arm, and the `InvalidLearningSourceError` arm becomes this code arm. If subtask 4 already added an arm or predicate, replace it with this one.

During implement, confirm `GateJvmFailureCode`, `InstallApplyFailureCode`, `CursorReviewStreamFailureCode` and `ValidationGateProcessFailureCode` cannot reach an MCP tool, since they were captured before. If one can, move its enum to `skillbill.error.core` and list it.

**9. Retarget remaining base-class catches (AC-001, AC-004).**
- Replace every remaining `catch (e: ShellContentContractException)`/`is ShellContentContractException` with `SkillBillRuntimeException` (the parent spec's transition rule), including:
  - function types such as `() -> ShellContentContractException` in `ClasspathContractSchemaLoader`, `ContractValidatorWireInput`, `FeatureTaskRuntimeHandoffFoundationSchemaValidators`;
  - in tests, `GoalPlanningPreparationStoreSchemaParityTest` (with a code assertion) and `PlatformPackSchemaCleanupTest`.
- Replace any `LegacyFailureCode` checks subtask 4 left.
- **Keep the handled set.** For each widened site, and each pre-existing `SkillBillRuntimeException` catch, a failure that was not a `ShellContentContractException` and can reach the `try` must be rethrown so it keeps today's route. That covers database codes, `FACT_UNAVAILABLE` where today it propagates, regeneration refusal, gate-JVM, validation-gate process and cursor codes.
  - Call `error.rethrowIfDatabaseFailure()` at every site whose `try` touches the database. `PlanDecompositionStop:211` (wrapping `persistDecomposeTerminal`) is mandatory.
  - Use `if (error.code is X) throw error` for the others, where reachability is real.
  - `GoalRunnerPauseBoundary.kt:31` rethrows only former shell-content codes and keeps `Failed(exception)` for database and fact codes.
  - If a site needs a code its module cannot import, the code moves to `skillbill.error.core` (see Constraints).
- `PhaseCommand.runPhase` merges into one catch: `UNKNOWN_REVIEW_TARGET` goes to `usageError`, reachable database and fact codes rethrow, and the rest goes to `completeText`.

**10. Finish the transition (AC-001, AC-005).** Once the census shows no class extending `SkillBillRuntimeException`/`ShellContentContractException` and no codeless construction anywhere in main, test or testFixtures:
- delete `ShellContentContractException`, `LegacyFailureCode` and the secondary constructor;
- make `SkillBillRuntimeException` final with `(code, message, cause = null)`.

If subtasks 2–4 left a subclass, stop short of this, record why in the summary, and leave the transition open.

**11. Telemetry `error_type` and class-name sinks (AC-005).**
- Add `RuntimeFailureCode.failureTypeName()` = `"${(this as Enum<*>).declaringJavaClass.simpleName}.$name"` in `skillbill.error.core`, unless subtask 4 already added an equivalent; reuse it if so. `RuntimeExceptionTelemetry` and `ExternalPlatformPackTelemetryPolicy` emit it for `SkillBillRuntimeException`, and other throwables keep today's value.
- `ExternalPlatformPackTelemetryPolicy`'s family branch checks `code` (`AMBIGUOUS`, `CONFIG`, subtask 4's manifest-schema code).
- `ExternalPlatformPackFailureCode.PUBLISH`: `remotePayload` has no production reader (only `ExternalPlatformPackCatalogIntegrationTest:406-409` reads it). Drop the payload construction in `InstallNativeAgentOperationsLinkCatalog.kt` and replace those four assertions with a code assertion. This is a deliberate edit beyond type→code, named in the summary.
- `ExternalPlatformPackTelemetryPolicyTest:27` and `ConfigExternalPlatformPackCommandTest:111` assert the code-derived value (type→code). Update the `error_type` row in `docs/telemetry-privacy.md`.
- Accepted text changes (recorded in task 12):
  - Former `RuntimeException`/`Exception`/ISE classes that reach `CliRuntime` now print through the `SkillBillRuntimeException` arm, without the `ClassName: ` prefix or the diagnostics record. Known case: `DatabaseAccessError` on the non-monitor `goal status`, where `CliGoalStatusDatabaseFailureTest` still passes.
  - `CodeReviewStep`'s generic arm and the `errorType=` diagnostics now name `SkillBillRuntimeException`.

  The message text itself does not change.

**12. Baseline, docs, decision (AC-001, AC-006).**
- Hand-delete every deleted class's row from the custom-throwable baseline. The target is `SkillBillRuntimeException` alone, or plus any class with a recorded retention reason (none expected). Leave verifying it to validate.
- `rg 'typealias'` confirms no alias is named after a deleted class.
- Delete emptied files and packages. Update the `skillbill.error.*` package description in `runtime-kotlin/ARCHITECTURE.md` (around lines 394–397), and grep `docs/`, `AGENTS.md` and `ARCHITECTURE.md` for deleted class names and `ShellContentContractException`.
- Add a newest-first entry to `runtime-kotlin/agent/decisions.md`: "SKILL-398 subtask 5: edge classification after the exception collapse". Use Context/Decision/Reason/Alternatives lines covering:
  - the code-placement rule for edge-discriminated codes;
  - the MCP capture list;
  - the widened-catch rethrow rule and the database helper;
  - the `error_type` derivation and its dashboard-continuity loss;
  - the top-level framing change;
  - the `remotePayload` removal.

### Test obligations

- **Planning stop.** A `databaseBusy(...)` failure thrown from `persistDecomposeTerminal` propagates out of `PlanDecompositionStop.apply` instead of becoming `Blocked`. Bug it catches: the widened catch absorbs a retryable database failure into a terminal planning block. It carries the invariant of the deleted "not absorbed" test.
- **Database condition extractor.** In `DatabaseAccessErrorTest`, `databaseAccessCondition(databaseAccessFailure(path, READ, cond))` equals the bounded condition, not the full message. Bug it catches: the goal-status payload `reason` gains the path and prefix, which `CliGoalStatusDatabaseFailureTest` would not notice.
- **MCP capture predicate.** In the `McpCaptureDiagnosticsTest` style:
  - a `TelemetryHttpFailureCode.PROXY_REQUEST_FAILED` failure is captured;
  - an `InvalidLearningSourceReason` failure is not.

  Bug it catches: the capture list drifts and persisted telemetry rows change.
- **Execution-plan admission (conditional).** Only if no existing test asserts the `reason=<wire>` warning: one test for an admission code and a regeneration refusal.
- **Covered by existing tests, no new test needed:**
  - database-busy retry: `InlineReviewPreparationDispositionTest`;
  - goal-status database payload: `CliGoalStatusDatabaseFailureTest`;
  - HTTP 404/405 fallback: the http client tests.
- Run nothing here: build, unit tests, detekt and the repoTest suite (`FailureCodeTotalityArchitectureTest`, `TypedParseBoundaryArchitectureTest`) belong to the build and validate phases.

## Next Path

skill-bill goal SKILL-398

## Spec Path

.feature-specs/SKILL-398-runtime-exception-reduction/spec_subtask_5_collapse-remaining-errors.md
