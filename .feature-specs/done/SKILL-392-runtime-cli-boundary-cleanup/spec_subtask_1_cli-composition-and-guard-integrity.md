# SKILL-392 Subtask 1 - CLI composition and guard integrity

Parent spec: [.feature-specs/SKILL-392-runtime-cli-boundary-cleanup/spec.md](spec.md)
Issue key: SKILL-392

## Scope

Covers F-002 through F-010 from [investigation.md](investigation.md). Changes stay in runtime-kotlin/runtime-cli/src/main, plus:
- `InjectConstructorDefaultsArchitectureTest.kt`, `ArchitectureScanGuardSupport.kt`, `ApplicationPackageAcyclicityArchitectureTest.kt` and `PrincipleEnforcementInventory.kt` under runtime-core/src/repoTest;
- four runtime-cli tests that construct `CliRunInputs`;
- the package-cycle sentence in runtime-kotlin/ARCHITECTURE.md;
- the SKILL-229 "prefer a holder" line in runtime-cli/agent/history.md.

Changes:
- **F-002.** Replace the ten collaborator-carrying argument data classes with `@Inject` classes that own their collaborators as private constructor parameters. Argument types keep only values. Extend the existing inject-property scanner with a data-class rule for `RUNTIME_CLI_MAIN`.
- **F-003.** Move the single-consumer helpers out of `goal.core`, `install.core` and `scaffold.commands` into their consumer packages. Run the runtime-cli cycle census at exact-package granularity.
- **F-004.** Move `usageError` and `StandaloneCodeReviewTarget.kt` into `skillbill.cli.kernel.cli`, replace the two inline copies, and remove `codereview` from `cliSharedLeafAreas`.
- **F-005.** Inject `RepositoryEnclosingRootPort` directly and remove it from `CliRunInputs`.
- **F-006.** Settle every result through the `CliRunState` methods and make its `result` setter private.
- **F-007.** Merge the `new` and `new-skill` command bodies.
- **F-008.** Declare the absent-projection lookup status vocabulary once.
- **F-009.** Build each option choice map once from its owner.
- **F-010.** Give CLI add-on initial resolution and entry rendering one owner each.

## Acceptance Criteria

1. runtime-cli main declares no data class with a property of type `CliRunState`, `Clock`, or a type whose simple name ends in `Service`, `Port`, `Gateway`, `Lookup`, `Repository`, `Coordinator`, `Runner`, `Launcher` or `Diagnostics`, except the public `CliRuntimeContext`. None of `NativeScaffoldRunArgs`, `AssistedScaffoldWizardArgs`, `ScaffoldWizardArgs`, `CreateAndFillArgs`, `NewAddonPayloadArgs`, `EditSkillRunArgs`, `FillSkillRunArgs`, `GoalRunInputValidationArgs`, `GoalRunAgentAddonHydrationArgs` or `VerifyRuntimeResumeArgs` carries a collaborator. The behavior those types fed lives on `@Inject` classes whose constructor parameters are all private.
2. `InjectConstructorDefaultsArchitectureTest` contains a test that applies the AC 1 rule to every `internal data class` under `PrincipleEnforcementInventory.RUNTIME_CLI_MAIN` with no baseline. It also contains a synthetic source case that the rule reports. The rule lives in the existing scanner support file.
3. `CliRunInputs` has no `RepositoryEnclosingRootPort` property. Every runtime-cli main use of that port receives it by injection or as an explicit parameter from an injected owner.
4. The runtime-cli package-cycle census in `ApplicationPackageAcyclicityArchitectureTest` uses `EXACT_PACKAGE_SCC` and equals the existing empty `runtime-cli-package-cycle-baseline.txt`. `goal.core`, `install.core` and `scaffold.commands` are imported by no other package in their area. runtime-kotlin/ARCHITECTURE.md states that runtime-cli uses exact-package SCC enforcement.
5. `PrincipleEnforcementInventory.cliSharedLeafAreas` is exactly {kernel, model}, and `RuntimeCliAreaIsolationArchitectureTest` passes. runtime-cli main contains one definition of the `UsageError`-wrapping helper, and neither feature-task nor goal code re-implements it for code-review mode parsing.
6. `CliRunState.result` cannot be assigned outside `CliRunState`. runtime-cli main constructs `CliExecutionResult` only in `skillbill.cli.core`, `skillbill.cli.model` and `CliRunState`.
7. `NewSkillCommand` and `NewCommand` share one command body. Both remain registered, in the same order, with unchanged names, help and options.
8. The `not_found`/`ok` lookup-status tokens are declared once in `skillbill.cli.kernel.payload`, and no other runtime-cli main file contains those literals as status values.
9. The `--scope` choices in `LearningCliCommands.kt` derive from `LearningScope` wire names. `InstallRequestCommand` maps each option to its enum through the choice declaration and has no `when` fallback for values the choice already rejects.
10. runtime-cli main has one function that performs initial add-on selection resolution with the configured external sources, and one function that renders a hydrated selection's entries. Both the `agent-addon` and `goal` areas use them.
11. For every command group, the subcommand registration order and help output match `ae23f4f28`. `CliRuntimeShellCommandsTest`, `CliAuthoringParityTest`, the goal and feature-task CLI suites, and the runtime-mcp parity tests pass without changes to their assertions.
12. No detekt suppression, detekt baseline entry, architecture baseline entry, new architecture-test class or new module is added. Every production package stays within its sibling limit.

