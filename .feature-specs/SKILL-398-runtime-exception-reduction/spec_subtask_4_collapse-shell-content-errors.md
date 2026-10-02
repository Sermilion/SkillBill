# SKILL-398 Subtask 4 - collapse-shell-content-errors

Parent spec: [.feature-specs/SKILL-398-runtime-exception-reduction/spec.md](./spec.md)
Issue key: SKILL-398

## Scope

(F-005) Replace the classes declared in `runtime-kotlin/runtime-contracts/src/main/kotlin/skillbill/error/shellcontent/` (96 at the census tree, in 9 files: AgentAddon, FeatureTaskRuntime, GovernedReview, Install, Manifest, ReviewContext, Scaffold, SkillStaging and Workflow `*ShellContentErrors.kt`) with `SkillBillRuntimeException` and owner codes, following the parent spec's "Target failure model".

- One code enum per file, in the same package and named for the file's area, for example `InstallFailureCode`, `WorkflowFailureCode`, `ReviewContextFailureCode`, `ScaffoldFailureCode`. Each implements `RuntimeFailureCode`. An enum entry exists per former class that main code discriminates or a test asserts; the others share the area's family entry.
- Where a class already carries a `FailureWireCode` (for example `InvalidFeatureTaskRuntimePhaseOutputSchemaError.failureCode: FeatureTaskRuntimePhaseOutputFailureCode`, `InvalidDecompositionManifestSchemaError.failureCode`), that enum value is the `code`; do not add a parallel entry.
- Message formats move to one function per former class next to the code enum when thrown from more than one site, for example `fun invalidInstallPlanSchema(fieldPath: String, reason: String, cause: Throwable? = null): SkillBillRuntimeException`, and are inlined at a single throw site otherwise. Text is byte-identical, including the `<root>` and `<unknown>` placeholders.
- Typed properties that main code reads today (`reason`, `fieldPath`, `payloadFreeReason`, `projectionName`, `subtaskId`, `phaseId`, `allowedValues`, per investigation.md F-005 and the catch census) are no longer read from an exception. Where the reader needs the value to build a result, the throwing function returns that result instead (tier 2). Where the value only feeds a message or a diagnostic line, the reader uses `message`, or the factory passes the value as part of the message. Do not add properties to `SkillBillRuntimeException`.
- Classes whose condition only a code defect can trigger (the condition cannot arise from user, agent, file, process or network input) become `require`/`check`/`error()` with the same message. When unsure, keep the code.
- Catch sites: `catch (e: <FormerClass>)` becomes `catch (e: SkillBillRuntimeException)` that handles the expected code(s) and rethrows any other code. Where the handler turns the failure into `null`, `emptyMap()` or an `Error` result (for example `WorkflowService.kt:143`, `:165`, `:173`, `VerifyWorkflowStore.kt:59`, `WorkflowStateRepositoryParentDiscovery.kt:76` at the census tree), prefer making the decode boundary return that value and drop the catch.
- Tests: `assertFailsWith<FormerClass>` becomes `assertFailsWith<SkillBillRuntimeException>` plus an assertion on `code`; message assertions stay.
- Delete the shell-content files' classes. If no class in main extends `SkillBillRuntimeException` or `ShellContentContractException` afterwards, finish the transition as the parent spec describes.

Excluded: `UnaddressedFindingsLedgerAbsentError` (subtask 3). If it still exists, leave it.

If the custom-throwable baseline exists, remove the rows of every deleted class.

## Acceptance Criteria

1. No main source declares a class in `skillbill.error.shellcontent` other than `UnaddressedFindingsLedgerAbsentError` if subtask 3 has not removed it; the package holds only code enums and message functions.
2. Each former failure throws `SkillBillRuntimeException` whose `code` is an entry of an enum implementing `RuntimeFailureCode` declared in that package (or the existing `FailureWireCode` value it carried), or fails through `require`/`check`/`error()` where only a code defect can trigger it.
3. Every user-visible message is byte-identical; no expected-output, wire-fixture or payload assertion is edited other than replacing an exception-type assertion with a code assertion.
4. No main code reads a typed property from a caught exception that this subtask replaced.
5. No main source declares a typealias named after a deleted class.
6. If the custom-throwable baseline exists, it lists none of the deleted classes and `FailureCodeTotalityArchitectureTest` passes.

