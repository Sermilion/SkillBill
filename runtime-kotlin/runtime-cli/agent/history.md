## [2026-10-01] SKILL-392 — CLI composition and guard integrity (subtask 1)
Areas: runtime-kotlin/runtime-cli (agentaddon, codereview, config, core, featuretask, goal, install, kernel, learning, model, scaffold, skillremove, workflow), runtime-core architecture repoTests, ARCHITECTURE.md
- RepositoryEnclosingRootPort is injected where used and no longer rides on CliRunInputs; CliRunState result setter is private and results settle through state methods.
- usageError and StandaloneCodeReviewTarget moved to kernel.cli, so codereview is no longer a shared leaf area.
- Collaborator-carrying argument data classes became @Inject classes; scaffold new/new-skill commands share an abstract base; agent-addon commands share one selection resolver and entry-map helper.
- Single-consumer helpers moved out of goal.core, install.core and scaffold.commands; goal control exit codes live in goal/control.
- CliPayloadStatus replaces ok/not_found literals; choice maps are built from their enum owners.
- reusable: data-class collaborator scanner guard; runtime-cli joined EXACT_PACKAGE_SCC and the acyclicity test delegates to the baseline census.
- Known limitation: build, spotless, detekt and tests were deferred to build and validate phases.
Feature flag: N/A
Acceptance criteria: 9/9 implemented

## [2026-09-30] SKILL-386 — cross-module contract ownership seen from the CLI (subtask 2)
Areas: runtime-kotlin/runtime-cli (scaffold, goal, agentaddon, kernel, system, workflow, featuretask), runtime-ports/scaffold, runtime-infra/{skills,launcher}, runtime-engine/goalrunner, runtime-contracts/agentaddon, runtime-core (RuntimeComponent, scaffold provides, architecture repoTests), ARCHITECTURE.md
- Deleted the UnsupportedScaffoldGateway and its RuntimeComponent accessor; retired scaffold modes now fail with the exact baseline error text, pinned by CliAuthoringParityTest.
- Goal pause/resume results carry typed status enums (pinned in the engine inbound API inventory) instead of raw strings; the CLI exit-code and presenter code consume the enums.
- The agent-addon contract version is one contracts constant, reused by the launcher command builders and the CLI selection messages.
- Continuation-candidate payload building lives in one kernel payload file shared by feature-task and workflow commands; malformed-JSON handling catches the single typed error.
- reusable PATTERN: a CLI-visible wire string or version gets one owning-module constant or enum; adapters consume it and never re-declare the literal.
- Repair: subtask 1 leftovers failing comment/KDoc and package-sibling-count architecture tests were fixed by removing comments and folding VerifyRuntimeResume into FeatureTaskRuntimeRunPreparation.
- Known limitation: agnix was not run locally; CI validate-agent-configs covers it.
Feature flag: N/A
Acceptance criteria: 7/7 implemented

## [2026-09-30] SKILL-386 — runtime-cli composition without dependency bags (subtask 1)
Areas: runtime-kotlin/runtime-cli (featuretask, goal, install, nativeagent, learning, review, scaffold, workflow command groups), runtime-kotlin/runtime-core (InjectConstructorDefaultsArchitectureTest)
- Replaced the feature-task run dependency bag with two injected CLI classes, run preparation and run execution, with all-private constructor params; the resolved workflow id is passed as a value, not a thunk.
- Deprecated feature-task-runtime alias commands now delegate to the same code as feature-task and add only hiddenFromHelp and the unchanged deprecation note.
- Inlined the scaffold-new dependency bag and deleted the goal-run execution wrapper; GoalRunCommand holds GoalRunner directly.
- Subcommand holders expose a body `commands` list built from private constructor params, following core/CliCommandGroups.kt; command order is unchanged from dfb489641.
- reusable PATTERN: compose CLI behavior through @Inject classes with private params, not `@Inject data class` bundles or function-typed params.
- The runtime-cli main guard now runs injectConstructorPropertyViolations over RUNTIME_CLI_MAIN with an empty baseline.
- Known limitation: the UnsupportedScaffoldGateway parameter stays where the scaffold bag carried it; subtask 2 deletes it.
Feature flag: N/A
Acceptance criteria: 10/10 implemented

