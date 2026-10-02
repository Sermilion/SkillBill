# SKILL-398 Subtask 4 - collapse-shell-content-errors

Parent spec: [.feature-specs/SKILL-398-runtime-exception-reduction/spec.md](spec.md)
Issue key: SKILL-398

## Scope revision (2026-10-02)

The first implement attempt blocked with no changes: all 97 shell-content classes, about 1,180 main reference lines and about 820 `assertFailsWith` sites were too much for one implement phase. This subtask now does two things:

- it lays the shared transition pieces that every shell-content conversion needs;
- it converts the two smallest areas, `AgentAddonShellContentErrors.kt` and `GovernedReviewShellContentErrors.kt`.

The other seven areas (FeatureTaskRuntime, Install, Manifest, ReviewContext, Scaffold, SkillStaging and Workflow) moved to the follow-up bundle `../../SKILL-399-shell-content-error-codes`. Leave their classes, throw sites, catch sites and tests unchanged here. `spec.md` was not changed, so the shared preplan's parent-spec hash stays valid.

Census on `feat/SKILL-398-runtime-exception-reduction` at `f9e4df35d`:

| Area | Classes | Main reference lines / files | catch, `is`, `as?` | `assertFailsWith` |
|---|---|---|---|---|
| AgentAddon | 6 | 46 / 12 | 2 | 27 |
| GovernedReview | 4 | 24 / 7 | 1 | 10 |
| `ShellContentContractException` edge | – | – | 36 | 2 |

## Scope

(F-005, first slice.) Follow the parent spec's "Target failure model".

1. **Shared transition pieces.** They are needed before any shell-content class is converted, because a plain `SkillBillRuntimeException` from a converted class would escape every `catch (e: ShellContentContractException)` and would render as `SkillBillRuntimeException` wherever a class name is rendered.
   - In `skillbill.error.core`, add `fun SkillBillRuntimeException.rethrowUnless(handled: Boolean): SkillBillRuntimeException`. It throws `this` when `handled` is false and returns `this` otherwise. Catch sites rethrow through it, which keeps them under detekt `ThrowsCount` 2 without `@Suppress`.
   - In `skillbill.error.shellcontent`, add `ShellContentContractFailures.kt` with `fun Throwable.isShellContentContractFailure(): Boolean`. It is true when `this is ShellContentContractException`, or when `this` is a `SkillBillRuntimeException` whose `code` is a `FailureWireCode`, or whose `code` is one of the shell-content area code enums that exist on the tree. When this subtask lands, the area enums are `AgentAddonFailureCode` and `GovernedReviewFailureCode`. Each later area conversion adds its enum to the check. `ScaffoldFailureCode` is never added, because scaffold errors never extended `ShellContentContractException`.
   - In `skillbill.error.core`, add `fun Throwable.failureCodeLabel(): String?`. It returns `"<CodeEnumSimpleName>.<ENTRY>"` for a `SkillBillRuntimeException` whose code is not `LegacyFailureCode`, and `null` otherwise. If subtask 5 already added `RuntimeFailureCode.failureTypeName()`, build the label from it rather than duplicating the format.
   - Every main site that renders a caught throwable's class name (`::class.simpleName`, `::class.qualifiedName`, `javaClass.name`) uses `failureCodeLabel() ?: <existing expression>`. Output for uncoded throwables stays byte-identical. Sites at `432d427c8`:
     - telemetry: `RuntimeExceptionTelemetry.kt:23` `error_type`, `ExternalPlatformPackTelemetryPolicy.kt:15`, `FeatureTaskRuntimeLifecycleTelemetryEmission.kt:93`;
     - install apply `causeClass` (9 sites);
     - `errorType=` diagnostics: `SchemaLoadFailureLogging`, `GoalRunnerObservabilityEmitter`, `GoalRunnerProgressEventEmitter`, `GoalRunnerLedgerRecorder`;
     - `"${simpleName}: ${message}"` reasons: `GoalPlanningSweepOutcomeDerivationTerminalClass.kt:37`, `CodeReviewStep.kt:402`, `ReviewServiceLaneComposition.kt:36`, `FileSystemFeatureTaskRuntimeSharedEvidenceStoreReads.kt:174-219`, `ParallelCodeReviewRunnerFailureAdmission.kt:193`, `PlatformPackSubstanceAuditCoreFns.kt:82`, `InstallStaging.kt:218-232`, `SkillRemove.kt:136`, `GoalPlanningRejectionRecorder.kt:41`;
     - the `cause.message ?: cause::class.simpleName` fallbacks.
   - Update the `error_type` row in `../../../docs/telemetry-privacy.md` to say "exception class simple name, or the failure code label for coded runtime failures".
   - **Edge sites.** Every `catch (e: ShellContentContractException)` and every `is ShellContentContractException` arm in main becomes a `SkillBillRuntimeException` catch or arm guarded by `isShellContentContractFailure()`. Other `SkillBillRuntimeException` codes are rethrown, so each site handles exactly the failures it handled before. Sites at planning time:
     - CLI: `PhaseCommand`, `AgentAddonCliCommands`, `InstallApplyExternalAddonsCommand`, five `Config*` commands, `CodeReviewCommand`;
     - MCP: `McpToolDispatcher.kt:27`. Shell-content failures stay in the no-telemetry-capture arm, and every other `SkillBillRuntimeException` keeps `recordCaptureFailure`;
     - engine: `GoalRunnerPauseBoundary.kt:31`, `GoalRunnerStatusProjectionAssembler.kt:360`, `IdeStatusProjector.kt:225`, `ValidationGateResolver.kt:44`;
     - infra: launcher, contracts, sqlite, http and skills;
     - application: `ReviewServiceLaneComposition.kt:33`.

     Where such a catch precedes a `SkillBillRuntimeException` catch (`InstallStaging.kt:201/204`, `AuthoringMutation.kt:54/87`), keep the order: predicate branch first, then the generic one. The CLI per-command catches keep printing on stdout for exactly the failures they caught before.
   - Leave `ShellContentContractException`, `LegacyFailureCode` and the secondary constructor in place, and keep `SkillBillRuntimeException` `open`. Subtask 5 or the follow-up bundle retires them. Function types such as `() -> ShellContentContractException` stay unless an AgentAddon or GovernedReview throw flows through them.

