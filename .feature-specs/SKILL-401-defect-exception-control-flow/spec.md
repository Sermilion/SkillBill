# SKILL-401 - defect-exception-control-flow

Issue key: SKILL-401
Origin: split out of SKILL-398 subtask 6 (`../done/SKILL-398-runtime-exception-reduction`) on 2026-10-02, after that subtask's implement phase blocked as too large. Investigation: `../done/SKILL-398-runtime-exception-reduction`, finding F-006.

## Outcome

`IllegalArgumentException` (IAE) and `IllegalStateException` (ISE) mean code defects. Outside `runtime-cli/.../core/CliRuntime.kt` and `runtime-mcp/.../core/McpToolDispatcher.kt`, no main code catches or `is`-checks them for control flow. Each site is handled by kind:

- Input validators get a non-throwing form, and callers branch on it.
- Library calls use their non-throwing form, or a catch narrowed to the documented subtype.
- Defect-only arms are dropped.
- Rollback catches become `finally` or `use`.

SKILL-398 subtask 6 keeps the SKILL-392 follow-up: `RuntimeOwnedReviewMode.parse`, `decodeScaffoldPayloadObject`, `validateReleaseRef` and their CLI sites. It also keeps the `CodeReviewExecutionMode`, `ValidationDepth`, `parsePersistedInstantOrNull` and `repositoryRelativePathViolation` validators. This bundle converts the catch sites.

Census on `feat/SKILL-398-runtime-exception-reduction` at `b29f42907` (after SKILL-398 subtask 5), with `catch \(\w+: (IllegalStateException|IllegalArgumentException|NumberFormatException)\)|is (IllegalArgumentException|IllegalStateException)\b` over `src/main`, excluding the two edge files:

| Area | Sites | Subtask |
|---|---|---|
| runtime-domain goalrunner, review, workflow/model | 11 | 1 |
| runtime-domain workflow/taskruntime | 16 | 2 |
| runtime-engine, runtime-ports, runtime-contracts (+ the run-invariants port) | 8 | 3 |
| config and telemetry reads: runtime-application config/telemetry, infra-host `FileTelemetryConfigStore`, infra-skills config stores | 4 + 5 | 4 |
| runtime-application review admission and legacy control migration; infra host, http, contracts, sqlite, workflow | 5 + 21 | 5 |
| runtime-infra/skills install, rollback, authoring, native agents | 15 | 6 |
| runtime-cli install, scaffold, wizard, agent add-on selection (+ scaffold infra input `require`s) | 6 | 7 |

## Shared validators

These are the non-throwing forms the catch sites depend on. A subtask that needs one and finds it missing adds it exactly as written here; a subtask that finds it present keeps it. SKILL-398 subtask 6 adds the first four, and the rest come with the subtask that first needs them.

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
  - Covers SKILL-398 subtask 6 AC-003.
- **`decodeScaffoldPayloadObject(payloadText)`** (`runtime-application/.../application/scaffold/ScaffoldCommandRequestDecoder.kt`)
  - Returns `JsonObject?`.
  - Add a `const val` holding `"Invalid JSON payload: expected an object."` (SKILL-398 subtask 6, AC-003).
  - `decodeScaffoldCommandRequest(payloadText)` is reached through MCP `ScaffoldInvocation` and is handled only at the edge, so it keeps throwing IAE with that same constant.
- **Any other model `init { require }`** that a catch site in this bundle depends on gets the same companion `violation(...)` treatment.
  - Expected ones include `RejectedOutputDiagnostic`, `ReviewContextBudgetPolicy`, `ReviewFindingCitation`, `ReviewLaneSegmentAccounting`, and the evidence, branch, handoff, checkpoint and gate-progress models listed in Task 3.
  - The census confirms which ones.

## Ground rules (every subtask)

Paths are relative to `runtime-kotlin/`, and `.../` stands for `src/main/kotlin/skillbill/`.

1. **Edge invariance (AC-003).** For a given input, the exception type that reaches `CliRuntime.run` or `McpToolDispatcher.dispatch` must not change.
   - `CliRuntime` prints `oneLine(message)` for both the `IllegalArgumentException` (IAE) arm and the `SkillBillRuntimeException` arm. The two differ only in the fallback used when the message is blank.
   - `McpToolDispatcher` skips telemetry capture for IAE and `IllegalStateException` (ISE). It also skips capture for `SkillBillRuntimeException` codes on its no-capture list. For every other code it calls `captureException`, which writes a persisted telemetry row.
   - So a failure that reaches MCP today as IAE must still reach it as IAE, or as a code on that no-capture list. Check the list as `McpToolDispatcher.kt` stands when the subtask runs (`uncapturedAtMcp()` once SKILL-398 subtask 5 has landed).
   - A `require` or IAE whose only handler is a top-level arm may stay. F-008 excludes those arms, and AC-002 targets validators that a *caller* catches.
2. **Message invariance (AC-003).**
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
8. **detekt limits** (AC-004): `ReturnCount` 4, `ThrowsCount` 2, `CyclomaticComplexMethod` 15, `LongMethod` 70, plus `SwallowedException`. Don't use `@Suppress`. Prefer `when` expressions and small helpers over early-return chains.
9. **Parse-boundary guard** (AC-004). `TypedParseBoundaryArchitectureTest` checks the `ParseBoundarySite` entries in `runtime-core/src/repoTest/.../architecture/PrincipleEnforcementInventory.kt`, roughly lines 305 to 630.
   - A listed function keeps its name. If it must be renamed, update its entry in the same commit; never drop the entry.
   - A listed function gains no `error()`, `require` or bare `throw` for input.
