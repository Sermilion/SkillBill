# SKILL-398 Subtask 6 - no-control-flow-on-defect-exceptions

Parent spec: [.feature-specs/SKILL-398-runtime-exception-reduction/spec.md](./spec.md)
Issue key: SKILL-398

## Scope

(F-006) `IllegalArgumentException` and `IllegalStateException` mean code defects. Main code catches them at 97 sites (infra 42, domain 27, cli 11, application 9, engine 6, contracts 1, ports 1 at the census tree; found with `catch \(\w+: (IllegalStateException|IllegalArgumentException)\)`), mostly to turn a `require` failure into a value or into another exception. Remove that control flow, by kind:

1. **The `try` body is the runtime's own `require`/`check`/decoder used to validate input** (for example `runtime-domain/.../goalrunner/AttemptLedgerDecoding.kt:73`, `.../goalrunner/model/FeatureTaskRuntimeGoalContinuationOutcome.kt:87`, `.../review/parallel/ParallelReviewFindingParser.kt:203`, `:221`, `.../review/parallel/ParallelReviewTrailingStructuredFields.kt:127`, `.../review/context/model/packet/ReviewRunLaneSegmentAccountingJson.kt:60`, `.../goalrunner/subtaskreview/GoalSubtaskReviewStructuredFindingsParse.kt:126`): add or use a non-throwing validator (`...OrNull`, a sealed validation result) and branch on it; where the caller rethrows a schema failure, it throws that failure directly from the validation result. The original `require` stays only where it guards an invariant no input can break.
2. **The `try` body is a JVM or library API with a non-throwing form** (`toInt` / `toLong` / `toBigInteger` → `...OrNull`; `enumValueOf` / `valueOf` → `entries.firstOrNull`): use the non-throwing form and drop the catch.
3. **The `try` body is a JVM or library API with no non-throwing form** (for example `URI.create` in `runtime-infra/http/.../HttpRequestUri.kt:11`, kotlinx `SerializationException` in `runtime-contracts/.../JsonCodec.kt:111`, `Path.of` → `InvalidPathException`): keep the catch, narrowed to the most specific type the API documents.
4. **SKILL-392 follow-up.** `RuntimeOwnedReviewMode.parse` (`runtime-application/.../review/service/RuntimeOwnedReviewMode.kt`), `decodeScaffoldPayloadObject` (`runtime-application/.../scaffold/ScaffoldCommandRequestDecoder.kt`) and `validateReleaseRef` (`runtime-infra/skills/.../scaffold/runtime/validation/RepoValidationRuntime.kt`, behind the `RepoValidationGateway` port) report malformed user input with `require`, so the CLI catches `IllegalArgumentException` at 11 sites (SKILL-392 investigation lines 122, 248, 355, 377). Each owner returns a result or throws `SkillBillRuntimeException` with a code; the CLI maps the result or code to the same `UsageError` text and exit code; the CLI's IAE catches for these paths go. If the two wrap-and-`initCause` bodies (`runCatching { usage.initCause(error) }` in `FeatureTaskRuntimeRunRequestAssembly.kt` and `GoalCliRunCommands.kt`) still exist, they go with it.

Any `runCatching` inside a touched function follows the parent spec's constraint.

Excluded: the top-level arms in `runtime-cli/.../core/CliRuntime.kt` and `runtime-mcp/.../core/McpToolDispatcher.kt` (investigation F-008), and catches inside custom exception types owned by subtasks 3 and 5 (for example `ClaudeMcpProfileFailure : IllegalArgumentException`); if those types are already gone, their catches are in scope.

## Acceptance Criteria

1. Outside `CliRuntime.kt` and `McpToolDispatcher.kt`, no main source catches or `is`-checks `IllegalArgumentException` or `IllegalStateException`. The only remaining catches in their family are of a specific library subtype (for example `SerializationException`, `InvalidPathException`) at a call into a JVM or library API that has no non-throwing form; `NumberFormatException` is not caught where an `...OrNull` function exists.
2. No main function validates external input with `require`/`check` and relies on a caller catching it; each such validator has a non-throwing form or a result the caller branches on.
3. `RuntimeOwnedReviewMode.parse`, `decodeScaffoldPayloadObject` and `validateReleaseRef` do not throw `IllegalArgumentException` for malformed input, and runtime-cli has no `IllegalArgumentException` catch for them; the CLI prints the same `UsageError` texts and exit codes as before.
4. Every user-visible message and every persisted byte is unchanged; existing tests pass with only exception-type assertion edits where a validator now returns a value.
5. `TypedParseBoundaryArchitectureTest` and detekt pass.

## Non-Goals

- Changing `CliRuntime.kt` or `McpToolDispatcher.kt` classification (F-008).
- The repo-wide `runCatching` sweep (F-007).
- Replacing `require`/`check` that guard true invariants.

## Dependency Notes

Depends on: none.
If subtasks 4 or 5 already turned a rethrown schema error into a code, throw that code from the validation result. Performs the follow-up SKILL-392 recorded and the `require` work SKILL-397 listed as a non-goal; applies to the files as they are after those bundles if they landed. The second lander keeps both edits.