2. **AgentAddon and GovernedReview areas.** In each of the two files, replace the classes with codes:
   - `AgentAddonFailureCode`: INVALID_SCHEMA, MISSING_DECLARATION, INVALID_SELECTION, SELECTION_DRIFT, and a family entry for delivery target and pointer collision.
   - `GovernedReviewFailureCode`: ledger schema, evidence transport, inline parallel unsupported, launch capability. `UnaddressedFindingsLedgerAbsentError` belongs to subtask 3; if it still exists, leave it.
   - The entry rule: one entry per class that main code discriminates or a test asserts; the others share the area's family entry. Each enum lives in the same package and implements `RuntimeFailureCode`.
   - A message function sits next to the enum for each class thrown from more than one site. It returns `SkillBillRuntimeException` and takes the old constructor's parameters, including `cause`. A class thrown at one site is inlined as `SkillBillRuntimeException(CODE, "<same text>", cause)`. Message-only classes are inlined as `SkillBillRuntimeException(CODE, message, cause)` at every site. Message text is copied verbatim.
   - Classes that only a code defect can trigger become `require`/`check`/`error()` with the same message. None is expected; when unsure, keep the code.
   - Throw sites: every `throw`, and every returned or constructed instance of the 10 classes, uses the function or the coded constructor. Lambdas typed as returning one of them become `SkillBillRuntimeException`.
   - Catch, `is` and `as?` sites: `catch (e: FormerClass)` becomes `catch (e: SkillBillRuntimeException) { e.rethrowUnless(e.code == X) … }`, and `is`/`as?` become code checks on `(error as? SkillBillRuntimeException)?.code`. Known sites: `AgentAddonSchemaValidator.kt:78`, `AgentAddonSourceOperation.kt:19`. No main code reads a typed property from a caught failure of these areas other than `code`, `message` and `cause`. A value a reader needs moves into a returned result (tier 2) or into the message.
   - Tests: `assertFailsWith<FormerClass>` becomes `assertFailsWith<SkillBillRuntimeException>` plus `assertEquals(<Code>.<ENTRY>, error.code)`. Message, `contains`, payload and exit-code assertions stay byte-for-byte. Tests that construct former classes switch to the message function or the coded constructor.
   - Delete the two areas' classes, and remove their rows from `../../../runtime-kotlin/runtime-core/src/repoTest/kotlin/skillbill/architecture/baselines/custom-throwable-baseline.txt`. Row format is `module:Class`; compare whole rows and edit no other row.

3. **Docs and decision.** Add a newest-first entry to `../../../runtime-kotlin/agent/decisions.md` recording three choices: coded failures render their code label where a class name was rendered; `isShellContentContractFailure` is a transitional classification that the retirement of `ShellContentContractException` removes; and the shell-content conversion was split into SKILL-398 subtask 4 plus SKILL-399.

## Acceptance Criteria

1. `AgentAddonShellContentErrors.kt` and `GovernedReviewShellContentErrors.kt` declare no class except `UnaddressedFindingsLedgerAbsentError` if subtask 3 has not removed it. They hold only code enums and message functions.
2. Each former AgentAddon and GovernedReview failure throws `SkillBillRuntimeException` whose `code` is an entry of `AgentAddonFailureCode` or `GovernedReviewFailureCode`, or fails through `require`/`check`/`error()` where only a code defect can trigger it.
3. `rethrowUnless`, `isShellContentContractFailure` and `failureCodeLabel` exist as specified. No main `catch`, `is` or `as?` on `ShellContentContractException` remains without the `isShellContentContractFailure()` guard.
4. Every user-visible message and every rendered class name for an uncoded throwable is byte-identical. No expected-output, wire-fixture or payload assertion is edited, other than replacing an exception-type assertion with a code assertion.
5. No main code reads a typed property from a caught failure of the two converted areas, and no main source declares a typealias named after a deleted class.
6. `custom-throwable-baseline.txt` lists none of the deleted classes, and `FailureCodeTotalityArchitectureTest` passes.
7. The other seven `*ShellContentErrors.kt` files are unchanged.