## Non-Goals

- Classes outside `skillbill.error.shellcontent` (subtask 5), and the classes named in subtasks 2 and 3.
- Renaming the `skillbill.error.shellcontent` package (SKILL-372 retention; it may hold only enums after this subtask).
- The CLI and MCP top-level arms (F-008).

## Dependency Notes

Depends on: none.
If subtask 1 has not landed, add the target-type pieces as the parent spec defines them. If subtask 5 finished first and left `ShellContentContractException` with only shell-content subclasses, this subtask finishes the transition. Coordinates with SKILL-391 (moved `InvalidMcpToolArgumentError` out; nothing to do here), SKILL-390, SKILL-396 and SKILL-397, which edit throw sites in their modules; the second lander keeps both edits. SKILL-392 subtask 2 throws `InvalidFeatureTaskExecutionIdentitySchemaError` from the engine `FeatureTaskRuntimeExecutionEntry` (via `FeatureTaskExecutionIdentityPolicy.normalizeIssueKey`) and maps a `WorkflowOpenResult.Error` to a CLI `UsageError`. If it has landed, convert that throw site to the code and confirm the `UsageError` text is unchanged; if not, SKILL-392 rechecks them when it lands.

## Validation Strategy

Goal gates: build, unit tests, detekt and the runtime-core repoTest suite. Existing tests pass with type-to-code assertion edits only. No new behavioural test is required unless a catch site became a returned value, in which case one test asserts the caller's branch.

## Implementation Details

Census taken on `base/SKILL-380-phase-slot-strategies` at `432d427c8`. This subtask runs after subtasks 1–3 on `feat/SKILL-398-runtime-exception-reduction`, so task 1 re-takes the census on the tree it actually gets. Line numbers below are as of `432d427c8`; apply each rule to the code wherever it is now.

### Facts the plan rests on

- `skillbill.error.shellcontent` declares 97 classes in 9 files. One of them, `UnaddressedFindingsLedgerAbsentError`, belongs to subtask 3. Two classes are `open`:
  - `InvalidWorkflowStateSchemaError`, subclassed by `InvalidFeatureTaskRuntimeCheckpointIdentityVersionError`.
  - `ScaffoldError`. It and its 9 subclasses extend `SkillBillRuntimeException` directly, not `ShellContentContractException`.
- About 30 classes outside the package still extend `ShellContentContractException`. They are subtask 5's. Examples: `skillbill.error.core` (`DurableExternalDecodeErrors`, `MalformedJsonTextError`, `ExternalPlatformPackErrors`, `ExternalAddonErrors`, `UnrecognizedFailureWireCodeError`), `skillbill.error.featuretask` (`PhaseSlotContractErrors`, execution-plan errors), `InvalidMcpToolArgumentError`, `InvalidGoalTelemetryRowError`, and the engine operation errors.

  So this subtask does **not** finish the transition. `ShellContentContractException`, `LegacyFailureCode` and the secondary constructor stay, and `SkillBillRuntimeException` stays `open`.
- About 75 main sites check a shell-content class by `catch`, `is` or `as?`. About 40 more catch or `is`-check `ShellContentContractException` itself:
  - CLI: `PhaseCommand`, `AgentAddonCliCommands`, `InstallApplyExternalAddonsCommand`, five `Config*` commands, `CodeReviewCommand`.
  - MCP: `McpToolDispatcher.kt:27`.
  - Engine: `GoalRunnerPauseBoundary.kt:31`, `GoalRunnerStatusProjectionAssembler.kt:360`, `IdeStatusProjector.kt:225`, `ValidationGateResolver.kt:44`.
  - Infra: launcher, contracts, sqlite, http and skills.
  - Application: `ReviewServiceLaneComposition.kt:33`.

  A plain `SkillBillRuntimeException` thrown by a converted class would escape every one of these. `CliRuntime` already routes every `SkillBillRuntimeException` to one arm, so the top-level CLI output does not change.