## Validation Strategy

Goal gates: build, unit tests, detekt and the runtime-core repoTest suite. Test obligations: one test per new non-throwing validator that a former catch depended on, covering the invalid input path, and one CLI test per SKILL-392 path (review mode, scaffold payload, release ref) asserting the unchanged `UsageError` text and exit code.

## Next Path

skill-bill goal SKILL-398

## Spec Path

.feature-specs/SKILL-398-runtime-exception-reduction/spec_subtask_6_no-control-flow-on-defect-exceptions.md

## Implementation Details

Planned against `base/SKILL-380-phase-slot-strategies` at `432d427c8`. This subtask runs last in the goal, so subtasks 1 to 5 have landed when it starts. Each rule below applies to the tree as it is then. Paths are relative to `runtime-kotlin/`, and `.../` stands for `src/main/kotlin/skillbill/`.

### Ground rules for every task

1. **Edge invariance (AC-004).** For a given input, the exception type that reaches `CliRuntime.run` or `McpToolDispatcher.dispatch` must not change.
   - `CliRuntime` prints `oneLine(message)` for both the `IllegalArgumentException` (IAE) arm and the `SkillBillRuntimeException` arm. The two differ only in the fallback used when the message is blank.
   - `McpToolDispatcher` skips telemetry capture for IAE and `IllegalStateException` (ISE). It also skips capture for `SkillBillRuntimeException` codes on its no-capture list. For every other code it calls `captureException`, which writes a persisted telemetry row.
   - So a failure that reaches MCP today as IAE must still reach it as IAE, or as a code on that no-capture list. Check the list as `McpToolDispatcher.kt` stands after subtask 5.
   - A `require` or IAE whose only handler is a top-level arm may stay. F-008 excludes those arms, and AC-002 targets validators that a *caller* catches.
2. **Message invariance (AC-004).**
   - Each new non-throwing validator is the single source of its text. The original `require` or `throw` is rewritten to call it, so the two cannot drift.
   - Where a catch built its text from `error.message`, the replacement puts the validator's message in the same position.
   - A schema error may lose an IAE `cause`, because causes are neither printed nor persisted. `RuntimeDiagnostics` records keep their message line.
3. **Literal text stays.** The degraded cause in `infra/workflow/.../featuretask/FileSystemFeatureTaskRuntimeSharedEvidenceStoreReads.kt` keeps its literal `"IllegalArgumentException: "` prefix, because those are emitted bytes.
4. **Validator shape.** Use the lightest shape that works:
   - `xOrNull(...)` when the only outcome is parsed or absent.
   - A companion `violation(...): String?` for model `init { require }` invariants. The `init` keeps a `require` that calls the same function.
   - A sealed result only where a port or caller needs a reason it can branch on.

   Constraints on where these live:
   - A port result is a `sealed interface` with nested `data` variants, in the port's existing `model` package beside its payload (as `ReleaseRefMetadata` is). Confirm `PortsDeclarationArchitectureTest` accepts it before adding.
   - Domain helpers stay pure (no `java.nio`, no ports) and follow the model-package import rule.
   - No `Result` or `Either` library, no new throwable, no typealias, and no new property on `SkillBillRuntimeException`.
5. **Kind-3 catches** narrow to the type the library documents:
   - `InvalidPathException` for `Path.of`.
   - `URISyntaxException` via `URI(url)` instead of `URI.create`. The message is identical, because `URI.create` wraps `URISyntaxException.message`.
   - `JsonProcessingException` via `ObjectMapper.readerFor(type).readValue(node)` or `treeToValue` instead of `convertValue`.
   - kotlinx `SerializationException` for kotlinx parsing.
   - `UncheckedIOException` while iterating a `Files.walk` or `Files.list` stream.
   - `DateTimeParseException` catches stay as they are.
6. **Rollback and cleanup on failure** use a success flag with `finally`, or `AutoCloseable.use`, which keeps the primary exception and adds cleanup failures as suppressed. Never catch a generic type, since detekt's `TooGenericExceptionCaught` forbids it.
7. **`runCatching` in a touched function** becomes a direct call or a narrow catch. Where a catch-all is still needed, it uses `getOrElse { it.rethrowIfCooperativeCancellationOrInterruption(); … }` or `getOrElseUnlessCooperative` from `runtime-application/.../application/CooperativeFailurePropagation.kt`. Engine already imports it, for example in `WorktreeEditJournalWriter`. Never add a `runCatching`.
8. **detekt limits** (AC-005): `ReturnCount` 4, `ThrowsCount` 2, `CyclomaticComplexMethod` 15, `LongMethod` 70, plus `SwallowedException`. Don't use `@Suppress`. Prefer `when` expressions and small helpers over early-return chains.
9. **Parse-boundary guard** (AC-005). `TypedParseBoundaryArchitectureTest` checks the `ParseBoundarySite` entries in `runtime-core/src/repoTest/.../architecture/PrincipleEnforcementInventory.kt`, roughly lines 305 to 630.
   - A listed function keeps its name. If it must be renamed, update its entry in the same commit; never drop the entry.
   - A listed function gains no `error()`, `require` or bare `throw` for input.