## Non-Goals

- The FeatureTaskRuntime, Install, Manifest, ReviewContext, Scaffold, SkillStaging and Workflow areas (SKILL-399).
- Classes outside `skillbill.error.shellcontent` (subtask 5), and the classes named in subtasks 2 and 3.
- Retiring `ShellContentContractException`, `LegacyFailureCode` or the secondary constructor.
- Renaming the `skillbill.error.shellcontent` package (SKILL-372 retention).
- The CLI and MCP top-level arms (F-008).

## Dependency Notes

Depends on: none. Subtask 1's pieces are on the branch (`RuntimeFailureCode`, coded `SkillBillRuntimeException`, `LegacyFailureCode`, the secondary constructor, the baseline). If any is missing, add it as the parent spec defines it. If subtask 5 already widened some edge sites, keep its edits and guard only what remains.

## Validation Strategy

Goal gates: build, unit tests, detekt and the runtime-core repoTest suite. Existing tests pass with type-to-code assertion edits only. Add one test for `McpToolDispatcher`, unless an existing MCP test already covers both arms: a coded AgentAddon failure stays in the no-capture arm, and a non-shell-content `SkillBillRuntimeException` still records a capture failure. Realistic bug: the predicate misses an area enum and the failure starts reporting telemetry. Add no other predicate or factory unit tests.

## Constraints

- No new module, dependency, `Result`/`Either` library, typealias for a deleted class, property on `SkillBillRuntimeException`, family metadata on codes, `@Suppress` or new `runCatching`.
- Cancellation and interruption keep propagating.
- The per-module package-cycle guard forbids `skillbill.error.core` from importing `skillbill.error.shellcontent`, so `isShellContentContractFailure` lives in shellcontent and `rethrowUnless`/`failureCodeLabel` in core.
- runtime-domain stays free of `java.nio` and ports imports. runtime-contracts declares no engine, infra or MCP code.
- detekt limits: `ThrowsCount` 2, `ReturnCount` 4, `LongMethod` 70, `CyclomaticComplexMethod` 15. `ArchitectureScanSupport.kt` must not grow.
- Spotless runs in a plain clone, not a linked worktree.

## Implementation Details

Census taken on `feat/SKILL-398-runtime-exception-reduction` at `f9e4df35d`, after subtasks 1–3. Line numbers are from that tree. Apply each rule to the code wherever it sits when implement runs.

### Facts the plan rests on

- **Subtask 1's pieces are in place**, so nothing needs adding. `RuntimeExceptionBases.kt` has `RuntimeFailureCode`, `LegacyFailureCode.UNCLASSIFIED`, an open coded `SkillBillRuntimeException` with the `(message, cause)` secondary constructor, and `ShellContentContractException`. The baseline file exists.
  - The three `FailureWireCode` enums in `skillbill.error.featuretask` and `DecompositionManifestValidationFailureCode` already implement `RuntimeFailureCode`.
  - `failureTypeName()` does not exist, so `failureCodeLabel` builds its own format.
- **Subtask 3 already removed `UnaddressedFindingsLedgerAbsentError`.** It added `GovernedReviewFailureCode { UNADDRESSED_FINDINGS_LEDGER_ABSENT }` to `GovernedReviewShellContentErrors.kt`, and `GoalCliRunCommands.kt:271` throws it. Keep both, and add the new entries to that enum.
- **Main throw and construct sites of the 10 classes** (nothing else references them):
  - AgentAddon:
    - `infra/skills/agentaddon/`: `AgentAddonSchemaValidator.kt:17, 51, 58, 79, 86`; `AgentAddonSourceOperation.kt:20, 27`; `AgentAddonSourceLoader.kt:69`; `AgentAddonSelectionResolver.kt:31, 42, 92, 95, 107, 118, 124, 136`; `AgentAddonSelectionResolverPersisted.kt:56, 67`; `AgentAddonDeliveryResolver.kt:41, 61, 101`.
    - Elsewhere in infra/skills: `install/staging/content/InstallStagingIdentity.kt:53` and `install/scaffold/FileSystemScaffoldGateway.kt:260`.
    - Other modules: `infra/sqlite/.../LegacyGoalRunnerControlLedgerMigration.kt:145`; `application/.../LegacyGoalRunnerControlMigration.kt:113`; `engine/.../GoalRunnerWorkflowFamilyLookup.kt:35, 47, 57, 85`; `engine/.../GoalPreflightGateBlockBuilder.kt:52`.
  - GovernedReview:
    - `infra/launcher`: `agentrun/AgentRunCommandBuildersLaunch.kt:31, 34`; `mcp/GovernedReviewMcpConfigWriter.kt:23, 64`; `review/GovernedReviewEvidenceEndpoint.kt:71, 289, 296, 333`.
    - `mcp/review/GovernedReviewEvidenceConnection.kt:49, 61, 66, 94`.
    - `engine/goalrunner/findings/UnaddressedFindingsLedgerService.kt:34, 92, 112`.
    - `application/.../ParallelCodeReviewRunner.kt:152`.
