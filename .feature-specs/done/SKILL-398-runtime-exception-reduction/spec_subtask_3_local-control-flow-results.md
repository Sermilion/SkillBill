# SKILL-398 Subtask 3 - local-control-flow-results

Parent spec: [.feature-specs/SKILL-398-runtime-exception-reduction/spec.md](spec.md)
Issue key: SKILL-398

## Scope

(F-004) Each type below is thrown and caught inside the runtime to carry an expected outcome. Return the outcome from the function that knows it, as a sealed result, a nullable, or an existing outcome type, and delete the type. Line numbers are at the census tree; apply the rule wherever the code is now.

| Type | Throw sites | Catch sites | Target |
|---|---|---|---|
| `RefreshRefused` | `GoalPlanningSharedPreplanSettlement.kt:230` | same file `:121` | the refresh returns a refused value carrying `reason` |
| `GoalRunnerExecutionAlreadyRunningException` | `GoalRunnerExecutionCoordinator.kt:105`, `:360` | `GoalRunner.kt:61` | the coordinator returns an already-running value |
| `GoalRunnerLaunchAuthorizationDeniedException` | `GoalRunnerControlCoordinator.kt:222`, `:265` | `GoalPlanningPhaseAttemptGateBurstCap.kt:36`, `GoalRunnerSelectedSubtaskLoop.kt:186` | authorization returns a denied value carrying `pauseReason` |
| `UnaddressedFindingsLedgerAbsentError` | `UnaddressedFindingsLedgerService.kt:28`, `:44`, `:79` | `ResolveUnaddressedFindingsLedger.kt:14`, `GoalRunnerFinalization.kt:373` | the service returns null or an absent value |
| `SpecIntentSourceUnavailable` | `SpecIntentProjectionExtractor.kt:124` | `SpecIntentProjectionResolver.kt:90`, `:126`, `:156` | the extractor returns an unavailable value carrying `specPath` and reason |
| `MissingCarriedForwardGoalReviewResultException` | `InlineReviewPreparation.kt:238` | same file `:178` | nullable or a missing value |
| `UsageValidationException`, `StackDetectionException`, `DiffResolutionException`, `ReviewContextBudgetExceededException` | review planning in `runtime-application/.../review/parallel/planning/`, `.../review/parallel/runner/ParallelCodeReviewEvidenceCoordinates.kt`, `.../reviewevidence/SharedReviewEvidenceAssembly.kt`, `runtime-domain/.../review/context/model/hunk/ReviewContextBudgetModels.kt:74`, `FeatureTaskRuntimeSharedReviewEvidenceResolver.kt:80` | `CodeReviewCommand.kt:259-263`, `CodeReviewStep.kt:385-389`, `FeatureTaskRuntimeSharedReviewEvidenceResolver.kt:40` | the review planning entry points return a sealed planning result with usage-invalid, stack-undetected, diff-unresolved and budget-exceeded variants carrying today's messages; internal helpers short-circuit by returning |
| `ReviewRegisterParseSeamException` | `ParallelCodeReviewRunnerFailureAdmission.kt:145`, `:147` | same file `:45`, `:189` | the seam parse returns a failed value naming seam and lane |
| `ClaudeMcpProfileFailure` | `McpRegistrationOperations.kt:139` | `UninstallMutations.kt:96`, `InstallMcpCliCommands.kt:64` | the registration returns a failed value carrying `succeeded` |
| `SkillRemovalRefusedException` | `runtime-domain/.../skillremove/SkillRemovalRefusal.kt:9`, `TargetValidation.kt:24` | `RemoveCliCommandExecution.kt:41` | validation returns a refusal value carrying the reason |
| `InvalidExecutionMatrix`, `InvalidCompactionSettings`, `InvalidValidationGateRepoConfig` | `runtime-domain/.../config/model/*Models.kt` | the same files (`ExecutionMatrixModels.kt:77`, `CompactionSettingsModels.kt:59`, `ValidationGateRepoConfigModels.kt:27`) | the helpers return the existing `Invalid` parse result directly |

Remove branching on exception message text at the three sites, using a result or a code instead:

- `runtime-engine/.../goalrunner/persist/GoalContinuationArtifactCodec.kt:28` (`contains("mode='")`): the repository or decoder reports a mode mismatch as a distinct value or code.
- `runtime-engine/.../goalrunner/planning/outcome/GoalPlanningSweepOutcomeDerivationTerminalClass.kt:48` (`contains("must be completed with non-empty produced_outputs")`): the failing check reports this case as a distinct value or code.
- `runtime-infra/contracts/.../workflow/decomposition/DecompositionManifestSchemaValidator.kt:122` (`contains("duplicate", ignoreCase = true)`): the parser reports duplicates as a distinct value or `DecompositionManifestValidationFailureCode` entry.

CLI and MCP output stays the same for every case. If the custom-throwable baseline exists, remove the rows of every deleted class.

## Acceptance Criteria

1. No main source declares any type in the table above.
2. Each former catch site branches on a returned value; no main code catches or `is`-checks an exception to detect any of these outcomes.
3. No main source decides behaviour from `Throwable.message` content (`contains`, `startsWith`, `endsWith`, `matches` or equality on a message).
4. The CLI code-review command (`CodeReviewCommand`), skill removal, MCP install and uninstall, goal runs and feature-task code review produce the same stdout, stderr, exit codes and persisted rows as before; existing tests pass with only type-to-value assertion edits.
5. If the custom-throwable baseline exists, it lists none of the deleted classes and `FailureCodeTotalityArchitectureTest` passes.

## Non-Goals