10. **Earlier subtasks.**
    - Where subtask 4 or 5 turned a rethrown schema class into `SkillBillRuntimeException(code, …)`, throw that code from the validation result.
    - When this subtask removes the last use of an IAE-based custom class, delete the class and its row in `runtime-core/src/repoTest/.../baselines/custom-throwable-baseline.txt`. Examples: `ReleaseLicensePolicyError : IllegalArgumentException`, or any `Invalid*Error` that still extends IAE.
    - Classes still owned by a live subtask-3 or subtask-5 decision are left as they are.

### Task 1: census and classification (serves all ACs)

Run the census again:

```
grep -rnE "catch \(\w+: (IllegalStateException|IllegalArgumentException|NumberFormatException)\)|is (IllegalArgumentException|IllegalStateException)\b"
```

Run it over every `src/main` under `runtime-kotlin`. At planning time it found 95 sites outside the two excluded files:

| Module | Sites |
|---|---|
| runtime-domain | 27 |
| runtime-infra | 41 (skills 20, sqlite 9, contracts 6, host 3, http 2, workflow 1) |
| runtime-cli | 10 |
| runtime-application | 9 |
| runtime-engine | 6 |
| runtime-ports | 1 |
| runtime-contracts | 1 |

For each site:

1. Follow the calls in the `try` body and list every IAE or ISE source.
2. Tag the site with one kind:
   - **A**: the runtime's own validator on input. This includes model `init { require }`, throwing `fromWire`, `parsePersistedInstant` and `requireRepositoryRelativePath`.
   - **B**: a library call that has a non-throwing form, such as `toInt` or `valueOf`.
   - **C**: a library call with no non-throwing form.
   - **D**: reachable only by a defect. Drop the arm.
   - **E**: a rollback or cleanup catch.
   - **F**: CLI-local handling.
3. For every validator that becomes non-throwing, also grep its `runCatching { … }` callers. AC-002 covers those too; for example, `AuthoringRenderOutput.kt:110` wraps `AgentAddonConsumer.fromId`.

Tasks 2 to 7 give the expected kind for each site. Where the census disagrees, apply the rule for the kind it finds.

### Task 2: shared non-throwing validators (AC-001, AC-002)

Add these first, because most catch sites depend on them.

- **`CodeReviewExecutionMode`** (`runtime-domain/.../review/context/model/execution/`)
  - Add `fromWireOrNull(value)` and `unknownWireValueMessage(value)`. The second returns `"Unknown code-review execution mode '$value'. Allowed: auto, inline, delegated."`.
  - `fromWire` becomes `fromWireOrNull(value) ?: throw IllegalArgumentException(unknownWireValueMessage(value))`, for callers that are handled only at the edge.
- **`ValidationDepth`** (`runtime-domain/.../workflow/model/`): add `fromWireOrNull(value)` and an unknown-value message function. `fromWire` delegates to them.
- **`parsePersistedInstantOrNull(value)`** (`runtime-domain/.../workflow/time/PersistedInstant.kt`)
  - Returns null when all three `DateTimeParseException` attempts fail.
  - `parsePersistedInstant` delegates and keeps `"Timestamp is not a supported persisted instant."` for the callers nothing catches.
- **`repositoryRelativePathViolation(path): String?`** (`runtime-domain/.../review/model/ReviewRepositoryRelativePath.kt`)
  - Returns the same four messages in the same order.
  - `requireRepositoryRelativePath` becomes `require(violation == null) { violation }` over it. The re-export in `review/context/model/hunk/ReviewContextCanonical.kt` stays.
- **`decodeParallelReviewStructuredStringOrNull(encoded)`** (`runtime-domain/.../review/parallel/ParallelReviewTrailingStructuredFields.kt`)
  - Returns null on a missing quote, a dangling escape, a short or non-hex `\u` sequence (via `toIntOrNull(radix)`), or an unsupported escape.
  - Remove the throwing version if nothing else uses it.
- **`PersistedAgentAddonSelectionEntry.violation(slug, sourceIdentity, contentSha256)`** and **`AgentAddonSelection.violation(entries)`** (`runtime-domain/.../agentaddon/model/AgentAddonModels.kt`)
  - Each returns the existing `require` text.
  - Both `init` blocks call them.
- **`AgentAddonConsumer.fromIdOrNull(id)`** plus its message function. `fromId` delegates.
- **`RuntimeOwnedReviewMode.parse(value)`** (`runtime-application/.../application/review/service/RuntimeOwnedReviewMode.kt`)
  - Returns `CodeReviewExecutionMode?`.
  - Add `unknownModeMessage(value)`, which returns `"Unknown code-review execution mode '$value'. Allowed: auto, inline."`.
  - Covers AC-003.
- **`decodeScaffoldPayloadObject(payloadText)`** (`runtime-application/.../application/scaffold/ScaffoldCommandRequestDecoder.kt`)
  - Returns `JsonObject?`.
  - Add a `const val` holding `"Invalid JSON payload: expected an object."` (AC-003).
  - `decodeScaffoldCommandRequest(payloadText)` is reached through MCP `ScaffoldInvocation` and is handled only at the edge, so it keeps throwing IAE with that same constant.