- **Main type-discrimination sites of the 10 classes:** `AgentAddonSchemaValidator.kt:78` and `AgentAddonSourceOperation.kt:19` (`is InvalidAgentAddonSchemaError -> this`), and `ResolveUnaddressedFindingsLedger.kt:13` (`catch (_: InvalidUnaddressedFindingsLedgerSchemaError) { null }`).
  - No main code reads `sourceLabel`, `reason`, `slug`, `expectedRoot`, `target`, `pointerName`, `sourceIdentity`, `requestedMode`, `provider` or `capability` from a caught failure, so AC-005 needs no result type.
- **Function types an AgentAddon throw flows through.** `AgentAddonSchemaValidator` passes `identityFailure` to `SchemaIdentityRequest` and `processingFailure` to `ClasspathContractSchemaLoader.compiledSchemaFromYamlNode`. Both are typed `-> ShellContentContractException` (`ClasspathContractSchemaLoader.kt:48`, `:115`).
  - Widen exactly those two to `-> SkillBillRuntimeException`. Callers that pass `ShellContentContractException` lambdas still compile, because function return types are covariant.
  - Leave every other `() -> ShellContentContractException` / `(…) -> ShellContentContractException` type alone: `ValidatedClasspathYamlNodeRequest`, `CompiledSchemaRequest`, `readClasspathYamlText` / `readClasspathYamlNode`, `ContractValidatorWireInput.kt:26`, `FeatureTaskRuntimeHandoffFoundationSchemaValidators.kt:42`, and the repoTest `PlatformPackSchemaCleanupTest.kt:361`.
- **Main `ShellContentContractException` discrimination sites: 49.**
  - Catch sites:
    - launcher `GovernedReviewEvidenceEndpoint.kt:194, 265, 293`
    - contracts `ClasspathContractSchemaLoader.kt:99, 160`, `review/ReviewContextSchemaValidator.kt:355`
    - sqlite `ReviewStatsArithmetic.kt:44`, `WorkflowStateSqlReads.kt:171`, `ReviewHealthEmbeddedPayloads.kt:15`, `LifecycleTelemetryPayloads.kt:154, 181`, `GoalTelemetryPayloads.kt:40`
    - http `GitHubReleaseCatalogAdapter.kt:54`, `HttpTelemetryClient.kt:189`
    - skills `InstallStaging.kt:148, 201`, `NativeAgentLinkInventoryReconcile.kt:52`, `NativeAgentLinkInventoryDecode.kt:31`, `NativeAgentLinkInventoryWrite.kt:54`, `RepoValidationRuntimeRepoChecks.kt:50`, `RepoValidationRuntimeRepoChecksManifest.kt:28, 110`, `RepoValidationRuntimeSkillDiscovery.kt:109`, `AuthoringMutation.kt:87`, `PointerValidationReport.kt:52`, `GeneratedArtifactGuardReport.kt:155`, `GovernedSkillDriftReport.kt:112`
    - application `ReviewServiceLaneComposition.kt:33`
    - CLI `PhaseCommand.kt:95`, `AgentAddonCliCommands.kt:72, 113`, `InstallApplyExternalAddonsCommand.kt:44`, `ConfigResolveExternalPlatformPacksCommand.kt:36`, `ConfigRegisterExternalPlatformPackCommand.kt:57, 79`, `ConfigResolveExternalAgentAddonsCommand.kt:28`, `ConfigUnregisterExternalPlatformPackCommand.kt:39`, `ConfigResolveExternalAddonsCommand.kt:25`, `ConfigCommand.kt:66`, `CodeReviewCommand.kt:260`
    - engine `GoalRunnerStatusProjectionAssembler.kt:360`, `IdeStatusProjector.kt:225`, `ValidationGateResolver.kt:44`
  - `when` arms: `McpToolDispatcher.kt:27` and `GoalRunnerPauseBoundary.kt:31`.
  - No `as? ShellContentContractException` exists in main.
- **Sibling-catch hazard.** A `rethrowUnless` throw from inside a catch clause skips the later catch clauses of the same `try`. Before this change, a non-shell `SkillBillRuntimeException` skipped the `ShellContentContractException` clause and reached the next clause that matched it.
  - Only one edge site has a later sibling catch that a `SkillBillRuntimeException` matches: `InstallStaging.kt:201`, followed by `catch (error: SkillBillRuntimeException)` at `:204` with an identical body.
  - Every other edge site's later siblings are `IOException`, `JsonProcessingException`, `IllegalArgumentException` or `CancellationException`, which `SkillBillRuntimeException` never matches.
  - `AuthoringMutation.kt:54` and `:87` are in different functions, so they don't interact.