- `SkillBillRollbackException`, `RuntimeOwnedFactUnavailable`, `DatabaseAccessError` and `DatabaseBusyError`: I/O failures that end the operation; subtask 5 turns them into codes.
- Execution-plan admission errors (subtask 5).
- The IAE/ISE catches (subtask 6), except where one of the types above extends `IllegalArgumentException`.

## Dependency Notes

Depends on: none.
If subtask 5 converted any of these types to codes first, replace the code checks with the values described here. If subtask 1 has not landed, add the target-type pieces this subtask uses as the parent spec defines them. Coordinates with SKILL-390 (engine), SKILL-392 (CLI code-review and run entry) and SKILL-397 (domain); the second lander keeps both edits.

## Validation Strategy

Goal gates: build, unit tests, detekt and the runtime-core repoTest suite. Test obligations: one test per new result branch that asserts the downstream behaviour the old catch produced (already running, launch denied, ledger absent, spec source unavailable, each review planning failure, skill removal refused, MCP profile failure), and one per message-branching site showing the case is now distinguished without the message.

## Implementation Details

Planned against `base/SKILL-380-phase-slot-strategies` at `432d427c8`. The goal runs subtasks in order 1 → 6, so subtask 1 (target type `RuntimeFailureCode` + coded `SkillBillRuntimeException`, custom-throwable baseline) and subtask 2 (operation, rejected-output and required-phase-write results) have landed when this runs. Apply every rule to the code as it is then; where subtask 2 already rewrote a line named here (notably the `RequiredPhaseWriteRejected` filters), keep its edit and apply this plan around it.

### Census corrections (current tree, not the census tree)

- `ReviewContextBudgetExceededException` (`runtime-domain/.../review/context/model/accounting/ReviewContextBudgetModels.kt:74`) is never thrown in main. Its only use is `ParallelCodeReviewRunnerLaneLaunch.kt:222`, which formats `.message`; the `is` arm in `CodeReviewStep.kt:389` is dead. It is not a planning outcome, so the planning result gets **no** budget-exceeded variant (no producer exists; adding one would be speculative). The message format is inlined at its single use.
- `ParallelCodeReviewEvidenceCoordinates.kt` (`evidenceCoordinates`, two `DiffResolutionException` throws) has no caller in main or tests. Delete the file.
- The `CodeReviewCommand.kt:263-268` arms for `UsageValidationException`, `DiffResolutionException` and `StackDetectionException` are unreachable: every planning call runs inside `CodeReviewStep.launch` (`reviewPass.review` → `DelegatedReviewPass` → `ParallelCodeReviewRunner.run`), whose `launchFailure` turns every `Exception` into a blocked outcome. Delete those three arms (keep the `ShellContentContractException` and `ReviewAggregationIntegrityError` arms). Before deleting, confirm by tracing `PhaseRunEntry.run` that nothing else calls the planning; if something does, branch on the returned planning result and call `usageError` with the same message.
- `executeRemoval` never reached the CLI refusal catch: the refusal is thrown inside `tryExecute`, and `mapSkillRemovalFailure` maps it (a `SkillBillRuntimeException`) to `SkillRemovalResult.Failed(exceptionName = "SkillRemovalRefusedException", rollbackComplete = true)`. Only `previewRemoval` (dry run) reaches the catch. Both outputs must stay byte-identical (T10).
- Message-text branching (AC-003) has nine code points, not three: the three named in Scope, plus `SQLiteDatabaseSessionFactory.kt:138-142` (`isSqliteBusy`), `StrictPhaseOutputParser.kt:71` (regex over `error.message`), and the goal-planning recovery classifier reading `Throwable.message` in three places (`GoalPlanningRecoveryKind.kt` `causeIndicatesContractVersionHardReset` else branch, `GoalChildPlanningHydrator.kt:314-316`, `GoalPlanningOperatorRemedies.kt:88`). The text checks of the typed `reason`/`fieldPath`/`payloadFreeReason` properties are not `Throwable.message` and stay for subtask 4.

### Ordered tasks

**T0. Prerequisites (AC-005, all).** Confirm `skillbill.error.core.RuntimeFailureCode` and the coded `SkillBillRuntimeException(code, message, cause)` exist. If they don't, add them exactly as the parent spec's "Target failure model" defines them, including `LegacyFailureCode` and the open transition shape. Check whether `../../../runtime-kotlin/runtime-core/src/repoTest/kotlin/skillbill/architecture/baselines/custom-throwable-baseline.txt` exists (T13 uses it).

**T1. Goal execution lease: already running as a value (AC-001, AC-002, AC-004).**
- In `runtime-engine/.../goalrunner/execution/core/GoalRunnerExecutionCoordinator.kt`, delete `GoalRunnerExecutionAlreadyRunningException`. Add `sealed interface GoalRunnerOwnedRun<out T>` with `data class Completed<T>(val value: T)` and `data class AlreadyRunning(val reason: String) : GoalRunnerOwnedRun<Nothing>`. `reason` keeps the text `"Goal parent '$parentWorkflowId' cannot start: $detail"`, built by one private function because there are three sites.
- `runOwned` and `runOwnedWithChildAdmission` return `GoalRunnerOwnedRun<T>`.
  - `reclaimableOwnerToken`, `reclaimAfterLiveOwner` and `cannotStart` stop returning `Nothing`. They return a private sealed lease claim (a reclaimable token, or blocked with detail), and `acquireLease` returns a private sealed acquisition (acquired lease, or already running).
  - A false `acquire…` result becomes `AlreadyRunning`.