- Main code reads these typed properties from caught shell-content errors:
  - `DecompositionManifestSchemaValidator.kt:235-238` reads `failureCode` and `reason`.
  - `FileSystemFeatureTaskRuntimeSharedEvidenceStoreReads.kt:122-127` reads `reason`.
  - `GoalRunnerSubtaskLaunchPrepare.kt:144-148`, `GoalPlanningSweepOutcomeDerivationTerminalClass.kt:45-52` and `GoalPlanningOperatorRemedies.kt:67-72` read `subtaskId` and `reason`.
  - `GoalPlanningRecoveryKind.kt:28-69` reads `reason`, plus `fieldPath`, `reason` and `payloadFreeReason` along the cause chain.
  - `FeatureTaskRuntimeRunLoopDrive.kt:164-169` reads `phaseId`.
  - `PhaseLaunchPreparation.kt:121`, `:221-222` and `FeatureTaskRuntimePhaseBriefingRecorder.kt:101-105` read `projectionName`, `projectionContractId` and `failureKind`.
  - `RuntimeGateRecordIntegrity.kt:35` reads `reason`.
  - `FeatureTaskRuntimeHandoffEnvelopeArtifactDecoders.kt:61-65` reads `reason`.
  - `FeatureTaskRuntimeRepairReceipt.kt:30-37` reads `fieldPath`, `reason` and `payloadFreeReason`.
  - `GoalSubtaskReviewState.kt:355` reads `payloadFreeReason`.

  No main code reads `structuralRepair*`, the build receipt's `failureCode` or `payloadFreeReason`, or any other property not listed here. Those are dead and are dropped.
- `GoalPlanningStatusReasonCoherenceTest` ("preparation state read stop uses recovery reason not cannot-be-recovered message") pins two things: the stop reason contains the recovery `reason`, and it does not contain the exception message's "cannot be recovered" text. So "the reader uses `message`" cannot replace `IncompatibleGoalPlanningPreparationRecoveryError.reason` without breaking a regression test. That case needs a returned value (task 6).
- Tests contain about 1,000 `assertFailsWith<FormerClass>` sites. Three tests pin class names as payload strings:
  - `FeatureTaskRuntimeHandoffEnvelopeSchemaValidatorTest` asserts `error::class.simpleName` per kind.
  - `InternalSkillCompanionInstallApplyTest:70` asserts `causeClass` = the qualified `InternalSkillSidecarCollisionError`.
  - `ConfigExternalPlatformPackCommandTest:110` asserts `ERROR_TYPE` = `InvalidManifestSchemaError`.

  Two tests assert `assertFailsWith<ShellContentContractException>`: `GoalPlanningPreparationStoreSchemaParityTest:89`, `:100`.
- Exactly four enums implement `FailureWireCode`, and only shell-content classes carry them:
  - `FeatureTaskRuntimePhaseOutputFailureCode`
  - `FeatureTaskRuntimePhaseOutputFailureKind`
  - `FeatureTaskRuntimeHandoffProjectionFailureKind`
  - `DecompositionManifestValidationFailureCode` (runtime-domain)
- `InvalidDecompositionManifestSchemaError.failureCode` is a free `String`:
  - Most throw sites use `DecompositionManifestValidationFailureCode` wire values.
  - `GoalPreflightInputValidation.kt:54` and `GoalPreflightLookupResolver.kt:121` use `issue_key_mismatch` and `duplicate_active`, which `GoalPreflightServiceTest:179`, `:206` assert.
  - `DecompositionPlanningContracts.kt:298`, in runtime-contracts, uses `invalid_shape` and cannot see the domain enum.

  `InvalidDecompositionManifestBundleJournalError.failureCode` carries journal codes that `DecompositionManifestBundleJournalValidationTest` asserts (`duplicate_staged`, `schema_invalid`, `unsupported_contract_version`).
- The per-module package-cycle guard forbids `skillbill.error.core` from importing `skillbill.error.shellcontent`, because shellcontent already imports core.

### Ordered tasks