- **Any other model `init { require }`** that a Task 3 to 6 catch depends on gets the same companion `violation(...)` treatment.
  - Expected ones include `RejectedOutputDiagnostic`, `ReviewContextBudgetPolicy`, `ReviewFindingCitation`, `ReviewLaneSegmentAccounting`, and the evidence, branch, handoff, checkpoint and gate-progress models listed in Task 3.
  - The census confirms which ones.

### Task 3: domain decoder catches, 27 sites (AC-001, AC-002, AC-004)

**Method for each site.** Replace the `try`/`catch (IllegalArgumentException)` with explicit checks before construction:

1. Call `violation(...)` and the `…OrNull` helpers.
2. On failure, throw the schema failure the catch used to throw, with byte-identical text (Ground rule 2).
3. Then construct the value. Its `require` is now an invariant that no decoded input can break.

Where the body builds a value only from runtime-produced data (a kind-D site), drop the catch and keep the `require`. For example, check the `fromMeasurements`-style factory at `FeatureTaskRuntimeValidationGateExecutionEvidence.kt:87`.

Sites, all under `runtime-domain/.../`:

- `goalrunner/ledger/AttemptLedgerDecoding.kt:73`. Also convert the `runCatching { parsePersistedInstant(…) }` at `:48` in the same function.
- `goalrunner/model/FeatureTaskRuntimeGoalContinuationOutcome.kt:89`
- `goalrunner/subtaskreview/GoalSubtaskReviewStructuredFindingsParse.kt:126`, via `repositoryRelativePathViolation`.
- `review/parallel/ParallelReviewFindingParser.kt:203`, `:208`, `:221`. Both rejection reasons keep their mapping.
- `review/parallel/ParallelReviewTrailingStructuredFields.kt:127`. Check the path before building `ReviewFindingCitation`; a failure keeps the `"invalid_path"` diagnostic.
- `review/context/model/packet/ReviewRunLaneSegmentAccountingJson.kt:60`
- `workflow/model/goalobservability/GoalObservabilityParsing.kt:100`, via `parsePersistedInstantOrNull`.
- `workflow/model/goalreview/GoalSubtaskReviewState.kt:315`. Includes the `CodeReviewExecutionMode.fromWire` uses at `:289` and `:308`.
- `workflow/taskruntime/model/phase/FeatureTaskRuntimePhaseLedgerPersistenceModels.kt:170`. Includes `parsePersistedInstant` at `:153` and `FeatureTaskRuntimePhaseExecutionOrigin.fromWireValue`.
- `workflow/taskruntime/model/phase/FeatureTaskRuntimePhaseRecord.kt:218`. Includes `parsePersistedInstant` at `:183` and `:184`. Failures still go to `incompatiblePhaseRecord()`.
- `workflow/taskruntime/model/core/FeatureTaskRuntimeResolvedBranch.kt:70`
- `workflow/taskruntime/model/handoff/task/FeatureTaskRuntimeHandoffEnvelope.kt:62`
- `workflow/taskruntime/model/audit/FeatureTaskRuntimeQuarantineModels.kt:130`
- `workflow/taskruntime/model/persistence/FeatureTaskRuntimeRunInvariantsPersistence.kt:48`, `:93` and `:145`. `:145` uses `fromWireOrNull`. Its message `"... must be one of auto, inline, delegated."` is unchanged.
- `workflow/taskruntime/model/persistence/FeatureTaskRuntimeCheckpointIdentityModels.kt:154`
- `workflow/taskruntime/model/persistence/FeatureTaskRuntimeGoalContinuationArtifact.kt:145`, via `ValidationDepth.fromWireOrNull`.
- `workflow/taskruntime/model/validation/FeatureTaskRuntimeValidationGateProgressModels.kt:173`, `:211`
- `workflow/taskruntime/model/validation/FeatureTaskRuntimeReadinessEvidence.kt:181`
- `workflow/taskruntime/model/validation/FeatureTaskRuntimeValidationGateExecutionEvidence.kt:87`, `:117`, `:137`
- `workflow/taskruntime/model/validation/FeatureTaskRuntimeValidationEvidence.kt:117`

Also check `GoalSubtaskReviewFindingArtifacts.kt:130` and `GoalObservabilityModels.kt:94` and `:229`. If a caller catches their throwing calls, switch those calls to the `…OrNull` form. Otherwise leave them.

### Task 4: engine, ports and contracts, 8 sites (AC-001, AC-002, AC-004)

The engine sites are in `runtime-engine/.../engine/featuretask/`.