- In `completeRun`, a body failure still wins and is thrown with the teardown failure suppressed. When the body succeeds and fencing is lost, return `AlreadyRunning(reason)`. Keep today's observable behaviour: a teardown failure in that case was suppressed onto an exception `GoalRunner` caught and discarded, so it stays unreported. Note this as pre-existing in the review. When there is no body failure and no fencing loss, a teardown failure is still thrown.
- `GoalRunner.kt:48-78`: replace the `try`/`catch` with a `when` on the returned value. `AlreadyRunning` builds the same `stopped(StoppedReportArgs(...))`, with `blockedReason = reason` (same text as the old `message`).
- Update `runtime-engine/src/testFixtures/.../GoalRunnerExecutionCoordinatorFixture.kt` to the new return type.
- Tests:
  - `GoalRunnerExecutionCoordinatorTest`: the `assertFailsWith<GoalRunnerExecutionAlreadyRunningException>` checks become `assertIs<GoalRunnerOwnedRun.AlreadyRunning>` plus the existing message checks against `reason`. Keep the "must not enter the goal body" lambdas, which still prove the body never runs.
  - Add one test at the `GoalRunner.run` boundary only if no existing test covers it. It asserts that an `AlreadyRunning` result yields a `Stopped` report with `BLOCKED`, the reason as the blocked reason, and `lastResumableStep` falling back to `plan`. Realistic bug: an `AlreadyRunning` value dropped and reported as completed.

**T2. Launch authorization denial as a value (AC-001, AC-002, AC-004).**
- `runtime-ports/.../agentrun/model/AgentRunLauncherModels.kt`:
  - `AgentRunSpawnAuthorization.withAuthorization` returns `AgentRunSpawnAuthorizationResult<T>`.
  - Add `sealed interface AgentRunSpawnAuthorizationResult<out T>` with `data class Authorized<T>(val value: T)` and `data class Denied(val pauseReason: String?) : AgentRunSpawnAuthorizationResult<Nothing>`.
  - Add the launch outcome variant `data class AgentRunLaunchDenied(override val agent: SupportedAgent, val pauseReason: String?) : AgentRunLaunchOutcome`.
  - These are data classes and a sealed interface, which `PortsDeclarationArchitectureTest.forbiddenTopLevelClassViolations` accepts.
- `GoalRunnerControlCoordinator.kt:211-261`: both authorization objects return `Denied(controls.pauseReason)` from the transaction instead of throwing, and `Authorized(spawn())` otherwise. Delete `runtime-contracts/.../error/goalrunner/GoalRunnerLaunchAuthorizationDeniedException.kt`. That empties `skillbill.error.goalrunner`; delete the package directory. No doc, guard or baseline names the package.
- `runtime-infra/launcher`:
  - `JvmAgentRunProcessRunner.runGoverned`: a `Denied` result returns an `AgentRunProcessResult` carrying the denial, through a new nullable field `spawnDenied: AgentRunSpawnAuthorizationResult.Denied?`. It starts no process. Keep the existing `runCatching { … }.getOrElse { cleanup; throw }` shape; it rethrows every failure after cleanup.
  - `ProcessAgentRunAdapter.launch` (`AgentRunAdapters.kt:35`) returns `AgentRunLaunchOutcome`. When `spawnDenied` is set, it returns `AgentRunLaunchDenied(agent, pauseReason)` before decoding or the `spawnFailed`/`processStarted` `require`.
  - `FileSystemAgentRunLauncher` passes the outcome through.
- Engine consumers of `AgentRunLaunchOutcome`: let the compiler find every exhaustive `when`.
  - **Planning path.** `DefaultPhaseRunner.classify` returns `null` for `AgentRunLaunchDenied`. The denial travels in `PhaseStepOutput.launchOutcome`, and only the untracked planning launch sets `PhaseStepFacts.spawnAuthorization` (`GoalPlanningPhaseAttemptGateLaunch.kt:48`). This stays inside the one generic runner path, with no phase-specific branch (decision `runtime-kotlin/agent/decisions.md#01a41a7ed8b3`). In `GoalPlanningPhaseAttemptGateBurstCap.produceAttemptAfterPauseCheck`, replace the second `runCatching` with a direct call and branch on `outcome is AgentRunLaunchDenied`: return `planningPauseOutcome(shared, currentSubtaskId, phaseId, outcome.pauseReason) ?: error("planning pause outcome was unexpectedly absent")`.
  - **Subtask path.** In `GoalRunnerSelectedSubtaskLoop`:
    - `launchAndReconcileSubtask`/`launchSubtaskWithWorkerResult` return a private sealed result with a denied variant when `subtaskLauncher.launch` returns `AgentRunLaunchDenied`. Skip `reconcileLaunchOutcome` and `workerRequestHandler.handle`, as the old unwind did.
    - `authorizeAndLaunchSelectedSubtask` maps denied to `SelectedSubtaskLaunch.Stopped(deniedLaunchPause(prepared, manifestStore.controlState(prepared.attemptedState.parentWorkflowId)))`, identical to the old catch body.
  - **Other exhaustive `when` sites.** At sites that are never reached because they never carry an authorization (review lanes in `runtime-application`, `toGoalRunnerLaunchFacts`, `GoalPlanningSweepOutcomeDerivationChildStatus`, ledger and observability), add the arm. Group it with `UnsupportedAgentRunLaunch` where the branch only means "no process launched". Otherwise use `error("<site> never launches with a spawn authorization")`. Never map a denial into persisted launch facts.
- Update test doubles that implement `AgentRunSpawnAuthorization`.
- Tests (each guards an irreversible side effect or a pause boundary):
  - A `JvmAgentRunProcessRunner` (or adapter) test: an authorization returning `Denied` produces `AgentRunLaunchDenied` and the process builder is never started. Realistic bug: a spawn despite a durable pause.
  - A planning sweep test: a denied planning launch yields the planning pause outcome with the control `pauseReason`.
  - A selected-subtask loop test: a denied subtask launch yields the durable pause and deletes the assigned child workflow when `openWithAssignedId` is set. Reuse existing tests where they already cover the throw path; they become value edits.