1. **Precondition census** (AC-001, AC-002, AC-006). On the working tree:
   - Confirm subtask 1's pieces exist: `RuntimeFailureCode`, coded `SkillBillRuntimeException`, `LegacyFailureCode`, the secondary constructor, and `baselines/custom-throwable-baseline.txt`. Add any piece that is missing, exactly as the parent spec defines it.
   - Confirm the four `FailureWireCode` enums implement `RuntimeFailureCode`; add it where missing. Do not make `FailureWireCode` extend `RuntimeFailureCode`.
   - Note what subtasks 2 and 3 already replaced (for example `UnaddressedFindingsLedgerAbsentError`, the three message-text branches, `GoalPlanningSweepOutcomeDerivationTerminalClass.kt:48`) and keep their edits.
   - Re-run the class, catch and assertion censuses with single `grep -rnE` pipelines.

2. **Code enums and message functions, one file per area** (AC-001, AC-002, AC-005). In each `*ShellContentErrors.kt`, replace the classes with:
   - one enum implementing `RuntimeFailureCode`;
   - one message function per former class thrown from more than one site, returning `SkillBillRuntimeException` and taking the old constructor's parameters (including `cause`);
   - message text copied verbatim, including `ifBlank { "<unknown>" }`, `"<root>"`, `"<absent>"`, the `REVIEW_*` prefixes and the sorted lane suffix.

   A class thrown at only one site is inlined there as `SkillBillRuntimeException(CODE, "<same text>", cause)`. Message-only classes (`message, cause`) are inlined as `SkillBillRuntimeException(CODE, message, cause)` at every site, with no function.

   The entry rule: one entry per class that main code discriminates or a test asserts; every other class in the file shares the area's family entry. Census entries (names are indicative):
   - `AgentAddonFailureCode`: INVALID_SCHEMA, MISSING_DECLARATION, INVALID_SELECTION, SELECTION_DRIFT. Family: delivery target, pointer collision.
   - `FeatureTaskRuntimeFailureCode`:
     - repair receipt, finding verification record
     - checkpoint identity schema, checkpoint identity version (its own entry, because `FeatureTaskRuntimeRemediationBaseReconciler.kt:78` discriminates it)
     - quarantine, implementation attempt, phase handoff, persistence
     - projection measurement (pinned by the envelope test)
     - shared evidence projection, build receipt, validation evidence, readiness evidence
     - phase order violation, execution identity, worker ownership
     - Family: operator decision rejected.

     The phase-output and handoff-projection classes take no new entry. Their code is `FeatureTaskRuntimePhaseOutputFailureCode` (default `SCHEMA_INVALID`) and `context.failureKind` (`FeatureTaskRuntimeHandoffProjectionFailureKind`).
   - `GovernedReviewFailureCode`: ledger schema, evidence transport, inline parallel unsupported, launch capability. `UnaddressedFindingsLedgerAbsentError` stays untouched if it still exists.
   - `InstallFailureCode`:
     - install plan, native agent composition, telemetry event
     - goal observability event, goal progress event, IDE status
     - goal subtask review state, goal planning preparation schema
     - goal planning preparation contract incompatible (task 6)
     - goal planning preparation conflict (task 6)
     - install selection missing, unreadable, malformed
     - baseline manifest unreadable
     - reconciliation conflict, repo-local config malformed, contract version mismatch
     - Family: unreadable repo-local config, malformed machine config.
   - `ManifestFailureCode`: missing manifest, manifest schema, validation gate declaration, composition cycle, ambiguous lane ownership, incompatible composition contract, missing composition layer. Family: missing validation gate.
   - `ReviewContextFailureCode`: identity mismatch, review context schema, rule text too long, title too long, hunk locator missing, hunk locator unreadable, hunk integrity, spec intent unreadable, aggregation integrity. Family: invalid skill content identity. Keep `REVIEW_HUNK_EVIDENCE_INTEGRITY` public only if code outside the file references it; otherwise make it private.
   - `ScaffoldFailureCode`: payload version mismatch, invalid payload, retired kind, unknown skill kind, unknown pre-shell family, skill already exists. Family: `ScaffoldError` direct throws, missing platform pack, missing supporting file target, rollback.
   - `SkillStagingFailureCode`: sidecar collision, authored sidecar, review skill structure, missing content file, composed budget exceeded, missing required section, SKILL.md shape, missing installed native agent, internal skill classification, missing baseline platform selection, fallback capability. Family: native agent link inventory.
   - `WorkflowFailureCode`:
     - workflow state schema, prose write refused, work list row, issue key conflict, legacy prose workflow
     - rejected output diagnostic schema, producer output evidence schema, verification boundary cap
     - `DECOMPOSITION_MANIFEST_INVALID_SHAPE`, used only by the runtime-contracts thrower
     - `DECOMPOSITION_MANIFEST_ISSUE_KEY_MISMATCH`, `DECOMPOSITION_MANIFEST_DUPLICATE_ACTIVE`
     - one entry per distinct bundle-journal `failureCode` literal at its throw sites

     Every other decomposition-manifest throw uses the `DecompositionManifestValidationFailureCode` entry for its wire value, or `SCHEMA_INVALID` where none was passed, matching `fromWire(null)`. Do not add a parallel entry for these.

   Then:
   - Delete all 97 shell-content classes except `UnaddressedFindingsLedgerAbsentError`.
   - Drop the dead data types `FeatureTaskRuntimePhaseOutputStructuralRepair` and `FeatureTaskRuntimePhaseOutputStructuralRepairSource`, after confirming they have no main or test readers. Otherwise move them next to their reader; they are not throwables.
   - Classify each failure as a code defect only where no user, agent, file, process or network input can trigger it. None is expected; when unsure, keep the code.