## [2026-09-23] SKILL-371 — single owners for cross-adapter contracts (subtask 3)
Areas: runtime-kotlin/runtime-cli, runtime-kotlin/runtime-application, runtime-contracts, runtime-engine, runtime-infra, runtime-mcp, orchestration/shell-content-contract
- Repository identity now comes from the canonical enclosing-root port; governed spec-path resolution and the goal-child launch tokens have single owners outside the CLI.
- Scaffold payload decoding and invocation ownership moved to runtime-application; CLI opts into external-source registration while MCP preserves explicit `repo_root` and does not register.
- reusable PATTERN: keep adapters limited to argument translation and `UsageError` wording; centralize shared wire tokens, identity derivation, and scaffold side effects at their owning boundary.
- Known limitations: child-repository identity parity, launcher/CLI field coverage, CLI/MCP `repo_root` parity, and partial-registration coverage remain follow-up test seams.
Feature flag: N/A
Acceptance criteria: 8/8 implemented

## [2026-09-23] SKILL-371 — one CLI process output contract (subtask 2)
Areas: runtime-kotlin/runtime-cli/{core,experiment,featuretask,goal,kernel,workflow}, runtime-contracts/shellcontent, runtime-engine/{experiment/report,goalrunner/experiment}, runtime-ports/{experiment/navigation,experiment/pair}
- Centralized CLI completion and failure mapping through `CliRunState`, `CliExecutionResult`, and `Main`; typed failures now settle stderr and exit status without command-level duplication.
- Typed goal/feature-task presenters and exit-code models preserve text/JSON parity; experiment navigation/reporting now owns spec validation and typed absent-pair failures.
- reusable PATTERN: keep command output as settled payloads and route cross-command failure/format policy through one runtime boundary.
- Removed redundant command groups and continuation mappings while retaining the deprecated feature-task alias with its stderr notice.
- Known limitation: remaining task-1 cleanup for path exception mapping, diagnostics, and all echo removal is outside this subtask.
Feature flag: N/A
Acceptance criteria: 10/10 implemented

## [2026-09-23] SKILL-371 — restore architecture guard enforcement (subtask 1)
Areas: runtime-cli, runtime-core/architecture, runtime-application, runtime-domain, runtime-engine, runtime-infra, runtime-ports, runtime-kotlin/ARCHITECTURE.md
- Restored module-root scanning with missing-root failures and non-empty visit assertions; refreshed the pinned engine inbound surface to `goalrunner.status` and `goalrunner.preflight`.
- Removed public raw-map exposure from accounting and IDE-status boundaries while preserving rendered and persisted wire bytes; kept serialization at its owning adapter boundaries.
- Documented the scan-root convention and verified scanner coverage through architecture tests.
- reusable PATTERN: resolve module source roots through the runtime-kotlin module directory, assert read coverage in each guard, and keep raw-map serialization private at adapter boundaries.
Feature flag: N/A
Acceptance criteria: 6/6 implemented

## [2026-09-16] SKILL-348 — authoritative CLI invocation inputs and output (subtask 2)
Areas: runtime-cli/{core,kernel,model,featuretask,workflow,codereview,scaffold,config,install,review}, runtime-application/diagnostics
- `CliRunState` now marks explicit text, raw-byte, and empty completions; `Main` and `CliRuntime` no longer append root help or a synthetic newline after settled raw or empty results.
- Rejected-output inspect/cleanup route through `RejectedOutputDiagnosticCliSession` in `runtime-application`; `featuretask` CLI no longer constructs `RejectedOutputDiagnosticService` from `UnitOfWork`.
- Stdin is consumed on demand after Clikt parse for `import-review`, `--body-file=-`, and `--payload=-`; `resolveCliRepositoryRoot` centralizes omitted `--repo-root` resolution.
- Verify-workflow commands are collapsed to concrete implementations; `CodeReviewDriverCommand` merged into `CodeReviewCommand`.
- Architecture guard: `featuretask` must not reference `RejectedOutputDiagnosticService(` or `unitOfWork.diagnosticService`. Clikt `.default(".")` on repo roots is not scanned.
- Known limitation: malformed `--step-updates` and omitted-versus-empty update-array coverage remains a follow-up test seam.
Feature flag: N/A
Acceptance criteria: 9/9 implemented

## [2026-09-11] SKILL-233 — named standalone code-review targets
Areas: runtime-cli/codereview
- Positional `pr`, `uncommitted`, `staged`, `unstaged`, `last`, and `HEAD` map to the matching packet. A SHA still reviews that commit against its first parent.
- Conflicting `--scope` still fails; matching tokens stay valid.
Feature flag: N/A
Acceptance criteria: n/a