**T3. Shared preplan refresh refusal as a value (AC-001, AC-002).**
- In `runtime-engine/.../goalrunner/planning/context/GoalPlanningSharedPreplanSettlement.kt`, delete `RefreshRefused`. Add `internal sealed interface SharedPreplanRefresh` with `Refreshed(val refreshed: RefreshedSharedPreplan)` and `Refused(val reason: String)`.
- `refreshStaleSharedPreplan` returns `Result<SharedPreplanRefresh>`. Inside the existing body, `refuseRefreshReason(...)?.let { return@runCatching SharedPreplanRefresh.Refused(it) }`. `refreshLiveness.resolve(state)` stays inside the wrapped body, so its failures still halt with their message.
- The failure filter becomes `getOrElse { error -> error.rethrowIfCooperativeCancellationOrInterruption(); … }` (application helper). Keep whatever required-write handling subtask 2 left there.
- `settleStaleValidSharedPreplan` and `reclassifyAfterStaleRefresh` branch with `when`. `Refused` gives `Halt(stopped(working, 0, reason, PHASE_PREPLAN))`, the same text as before. `refreshHaltOutcome` keeps only the message path.
- Tests: existing refresh-refused coverage becomes value edits. If none asserts the refused halt reason, add one: a refused refresh halts at preplan with the refusal reason and no shared-preplan write.

**T4. Unaddressed-findings ledger absence as null (AC-001, AC-002, AC-004).**
- `UnaddressedFindingsLedgerService` (`runtime-engine/.../goalrunner/findings/`): `ledger`, `verificationDispositions` and `repairLedgersByWorkflow` return a nullable result (`null` when `issueExists` is false). Delete `UnaddressedFindingsLedgerAbsentError` from `runtime-contracts/.../error/shellcontent/GovernedReviewShellContentErrors.kt`.
- `ResolveUnaddressedFindingsLedger.kt`: `service.ledger(issueKey) ?: UnaddressedFindingsLedger(issueKey, emptyList())`. Keep the `InvalidUnaddressedFindingsLedgerSchemaError` catch (subtask 4 owns it). Make `GoalRunnerFinalization.resolveFindingsLedger` delegate to `resolveUnaddressedFindingsLedger` to remove the duplicate body.
- `GoalFindingsCommand` (`runtime-cli/.../goal/run/GoalCliRunCommands.kt:257`): when any of the three reads returns null, throw `SkillBillRuntimeException(GovernedReviewFailureCode.UNADDRESSED_FINDINGS_LEDGER_ABSENT, "No goal exists for issue key '$issueKey'.")`. This is a tier-3 operator failure. It routes through the unchanged `CliRuntime` `SkillBillRuntimeException` arm: same stderr, exit code 1.
  - Declare `enum class GovernedReviewFailureCode : RuntimeFailureCode { UNADDRESSED_FINDINGS_LEDGER_ABSENT }` in `GovernedReviewShellContentErrors.kt`, the package that declared the replaced class, per the parent rule. Subtask 4 extends this same enum for the file's other classes.
- Tests: `UnaddressedFindingsLedgerServiceTest` assertions move from the exception type to `assertNull`. Add one CLI test, only if none exists: `goal findings --issue-key <unknown>` gives exit 1, empty stdout and stderr `No goal exists for issue key '<KEY>'.`.

**T5. Spec intent source unavailable as a value (AC-001, AC-002).**
- `SpecIntentProjectionExtractor.kt`: delete `SpecIntentSourceUnavailable`. Add `internal sealed interface SpecIntentSourceRead<out T>` with `Read<T>(val value: T)` and `Unavailable(val specPath: String, val reason: String, val cause: Throwable?)`.
  - `extract` and `surroundingContext` return it and drop the `explicit` flag.
  - `readSpecBytes`, `fail` and the `InvalidReviewContextSchemaError` and `IOException` catches return `Unavailable`. The narrow catches stay, keeping `rethrowIfCooperativeCancellationOrInterruption()` on the I/O path.
- `SpecIntentProjectionResolver.kt`:
  - **Explicit path (`resolve` line 34):** `Unavailable` throws `UnreadableSpecIntentProjectionError(specPath, reason, cause)`, the same arguments and message as today; subtask 4 owns that class.
  - **Manifest, glob and surrounding paths (`:90`, `:126`, `:156`):** branch with `when` and record the same `SpecIntentDegradationRecord`s using `Unavailable.specPath`.
- Tests: `ApplicationCooperativeFailureBoundaryTest:88` asserts `Unavailable` (reason `unreadable`) instead of the type. Existing resolver tests cover the degradations. Add one only if none asserts the manifest-rung `resolvedPath`: a missing owning sub-spec records a `NO_SPEC_FOUND` degradation with that path.

**T6. Missing carried-forward review result as null (AC-001, AC-002, AC-004).**
- In `InlineReviewPreparation.settleCarriedForward` (`runtime-engine/.../slot/codereview/InlineReviewPreparation.kt:168`):
  - Read `state.carriedForwardReviewResult()` and parse it inside `runCatching { … }.getOrElse { error -> error.rethrowIfCooperativeCancellationOrInterruption(); block("malformed: ${error.message.orEmpty()}") }`.
  - A null result blocks with `"missing."`.
  - Both reasons keep the exact prefix `"Goal-subtask review pass budget is exhausted but its durable raw review result is "`.
- Delete `MissingCarriedForwardGoalReviewResultException`.
- Test: add one only if none exists. An exhausted pass budget with no carried-forward result blocks with the `…is missing.` reason and `NEEDS_USER_ACTION`.