- **The evidence endpoint depends on the predicate.** `GovernedReviewEvidenceEndpoint.kt:265` and `:293` catch the endpoint's own `GovernedReviewEvidenceTransportError`, which `openGovernedReviewChannel` and `privateDirectory()` throw, so that the bind artifacts are rolled back. They keep doing so only because `isShellContentContractFailure()` covers `GovernedReviewFailureCode`.
- **Test census:**
  - 37 `assertFailsWith` sites on the 10 classes, in 12 files:
    - launcher `AgentRunCommandBuildersTestSupport.kt:28`, `AgentRunCommandBuildersTest.kt:415`, `GovernedReviewEvidenceEndpointTest.kt:106, 117`
    - skills `AgentAddonSchemaValidatorTest.kt:24, 31`, `AgentAddonSourceLoaderTest.kt` (19 sites), `AgentAddonSelectionResolverTest.kt:40, 43, 46, 84`, `ScaffoldServiceParityTest.kt:789`
    - application `ParallelCodeReviewRunnerTest.kt:384`
    - mcp `GovernedReviewEvidenceBridgeTest.kt:162, 185`
    - engine `GoalPreflightServiceTest.kt:257`, `UnaddressedFindingsLedgerServiceTest.kt:75, 83, 137`, `GoalRunnerWorkflowFamilyLookupDecodeTest.kt:10, 13`
  - Some tests read typed properties after the assertion:
    - `error.reason.contains(...)`: 17 sites in `AgentAddonSourceLoaderTest`.
    - `error.provider` and `error.capability`: `AgentRunCommandBuildersTest.kt:416-417` and `AgentRunCommandBuildersTestSupport.kt:29-32`.
    - `error.requestedMode`: `ParallelCodeReviewRunnerTest.kt:388`.
  - No test constructs one of the 10 classes. No test pins one of their class names as a string.
  - The two `assertFailsWith<ShellContentContractException>` in `GoalPlanningPreparationStoreSchemaParityTest` belong to other areas and stay.
- **Existing MCP coverage.** `McpCaptureDiagnosticsTest` already covers the IAE/ISE no-capture arm against the capture arm. Nothing covers a coded `SkillBillRuntimeException` in either arm, so the one new test is required.
- **Baseline rows to delete** in `custom-throwable-baseline.txt`:
  - `runtime-contracts:AgentAddonPointerCollisionError`
  - `runtime-contracts:AgentAddonSelectionDriftError`
  - `runtime-contracts:GovernedReviewEvidenceTransportError`
  - `runtime-contracts:GovernedReviewLaunchCapabilityError`
  - `runtime-contracts:InlineParallelReviewUnsupportedError`
  - `runtime-contracts:InvalidAgentAddonDeliveryTargetError`
  - `runtime-contracts:InvalidAgentAddonSchemaError`
  - `runtime-contracts:InvalidAgentAddonSelectionError`
  - `runtime-contracts:InvalidUnaddressedFindingsLedgerSchemaError`
  - `runtime-contracts:MissingAgentAddonDeclarationError`

  `InvalidAgentAddonAgentIdError` and `InvalidGovernedReviewEvidenceRequestError` are declared elsewhere, and their rows stay.
- **Language.** Kotlin is `2.4.0-Beta2`. Use plain subjectless `when` rather than guard conditions (`is X if …`), so ktlint and detekt don't depend on parser support for guards.

### Ordered tasks

1. **Core helpers** (AC-003). In `runtime-contracts/.../skillbill/error/core/RuntimeExceptionBases.kt`, add both helpers next to the types:
   - `fun SkillBillRuntimeException.rethrowUnless(handled: Boolean): SkillBillRuntimeException`. It throws `this` when `handled` is false and returns `this` otherwise.
   - `fun Throwable.failureCodeLabel(): String?`. It returns `null` unless `this` is a `SkillBillRuntimeException` whose `code` is not a `LegacyFailureCode`. Otherwise it returns `"<enum simple name>.<entry name>"`.
     - Take the enum name from the enum's declaring class, not from `javaClass` or `::class`, which are anonymous for entries with bodies. If the star-projected `declaringJavaClass` extension doesn't resolve, use `javaClass` when `isEnum` is true and its `superclass` otherwise.
     - Take the entry name from `Enum.name`.
     - Fall back to `code::class.simpleName` and `code.toString()` for a non-enum code.
   - Don't import `skillbill.error.shellcontent` from core.
   - Tests: none. The MCP test in task 6 exercises both helpers end to end.

2. **Shell-content predicate** (AC-003). Add `runtime-contracts/.../skillbill/error/shellcontent/ShellContentContractFailures.kt` with `fun Throwable.isShellContentContractFailure(): Boolean`. It is true when either holds:
   - `this is ShellContentContractException`;
   - `this is SkillBillRuntimeException` and `code` is a `FailureWireCode`, an `AgentAddonFailureCode` or a `GovernedReviewFailureCode`.

   Add a KDoc line saying each later area conversion adds its enum here, and that `ScaffoldFailureCode` is never added. Tests: none, per the Validation Strategy.