## Non-Goals

- Moving feature-task run execution into the engine (subtask 2).
- Editing `UninstallCommand.kt`, which SKILL-388 changes.
- Changing any option name, token, JSON key, exit code or message text.
- Narrowing the 11 `IllegalArgumentException` catches.

## Dependency Notes

Depends on: none.
- This subtask waits for no other issue. If SKILL-388 or SKILL-389 (runtime-core) has landed, rebase onto it first, since both edit `PrincipleEnforcementInventory.kt`; otherwise implement against the current tree. SKILL-388 also edits repoTest scanner support. Whichever lands second rebases onto `PrincipleEnforcementInventory.kt` and `ArchitectureScanGuardSupport.kt` and keeps both changes.
- SKILL-393 (runtime-ports) switches the imports of `GoalCliFormatting.kt` and `GoalCliExitCodes.kt` from ports to engine types. Whichever lands second applies the other's change to the files where they now live.
- The scaffold cycle breaks as a consequence of AC 1: the new scaffold classes live in `scaffold.payload` and `scaffold.wizard`, and `scaffold.commands` imports them.
- Where `externalAddonOverlayService` is optional today (create-and-fill, new add-on), whether to register external sources becomes a per-call value; the collaborator does not become nullable.

## Validation Strategy

- **Build**: compiles runtime-cli and runtime-core, proving kotlin-inject resolution of the new classes and of the parent-provided port.
- **Validate**: runs the full project checks, including `:runtime-core:repoTest` and the runtime-cli and runtime-mcp suites. The unchanged parity suites are the evidence for byte-identical output.

## Implementation Details

This child covers subtask 1 only (F-002 to F-010). F-001, F-011 and F-012 belong to the subtask 2 child. Paths below are relative to `../../../runtime-kotlin/runtime-cli/src/main/kotlin/skillbill/cli` unless they start with `runtime-kotlin/`. The plan was checked against HEAD `677d01c00`. SKILL-388, SKILL-389, SKILL-393 and SKILL-395 have landed, so edit the current text of `PrincipleEnforcementInventory.kt`, the scanner support files and the goal formatting files, which already use the SKILL-393 engine imports.

### Ordered tasks