**T7. Review planning returns a sealed result (AC-001, AC-002, AC-004).**
- **Types.**
  - `runtime-application/.../reviewevidence/model/ReviewEvidenceScopeModels.kt`: replace `DiffResolutionException` with `sealed interface DiffResolution<out T>` (`Resolved<T>(val value: T)`, `Unresolved(val message: String) : DiffResolution<Nothing>`).
  - `runtime-application/.../review/model/ParallelCodeReviewModels.kt`: replace `UsageValidationException` and `StackDetectionException` with:
    - `sealed interface ParallelCodeReviewPlanningFailure { val message: String }`, with `UsageInvalid`, `StackUndetected` and `DiffUnresolved` data classes;
    - `sealed interface ParallelCodeReviewPlanned<out T>` (`Ready`, `Failed(failure)`);
    - `sealed interface ParallelCodeReviewRunOutcome` (`Reviewed(val result: ParallelCodeReviewResult)`, `PlanningFailed(val failure: ParallelCodeReviewPlanningFailure)`).
  - `reviewevidence` must not import `review.model`, to avoid a package cycle. That's why diff helpers there use `DiffResolution`.
  - Add no `map`/`flatMap` combinators. Callers use `when` with early returns.
- **Helpers return instead of throwing.** Every message stays byte-identical.
  - In `ParallelCodeReviewRunnerPlanningLaneMap.kt`:
    - `resolveDiff`, `resolveWorktreeFromBaseDiff` and `queryDiff` return `DiffResolution<String>`.
    - `resolveAgent` returns `ParallelCodeReviewPlanned<SupportedAgent>` and replaces its `runCatching` with `SupportedAgent.entries.firstOrNull { it.id == agentId.trim().lowercase() }`.
    - `detectStack` returns `ParallelCodeReviewPlanned<ParallelCodeReviewStackDetection>`; its `runCatching` uses the cooperative rethrow before building `StackUndetected` with today's text.
  - In `ParallelCodeReviewRunnerPlanningRevisions.kt`, `resolveReviewRevisions`, `canonicalRange`, `declaredRange`, `canonicalRevision`, `detectPrBase` and `detectBranchBase` return `DiffResolution<…>`.
  - In `SharedReviewEvidenceAssembly.kt`, `SharedReviewEvidenceAssembler.assemble`, `revList`, `readCommit` and `SharedReviewEvidenceProjection.project`/`verifyCoverage` return `DiffResolution<…>`.
  - In `SharedReviewEvidenceResolution.resolve`, the aggregate-diff supplier and the result are `DiffResolution`. The port deriver closure records the first `Unresolved` in a local and returns `null`; the `?: derive()` decode fallback also handles `Unresolved`.
- **Shared-evidence port (nullable, no new port types).**
  - `FeatureTaskRuntimeSharedEvidenceDeriver.derive` returns `FeatureTaskRuntimeSharedEvidenceDerivation?`, and `FeatureTaskRuntimeSharedEvidenceResolverPort.resolve` returns `FeatureTaskRuntimeSharedEvidenceResolution?`. It returns null exactly when the deriver returned null, and nothing is persisted.
  - Update `FileSystemFeatureTaskRuntimeSharedEvidenceStore.resolve` (`runtime-infra/workflow`): `deriver.derive(...) ?: return null` before `persist`.
  - `FeatureTaskRuntimeSharedReviewEvidenceResolver` (engine): `derive` logs the same `seam=shared_review_evidence_derive …` warning line, with `cause=Could not read the shared review evidence diff for $query.`, and returns null. `resolve` returns null when the port returns null; the catch is deleted. This is a JUL log line only; it loses the attached stack trace.
- **Entry points.**
  - `ParallelCodeReviewRunnerPlanning.prepareInitialRun` returns `ParallelCodeReviewPlanned<ParallelCodeReviewInitialRun>`, and `prepare` returns `ParallelCodeReviewPlanned<ParallelCodeReviewCompiledLaunches>`.
  - `ParallelCodeReviewRunner.run` returns `ParallelCodeReviewRunOutcome`, and `earlyEmptyDelta` returns `ParallelCodeReviewRunOutcome?`.
  - Split helpers to stay within detekt `ReturnCount` 4, `CyclomaticComplexMethod` 15 and `LongMethod` 70. Prefer `when` expressions over early-return chains.
  - Delete `ParallelCodeReviewEvidenceCoordinates.kt`.
- **Engine.**
  - `CodeReviewPass.review` (`CodeReviewSlot.kt:52`) returns `ParallelCodeReviewRunOutcome`; `InlineReviewPass` returns `Reviewed(...)`.
  - **`DelegatedReviewPass`.** The `PhaseStepSession` captures `planningFailure` beside `reviewed`. On `PlanningFailed` it returns `UnsupportedAgentRunLaunch(SupportedAgent.fromWire(agentId), failure.message)`, so the generic runner records no token usage and does only its read-only after-capture. After `runner.run`, a captured failure returns `PlanningFailed(failure)`.
  - **`CodeReviewStep.launch`.**
    - Keep line 175's `runCatching` expression unchanged. Its interruption semantics are F-007's concern.
    - Map `PlanningFailed` with `when`. `DiffUnresolved` gives `ReviewPassLaunch.Failed("Runtime-owned review could not resolve the child-owned diff: $message")`. `UsageInvalid` and `StackUndetected` give `ReviewPassLaunch.Failed("Runtime-owned review failed: $message", RETRYABLE)`.
    - Delete the four deleted-type arms from `launchFailure`, including the dead `ReviewContextBudgetExceededException` arm.
  - **Verify.** `VerifyDelegatedReviewer.review` returns `ParallelCodeReviewRunOutcome`. In `VerifyCodeReviewStep.delegatedSession`, capture the failure the same way and return `VerifyCodeReviewOutcome.Failed(failure.message)` before the step-result check. The `RuntimeOperationProvides` wiring `VerifyDelegatedReviewer(reviewRunner::run)` is unchanged.
    - **Deliberate behaviour change.** Today a delegated-verify planning exception escapes the operation to the edge (CLI arm `"<ClassName>: message"`). The class can't survive, so verify now fails its code-review step with the message. Read `VerifyOperation`'s failure mapping first; if an operation-level conversion exists, produce its exact text. Record this in the PR notes.