10. **Earlier subtasks.**
    - Where SKILL-398 subtasks 4 or 5, SKILL-399 or SKILL-400 turned a rethrown schema class into `SkillBillRuntimeException(code, …)`, throw that code from the validation result.
    - When this subtask removes the last use of an IAE-based custom class, delete the class and its row in `runtime-core/src/repoTest/.../baselines/custom-throwable-baseline.txt`. Examples: `ReleaseLicensePolicyError : IllegalArgumentException`, or any `Invalid*Error` that still extends IAE.
    - Classes still owned by a live SKILL-398 subtask-3 or subtask-5, SKILL-399 or SKILL-400 decision are left as they are.

## Site classification (every subtask)

For each site in the subtask's scope, re-run the census, follow the calls in the `try` body, and list every IAE or ISE source. Then tag it:

- **A**: the runtime's own validator on input. This includes model `init { require }`, throwing `fromWire`, `parsePersistedInstant` and `requireRepositoryRelativePath`.
- **B**: a library call that has a non-throwing form, such as `toInt` or `valueOf`.
- **C**: a library call with no non-throwing form.
- **D**: reachable only by a defect. Drop the arm.
- **E**: a rollback or cleanup catch.
- **F**: CLI-local handling.

For every validator that becomes non-throwing, also grep its `runCatching { … }` callers; AC-002 covers those too. Each subtask spec gives the expected kind per site. Where the census disagrees, apply the rule for the kind it finds.

## Test rules (every subtask)

- Add one invalid-input test per new non-throwing validator that a former catch depended on, only where no existing test already drives that invalid branch through its decoder or caller. Existing tests that assert the schema text count as the coverage, and the audit lists them. One test per rule, with no sibling tests that repeat a branch using different literals.
- Edit tests only to change exception types, never expected text. Update the test fakes of any port the subtask changes.
- Don't add tests for kind-D arm removals, for library narrowing, for `finally`/`use` refactors that existing rollback tests already drive, or mock-interaction tests. Do not use `relaxed = true` mocks or `environment = emptyMap()`.

## Execution Rule

Every subtask runs on the current tree and waits for no other subtask or issue. It applies its rule to the anchors present when it runs:

- where a site is already converted, it keeps the conversion;
- where a site moved, it edits it where it is now;
- on a shared file, whichever subtask lands second keeps both edits;
- a missing shared validator is added as written above.

Line numbers come from the census at `b29f42907`; apply each rule to the code wherever it is now.

## Subtasks

1. `spec_subtask_1`: domain goalrunner, review and workflow/model decoders.
2. `spec_subtask_2`: domain workflow/taskruntime decoders.
3. `spec_subtask_3`: engine, ports and contracts, including the run-invariants port result.
4. `spec_subtask_4`: config and telemetry reads, including the telemetry config and settings port results.
5. `spec_subtask_5`: application review admission and legacy control migration, plus infra host, http, contracts, sqlite and workflow.
6. `spec_subtask_6`: infra skills install, rollback, authoring and native agents.
7. `spec_subtask_7`: CLI install, scaffold, wizard and agent add-on selection.

Split reason: the single subtask blocked as too large to implement in one phase that cannot compile. The slices follow modules, and each port reshape lands with every caller and fake it touches. No subtask depends on another.

## Acceptance Criteria

The feature is done when every subtask's criteria hold. Together:

1. Outside `CliRuntime.kt` and `McpToolDispatcher.kt`, no main source catches or `is`-checks `IllegalArgumentException` or `IllegalStateException`. The only remaining catches in their family are of a specific library subtype (for example `SerializationException`, `InvalidPathException`) at a call into a JVM or library API that has no non-throwing form. `NumberFormatException` is not caught where an `...OrNull` function exists.
2. No main function validates external input with `require`/`check` and relies on a caller catching it; each such validator has a non-throwing form or a result the caller branches on.
3. Every user-visible message and every persisted byte is unchanged; existing tests pass with only exception-type assertion edits where a validator now returns a value.
4. `TypedParseBoundaryArchitectureTest` and detekt pass.

## Non-Goals

- The SKILL-392 follow-up and the four validators SKILL-398 subtask 6 keeps.
- Changing `CliRuntime.kt` or `McpToolDispatcher.kt` classification (F-008).
- The repo-wide `runCatching` sweep (F-007).
- Replacing `require`/`check` that guard true invariants.

## Constraints

- No installer or install-sync commands.
- No edits to `CliRuntime.kt` or `McpToolDispatcher.kt` (F-008).
- `CancellationException` and `InterruptedException` keep propagating wherever they do today.
- Ownership placement from SKILL-374 and SKILL-391 stays. runtime-domain stays free of `java.nio` and ports imports. Ports hold declarations only (`PortsDeclarationArchitectureTest`, SKILL-393).
- No phase-specific branches in the SKILL-380 generic runner.
- Files stay under the 1,200-line ceiling.
- Spotless runs in a plain clone, not a linked worktree. Authored Kotlin carries no `//` or non-KDoc block comments.

## Validation Strategy

Goal gates: build, unit tests, detekt and the runtime-core repoTest suite, including `TypedParseBoundaryArchitectureTest` and `FailureCodeTotalityArchitectureTest`. Each subtask names its behavioural tests.