- **`lifecycle/execution/FeatureTaskRuntimeExecutionPlanDecode.kt:72`**: use `ValidationDepth.fromWireOrNull` and the other sources the census finds. Keep `"execution plan settings are invalid: <message>"`.
- **`definition.traversal(...)` callers.** Add a non-throwing form next to the existing extension, either `traversalOrViolation` or a nullable result plus its message. Callers:
  - `lifecycle/execution/FeatureTaskRuntimeExecutionPlanCompatibility.kt:85` goes to `incompatible()`.
  - `slot/PhaseStrategyLookup.kt:144` goes to `invalidComposition("definition … has incoherent traversal: <message>")`.
  - `lifecycle/execution/FeatureTaskRuntimeExecutionPlanCodec.kt:30` keeps its own handling.
  - The throwing `traversal` stays for callers handled only at the edge.
  - It is a shared skeleton helper with one generic path; no phase-specific branch (`runtime-kotlin/agent/decisions.md#01a41a7ed8b3`).
- **`lifecycle/execution/FeatureTaskRuntimeExecutionPlanCodec.kt:114`**: make the IAE sources of `decodeExecutionPlan` non-throwing, and throw `"execution plan cannot be reconstructed"` from the result.
- **`review/core/FeatureTaskRuntimeSharedReviewEvidenceResolver.kt:84`**
  - Add a non-throwing `ReviewDiffEvidence` parse in `runtime-application/.../application/reviewevidence/`, for example `parseOrRejection(diff)` returning the evidence or the `require` message, such as `"The authoritative review diff contains no attributable diff records."`.
  - `recordParseDegradation` must emit the same record text as before. If it took a `Throwable`, give it a message overload.
- **`phaserun/PhaseRunIntakeResolver.kt:79` and the `FeatureTaskRuntimeRunInvariantsSource` port**
  - Change `FeatureTaskRuntimeRunInvariantsSource.read(specPath)` (`runtime-ports/.../ports/taskruntime/`) to return a sealed `FeatureTaskRuntimeRunInvariantsRead { Read(invariants); Rejected(reason) }` in `ports/taskruntime/model`.
  - In `FileSystemFeatureTaskRuntimeRunInvariantsSource` (`runtime-infra/workflow`), its three path `require`s return `Rejected` with the same text.
  - `PhaseRunIntakeResolver` maps `Rejected` to `null`.
  - `goalrunner/planning/outcome/GoalPlanningSubtaskPlanProduction.kt:39` and `goalrunner/planning/context/GoalPlanningSharedPreplanProduction.kt:52` map `Rejected(reason)` to the stop reason they produce today. `invariantReadReason` takes the message, so the output is still `"…run-invariants could not be read: <reason>"`.
  - Their touched `runCatching` keeps catch-all behaviour for I/O through the cooperative rethrow (Ground rule 7).
  - Update the test fakes `FakeInvariantsSource` and the `GoalRunnerTestFactory` source.
- **`runtime-ports/.../ports/workflow/model/WorkflowArtifactTimestampMapping.kt:68`**: use `parsePersistedInstantOrNull`, and keep `"Workflow artifact contains an invalid timestamp."`.
- **`runtime-contracts/.../contracts/JsonCodec.kt:111`**: drop the IAE arm. `parseToJsonElement` reports malformed text as `SerializationException`, which is already caught (kind C).

### Task 5: application, 9 sites (AC-001, AC-002, AC-004)

**`config/ConfigResolutionService.kt:30` and `:56`, plus the telemetry config read.**

- Change `TelemetryConfigStore.read()` (`runtime-ports/.../ports/telemetry/transport/`) to return a sealed `TelemetryConfigRead { Absent; Malformed(reason); Present(document) }` in the port's model package.
- `infra/host/.../FileTelemetryConfigStore.kt`:
  - `readTelemetryConfigFile` becomes the non-throwing `readTelemetryConfigFileRead(path)`.
  - The reasons stay `"Telemetry config at '<path>' is not valid JSON."` and `"... must contain a JSON object."`.
  - `ensureTelemetryConfigFile` and the edge-only callers keep throwing IAE with the same reason.
- `ConfigResolutionService` maps `Malformed` to today's `MalformedMachineConfigError` text, or its subtask-4 code.
- `TelemetrySettingsFromStore.loadTelemetrySettingsFromStore` keeps throwing IAE on `Malformed` for `load()` callers, which are handled only at the edge.

**`telemetry/settings/TelemetrySettingsLoading.kt:27` and `:29`.**

- Add a non-throwing `resolveTelemetrySettingsFromStore(...)` that returns a sealed `TelemetrySettingsLoad { Loaded(settings); Unavailable(reason) }`. It covers malformed config, the `install_id` `require`, and any other input `require`/`check` the census finds in the chain.
- `loadTelemetrySettingsFromStore` becomes that function plus `throw IllegalArgumentException(reason)`, so edge behaviour is unchanged.
- Add `loadOrUnavailable(materialize)` to `TelemetrySettingsProvider`. `DefaultTelemetrySettingsProvider` implements it, and about 8 test fakes return `Loaded(settings)`.
- `telemetrySettingsOrNull` branches on the result and still emits `diagnostics.error(TELEMETRY_SETTINGS_LOAD_FAILURE_MESSAGE)`. The degrade record stays.

**`workflow/service/LegacyGoalRunnerControlMigration.kt:63`, `:121` and `:149`**: use the Task 2 validators. All three messages are unchanged.

**`review/parallel/verification/ParallelCodeReviewRunnerFailureAdmission.kt:144` and `:146`.**