1. **F-005: inject `RepositoryEnclosingRootPort` (AC 3, AC 1).**
   - Delete `repositoryEnclosingRootPort` from `model/CliRunInputs.kt` and the matching argument in `core/CliRuntime.kt:44`. Keep the `RuntimeComponent` accessor and its `runtimeComponentInboundApi` pin, because `CliComponent` resolves the port through the parent `@Provides fun repositoryEnclosingRootPort()`.
   - Add `private val repositoryEnclosingRootPort: RepositoryEnclosingRootPort` to these classes: `FeatureTaskLookupCommand` and `FeatureTaskRuntimeRepairIdentityCommand` (`featuretask/FeatureTaskRuntimeControlCliCommands.kt`), `FeatureTaskRuntimeRunExecution` (10 parameters, still under detekt's 12), and `FeatureTaskRuntimeRunPreparation` (see task 5).
   - `goal/status/GoalCliStatusFormatting.kt`: `CliRunInputs.goalStatusRequest` gains an explicit `repositoryEnclosingRootPort` parameter. `GoalStatusCommand` and `GoalWatchCommand` in `goal/status/GoalCliStatusCommands.kt` inject the port and pass it.
   - Tests: remove the `repositoryEnclosingRootPort = CanonicalRepositoryRoot` line, and the import if it becomes unused, from `CliScaffoldPartialOutcomeTest`, `UninstallMutationFailurePolicyTest`, `IdeStatusReadSnapshotConcurrencyTest` and `scaffold/wizard/ScaffoldPlatformPackWizardPayloadTest`. Change no assertion. Subtask 2 moves `IdeStatusReadSnapshotConcurrencyTest`; this child only edits its constructor call.

2. **F-004: one `UsageError` helper, no `codereview` exemption (AC 5).**
   - Move `codereview/StandaloneCodeReviewTarget.kt` unchanged into `kernel/cli/` as `package skillbill.cli.kernel.cli`. `kernel.cli` goes from 7 to 8 files and `codereview` keeps only `CodeReviewCommand.kt`.
   - Move `usageError(error: Throwable): Nothing` from `codereview/CodeReviewCommand.kt:272` into `kernel/cli/DocumentedCliCommand.kt`.
   - Repoint the imports in `CodeReviewCommand.kt`, `operation/OperationCommand.kt` and `phase/PhaseCommand.kt` (`usageError`, `namedStandaloneScope`). `goal/run/GoalRunInputPreparation.kt:6` imports `usageError` but never uses it, because a local `val usageError` shadows it. Delete that import.
   - Replace the inline wrap-and-`initCause` bodies with `usageError(error)` in `parseCodeReviewMode` (`goal/run/GoalCliRunCommands.kt:90-99`) and `parseRequestedCodeReviewMode` (`featuretask/FeatureTaskRuntimeRunRequestAssembly.kt:138-145`). The output stays byte-identical: `RuntimeOwnedReviewMode.parse` always throws with a non-null message, so the `?: "Unknown code-review execution mode."` fallback never runs.
   - Leave `parseQualityGateSelection` (custom message) and `invalidAgentAddonSelection` (message plus cause) alone. Neither re-wraps a code-review mode parse failure.
   - Tests: add `skillbill.cli.kernel.cli.*` imports to `src/test/.../codereview/StandaloneCodeReviewTargetTest.kt` and `CodeReviewPhaseRequestTest.kt`. Change imports only.
   - Set `cliSharedLeafAreas` (`runtime-kotlin/runtime-core/src/repoTest/kotlin/skillbill/architecture/PrincipleEnforcementInventory.kt:102`) to `setOf("kernel", "model")`.

3. **F-006: private result setter and state-owned settlement (AC 6).**
   - `kernel/cli/CliRunState.kt`: `var result: CliExecutionResult? = null` becomes `private set`. `CliRuntime` keeps reading it.
   - Widen `completeText`'s `payload` to `Map<String, Any?>? = null`, so that payload-less text results keep `payload == null` exactly as they do today. Existing callers compile unchanged.
   - `install/nativeagent/InstallAgentPathCliCommands.kt`: the writes at :27 and :41 become `state.completeText(...)`, and the write at :73 becomes `state.completeText("")`.
   - `system/SystemCliCommands.kt`: `retiredSubjectResult` returns the message string, and the command calls `state.completeText(message, exitCode = 1)`.
   - `skillremove/`: `executeRemoveCommand(request, state)` settles through `state.complete(payload, format, exitCode)`. The preview, success, failed and error builders return payload maps, and exit codes stay 0/0/1/1. `RemoveCommandExecutionRequest` does not gain `state`, because that would break AC 1.
   - Scaffold settlement (lands with task 4): `errorResult`, `unsupportedNativeScaffoldResult` and `authoringResult` in `scaffold/payload/ScaffoldCliPayloadRuns.kt` become `CliRunState` extensions (`completeScaffoldError`, `completeUnsupportedScaffold`, `completeAuthoring`) that call `complete(...)`.
   - Keep the `try` scope of `completeAuthoring` the same as `authoringResult`, with `complete` inside the `try`, so a failing `CliOutput.emit` is still caught the same way.
   - Byte check: only `FeatureTaskRuntimeAliasCliCommands` calls `appendStderr`, so no rerouted path picks up stderr. `TEXT` and `IMPLICIT` print the same bytes in `emitCliProcessStdout`.
   - Afterwards `CliExecutionResult(` appears only in `core/`, `model/` and `CliRunState.kt`.

4. **F-002 scaffold, F-007, and the scaffold cycle (AC 1, AC 4, AC 7, AC 11).**
   - Add `internal data class NativeScaffoldRunOptions(val dryRun: Boolean, val format: CliFormat, val withExternalAddonOverlay: Boolean)` (values only) to `scaffold/payload/ScaffoldPayloadInputs.kt`. Move the value classes `CreateAndFillContentArgs` and `NewAddonPayloadArgs` there too, without `state`. `newAddonPayload(args, state)` keeps reading the body file at the same point it does today.
   - Add `@Inject class NativeScaffoldPayloadRun` to `scaffold/payload/ScaffoldCliPayloadRuns.kt`. Its private constructor parameters are `CliRunState`, `CliRunInputs`, `Clock`, `ScaffoldGateway` and `ExternalAddonOverlayService`. Its methods:
     - `runPayloadFile(payloadPath, options, transform)`
     - `runPayload(payload: Map<String, *>, options)`
     - a private `JsonObject` overload
     - `createAndFill(content, options)`
   - Each method settles on the state. `ScaffoldInvocationArgs.externalAddonOverlayService` receives `externalAddonOverlayService.takeIf { options.withExternalAddonOverlay }`. That is `true` for new, new-skill and new-addon, and `false` for create-and-fill, which matches today's null service. `registerExternalSources = true` stays.
   - Add `@Inject class ScaffoldWizardRun` to `scaffold/wizard/ScaffoldCliWizardRuns.kt`. Its private parameters are `CliRunState`, `CliRunInputs`, `ScaffoldCatalogGateway`, `InstallAgentService` and `NativeScaffoldPayloadRun`. It has `runWizard(options)` and `runAssistedWizard(options)`. The `collect*WizardPayload` free functions stay.
   - Move `scaffold/commands/AssistedPlatformProfile.kt` and `ScaffoldAssistedPlatformProfiles.kt` to `scaffold/wizard/`, renaming the package only.
   - Delete `scaffold/commands/ScaffoldCliArgs.kt`, which removes `NativeScaffoldRunArgs`, `AssistedScaffoldWizardArgs`, `ScaffoldWizardArgs`, `NativeScaffoldPayloadPathArgs` and `CreateAndFillArgs`.
   - Delete `EditSkillRunArgs`, `FillSkillRunArgs`, `editSkillResult` and `fillSkillResult` from `ScaffoldAuthoringCliCommandRuns.kt`. Their bodies become private members of `EditSkillCommand` and `FillSkillCommand`, using the private `state` and `scaffoldGateway`. `resolveRenderSkillName` stays.
   - F-007: in `ScaffoldNewCliCommands.kt`, add `abstract class NewSkillScaffoldCommand(name, private val state, private val payloadRun, private val wizardRun)`, following the `WorkflowGetCommand` pattern. It holds the existing help text, options in their current declaration order, and the `run()` dispatch. `@Inject class NewSkillCommand(...) : NewSkillScaffoldCommand("new-skill", ...)` and `@Inject class NewCommand(...) : NewSkillScaffoldCommand("new", ...)` pass plain parameters, not properties.
   - `CreateAndFillCommand` and `NewAddonCommand` inject `CliRunState` and `NativeScaffoldPayloadRun`. Leave `ScaffoldCliSubcommandGroups.kt` registration order unchanged.
   - After this task, `scaffold.payload` imports no other scaffold package, `scaffold.wizard` imports only `payload`, and `scaffold.commands` imports `payload` and `wizard`.
   - Test: `CliScaffoldPartialOutcomeTest` constructs `NativeScaffoldPayloadRun(...)`, calls `runPayload(map, NativeScaffoldRunOptions(false, CliFormat.JSON, true))`, and reads `state.result`. Its assertions do not change.

5. **F-002 goal and feature-task, and F-010 (AC 1, AC 10).**
   - Add `@Inject class ConfiguredAgentAddonSelectionResolver` in a new file `kernel/agent/ConfiguredAgentAddonSelectionResolver.kt`. `kernel.agent` goes from 4 to 5 files.
     - Its private parameters are `AgentAddonSelectionPort`, `ExternalAgentAddonSourceConfigPort` and `CliRunInputs`.
     - Its single method `resolveInitial(repoRoot, requestedSlugs, receivingAgentIds)` reads the configured external sources and then calls `resolveInitial` with `AgentAddonConsumer.SKILL_BILL`, in the same order as today.
     - The same file holds `internal fun HydratedAgentAddonSelection.toCliEntryMaps(): List<Map<String, Any?>>`, which produces the slug, source identity, content sha256 and description `linkedMapOf`.
   - `agentaddon/AgentAddonCliCommands.kt`:
     - The resolve command injects the resolver instead of the two ports, and calls it inside the existing `complete {}` try block.
     - Both commands render entries with `toCliEntryMaps()`.
   - `goal/run/GoalCliRunArgs.kt`: `GoalRunInputValidationArgs` and `GoalRunAgentAddonHydrationArgs` keep only values. Drop `inputs`, `executableLookup` and both ports.
   - `goal/run/GoalRunInputPreparation.kt` becomes `@Inject class GoalRunInputPreparation` with private parameters `ExecutableLookup`, `AgentAddonSelectionPort`, `ConfiguredAgentAddonSelectionResolver` and `CliRunInputs`. It has two methods:
     - `validate(args)`
     - `hydrateAgentAddonSelection(args)`, which calls the resolver for slugs and keeps `verifyPersisted` for persisted JSON.
   - `resolveInvokedAgentId` stays a free function.
   - `GoalRunCommand` (`goal/core/GoalCliCommands.kt`) injects `GoalRunInputPreparation` instead of the three ports, which brings it to 9 parameters. Leave the `ProcessHandle` line for subtask 2.
   - The goal area renders no entry map today, and this subtask adds none. AC 10's "both areas use them" holds because the goal area uses the shared resolution function and the agent-addon area uses both functions.
   - Fold `VerifyRuntimeResumeArgs` (`featuretask/FeatureTaskRuntimeCliFormatting.kt:37-45`) into `FeatureTaskRuntimeRunPreparation`:
     - Add the private injected port (7 parameters).
     - `verifyRuntimeResume(workflowId, issueKey, specPath, repoRoot, goalChild)` and `requireMatchingGovernedSpec(...)` become private members with at most 5 parameters each.
     - `resumableRuntimeCandidate` and `requireRuntimeMode` stay top-level and private.
     - Every `UsageError` text stays the same.

6. **F-003: move single-consumer helpers (AC 4, AC 12).**
   - `goal/core/GoalCliFormatting.kt` moves to `goal/control/` as is.
   - The control exit codes (`goalPauseExitCode`, `goalResumeExitCode`, `goalStopExitCode`, `goalResetExitCode`, `goalReplanExitCode`, `goalRepairExitCode`, `goalOperatorDecisionExitCode`, `goalAcceptExitCode`) go to a new file `goal/control/GoalCliControlExitCodes.kt`, keeping their current engine imports. `goal.control` goes from 3 to 5 files.
   - `goal/core/GoalCliWatchPresentation.kt` moves to `goal/status/`. Drop its now same-package `appendDiffStatusLines` import, and move `goalStatusExitCode` into that file. `goal.status` goes from 3 to 4 files.
   - `goal/core/GoalCliExitCodes.kt` keeps `GOAL_EXIT_*`, `goalRunExitCode` and the private `goalExitCode`.
   - `install/core/InstallCliPayloads.kt` moves to `install/apply/`, taking `install.apply` from 5 to 6 files. `install/core/InstallCliCommands.kt` imports it from `apply`, an edge that already exists.
   - Update the imports in `goal/control/GoalCliControlCommands.kt`, `GoalCliControlFormatting.kt`, `goal/status/GoalCliStatusCommands.kt` and `install/apply/InstallCliApplyPayloads.kt`.
   - Test imports only: `CliGoalResetOptionGateTest` (`goal.control.goalResetExitCode`) and `CliInstallPlanApplyRuntimeTest` (`install.apply.installPlanPayload`). `GoalRunExitCodeTest` stays as it is.
   - After this task, nothing in their area imports `goal.core`, `install.core` or `scaffold.commands`. Only the composition root `skillbill.cli.core` does.

7. **F-008: one status vocabulary (AC 8).**
   - Add `internal object CliPayloadStatus { const val OK = "ok"; const val NOT_FOUND = "not_found" }` in a new file `kernel/payload/CliPayloadStatus.kt`, taking `kernel.payload` from 3 to 4 files. Add a `fun of(found: Boolean): String` helper only if more than one site would use it.
   - Name the object without a `Keys` suffix and make it an object, not an enum, so the wire-vocabulary scanner does not treat it as a key or token owner. That avoids duplicate-token and restatement hits against other modules' `"ok"`.
   - Replace the 13 absent-projection literals: `FeatureTaskRuntimeStatusPresentation.kt:53,101`, the moved `GoalCliWatchPresentation.kt` (29, 40, 51), `GoalCliStatusFormatting.kt:82,176,178,247`, and `GoalCliControlFormatting.kt:44,65,212,250`.
   - The AC forbids those literals as status values in any other main file, so replace every other `"ok"` status value in runtime-cli main as well:
     - `GoalCliControlFormatting.kt:21,52,114`, the moved `GoalCliFormatting.kt:148`, `GoalCliStatusFormatting.kt:65`, `FeatureTaskRuntimeStatusPresentation.kt:14`
     - `GoalCliPurgeFormatting.kt:7,19,21`, including the `when` branch
     - `ScaffoldCliPayloadRuns.kt:82`
     - `RemoveCliCommandExecution.kt:126`
     - `config/*` (6 sites)
     - `install/apply/InstallReconcilePayloads.kt:15`, `InstallApplyExternalAddonsCommand.kt:54,69`
     - `workflow/WorkflowCliResultMappers.kt` (5 sites), `WorkflowContinueCliMaps.kt:29`
   - The other status literals (`partial`, `refused`, `error`, `failed`, `preview`, `unsupported`) stay. The bytes do not change.

8. **F-009: choice maps from owners (AC 9, AC 11).**
   - `learning/LearningCliCommands.kt`: add `private val learningScopeChoices = LearningScope.entries.associateBy(LearningScope::wireName)`. `LearningsAddCommand` and `LearningsEditCommand` use `.choice(learningScopeChoices)`. Entry order (global, repo, skill) matches today's keys.
   - `install/apply/InstallRequestCommand.kt`: each option maps to its type in the choice declaration, keeping the current key order:
     - `--telemetry`: `.choice(InstallTelemetryLevel.entries.associateBy(InstallTelemetryLevel::id)).default(ANONYMOUS)`
     - `--agent-mode`: `.choice("detected" to DETECTED, "manual" to MANUAL)`
     - `--platform-mode`: `.choice("none" to NONE, "selected" to SELECTED, "all" to ALL)`
     - `--mcp`: `.choice("register" to true, "skip" to false)`
     - `--windows-symlink-state` and `--windows-symlink-decision`: pairs to their enums. These enums have no wire value, so the pair list in the choice is their single CLI map.
   - Delete `telemetryLevel()`, `windowsSymlinkPreflightState()` and `windowsSymlinkPreflightDecision()` with their unreachable `else` branches. `selectedPlatformMode()` becomes `if (platforms.isNotEmpty()) SELECTED else platformMode`, and `selectedAgentMode` compares against the enum.
   - Help output stays the same, because Clikt prints choice keys in insertion order and does not show defaults.

9. **Guards (AC 2, AC 4, AC 5, AC 12).**
   - `../../../runtime-kotlin/runtime-core/src/repoTest/kotlin/skillbill/architecture/ArchitectureScanGuardSupport.kt` (735 lines now): next to the inject-property scanner, add the following.
     - An `INTERNAL_DATA_CLASS_PATTERN`.
     - The collaborator rule: exact names `CliRunState` and `Clock`, plus the suffixes `Service`, `Port`, `Gateway`, `Lookup`, `Repository`, `Coordinator`, `Runner`, `Launcher` and `Diagnostics`.
     - `ArchitectureScanSupport.dataClassCollaboratorPropertySites(scanRoot)` and `dataClassCollaboratorPropertySitesInSource(relativePath, source)`. These reuse `sourceWithoutCommentsOrLiterals`, `afterClassTypeParameters`, `extractBalanced`, `splitTopLevelParameters` and the `->` masking from `nonPrivateConstructorProperties`, and return `InjectConstructorDefaultSite`.
     - `dataClassCollaboratorPropertyViolations(scanRoot)`, with no baseline parameter.
     - The property type is read after `:`, with any top-level `= default` cut off. Then `?` and generic arguments are stripped and the simple name is taken after the last `.`. Function types are skipped.
   - `InjectConstructorDefaultsArchitectureTest.kt` gets two tests:
     - `runtime-cli internal data classes carry no collaborator property`: scans `PrincipleEnforcementInventory.RUNTIME_CLI_MAIN` and asserts an empty list.
     - One synthetic test. Its source has an `internal data class` with `state: CliRunState`, `clock: java.time.Clock`, `gateway: ScaffoldGateway`, `overlay: ExternalAddonOverlayService? = null`, a value `format: CliFormat` and a function-typed `transform`. The same source also has a public `data class` holding a `...Launcher` and an `@Inject` class. The test asserts that exactly state, clock, gateway and overlay are reported.
   - `PrincipleEnforcementInventory.kt:41`: add `runtime-cli` to the `EXACT_PACKAGE_SCC` condition, so `ArchitectureBaselineRecorder` records at the same granularity.
   - `ApplicationPackageAcyclicityArchitectureTest.kt:24-35`: keep the test name and make the body `assertPackageCyclesMatchBaseline("runtime-cli")`. That uses the scan case's exact SCC and compares against the existing empty `runtime-cli-package-cycle-baseline.txt`.
   - Add no new test class and no baseline entry.

10. **Docs (AC 4).**
    - `runtime-kotlin/ARCHITECTURE.md:675-679`: rewrite the sentence so exact declared-package SCC enforcement names `runtime-domain` (including nested model packages), `runtime-contracts` and `runtime-cli`. Other scan cases keep first-segment mutual pairs.
    - `runtime-kotlin/runtime-cli/agent/history.md:94`: replace "Prefer a holder over widening a command's parameter list." with a note that SKILL-392 supersedes it. Collaborators stay private on `@Inject` classes, and argument types carry values only.
    - Leave every other history line alone. The write_history phase adds this run's entry.

### Test obligations

- AC 2: the production-scan test and the synthetic rejection test from task 9. The realistic bug they catch: a collaborator-carrying argument bag reintroduced into runtime-cli, which every other guard accepts.
- AC 4: the delegated runtime-cli exact-SCC census. The realistic bug: a reintroduced hub back-edge such as `goal.control → goal.core`, which the first-segment algorithm cannot see.
- AC 11 needs no new test. The existing `CliRuntimeShellCommandsTest`, `CliAuthoringParityTest`, goal and feature-task CLI suites and runtime-mcp parity tests are the byte-identity evidence, and their assertions stay unchanged. The F-009 maps, F-008 constants and file moves are mechanical and need no new tests.
- Test edits outside the four `CliRunInputs` tests are limited to the imports and construction a moved or reshaped main symbol requires: `CliScaffoldPartialOutcomeTest`, the two `codereview` tests, `CliGoalResetOptionGateTest` and `CliInstallPlanApplyRuntimeTest`. No assertion changes.

### Constraints

- No `//` comments, no KDoc on classes, and no detekt suppression.
- Functions take at most 5 parameters (detekt `functionThreshold: 6`) and constructors at most 11 (`constructorThreshold: 12`).
- New `@Inject` classes have private constructor parameters, no default arguments and no non-private body properties with initializers.
- Production packages stay at 12 files or fewer and model packages at 20 or fewer. The resulting counts:

  | Package | Files |
  |---|---|
  | `scaffold.commands` | 7 |
  | `scaffold.payload` | 2 |
  | `scaffold.wizard` | 6 |
  | `goal.core` | 2 |
  | `goal.control` | 5 |
  | `goal.status` | 4 |
  | `install.core` | 3 |
  | `install.apply` | 6 |
  | `kernel.cli` | 8 |
  | `kernel.agent` | 5 |
  | `kernel.payload` | 4 |
  | `codereview` | 1 |
  | `featuretask` | 12 (unchanged) |

- New file names avoid the spillover suffixes `Helpers`, `Support`, `Misc` and `Extras`.
- Keep every option name, help string, message text, JSON key and order, exit code, and the subcommand registration order. The reference is `ae23f4f28`, which is tree-identical to `d13547842` apart from README.
- Do not edit `system/UninstallCommand.kt`, the `RuntimeComponent` accessor set, engine code, or another bundle's specs.
- Do not install, build, run tests or run check in this phase or the implement phase. The build phase proves kotlin-inject resolution of `NativeScaffoldPayloadRun`, `ScaffoldWizardRun`, `GoalRunInputPreparation`, `ConfiguredAgentAddonSelectionResolver` and the parent-provided port. The validate phase runs `./gradlew check`.

## Next Path

skill-bill goal SKILL-392

## Spec Path

.feature-specs/SKILL-392-runtime-cli-boundary-cleanup/spec_subtask_1_cli-composition-and-guard-integrity.md
