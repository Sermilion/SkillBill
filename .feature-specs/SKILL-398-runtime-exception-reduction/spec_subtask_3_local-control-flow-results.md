# SKILL-398 Subtask 3 - local-control-flow-results

Parent spec: [.feature-specs/SKILL-398-runtime-exception-reduction/spec.md](./spec.md)
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

## Next Path

skill-bill goal SKILL-398

## Spec Path

.feature-specs/SKILL-398-runtime-exception-reduction/spec_subtask_3_local-control-flow-results.md