- After Task 3, run the census over `ParallelReviewFindingParser.parse`.
- If no input can make it throw, drop both arms. A defect then propagates.
- A test that injects a throwing `parse` lambda to assert `ReviewRegisterParseSeamException` (or its code) pins the removed control flow. Change its expected type to the propagated defect (exception-type edit).
- If an input path still throws, give it a parse-result variant instead.

### Task 6: infra, 41 sites (AC-001, AC-002, AC-004)

**host**

- **`FileSystemRepoLocalConfig.kt:110`**: apply `ReviewContextBudgetPolicy.violation(...)` before construction, and keep the `MalformedRepoLocalConfigError` fields and text.
- **`JdkFeatureTaskRuntimeWorkerSupervisor.kt:196` and `:200`** (heartbeat liveness).
  - Find which types `heartbeat()` raises for expected renewal failures. Expected: `IOException`, plus the coded `SkillBillRuntimeException` that subtask 5 gives database-busy and database-access failures.
  - Replace the IAE and ISE arms with an arm for those types, keeping `reportFailure` and the retry.
  - If the census shows renewal still signals an expected condition with ISE, convert that source to a `FeatureTaskRuntimeHeartbeatTick` value or the coded failure first.

**http**

- **`HttpRequestUri.kt:11`**: use `URI(url)` and catch `URISyntaxException`. The `TelemetryProxyRequestFailureError` fields and detail stay.
- **`GitHubReleaseCatalogAdapter.kt:33`**: the URL and headers are constants, so this is kind D. Drop the arm.

**contracts**

- **`ClasspathContractSchemaLoader.kt:103`, `:124`, `:167`**
  - Own identity checks throw the failure directly.
  - Library failures are narrowed to the networknt or Jackson types that `getSchema`, `readTree` and `writeValueAsString` document, such as `JsonProcessingException` and `com.networknt.schema.JsonSchemaException` if it applies. Then drop the IAE arms.
- **`workflow/decomposition/DecompositionManifestSchemaValidator.kt:134`**
  - The source is the code's own `require(parser.nextToken() == null)`. Replace it with an explicit throw with reason `"YAML is malformed: YAML contains trailing content or multiple documents."` and failure code `malformed`.
  - Keep subtask 3's duplicate-key handling.
- **`DecompositionManifestSchemaValidator.kt:149`** and **`DecompositionManifestBundleJournalSchemaValidator.kt:115`**: replace `convertValue` with a reader or `treeToValue` and catch `JsonProcessingException`, keeping `error.message` in the same position.

**sqlite**

- **`SqliteRejectedOutputDiagnosticRepository.kt:264`**
  - Replace `RejectedOutputLifecycle.valueOf` with `entries.firstOrNull { it.name == raw.uppercase() }`.
  - Call `RejectedOutputDiagnostic.violation(...)` before construction. If `corruptRecord` takes a `Throwable`, give it a message variant that yields the same output.
  - Apply this to the read shape subtask 2 left.
- **`workflow/goalrunner/runner/LegacyGoalRunnerControlLedgerMigration.kt:93`, `:153`, `:182`** and **`GoalRunnerControlStoreDecodePolicies.kt:29`, `:35`, `:76`**: use the Task 2 validators. For example, `:29` keeps `"…invalid code_review_mode: <unknownWireValueMessage>"`.
- **`GoalRunnerControlStoreDecodePolicies.kt:101`**: drop the IAE arm, because `SerializationException` is already caught.
- **`worklist/SQLiteWorkListRepository.kt:154`**: use `parsePersistedInstantOrNull`, keeping `"invalid $column '$value'"`.

**workflow**

- **`featuretask/FileSystemFeatureTaskRuntimeSharedEvidenceStoreReads.kt:134`**: use model validators before construction, and keep the degraded `seam`, `used`, `expected` and the literal `cause` prefix (Ground rule 3).

**skills**

- **Five config-read sites**: `externalplatformpack/FileExternalPlatformPackSourceConfigStore.kt:36`, `:63`, `:100`, plus `externaladdon/FileExternalAddonSourceConfigStore.kt:37` and `externaladdon/ExternalAddonSourceEntries.kt:26`.
  - Use `readTelemetryConfigFileRead` and map `Malformed(reason)` to the same `ExternalPlatformPackConfigError` or `ExternalAddonConfigError` message, or their codes.
- **`FileExternalPlatformPackSourceConfigStore.kt:151`**: catch `InvalidPathException` from `Path.of` in `resolveExternalPlatformPackSourcePath`, keeping the text.
- **`install/staging/InstallStaging.kt:152`**: own `require`s in `prepareStageInstalledSkill` throw `invalidStageInstalledSkill` directly. Catch `InvalidPathException` if path parsing is involved.
- **`install/nativeagent/inventory/NativeAgentLinkInventoryDecode.kt:35`**: own `require`s in `decodeEntries` and `validateDecodedEntries` throw `throwDecodeError(path, …)` directly.
- **`install/staging/InstallStagingPrune.kt:79`**, **`install/staging/InstallStagingAtomicMoves.kt:53` and `:67`**, **`install/scaffold/ScaffoldRollbackBridge.kt:25`** and **`scaffold/runtime/service/ScaffoldServiceRollback.kt:87`**
  - No ISE source is visible: `deleteInstallStagingDirectory` and `rollbackDeleteEmptyDirectory` iterate `Files.walk` and `Files.list`.
  - Replace each ISE arm with `UncheckedIOException`, which keeps the logging and error accumulation for real I/O failures.
  - Drop the arm wherever no stream is iterated.