3. **Shared classification functions** (AC-002, AC-003, AC-004).
   - In `skillbill.error.core`, add `fun SkillBillRuntimeException.rethrowUnless(handled: Boolean): SkillBillRuntimeException`. Catch sites rethrow through it, which keeps them under detekt `ThrowsCount` 2 without `@Suppress`.
   - In `skillbill.error.shellcontent`, add `ShellContentContractFailures.kt` with two functions:
     - `isShellContentContractFailure()`: `this is ShellContentContractException` **or** `code is FailureWireCode` **or** `code is` one of the 8 shell-content area enums. `ScaffoldFailureCode` is excluded, because scaffold errors never extended `ShellContentContractException`.
     - `isInvalidWorkflowStateFailure()`: the workflow-state entry or the checkpoint-identity-version entry, mirroring the old subclass relationship.

     These are classification functions over the package's own codes, not classes. Subtask 5 deletes the first one when it retires `ShellContentContractException`.
   - In runtime-domain `skillbill.workflow.decomposition.model`, add `isDecompositionManifestSchemaFailure()`: `code is DecompositionManifestValidationFailureCode`, or one of the `WorkflowFailureCode` decomposition entries.

4. **Throw sites** (AC-002, AC-003). In every module, replace each `throw FormerClass(...)` and returned or constructed `FormerClass(...)` with the message function or the inlined coded constructor. Covered modules: contracts, domain, application, engine, infra contracts, skills, sqlite, workflow, launcher, host, http, cli, mcp. Rules:
   - Lambdas typed as returning a former class become `SkillBillRuntimeException`. Examples: `ClasspathContractSchemaLoader` `missingResource`/`processingFailure`/`identityFailure` callbacks, `featureTaskRuntimeWireArtifactNonObjectError`, and `coherenceError`.
   - Pass `FeatureTaskRuntimePhaseOutputFailureCode` and `DecompositionManifestValidationFailureCode` entries directly rather than wire strings.
   - Convert SKILL-392's `FeatureTaskExecutionIdentityPolicy.normalizeIssueKey` throw to the execution-identity code. Leave the `WorkflowOpenResult.Error` → `UsageError` text unchanged.