3. **Codes and message functions** (AC-001, AC-002).
   - **Rewrite `AgentAddonShellContentErrors.kt`** to hold only:
     - `enum class AgentAddonFailureCode : RuntimeFailureCode { INVALID_SCHEMA, MISSING_DECLARATION, INVALID_SELECTION, SELECTION_DRIFT, INVALID_DELIVERY }`. `INVALID_DELIVERY` is the family entry for the former delivery-target and pointer-collision classes, which no test or main code discriminates.
     - Message functions for the classes thrown from more than one site. Each returns `SkillBillRuntimeException`, and each message is copied verbatim, including the `ifBlank { "<unknown>" }`:
       - `invalidAgentAddonSchema(sourceLabel: String, reason: String, cause: Throwable? = null)`
       - `missingAgentAddonDeclaration(slug: String, expectedRoot: String)`
       - `invalidAgentAddonDeliveryTarget(slug: String, target: String, reason: String)`
       - `agentAddonPointerCollision(pointerName: String)`
     - `InvalidAgentAddonSelectionError` is message-only, so it is inlined as `SkillBillRuntimeException(AgentAddonFailureCode.INVALID_SELECTION, message, cause)` at every site.
     - `AgentAddonSelectionDriftError` has one site, so it is inlined at `AgentAddonSelectionResolverPersisted.kt:56` with its exact text.
   - **Rewrite `GovernedReviewShellContentErrors.kt`** to hold:
     - `GovernedReviewFailureCode { UNADDRESSED_FINDINGS_LEDGER_ABSENT, INVALID_LEDGER_SCHEMA, EVIDENCE_TRANSPORT, INLINE_PARALLEL_UNSUPPORTED, LAUNCH_CAPABILITY }`. Tests assert each of the four former classes, so each gets its own entry.
     - `governedReviewLaunchCapability(provider: String, capability: String)`, thrown at two sites.
     - The ledger-schema and evidence-transport classes are message-only and are inlined with `cause`. `InlineParallelReviewUnsupportedError` has one site and is inlined at `ParallelCodeReviewRunner.kt:152` with its exact two-part string.
   - Neither file keeps a class declaration. No typealias anywhere.
   - No failure becomes `require`/`check`: every one can be triggered by file, manifest, process, socket or user input.

4. **Throw and construct sites** (AC-002, AC-004, AC-005). Replace every site listed in the facts with the function or the coded constructor, keeping argument values and `cause` exactly as they are.
   - **The two `is` sites.** In `AgentAddonSchemaValidator.asAgentAddonSchemaError` and `AgentAddonSourceOperation.asSourceSchemaError`, the `when (this)` becomes a subjectless `when`:
     - first arm: `(this as? SkillBillRuntimeException)?.code == AgentAddonFailureCode.INVALID_SCHEMA -> this`;
     - then `this is Exception -> invalidAgentAddonSchema(sourceLabel, message ?: fallbackReason, this)`;
     - then `else -> this`.

     The `runCatching(operation).getOrElse` lines that call these helpers stay byte-identical: this subtask edits the classifier, not the `runCatching` call, and the F-007 sweep is a non-goal.
   - **`ResolveUnaddressedFindingsLedger.kt:13`** becomes `catch (error: SkillBillRuntimeException) { error.rethrowUnless(error.code == GovernedReviewFailureCode.INVALID_LEDGER_SCHEMA); null }`.
   - **Module-local helpers** keep their names and only change their bodies: `invalidAgentAddonSelection` (`AgentAddonSelectionResolverPersisted.kt:67`), `invalidSchema` / `invalid` (validator and source operation), and both `LegacyGoalRunnerControl*Migration` `Nothing` helpers.
   - **Widen the two function types** named in the facts (`SchemaIdentityRequest.identityFailure` and the `compiledSchemaFromYamlNode` `processingFailure` parameter) to return `SkillBillRuntimeException`.
   - **`GovernedReviewEvidenceEndpoint.kt:289/296`** assign a constructed instance to `failure: Throwable?`. Construct it with the coded constructor.
   - Remove every now-unused `skillbill.error.shellcontent.<FormerClass>` import, and add `SkillBillRuntimeException` and code imports.

5. **Edge sites** (AC-003, AC-004). At each of the 49 sites listed in the facts:
   - **`catch (x: ShellContentContractException) { body }`** becomes `catch (x: SkillBillRuntimeException) { x.rethrowUnless(x.isShellContentContractFailure()); body }`, with `body` unchanged.
     - Where the variable was `_`, name it `error`. The variable is then referenced, so detekt `SwallowedException` stays quiet.
     - Keep clause order: the guarded clause stays where the old clause was.
   - **`InstallStaging.kt:201`:** delete the `ShellContentContractException` clause. The `SkillBillRuntimeException` clause at `:204` has an identical body and already catches every shell-content failure. A guarded clause there would rethrow non-shell failures past `:204` and skip `logInstallStagingFailure`, which changes behaviour.
   - **`McpToolDispatcher.kt:25-41`:** turn `when (error)` into a subjectless `when`, in this order:
     1. `error is CancellationException -> throw error`
     2. `error.isShellContentContractFailure() || error is InvalidLearningSourceError || error is IllegalArgumentException || error is IllegalStateException -> mcpToolErrorResult(toolName, error)`
     3. `error is Exception -> { recordCaptureFailure(...); mcpToolErrorResult(toolName, error) }`
     4. `else -> throw error`

     Every other `SkillBillRuntimeException` keeps `recordCaptureFailure`. Keep the `runCatching` line as it is (F-008 and F-007 are non-goals).
   - **`GoalRunnerPauseBoundary.kt:31`:** turn the `when (error)` into a subjectless `when`, with the arm `error.isShellContentContractFailure() -> throw error`. Keep the cancellation and interrupt arms ahead of it.
   - Don't touch `CliRuntime.kt`.
   - **Proof that output is unchanged:**
     - a former `ShellContentContractException` is still handled by the same clause;
     - a converted AgentAddon or GovernedReview failure is handled by the same clause as before;
     - any other `SkillBillRuntimeException` propagates exactly as before, because no edge site except `InstallStaging.kt:201` had a later sibling that matched it.