- **`scaffold/authoring/AuthoringDiscovery.kt:49`** and **`AuthoringMutation.kt:60`**: rollback-and-rethrow becomes a success flag with `finally { if (!committed) rollback… }`.
- **`nativeagent/rendering/NativeAgentOperations.kt:161` and `:163`**
  - Use `Closeable { deleteNativeAgentRenderStaging(staging) }.use { stageAndPromote… }`. This keeps the initiating-failure-plus-suppressed-cleanup order and removes the touched `runCatching`.
  - Cleanup now also runs on failures it used to skip. That is a strict improvement.
- **`scaffold/runtime/service/ScaffoldServicePlanningPayloadMerge.kt:141`**: use `AgentAddonConsumer.fromIdOrNull`, and keep `"Unknown agent add-on consumer…"` exactly. Also convert `AuthoringRenderOutput.kt:110`.
- **`nativeagent/composition/NativeAgentBundle.kt:17`**: the `require`s in `parseValidatedNativeAgentBundle`, `requireSupportedKeys` and the entry parsers throw `InvalidNativeAgentCompositionSchemaError(sourceLabel = path, reason = <same text>)` or its code directly. Check whether `invalidBundle` itself throws IAE.
- **`scaffold/validation/review/ReviewSkillStructureValidatorContent.kt:92`**: catch the composition schema failure or its code that `parseNativeAgentBundle` now throws, instead of IAE.
- **`validateReleaseRef` (SKILL-392 follow-up, AC-003)**
  - Change `RepoValidationGateway.validateReleaseRef` (`runtime-ports/.../ports/validation/`) to return a sealed `ReleaseRefValidation { Valid(metadata); Rejected(message) }` in `ports/validation/model`.
  - In `RepoValidationRuntimeReleasePolicy`:
    - Add a non-throwing `parseReleaseRefOrNull`, keeping `"Release tag must match canonical vMAJOR.MINOR.PATCH with optional SemVer prerelease/build metadata."`.
    - The force-prerelease check and `RepoValidationRuntimeReleasePolicyGate` return `Rejected` with their current texts.
  - `FileSystemRepoValidationGateway` and `RepoValidationRuntime.validateReleaseRef` pass the result through.
  - Delete `ReleaseLicensePolicyError` and its baseline row if this removes its last use (Ground rule 10).

### Task 7: CLI, 10 sites (AC-001, AC-003, AC-004)

The CLI sites are in `runtime-cli/.../cli/`.

**`featuretask/FeatureTaskRuntimeRunRequestAssembly.kt:142`** and **`goal/run/GoalCliRunCommands.kt:94`**

- Write each as `RuntimeOwnedReviewMode.parse(raw) ?: throw UsageError(RuntimeOwnedReviewMode.unknownModeMessage(raw))`.
- Both stop calling `usageError(error)`, so their wrap-and-`initCause` path is gone.
- The shared `usageError` in `kernel/cli/DocumentedCliCommand.kt` stays for its other callers (`OperationCommand`, `PhaseCommand`, `CodeReviewCommand`), which don't catch IAE.

**`repovalidation/RepoValidationCliCommands.kt:116`**: `when` over `ReleaseRefValidation`. `Rejected` produces exactly today's JSON payload `{status: failed, error: message}` or the text `"$message\n"`, with exit code 1.

**`install/core/InstallCliCommands.kt:215`**

- Replace the `require(staleSlugs.isEmpty())` with an explicit branch that calls the same `completeText("Saved install selection references unavailable platform pack slug(s): ….\n", emptyMap(), exitCode = 1)`.
- Drop the IAE arm.
- If the census finds other input IAE sources in the `try` body, convert them to the coded failures that the sibling `SkillBillRuntimeException` arm already prints.

**`scaffold/payload/NativeScaffoldPayloadRun.kt:39`, `:58`, `:158`, `:174`** and **`scaffold/wizard/ScaffoldWizardRun.kt:41`**

- Census the reachable IAE sources:
  - `decodeScaffoldPayloadObject`, which now returns null and is mapped to the constant message in both `runPayload` and `ScaffoldPayloadInputs.readScaffoldPayload`.
  - `readScaffoldPayloadText`'s `"--payload is required for this command."`.
  - `Path.of` in `readCliTextFile`, which throws `InvalidPathException`.
  - The wizard prompt and normalization `require`s (`ScaffoldWizardPrompts`, `ScaffoldWizardValueNormalization`).
  - The scaffold infra `require`s on payload values (`ScaffoldService*`, `PointerOperations`, `PointerRendering`, `FileSystemScaffoldGateway`).