5. **Catch and `is` sites** (AC-002, AC-004).
   - Each `catch (e: FormerClass)` becomes `catch (e: SkillBillRuntimeException) { e.rethrowUnless(e.code == X) … }`, or `code is <Enum>` for a FailureWireCode family. Two catches on the same `try` (for example `IdeStatusService.kt:79/87`, `GoalChildPlanningHydrator.kt:301/303`, `GoalPlanningPreparationCheckpoint.kt:271/273`, `FeatureTaskRuntimeRejectedOutputRecorder.kt:189-221`) merge into one catch with a `when (e.code)`.
   - `is FormerClass` and `as? FormerClass` become code checks on `(error as? SkillBillRuntimeException)?.code`. Sites: `AgentAddonSchemaValidator.kt:78`, `AgentAddonSourceOperation.kt:19`, `FileSystemInstallSelectionValidation.kt:55`, `FileSystemBaselineManifestWire.kt:98`, `InstallApply.kt:195`, `CodeReviewStep.kt:391-393`, `GoalPlanningPhaseAttemptGateBurstCap.kt:25`, `ExternalPlatformPackTelemetryPolicy.kt:23`, `FeatureTaskRuntimeRepairReceiptParser.kt:81`.
   - Former `InvalidWorkflowStateSchemaError` catches use `isInvalidWorkflowStateFailure()`. Former `InvalidDecompositionManifestSchemaError` catches use `isDecompositionManifestSchemaFailure()`.
   - Every `catch (e: ShellContentContractException)` and every `is ShellContentContractException` arm becomes a `SkillBillRuntimeException` catch, or an arm guarded by `isShellContentContractFailure()`. This includes `McpToolDispatcher.kt:27` and `GoalRunnerPauseBoundary.kt:31`. For MCP, that keeps shell-content failures in the no-telemetry-capture arm, and every other `SkillBillRuntimeException` keeps `recordCaptureFailure`. The CLI per-command catches keep printing on stdout for exactly the failures they caught before.
   - Where a catch currently precedes a `SkillBillRuntimeException` catch (`InstallStaging.kt:201/204`, `AuthoringMutation.kt:54/87`), keep the order: the predicate branch first, then the generic one.
   - The spec prefers returning the decode value over a catch-to-null (`WorkflowService.kt:143/165/173`, `VerifyWorkflowStore.kt:59`, `WorkflowStateRepositoryParentDiscovery.kt:76`). A code-checked catch is acceptable; do not widen this task into decoder refactors.
   - No touched catch becomes a new `runCatching`. Cancellation and interruption keep propagating.