## [2026-09-03] SKILL-229 loud-fail seams and package structure (subtask 3)
Areas: runtime-kotlin/runtime-cli/{core,kernel,model,system,telemetry,scaffold,install,learning,featuretask,goal}, runtime-kotlin/runtime-core/{skillbill/di,src/repoTest/kotlin/skillbill/architecture{,/baselines}}, runtime-kotlin/ARCHITECTURE.md
- `uninstall`'s six mutation sites (launcher, desktop entry, recursive tree removal, agent-target cleanup, native-agent unlink, MCP unregistration) route every failure through one `UninstallMutationRecorder` to a `RuntimeDiagnostics` error record plus a non-zero exit; the old warning-string-on-zero-exit path is gone. reusable: one recorder owns the failure policy for a whole command family instead of per-site handling.
- All three telemetry-drain abandonment paths emit a `RuntimeDiagnostics` warning and still cannot touch stdout, stderr, or the exit code. The drain's silence is now observable without becoming load-bearing.
- reusable seam: a `runtime-core` port reaches `CliComponent` through `abstract val runtimeDiagnostics: RuntimeDiagnostics` on `RuntimeComponent` — the same `@Provides`-plus-same-name-accessor shape `installSelectionPersistencePort` already used. Adding the accessor (not a new binding) keeps `runtime-cli` free of any `runtime-infra-fs` dependency and leaves `RuntimeAdapterDependencyAllowlistTest`'s pinned list untouched. Use this when a CLI command needs a port the adapter module owns.
- `skillbill.cli.core` now holds only the composition root; shared behavior moved to `skillbill.cli.kernel` and shared data to `skillbill.cli.model`. `runtime-cli` has zero package cycles and subtask 1's acyclicity baseline is an empty file.
- Decision: `CliRunInputs` went to `skillbill.cli.model` rather than staying in `core`, because 46 command-area files import it and any area importing the composition root re-creates the hub cycle. Data types that many areas read belong in `model`, never beside the graph.
- Decision: `LearningCliPayloads` moved to `skillbill.cli.kernel` as shared glue (`system`, `review`, and `repovalidation` all call `toPayload`), which is what makes the isolation probe's import closure exactly `{kernel, model}`. Shared mappers live in `kernel`, not in whichever area authored them.
- The eleven `*Extras`/`*Extras2`/`*Extras3` files were regrouped by responsibility into fourteen named units, not renamed one-for-one; two were small enough to fold into their owners. Zero `*Extras` filenames remain and the per-file ceiling was not relaxed.
- Constraint worth knowing: detekt `MatchingDeclarationName` decides where a promoted top-level declaration may live, so responsibility splits sometimes need a new file rather than a move (`AssistedPlatformProfile`, `ScaffoldWizardValueNormalization`). `PreparedRuntimeRun` and `resolveInvokedRuntimeAgentId` stay in `FeatureTaskRuntimePhaseAssignmentParsing.kt` for that reason — deferred, not resolved.
- `RuntimeArchitectureDocumentationTest` bans hedging substrings in `ARCHITECTURE.md` (including `while the`), so prose asserting a guard's scope must be phrased without them. The runtime-cli baseline paragraph was rewritten rather than appended to, since its census counts had gone stale once subtasks 1 and 2 emptied the baselines.
- Known limitation: the area-isolation guard probes only `skillbill.cli.system`. The one-directional `featuretask -> telemetry/workflow`, `goal -> telemetry`, and `scaffold -> install` edges remain and no guard covers them; widening the probe to every area is follow-up work.
- Known limitation: `docs/observability-policy.md` was left unchanged — it states general policy and enumerates no individual sites, so the two new records are covered by ARCHITECTURE.md's destructive-command failure-policy section, which cites it.
Feature flag: N/A
Acceptance criteria: 8/8 implemented