- **CLI.** Remove the three dead `runPhaseReview` arms and their imports (see Census corrections).
- Tests:
  - `ParallelCodeReviewRunnerTest` and `ReviewCommitSequenceResolverTest`: the `assertFailsWith<UsageValidationException|StackDetectionException|DiffResolutionException>` checks become `assertIs` on the returned failure variant, plus the existing message assertions.
  - Runner-returning tests unwrap `Reviewed` with one private helper inside each existing test file. No new test module.
  - Add one `CodeReviewStep` test per mapping branch, two in total, if not already covered: `DiffUnresolved` blocks `NEEDS_USER_ACTION` with the diff prefix; `UsageInvalid` blocks `RETRYABLE` with the review-failed prefix.
  - Add one `FeatureTaskRuntimeSharedReviewEvidenceResolver` test: an unreadable diff yields `null` and persists no evidence artifact.

**T8. Register parse seam and budget message (AC-001, AC-002).**
- In `ParallelCodeReviewRunnerFailureAdmission.kt`:
  - `parseLaneRegisterSeam` returns an internal sealed `LaneRegisterParse` (`Parsed(result)`, `Failed(seam, lane, detail)`). `detail` keeps the exact message text and the 200-char cause bound, and the non-blank `require` on seam and lane moves to the data class `init`. Keep the narrow IAE/ISE catches inside it; subtask 6 owns those.
  - `softAdmitFindings` uses `when`, with `Failed` → empty admission, and no `try`.
  - In `parallelCodeReviewCaptureLane`, drop the `is ReviewRegisterParseSeamException` rethrow; it is unreachable once the seam can't escape.
- Delete `ReviewRegisterParseSeamException` and `ReviewContextBudgetExceededException` from `ReviewContextBudgetModels.kt`, keeping `CAUSE_DETAIL_MAX_LENGTH` where `detail` uses it. Inline `"${outcome.type}: ${outcome.budgetKind.wireValue} ${outcome.observedValue} > ${outcome.configuredLimit}"` at `ParallelCodeReviewRunnerLaneLaunch.kt:222`.
- Test: `ParallelCodeReviewRegisterSeamTest:113` asserts the `Failed` value's seam, lane and bounded `detail`. Keep the regression coverage that the lane body is not echoed.

**T9. MCP profile failure as a value (AC-001, AC-002, AC-004).**
- In `runtime-domain/.../install/model/InstallPlanApplyModels.kt`, replace `ClaudeMcpProfileFailure` with:
  - `sealed interface McpRegistrationOutcome`, with `Applied(val mutation: McpMutationResult)` and `ProfilesFailed(val message: String, val succeeded: List<McpProfileOutcome>)`;
  - `enum class McpRegistrationFailureCode : RuntimeFailureCode { PROFILE_UPDATE_FAILED }`. Model packages may import `skillbill.error.core`; verify with the domain model-package import guard.
- `McpRegistrationOperations.register`/`unregister`/`profileFanOut` return `McpRegistrationOutcome`, with the same message text. Leave the per-profile `runCatching { mutate(...) }` capture as is.
- The port model `InstallMcpRegistrationResult` swaps `mutation` for `outcome: McpRegistrationOutcome`, and `FileSystemInstallMcpRegistration` passes it through.
- **Callers.**
  - `InstallRegisterMcpCommand`: `ProfilesFailed` throws `SkillBillRuntimeException(McpRegistrationFailureCode.PROFILE_UPDATE_FAILED, message)`. Same stderr and exit 1: the `IllegalArgumentException` and `SkillBillRuntimeException` arms of `CliRuntime` print the same one-line message when it is non-blank.
  - `InstallUnregisterMcpCommand`: `liveStdout` the changed succeeded paths, then throw the same.
  - `UninstallMutations.cleanupMcpRegistrations`: `ProfilesFailed` adds the changed succeeded paths to `removed`, then records the failure through a new `UninstallMutationRecorder.recordFailure(description, detail: String)`. It produces the identical `"$description: $detail"` line and calls `diagnostics.error(message)` with no throwable.
  - `InstallApplySideEffects.registerMcpAgent`: map `ProfilesFailed` to `failedMcpRegistrationOutcome` with the same `"<message>. Already updated: …"` text and `profiles = succeeded`.
    - Pass `causeClass` explicitly. The profile-failure path passes a private const holding the former wire value `"skillbill.install.model.ClaudeMcpProfileFailure"`. `cause_class` is emitted on the `install apply` payload (`InstallCliApplyPayloads.kt:123`), and AC-004 requires byte-identical stdout. Document the const as a retained wire value, not a type.
    - The touched `runCatching` gets the cooperative rethrow. Use an existing infra-skills helper, or inline the `CancellationException`/`InterruptedException` rethrow.
- Tests: `SkillBillUninstallCooperativeCancellationTest:89` makes its fake port return `ProfilesFailed` instead of throwing. Add one `InstallUnregisterMcpCommand` CLI test, if none exists: a partial profile failure prints the removed paths on stdout, then the failure message on stderr, with exit 1.