6. **Property readers become values or failure factories** (AC-004, AC-003). No main code reads anything from a caught exception other than `code`, `message` and `cause`. Each reader converts as follows:
   - **Goal planning preparation conflict** (`IncompatibleGoalPlanningPreparationRecoveryError`; 14 sqlite and 10 engine throw sites; readers `blockedOnRecoveryError`, `recoverySubtaskId`/`preparationStateReadReason`, `goalPlanningPreparationStateReadStopReason`, `goalPlanningChildImportConflictBlockedReason`). This is tier 2:
     - Add a ports value `GoalPlanningPreparationConflict(workflowId, subtaskId, reason, cause: Throwable?)`.
     - Add result types shaped like `WorkflowGitOperationResult`: a `sealed interface` with nested data variants for applied/found versus `Conflicted(conflict)`. Check `PortsDeclarationArchitectureTest` and the SKILL-393 no-behaviour rule before writing; no functions in ports.
     - The `SharedGoalPreplanRepository`/`GoalSubtaskPlanRepository` methods that throw a conflict today return these results, and the sqlite stores return `Conflicted` instead of throwing.
     - In runtime-engine, an extension `GoalPlanningPreparationConflict.toFailure()` builds the coded failure through the install-area message function, keeping the "… subtask N cannot be recovered: reason" text.
     - Callers that never inspected the conflict call `toFailure()` and throw, so behaviour is unchanged.
     - The three reader paths return the conflict as a value and branch on it: `prepareAttemptedLaunch` in hydration and child persistence; `recoveryProgress` plus `requireStoredPlansReady`, which returns the unready subtask id; and the sweep stop reason.
     - The reader functions take `GoalPlanningPreparationConflict`.
   - **Contract-version hard reset.** `causeIndicatesContractVersionHardReset` stops reading `fieldPath`, `reason` and `payloadFreeReason`:
     - Throw sites that reject a stored planning or phase-output record for a contract id or version mismatch use the install-area "contract incompatible" code.
     - The classifier walks the cause chain for that code.
     - Find those producers with a single `grep` for `phase_output_contract_version|planning_contract_version|phase_output_contract_id|planning_contract_id`.
     - Keep whatever generic message branch subtask 3 left in place.
   - **Phase order violation.** `FeatureTaskRuntimeTransitionFunction.nextTransition` (runtime-domain) returns a sealed result with a violation variant carrying `phaseId` and the byte-identical message. `FeatureTaskRuntimeRunLoopDrive` branches on it. Its own throw at `:104` stays a coded failure if it ends the run.
   - **Handoff projection rejection.**
     - Context: `InvalidFeatureTaskRuntimeHandoffProjectionContext` is already a value.
     - Change: the projection build and validation entry points used by `PhaseLaunchPreparation` (both catches) and `FeatureTaskRuntimeRunLoopOutputVerification.kt:158` → `FeatureTaskRuntimePhaseBriefingRecorder.recordProjectionRejection` return the rejection context. The readers build measurement rows from `context.projectionName`, `projectionContractId` and `failureKind`.
     - Other callers throw the coded failure, built from the context by the message function.
   - **Re-wrapping readers.** Move the re-wrap to the throw site by passing the wrap context in, and drop the catch. The final failure is built once, with the same text and the inner failure as `cause` where one existed:
     - `FeatureTaskRuntimeRepairReceipt.anchoredToDecodePath`: pass the anchor path into the nested decode.
     - `GoalSubtaskReviewState.decodeRepairReceipts`: pass a `(reason) -> Nothing` review-state failure factory. For receipts `payloadFreeReason == reason`; confirm this in `FeatureTaskRuntimeRepairReceiptSanitizer`.
     - `RuntimeGateRecordIntegrity.kt:35`: the phase id goes into the validation-evidence check.
     - `FeatureTaskRuntimeHandoffEnvelopeArtifactDecoders.kt:61`: `validatePersistenceRecord` returns the violation reason (`String?`) instead of throwing.
   - **`FileSystemFeatureTaskRuntimeSharedEvidenceStoreReads.kt:122`.** The validator gains a non-throwing `violation(...)`: `String?`; `validate` throws through it, and the degraded record keeps `cause = reason`.
   - **`DecompositionManifestSchemaValidator.kt:235`.**
     - Problem: the `try` body spans many domain and infra throwers.
     - Change: `DecompositionManifestValidationResult.Rejected` gains `failure: SkillBillRuntimeException? = null`. The catch builds `Rejected(code = <the failure's DecompositionManifestValidationFailureCode, mapping DECOMPOSITION_MANIFEST_INVALID_SHAPE→INVALID_SHAPE and anything else to SCHEMA_INVALID>, reason = failure.message.orEmpty(), failure = it)`. `requireAccepted` rethrows `failure` when present, otherwise builds the failure as before.
     - Message check: the messages stay byte-identical because every caller passes the same label to both calls. Verify this for `DecompositionManifestDiscovery`, `DecompositionManifestFileWrites` (both functions) and `GoalRunnerPurgeCoordinator`.

7. **Class-name renders** (AC-003). Add `fun Throwable.failureCodeLabel(): String?` to `skillbill.error.core`. It returns `"<CodeEnumSimpleName>.<ENTRY>"` for a `SkillBillRuntimeException` whose code is not `LegacyFailureCode`, and `null` otherwise.
   - Every main site that renders a caught throwable's class name uses `failureCodeLabel() ?: <existing expression>`, so output for uncoded throwables stays byte-identical. This covers `::class.simpleName`, `::class.qualifiedName` and `javaClass.name`. Sites at `432d427c8`:
     - telemetry: `RuntimeExceptionTelemetry.kt:23` `error_type`, `ExternalPlatformPackTelemetryPolicy.kt:15`, `FeatureTaskRuntimeLifecycleTelemetryEmission.kt:93`
     - install apply `causeClass` (9 sites)
     - `errorType=` diagnostics: `SchemaLoadFailureLogging`, `GoalRunnerObservabilityEmitter`, `GoalRunnerProgressEventEmitter`, `GoalRunnerLedgerRecorder`
     - `"${simpleName}: ${message}"` reasons: `GoalPlanningSweepOutcomeDerivationTerminalClass.kt:37`, `CodeReviewStep.kt:402`, `ReviewServiceLaneComposition.kt:36`, `FileSystemFeatureTaskRuntimeSharedEvidenceStoreReads.kt:174-219`, `ParallelCodeReviewRunnerFailureAdmission.kt:193`, `PlatformPackSubstanceAuditCoreFns.kt:82`, `InstallStaging.kt:218-232`, `SkillRemove.kt:136`, `GoalPlanningRejectionRecorder.kt:41`
     - the `cause.message ?: cause::class.simpleName` fallbacks
   - Update the `error_type` row in `docs/telemetry-privacy.md` to say "exception class simple name, or the failure code label for coded runtime failures".
   - The three pinned tests become code assertions: the envelope test asserts `code` per kind; the other two expect the code label.