6. **Class-name renders** (AC-004). The rule: where main code renders a caught throwable's class name and the throwable's static type admits a `SkillBillRuntimeException` (`Throwable`, `Exception`, `RuntimeException`, or `SkillBillRuntimeException` and its subclasses), wrap the existing expression as `failureCodeLabel() ?: <existing expression>`. Uncoded throwables render byte-identically.
   - The label replaces the whole existing expression, so `FeatureTaskRuntimeLifecycleTelemetryEmission.terminalFailureClass()` returns the label verbatim, without the `" exception"` suffix logic.
   - **Sites to wrap** (implement confirms each one's static type):
     - Telemetry: `RuntimeExceptionTelemetry.kt:23`, `ExternalPlatformPackTelemetryPolicy.kt:15` (runtime-domain already imports `skillbill.error.core`), `FeatureTaskRuntimeLifecycleTelemetryEmission.kt:93`.
     - Install apply `causeClass = error::class.qualifiedName`: `InstallApplyPlatformPackView.kt:68, 92`, `InstallApplyNativeAgents.kt:151`, `InstallApplySideEffects.kt:69, 208`, `InstallApply.kt:224`, `InstallApplySkillLinks.kt:127`, `InstallApplyRepoLocalConfig.kt:34`, `InstallApplyCleanup.kt:77`.
     - `errorType=` diagnostics: `SchemaLoadFailureLogging.kt:16`, `GoalRunnerObservabilityEmitter.kt:62`, `GoalRunnerProgressEventEmitter.kt:50`, `GoalRunnerLedgerRecorder.kt:131`.
     - `"Name: message"` reasons and log lines:
       - engine: `GoalPlanningSweepOutcomeDerivationTerminalClass.kt:37`, `CodeReviewStep.kt:408`, `GoalPlanningRejectionRecorder.kt:41`
       - application: `ReviewServiceLaneComposition.kt:36`, `ParallelCodeReviewRunnerFailureAdmission.kt:175, 221`, `SkillRemove.kt:149`
       - infra-workflow: `FileSystemFeatureTaskRuntimeSharedEvidenceStoreReads.kt:174, 190, 212, 219`, `GhCommandRunner.kt:55` (both renders)
       - infra-skills: `PlatformPackSubstanceAuditCoreFns.kt:82`, `InstallStaging.kt:232`, `SkillRemoveJvmFileSystemApplyArtifactDelete.kt:151`
     - Blank-message fallbacks (`ifBlank { simpleName }` / `takeIf(String::isNotBlank) ?: simpleName`), where a coded failure with a blank message reaches the class name:
       - launcher `JvmAgentRunProcessRunner.kt:125`, `ProcessRunDegradationRecorder.kt:50, 60, 73, 128`
       - sqlite `ConnectionTransactions.kt:116`
       - http `HttpInstallerScriptFetchAdapter.kt:119`, `GitHubReleaseCatalogAdapter.kt:92`
       - skills `RepoValidationRuntimeSkillDiscovery.kt:143`
       - application `AgentActivityStampWriter.kt:124`, `RuntimeOwnedPersistenceBoundary.kt:110`, `ScaffoldInvocation.kt:87`, `TelemetryOutboxDrain.kt:210`, `DecompositionManifestWriter.kt:308`
       - engine `WorktreeEditJournalWriter.kt:53`, `FeatureTaskRuntimeRunLoopCheckpoint.kt:386`, `RuntimeOwnedPersistenceBoundary.kt:107`

       In these, put the label in the fallback position, for example `ifBlank { failureCodeLabel() ?: simpleName }`.
   - **Deliberately left unchanged:**
     - **Pure null fallbacks** (`message ?: x::class.simpleName`). The scope lists them, but `SkillBillRuntimeException` always has a non-null message, so the label could never render there and would be dead code. This covers the ~25 schema-validator `cause.message ?: cause::class.simpleName` sites, `AgentAddonSchemaValidator.kt:58`, `InstallStaging.kt:218`, `PlatformPackSubstanceAuditCoreFns.kt:63`, `ParallelCodeReviewRunnerPlanningLaneMap.kt:148`, `FeatureTaskRuntimeRunLoopCheckpoint.kt:471`, `FeatureTaskRuntimeSharedReviewEvidenceResolver.kt:113` and `ValidationGateResolver.kt:46`. Record this in the decision entry.
     - **Catches whose type excludes `SkillBillRuntimeException`:** `FsContentPrimitives.kt:54` (`FileSystemException`), `GitWorkflowGitOperationsFingerprint.kt:239, 270` (`IOException`), `SkillRemoveJvmFileSystemApply.kt:91` (`IOException`).
     - **Not caught throwables:** logger names, value-type renders (`JsonCodec`, `ScaffoldPayloadParsing`, `ShellContentLoaderPackBuild`, `InvalidGoalTelemetryRowError`, `ReviewStatsArithmetic:59`, `ContinuationStepResult`, `WorkflowMcpResultMappers`, `PhaseStrategy*`, run-observability event names), and the `InstallApply.kt:268` `InstallSymlinkException::class` comparison.
     - **`CliRuntime.kt:113-114`:** the F-008 top-level arm, which coded failures never reach.

7. **Tests** (AC-002, AC-004).
   - **Convert each of the 37 `assertFailsWith<FormerClass>`** to `assertFailsWith<SkillBillRuntimeException>`, and add `assertEquals(<Code>.<ENTRY>, error.code)`. Where the result was discarded, bind it as `val error =`.
   - **Typed-property reads** move to `message`, with the expected literal unchanged:
     - `error.reason.contains(X)` becomes `error.message.orEmpty().contains(X)`, and the diagnostic argument becomes `error.message`.
     - `error.provider` / `error.capability` become `assertTrue(error.message.orEmpty().contains("Agent '<provider>'"))` / `contains("missing capability '<capability>'")`, using the same expected values, including the Junie either-or check.
     - `error.requestedMode` becomes `contains("requested mode '${mode.wireValue}'")`.
   - Edit no message, payload, exit-code or wire-fixture assertion.
   - **Add one test** to `runtime-mcp/src/test/kotlin/skillbill/mcp/core/McpCaptureDiagnosticsTest.kt`, built like the existing dispatcher test (failing `RemoteTransportPort`, `CAPTURED_TOOL`, `capturedErrorTypes`):
     - one call fails with `SkillBillRuntimeException(AgentAddonFailureCode.INVALID_SELECTION, "…")`;
     - one call fails with `SkillBillRuntimeException(ProbeFailureCode.PROBE, "…")`, where `ProbeFailureCode` is a private test enum implementing `RuntimeFailureCode`;
     - assert both error messages come back, and that `capturedErrorTypes` is exactly `listOf("ProbeFailureCode.PROBE")`.

     Bugs this catches: the predicate misses an area enum, so shell-content failures start reporting telemetry; a non-shell coded failure loses its capture; or `error_type` doesn't use the code label. Add no other test.

8. **Baseline** (AC-006). Delete the 10 rows listed in the facts from `../../../runtime-kotlin/runtime-core/src/repoTest/kotlin/skillbill/architecture/baselines/custom-throwable-baseline.txt`. Edit no other row, and don't re-record. `FailureCodeTotalityArchitectureTest` is the stale and new-row check.

9. **Docs and decision** (scope item 3).
   - In `docs/telemetry-privacy.md:279`, change `(exception class simple name)` to `(exception class simple name, or the failure code label for coded runtime failures)`.
   - Add a dated `## [2026-10-02] SKILL-398 subtask 4: …` entry at the top of `../../../runtime-kotlin/agent/decisions.md`, with `Context` / `Decision` / `Reason` / `Revisit when` lines, below the H1 and above the current SKILL-398 failure-model entry. It records:
     - coded failures render `<CodeEnum>.<ENTRY>` wherever a caught throwable's class name was rendered, and pure null-message fallbacks are left alone because coded failures always carry a message;
     - `isShellContentContractFailure` is a transitional classification, removed when `ShellContentContractException` retires;
     - the shell-content conversion is split between SKILL-398 subtask 4 and SKILL-399.

10. **Self-check before handoff** (AC-001, AC-003, AC-005, AC-007). Use single `grep` pipelines only; leave build, test, detekt and check to the build and validate phases.
    - No main or test references remain to the 10 class names.
    - No `catch (…: ShellContentContractException)` or `is ShellContentContractException` remains in main.
    - No `typealias` names a deleted class.
    - `git diff --stat` shows the other seven `*ShellContentErrors.kt` files untouched.

### Constraints carried into implement

- `ShellContentContractException`, `LegacyFailureCode` and the secondary constructor stay, and `SkillBillRuntimeException` stays `open`.
- Adds none of the following: a property on `SkillBillRuntimeException`, family metadata on codes, `@Suppress`, `runCatching`, `Result`/`Either`, a module, a dependency, or an architecture-test class. `ArchitectureScanSupport.kt` is not touched.
- detekt: `rethrowUnless` keeps every catch site within `ThrowsCount` 2. The subjectless `when` rewrites add no returns.
- Cancellation and interruption arms keep their current position ahead of every guarded arm.
- The seven SKILL-399 areas' classes, throw sites and tests are untouched. Their edge catches change only to the guarded `SkillBillRuntimeException` form, which still handles them through the `is ShellContentContractException` branch of the predicate.

## Next Path

skill-bill goal SKILL-398

## Spec Path

.feature-specs/SKILL-398-runtime-exception-reduction/spec_subtask_4_collapse-shell-content-errors.md