**T10. Skill removal refusal as a value (AC-001, AC-002, AC-004).**
- `runtime-domain/.../skillremove`:
  - Add `data class Refused(val reason: SkillRemovalRefusalReason, val message: String) : SkillRemovalResult()`.
  - `TargetValidation.validateOrRefuse` becomes `refusal(request): SkillRemovalResult.Refused?`.
  - Delete `SkillRemovalRefusedException.kt` and `SkillRemovalRefusal.kt` (`refuseSkillRemoval`).
- `runtime-application/.../scaffold/SkillRemove.kt`:
  - `enforceRefusalPolicy` returns `Refused?` with the same messages.
  - `previewRemoval` returns `SkillRemovalResult`: `Refused` or `Preview`.
  - `executeRemoval` checks the refusal inside `tryExecute`. A refusal returns `SkillRemovalResult.Failed(exceptionName = <private const "SkillRemovalRefusedException">, exceptionMessage = message, rollbackComplete = true)`, exactly what `mapSkillRemovalFailure` produced, so the non-dry-run payload stays byte-identical. Document the const as the retained `exception` wire value.
- `RemoveCliCommandExecution.kt`: delete the catch. A `Refused` branch prints `errorPayload(refusalErrorMessage(refused, rawTarget, repoRoot))` with exit 1, and `refusalErrorMessage` takes the value.
- Tests:
  - `SkillRemoveTest:60`, `:137` and `:188` assert `Refused` and its `reason`.
  - `SkillRemoveTest:244` (the generic-`SkillBillRuntimeException` mapping) throws a coded `SkillBillRuntimeException` from the fake instead of the deleted class.
  - Add one test: a non-dry-run shipped-skill removal without `--allow-shipped` returns `Failed` with `exceptionName "SkillRemovalRefusedException"`, `rollbackComplete = true` and the refusal message. That pins the retained wire value.

**T11. Config parsers return `Invalid` directly (AC-001).**
- In `ExecutionMatrixModels.kt`, `CompactionSettingsModels.kt` and `ValidationGateRepoConfigModels.kt`, delete the private exception classes and the `invalid…(): Nothing` throwers. Restructure each parser as validate-then-build:
  - Private check functions return the file's `…Parse.Invalid?`, built with the same `keyPath`, `value?.toString() ?: "null"` and `reason` strings. Map entries are walked with `firstNotNullOfOrNull`, preserving today's first-failure order.
  - The public `parse…` returns that `Invalid` or `Valid(build(raw))`.
  - Builders run only on validated input; use `checkNotNull`/`check` for impossible states.
  - Keep `parseAgentDirectives`' collision `require` and `PhaseCompactionDirective`'s `require`s.
- Public signatures are unchanged.
- Tests: none added. The existing parser tests pin every `Invalid` and its order.

**T12. Remove `Throwable.message` branching (AC-003).**
1. **Mode mismatch.** In `GoalContinuationArtifactCodec.taskRuntimeRecordOrNull`, read `workflowStates.getFeatureTaskWorkflow(workflowId)` first. Return null when it is absent or its mode isn't the `TASK_RUNTIME` family's mode (use the existing family-to-mode mapping, not a literal). Otherwise call `get(TASK_RUNTIME, …)`, letting other `InvalidWorkflowStateSchemaError`s propagate; delete the catch. Test: a workflow stored under another mode returns null, and a malformed `TASK_RUNTIME` row still throws.
2. **Incomplete plan payload.** In `GoalPlanningPreparationCheckpoint`, make the non-completed-payload divergence (`nonCompletedPlanPayloadReason`) a value:
   - `recoveryProgress` returns a sealed result: `Ready(progress)`, or `IncompletePlan(parentGoalWorkflowId, subtaskId, reason)` returned at the first such descriptor, keeping descriptor order.
   - The order, sub-spec-hash and provenance divergences keep throwing `IncompatibleGoalPlanningPreparationRecoveryError` (subtask 4).
   - Add a fields-based overload of `goalPlanningPreparationStateReadStopReason(reason, recordedSubtaskId, issueKey, subtaskId)`, which the throwable version delegates to, so the stop text is identical.
   - `GoalPlanningRunProgress` stops `IncompletePlan` at subtask 0 / `PHASE_PREPLAN`, and its touched `runCatching` gets the cooperative rethrow. `recoverySubtaskId` reduces to `(error as? IncompatibleGoalPlanningPreparationRecoveryError)?.subtaskId ?: 0`.
   - Test: a stored plan with a non-completed status stops at subtask 0 in preplan with the "must be completed with non-empty produced_outputs" reason.
3. **Decomposition duplicates.** In `DecompositionManifestSchemaValidator`, build `yamlMapper` with `DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY` instead of `JsonParser.Feature.STRICT_DUPLICATE_DETECTION`. Add `catch (error: MismatchedInputException)` → `duplicate_key` (`DecompositionManifestValidationFailureCode.DUPLICATE_KEY.wireValue`), with the same fixed reason, ahead of `JsonProcessingException` → `malformed`. Test: malformed YAML whose offending line contains the word "duplicate" classifies `malformed`; Jackson's snippet-bearing message misclassified it before. The existing duplicate test keeps `duplicate_key`.
4. **Strict phase-output duplicates.** In `StrictPhaseOutputParser`, apply the same mapper change to both mappers and catch `MismatchedInputException` → `DUPLICATE_KEY`, then delete `DUPLICATE_FIELD_OR_KEY`. Test: same shape as item 3, with malformed YAML whose snippet contains "duplicate key" → `MALFORMED`.
5. **SQLite busy.** In `SQLiteDatabaseSessionFactory.isSqliteBusy`, walk the cause chain for `org.sqlite.SQLiteException` with `resultCode.code and 0xFF == SQLiteErrorCode.SQLITE_BUSY.code`, which covers the extended BUSY codes. `DatabaseRuntime` already imports `org.sqlite`.
   - Test:
     - `SQLiteDatabaseSessionFactoryBusyTranslationTest` already uses `SQLiteErrorCode.SQLITE_BUSY`.
     - Switch the fixture at `SQLiteDatabaseSessionFactoryTest:57` from a plain `SQLException` to `SQLiteException(…, SQLiteErrorCode.SQLITE_BUSY)`. That is the driver's real shape; a plain `SQLException` busy carries no result code.
     - Add one test: a non-busy `SQLiteException` whose message contains "database is locked" is not translated.