- Convert each input source to the scaffold payload failure code (`InvalidScaffoldPayloadError`, or what subtask 4 made it). The sibling `SkillBillRuntimeException` arm already prints that through `completeScaffoldError` with the same text and exit code.
- Handle `InvalidPathException` with a narrow catch that calls the same `completeScaffoldError`.
- MCP check (Ground rule 1): the scaffold infra sources are also reached through MCP `new_skill_scaffold`.
  - Before converting them, confirm the scaffold code is on `McpToolDispatcher`'s no-capture arm. Today's IAE path doesn't capture.
  - If it is not, don't change MCP telemetry. Report it as an implementation obstacle.

**`kernel/agent/AgentAddonSelectionParsing.kt:67`**: apply the Task 2 validators before construction, and call `invalidAgentAddonSelection("Invalid agent add-on selection: <violation>")`.

### Task 8: tests (AC-003, AC-004; validation strategy)

**Add:**

1. A CLI test for the review mode. Run `goal` (or the phase-agent command) with `--code-review-mode delegated`. Assert the clikt `UsageError` stderr text containing `"Unknown code-review execution mode 'delegated'. Allowed: auto, inline."` and its exit code, using the existing CLI test harness. Bug it catches: the null branch maps to the wrong message, or falls through to the CliRuntime arm.
2. A CLI test for the scaffold payload. Use a scaffold payload command with a `--payload` file containing `[]`. Assert `"Invalid JSON payload: expected an object."` through `completeScaffoldError`, with the exit code and output stream unchanged.
3. A repoTest for the release ref, next to the existing tests in `runtime-cli/src/repoTest/.../cli/CliRepoValidationRuntimeTest.kt`. Run `validate-release-ref not-a-tag` in text format and assert exit 1 and stdout `"Release tag must match canonical vMAJOR.MINOR.PATCH with optional SemVer prerelease/build metadata.\n"`. The existing license-policy test covers the `Rejected` policy branch.
4. A heartbeat recovery regression test, only if the Task 6 arm type changed. A heartbeat that throws the coded persistence failure is reported and rescheduled, and does not stop renewing. This guards worker liveness.
5. One invalid-input test for each new non-throwing validator that a former catch depended on, but only where no existing test already drives that invalid branch through its decoder or caller. Existing tests that assert the schema text count as the coverage, and the audit lists them.

   Likely gaps:
   - `decodeParallelReviewStructuredStringOrNull`: a malformed `\u` escape gives `UNPARSEABLE_STRUCTURED_PATH`.
   - `repositoryRelativePathViolation`: a traversing path gives `NO_ADMISSIBLE_LOCATION`.
   - `FeatureTaskRuntimeRunInvariantsRead.Rejected`: `PhaseRunIntakeResolver` returns null for an unreadable spec token.
   - `TelemetrySettingsLoad.Unavailable`: `telemetrySettingsOrNull` returns null and records.
   - The `AgentAddonSelection` duplicate-slug violation.

   Write one test per rule, with no sibling tests that repeat a branch using different literals.

**Edit:** only change exception types, never expected text:

- `RuntimeOwnedReviewModeTest`: `assertFailsWith<IllegalArgumentException>` becomes `assertNull(parse(value))` plus an `assertEquals` on `unknownModeMessage(value)`.
- Tests of `decodeScaffoldPayloadObject`, `parseReleaseRef`/`validateReleaseRef`, `readTelemetryConfigFile`, and any `cause is IllegalArgumentException` assertion.
- Test fakes for the changed ports: `TelemetryConfigStore`, `TelemetrySettingsProvider`, `FeatureTaskRuntimeRunInvariantsSource` and `RepoValidationGateway`.

**Don't add:**

- Tests for kind-D arm removals.
- Tests for library narrowing.
- Tests for `finally`/`use` refactors, where existing rollback tests already drive the failure path.
- Mock-interaction tests.

### Task 9: completeness checks (no gate runs here)

1. The Task 1 grep returns hits only in `cli/core/CliRuntime.kt` and `mcp/core/McpToolDispatcher.kt` (AC-001).
2. `grep -rn "catch (\w*: NumberFormatException)"` returns nothing in main.
3. Every `runCatching` over a converted validator is gone (AC-002).
4. Grep the branch diff for edits to string literals in test sources other than exception-type and code assertions (AC-004).
5. The throwable baseline has no rows for deleted classes, and no new throwable was declared.
6. Build, unit tests, detekt and the runtime-core repoTest suite, including `TypedParseBoundaryArchitectureTest` and `FailureCodeTotalityArchitectureTest`, belong to the build and validate phases (AC-005).

### Constraints

- No installer or install-sync commands.
- No edits to `CliRuntime.kt` or `McpToolDispatcher.kt` (F-008).
- No repo-wide `runCatching` sweep (F-007).
- Leave `require`/`check` that guard true invariants (no input can break them) as they are.
- `CancellationException` and `InterruptedException` keep propagating wherever they do today.
- Ownership placement from SKILL-374 and SKILL-391 stays.
- No phase-specific branches in the SKILL-380 generic runner.
- Files stay under the 1,200-line ceiling.