## [2026-09-03] SKILL-229 single-owner run inputs (subtask 2)
Areas: runtime-kotlin/runtime-cli/{core,goal,featuretask,scaffold,install,system,workflow,repovalidation,codereview}, runtime-kotlin/runtime-core/skillbill/di, runtime-kotlin/runtime-ports/{model,ports/system}, runtime-kotlin/runtime-infra-fs
- The six settings `CliRuntimeContext` and `CliRunState` both carried (`dbPathOverride`, `environment`, `userHome`, `externalCommandRunner`, `liveStdout`, `liveStderr`) now live once in an immutable `CliRunInputs`, constructed in `CliRuntime.run` from `RuntimeComponent.resolvedEnvironmentContext` and injected through `CliComponent`. `dbPathOverride` — the field `CliRuntime.run` never copied — reaches consumers as a settled read.
- reusable PATTERN: `RootFlagProbeCommand` pre-parses the root flags (`--db`, `--home`) and folds them into `RuntimeContext` before `RuntimeComponent` is created, so embedding-context-vs-flag precedence is resolved at exactly one seam and no consumer reconciles. Use this seam for any future run-scoped flag that adapters below `runtime-cli` also need.
- `CliRunState` shrank from eight JVM-seeded `var` defaults to result plus the stdin line cursor; per-run mutable state and resolved inputs are now separate objects. `runtime-cli`'s three subtask-1 architecture baselines (ambient clock, ambient environment, `@Inject` constructor defaults) are empty files.
- Commands read `CliRunInputs.repositoryRoot`, the coordinate SKILL-227 already resolves through `canonicalRepositoryRoot`; the fourteen `Path.of("")` fallbacks are gone and `findRepoRoot` lost its default argument. No second derivation was added.
- reusable `HostPlatformPort` (`runtime-ports/skillbill/ports/system`) exposes `osName`, `jvmClassPath`, `pathSeparator`, with `JdkHostPlatformPort` as an `object` adapter in `runtime-infra-fs` constructed by its DI binding. `SKILL_BILL_QUALITY_GATE_SELECTION` reads the injected `environment` map; scaffold dates come from the injected `Clock`.
- Constructor-parameter pressure from threading inputs was absorbed by `@Inject` dependency holders (`UninstallDependencies`, `ScaffoldNewDependencies`), which detekt's `ignoreDataClasses` exempts — the same shape as `GoalRunDependencies`. SKILL-392 supersedes this: collaborators stay private on `@Inject` classes, and argument types carry values only.
- `CliRuntimeContext` keeps its public field shape and stays the embedding surface, so the `runtime-mcp` `CliRuntime.run` call sites needed no change; the resolution seam is internal to `runtime-cli`.
- Known limitation: the `skillbill.cli.core` split, the `*Extras` renames, the `uninstall` failure policy, and the telemetry-drain record stay open for subtask 3.
Feature flag: N/A
Acceptance criteria: 10/10 implemented

## [2026-08-09] SKILL-179 CLI goal no-terminal parks at implement (subtask 2)
Areas: runtime-kotlin/runtime-cli (CliGoalRuntimeTest / GoalFixtureAgentRunLauncher)
- Bound GoalFixtureAgentRunLauncher.startSubtaskWorkflow to the GoalRunner-preopened child via skillRequest.goalContinuation.assignedWorkflowId (or childWorkflowId on resume) instead of continueByIssueKey Start, which minted an unhydrated preplan row and overwrote the manifest pointer.
- Retained the no-terminal assertion at current_step_id=implement: GoalChildPlanningHydrator hydrates goal children to implement after completed preplan+plan (FeatureTaskRuntimePhaseWorkflowDefinition phase sequence); markBlocked's firstUnfinishedStepId scan parks there. Prior preplan observation was fixture-induced, not contractual.
- Optional noTerminal stamp marks implement running so the blocked row mirrors a live hydration-boundary child. CLI test surface remains free of retired prose step ids assess/create_branch.
Feature flag: N/A
Acceptance criteria: 4/4 implemented (validate passed: runtime-cli compileKotlin + compileTestKotlin)

## [2026-08-09] SKILL-175 remove CLI prose workflow family and implement-stats (subtask 5)
Areas: runtime-kotlin/runtime-cli/{workflow,review}, docs, docs/assets, orchestration/workflow-contract
- Removed the Clikt `skill-bill workflow {open,update,show,get,list,latest,resume,continue}` tree bound to `WorkflowFamilyKind.TASK_PROSE` (`ImplementWorkflow*` commands); `WorkflowTopLevelCommands` now registers only `verify-workflow`. Hard removal — no stub that can open prose.
- Deleted `skill-bill implement-stats` (`FeatureImplementStatsCommand`) from the stats command group; operators use `feature-task-stats` / `goal-stats` / `verify-stats` instead.
- CLI tests and the `cli-workflow-show` golden no longer expect prose workflow commands; `ProseWorkflowTestSupport` covers absence / redirect-to-runtime assertions. reusable PATTERN: retire a CLI family by deleting Clikt wiring + help + goldens together, then assert absence rather than stubbing.
- Getting-started, review-telemetry, demo storyboard/gif script, and workflow PLAYBOOK retarget operators to `feature-task` / `goal` / `verify-workflow` surfaces.
- Known limitation: SQLite prose tables / `FeatureImplement*` persistence and IDE prose branch remain until SKILL-175 subtask 6.
Feature flag: N/A
Acceptance criteria: 6/6 implemented