6. **Recovery classifier.**
   - Delete the `else -> current.message` branch in `causeIndicatesContractVersionHardReset`, and add an `is IncompatibleGoalPlanningPreparationRecoveryError -> current.reason` arm.
   - `GoalChildPlanningHydrator.importedPayloadRecoveryError` classifies with `classifyGoalPlanningRecovery("", error)`. The caught error is always one of the two typed schema errors, and their typed `reason`/`fieldPath` hold the same keywords as their messages.
   - `statusRecoverabilityOrRefuse` classifies with `(error as? IncompatibleGoalPlanningPreparationRecoveryError)?.let(::classifyGoalPlanningRecovery) ?: classifyGoalPlanningRecovery("", error)`, and adds the cooperative rethrow.
   - Before landing, census the producers of contract-version reasons (`contract_version`, `hard-reset`, `must be the constant value`). Confirm that each throws one of the three typed classes or wraps one as a cause. The census so far found `GoalPlanningPreparationSchemaValidator.kt:37`, which throws the typed schema error.
   - Test: an untyped cause whose message mentions "hard reset" now classifies `SCOPED_REPLAN`. Realistic bug: a remedy text echoed in an unrelated failure forcing a hard-reset remedy. The existing `GoalPlanningRecoveryClassificationTest` cases stay green unchanged.

**T13. Baseline and residual references (AC-001, AC-005).** If `custom-throwable-baseline.txt` exists, delete by hand the rows for every deleted class, using the module ids subtask 1 recorded and comparing rows as whole strings. The deleted classes are:
- `runtime-engine`: `RefreshRefused`, `GoalRunnerExecutionAlreadyRunningException`, `MissingCarriedForwardGoalReviewResultException`
- `runtime-contracts`: `GoalRunnerLaunchAuthorizationDeniedException`, `UnaddressedFindingsLedgerAbsentError`
- `runtime-application`: `SpecIntentSourceUnavailable`, `UsageValidationException`, `StackDetectionException`, `DiffResolutionException`
- `runtime-domain`: `ReviewContextBudgetExceededException`, `ReviewRegisterParseSeamException`, `ClaudeMcpProfileFailure`, `SkillRemovalRefusedException`, `InvalidExecutionMatrix`, `InvalidCompactionSettings`, `InvalidValidationGateRepoConfig`

Then grep main sources for each deleted name. Only the two documented wire-value strings, `"SkillRemovalRefusedException"` and `"skillbill.install.model.ClaudeMcpProfileFailure"`, may remain. Grep main for `.message` combined with `contains`, `startsWith`, `endsWith`, `matches`, `containsMatchIn` or `==`, including messages first captured into locals; nothing may remain. These are text searches, not builds.

### Constraints

- Messages, payload keys and exit codes stay byte-identical; type-to-value edits are the only assertion changes in the AC-004 flows. Fixture edits that turn a thrown fake into a returned value count as value edits.
- No `Result`/`Either` library, no shared generic combinators, no typealias named after a deleted class, no new property on `SkillBillRuntimeException`, no new module, test-helper module or architecture-test class, and no `@Suppress`. The scoped generic sealed types above (`GoalRunnerOwnedRun`, `AgentRunSpawnAuthorizationResult`, `DiffResolution`, `ParallelCodeReviewPlanned`, `SpecIntentSourceRead`) are plain domain results, each used with `when`.
- No new `runCatching`. Every touched `runCatching` uses the cooperative rethrow or becomes a direct call. The listed exceptions stay byte-unchanged: `CodeReviewStep.launch`, `JvmAgentRunProcessRunner.runGoverned` and `profileFanOut`, which either rethrow everything or are untouched.
- Where cooperative rethrow makes `CancellationException`/`InterruptedException` propagate from a site that used to convert them (T3, T6, T9, T12.2, T12.6), that is the parent constraint's sanctioned option; note it in the review.
- Stay within detekt limits: `ReturnCount` 4, `ThrowsCount` 2, `LongMethod` 70, `CyclomaticComplexMethod` 15, `NestedBlockDepth` 6. Split helpers rather than suppress.
- Ports additions are data classes or sealed interfaces only, and add no repository-driving function (decision `runtime-kotlin/agent/decisions.md#d50a10af8ef0`). Domain additions import no ports and no `java.nio`.
- Out of scope, left for later subtasks:
  - `SkillBillRollbackException`, `RuntimeOwnedFactUnavailable`, `DatabaseAccessError`, `DatabaseBusyError` and the remaining shell-content classes, including `IncompatibleGoalPlanningPreparationRecoveryError`, `UnreadableSpecIntentProjectionError` and `InvalidUnaddressedFindingsLedgerSchemaError`;
  - IAE/ISE catches not named here;
  - the CLI and MCP top-level arms.
- This phase plans only. Implementation, compile proof and every gate (build, unit tests, detekt, the runtime-core repoTest suite including `FailureCodeTotalityArchitectureTest`) belong to the implement, build and validate phases.

## Next Path

skill-bill goal SKILL-398

## Spec Path

.feature-specs/SKILL-398-runtime-exception-reduction/spec_subtask_3_local-control-flow-results.md