8. **Tests** (AC-003).
   - Each `assertFailsWith<FormerClass> { … }` becomes `assertFailsWith<SkillBillRuntimeException> { … }` plus `assertEquals(<Code>.<ENTRY>, error.code)`. Use `assertIs<FeatureTaskRuntimeHandoffProjectionFailureKind>`, or an equality on the entry, for FailureWireCode families.
   - Message, `contains`, payload and exit-code assertions stay byte-for-byte.
   - Property assertions change only like this: `error.failureCode == "x"` → `error.code == <entry>`, and `error.failureKind` → `error.code`.
   - `assertFailsWith<ShellContentContractException>` becomes `SkillBillRuntimeException` plus the code.
   - Tests that construct former classes directly switch to the message function or the new value (task 6). Their expected-output assertions stay unchanged.
   - No new test-helper module, no `relaxed = true` mocks, and no `environment = emptyMap()`.

9. **Baseline, docs and decision** (AC-006).
   - Delete the deleted classes' rows from `custom-throwable-baseline.txt` by hand. Row format is `module:Class`; compare whole rows.
   - Update the `skillbill.error.shellcontent` description in `runtime-kotlin/ARCHITECTURE.md` to "code enums and message functions".
   - Add a newest-first entry to `runtime-kotlin/agent/decisions.md` that records three choices: goal-planning preparation conflicts are repository results; coded failures render their code label where a class name was rendered; and `isShellContentContractFailure` is a transitional classification that subtask 5 removes.

10. **AC self-check before handing off.** Confirm each of these with one `grep -rnE` over main:
    - no `^(open )?class ` in `error/shellcontent` except the ledger-absent class;
    - no former class name anywhere in main or test sources;
    - no `typealias` with a deleted name;
    - no `.reason`, `.fieldPath`, `.payloadFreeReason`, `.subtaskId`, `.phaseId` or `.projectionName` read from a caught `SkillBillRuntimeException`;
    - no remaining `catch (… : ShellContentContractException)` without the predicate;
    - the diff edits no message or payload literal in tests.

### Test obligations

Each of these holds only if no converted existing test already asserts the branch:

- One test showing that a conflicting stored plan during selected-subtask launch blocks that subtask's child with the conflict reason, not the "cannot be recovered" message. Realistic bug: the returned conflict is dropped, or routed to the wrong subtask id.
- One test showing that `nextTransition`'s phase-order violation blocks at the violation's `phaseId` with the same message. Realistic bug: the run blocks at the current phase instead.

The rest is covered by converted assertions plus the existing CLI, MCP and wire-fixture tests. Add no predicate or factory unit tests.

### Constraints

- No new module, dependency, `Result`/`Either` library, typealias for a deleted class, property on `SkillBillRuntimeException`, family metadata on codes, `@Suppress` or new `runCatching`.
- Messages stay byte-identical.
- runtime-domain stays free of `java.nio` and ports imports.
- runtime-contracts declares no engine, infra or MCP code.
- The SKILL-380 attempt boundary stays phase-generic.
- detekt limits: `ThrowsCount` 2, `ReturnCount` 4, `LongMethod` 70, `CyclomaticComplexMethod` 15. `ArchitectureScanSupport.kt` must not grow.
- Spotless runs in a plain clone, not a linked worktree.
- This plan runs nothing. Build, tests, detekt and the repoTest suite belong to the build and validate phases.

## Next Path

skill-bill goal SKILL-398

## Spec Path

.feature-specs/SKILL-398-runtime-exception-reduction/spec_subtask_4_collapse-shell-content-errors.md
