# SKILL-386 - runtime-cli-architecture

## Mode

decomposed

## Intended Outcome

# SKILL-386: runtime-cli architecture investigation

## Judgment
runtime-cli is a healthy leaf adapter. Its six main edges (application, contracts, core, domain, engine, ports) are all used, and RuntimeModuleCatalog.moduleEdgeExpectations enforces them. There are no main-source consumers. The only cross-module users are runtime-mcp parity tests (runtime-mcp/build.gradle.kts:32; McpSystemToolsTest, ReviewAttributionResolutionParityTest, McpReviewToolsTest), which SKILL-375 retained. Package cycles are guarded by ApplicationPackageAcyclicityArchitectureTest on RUNTIME_CLI_MAIN, and area isolation now covers every area (RuntimeCliAreaIsolationArchitectureTest), which fixes the SKILL-229 system-only limitation. Errors are mapped once in CliRuntime.execute, with cancellation and interrupts rethrown. What remains is composition debt and restated wire vocabulary. Dependency bags keep coming back because data classes are exempt from detekt LongParameterList (ignoreDataClasses default), and runtime-kotlin/ARCHITECTURE.md explicitly forbids hiding dependencies in bags. Nothing here calls for a new layer. Every fix removes or merges code.

## Method and baseline
- Baseline: HEAD dfb489641f6d67a3e3f45e4a67330a3fff798029 on base/SKILL-380-phase-slot-strategies, with a clean tree. .feature-specs/ holds only done/, so there are no untracked sibling bundles.
- The census was done by hand with grep, ls and wc; no review subagents were used. Prior investigations were read: SKILL-229, SKILL-348 and SKILL-371 (runtime-cli), SKILL-373 (runtime-core test ownership), SKILL-375 (runtime-mcp) and SKILL-231 (decision on direct port resolution, decisions.md:730-735).
- Key census: the highest key under .feature-specs/done is SKILL-385, and no local ref names SKILL-386. `git fetch` and `git branch -a` needed approval this session and did not run, so the runtime's key claim is authoritative.

## Census
| Source set | Files | Lines |
|---|---|---|
| main | 116 | 12,369 |
| test | 70 | 14,668 |
| repoTest | 10 | 3,021 |

- Main has 31 leaf packages under 20 areas. The largest files are GoalCliRunCommands.kt (401), FeatureTaskRuntimeCliCommands.kt (362), GoalCliStatusFormatting.kt (349), GoalCliControlCommands.kt (346) and CodeReviewCommand.kt (343).
- Main import counts by namespace: skillbill.cli 372, application 136, ports 96, engine 96, contracts 88, domain namespaces 149, di 7.
- Main declares zero interfaces and zero typealiases, and makes zero echo( calls.
- Raw maps: 131 `Map<String, Any?>` occurrences, 67 internal top-level map functions and no public ones, so the raw-map guard is satisfied.
- Non-private constructor vals on @Inject classes:

| Kind | File | Count |
|---|---|---|
| Bag | FeatureTaskRuntimeRunDependencies | 12 |
| Bag | ScaffoldNewDependencies | 5 |
| Holder | FeatureTaskRuntimeSubcommands.kt | 8 |
| Holder | GoalCliCommands.kt | 16 |
| Holder | InstallCliSubcommandGroups.kt | 17 |
| Holder | NativeAgentCliCommandGroups.kt | 8 |
| Holder | LearningCliCommands.kt | 8 |
| Holder | ReviewCliCommands.kt | 10 |
| Holder | ScaffoldCliSubcommandGroups.kt | 12 |
| Holder | WorkflowCliCommands.kt | 8 |

## SKILL-371 landing check
Landed:
- F-001 guards read runtime-kotlin/runtime-cli with asserted visits.
- F-002 error mapping.
- F-003 experiments deleted.
- F-004 repository identity via port.
- F-005 launch tokens in contracts.
- F-006 typed exit codes, except the pause/resume strings (F-006 below).
- F-007 CLI scaffold parser removed.

Partial:
- F-008: an UninstallCommand residual remains.
- F-009: the alias duplication, the thunk and the duplicate mappers remain.

## Checklist (13 items)
1. **Module edges.** Clean: every edge is used and guarded by RuntimeAdapterDependencyAllowlistTest. The docs have drifted (F-012).
2. **Cycles/SCC.** Clean. The acyclicity guard covers RUNTIME_CLI_MAIN, and there is no reverse edge because the module is a leaf.
3. **Port necessity.** UnsupportedScaffoldGateway does no I/O and returns CLI text (F-005). The other 26 driven-port imports stay: this is the SKILL-231 decision against forwarding wrappers, and SKILL-373 F-013 is closed as keep.
4. **Invariant bypass.** Clean. Add-on selection decode goes through the domain AgentAddonSelection and PersistedAgentAddonSelectionEntry validation (AgentAddonModels.kt:80-108), and repository identity goes through RepositoryEnclosingRootPort.
5. **Adapter logic leakage.** Run preparation (matrix, launcher availability, add-on verification) stays in the CLI because the CLI is its only consumer. Moving it would be speculative.
6. **DI/composition.** Two bags (F-001, F-003), 87 exposed holder vals (F-004), one forwarder (F-007) and one thunk (F-008).
7. **Wire vocabulary.** Stringly pause/resume statuses (F-006), a restated add-on selection version and raw keys (F-010), and a stringly format comparison (F-011).
8. **Error model.** There is one mapping point in CliRuntime.execute. Of 43 catch sites, 42 are typed. WorkflowCliCommands.kt:244 catches Exception (F-011).
9. **State/transaction ownership.** Clean. CliRunState is per run and is provided by CliComponent. The CLI opens no transactions.
10. **Time/IO/JVM typing.** Clean. RuntimeLayerBoundaryArchitectureTest:428-445 bans java.nio.file.Files, java.sql and java.net.http in CLI. ZoneId.systemDefault at WorkCliCommands.kt:106 is presentation. Thread.sleep at GoalCliStatusCommands.kt:240 is the CLI-only watch loop.
11. **Dead code/over-engineering.** Found the alias duplication (F-002), GoalRunExecution (F-007), the thunk (F-008) and duplicate mappers (F-009).
12. **Test ownership.** IdeStatusReadSnapshotConcurrencyTest in runtime-cli tests exercises engine plus infra-sqlite through RuntimeComponent::class.create (line 192) and never uses CliRuntime. This is a follow-up; its feasibility against the runtime-core test classpath was not checked.
13. **Guard validity.** Every CLI guard resolves real roots and asserts that files were contributed: RuntimeEngineInboundApiTest, RuntimeCliAreaIsolationArchitectureTest, ApplicationPackageAcyclicityArchitectureTest, ImplementationOwnershipArchitectureTest:151-175, RuntimeLayerBoundaryArchitectureTest and RuntimeAdapterDependencyAllowlistTest. PlanningProjectionNoopValidatorGuardTest filters missing roots but pins its consumer set with an equality assertion, so it is not vacuous. Gap: InjectConstructorDefaultsArchitectureTest.kt:45-53 scans only RUNTIME_APPLICATION_MAIN (F-004).

## Principles
| Principle | Status |
|---|---|
| SRP | Violated by the bags, which move collaborators rather than owning behavior (F-001, F-003). |
| ISP | Violated by the bag getters, which expose 12 collaborators to every consumer. |
| DIP | Clean. |
| OCP | Clean. |
| LSP | Clean: no inheritance misuse. The alias classes are copies, not subtypes. |
| YAGNI | Violated by the forwarder, the thunk and the no-I/O port. |
| Hexagonal | Violated where CLI-owned text lives behind a driven port with an infra adapter (F-005). |

## Findings
**F-001 (P1). FeatureTaskRuntimeRunDependencies is a dependency bag.**
- Evidence: FeatureTaskRuntimeRunDependencies.kt:17-31 is an @Inject data class with 12 public vals. It is injected into six commands: run, explicit run, resume (FeatureTaskRuntimeControlCliCommands.kt:135) and the three alias commands. It is also threaded as `deps` into resolveRunWorkflowId, executeRuntimeRun and resolveSpecPath (FeatureTaskRuntimeCliCommands.kt:133-272) and into prepareRuntimeRun (FeatureTaskRuntimeRunRequestAssembly.kt:25).
- Fix: move the behavior onto injected CLI classes with private constructor properties.
  - The split follows the existing responsibility boundary, not parameter count. Pre-launch preparation (spec-path resolution, config matrix and compaction, launcher availability, add-on verification) is one class. Workflow execution (open or reuse the workflow id, runOwned, completion and telemetry drain) is the other.
  - A single owner would need 13 constructor parameters, over constructorThreshold 12.
  - Option parsing stays on FeatureTaskRuntimePhaseAgentCommand; parsed values are passed as arguments.

**F-002 (P1). The deprecated `feature-task-runtime` alias duplicates command bodies.**
- Evidence: FeatureTaskRuntimeAliasCliCommands.kt:20-159 re-implements the run, explicit-run, status and resume bodies line for line.
- Keep the alias. SKILL-371 retained it, and CliRuntimeShellCommandsTest.kt:158-179 pins the stderr note. External scripts may depend on it, and no removal decision is recorded.
- Fix: the alias commands delegate to the same code as feature-task and add only hiddenFromHelp and the unchanged note.

**F-003 (P2). ScaffoldNewDependencies is a bag.**
- Evidence: ScaffoldNewCliCommands.kt:27-33 bundles clock, scaffoldGateway, scaffoldCatalogGateway, installAgentService and externalAddonOverlayService. It is used at :39 and :97.
- Fix: inline the collaborators. The resulting constructors have about 7 parameters.

**F-004 (P2). The inject-property rule is not enforced for runtime-cli.**
- Evidence: bags were removed in SKILL-229/348/371 (UninstallDependencies, GoalRunDependencies) and came back.
- Fix: extend the existing `injectConstructorPropertyViolations` call to PrincipleEnforcementInventory.RUNTIME_CLI_MAIN with an empty baseline. Convert holders to the body `val commands: List<CliktCommand>` pattern already used in core/CliCommandGroups.kt. Flatten the goal chain `goalRunSubcommands.controls.flow.pause` (GoalCliCommands.kt:180-195). Keep registration order identical.

**F-005 (P2). UnsupportedScaffoldGateway is a port that does no I/O.**
- Evidence: the chain is port ScaffoldGateways.kt:96, adapter FileSystemUnsupportedScaffoldGateway (FileSystemScaffoldGateway.kt:294-305), AuthoringOperations.kt:254-262, binding RuntimeScaffoldProvides.kt:25 and accessor RuntimeComponent.kt:210.
- It has 7 call sites, all CLI (ScaffoldCliPayloadRuns.kt:107, ScaffoldAuthoringCliCommandRuns.kt:57,75, ScaffoldNewCliCommands.kt:157,209,245, ScaffoldAuthoringCliCommands.kt:187). There are no MCP users and no test references the type.
- The CLI already renders the sibling message in-process at SystemCliCommands.kt:172.
- Fix: delete the whole chain and render the two messages in runtime-cli byte-identically. The texts are `<command> interactive mode was retired in SKILL-32; use` `<replacement>` `instead.` and the same text with `editor mode` in place of `interactive mode`.

**F-006 (P2). Pause/resume statuses are strings.**
- Evidence: GoalRunnerPauseResult.status and GoalRunnerResumeResult.status are String (GoalRunnerControlModels.kt). Their literals are set at GoalRunnerStatusControlVerbs.kt:36,41,55,127,137,141,145, and the CLI restates `not_found` at GoalCliExitCodes.kt:49,51. The sibling GoalRunnerStopStatus is an enum with wireValue.
- Fix: add engine enums with wireValue, pin them in PINNED_ENGINE_INBOUND_API_TYPES, and keep the JSON unchanged.

**F-007 (P3). GoalRunExecution is a pure forwarder.**
- Evidence: GoalCliCommands.kt:94-99. GoalRunner is concrete and already pinned.

**F-008 (P3). A thunk is invoked immediately.**
- Evidence: `workflowId: () -> String` at FeatureTaskRuntimeCliCommands.kt:176 is invoked on the first line (:179).
- Fix: pass the resolved value. Order stays prepare, then resolve id, then run.

**F-009 (P3). Continuation-candidate mappers are duplicated.**
- Evidence: FeatureTaskRuntimeControlCliCommands.kt:79,93 and GoalCliRunCommands.kt:120,165 have identical key sets.
- Fix: keep one mapper per type, in a package both areas may import.

**F-010 (P3). The add-on selection contract version is restated.**
- Evidence: `0.1` appears at AgentRunCommandBuildersLaunch.kt:139, AgentAddonCliCommands.kt:68,120 and AgentAddonSelectionParsing.kt:29. AgentAddonCliCommands also uses raw keys instead of FeatureTaskRuntimeGoalContinuationArtifactPayloadKeys.
- Fix: add one runtime-contracts const, following the existing `*_CONTRACT_VERSION` files. runtime-infra/launcher already depends on runtime-contracts.

**F-011 (P3). Stringly/broad-catch residuals.**
- Evidence: UninstallCommand.kt:86 compares `format.wireName` with a literal, and WorkflowCliCommands.kt:244 has `catch (_: Exception)` around JsonCodec.parseValue.

**F-012 (P3). ARCHITECTURE.md documentation drift.**
- Evidence: runtime-kotlin/ARCHITECTURE.md:483-485 (runtime-cli) and :492-494 (runtime-mcp) omit runtime-engine from the allow-list, while RuntimeModuleCatalog.moduleEdgeExpectations includes it.

## Follow-ups (not in this bundle)
- The add-on selection entry decoders are duplicated in runtime-domain (FeatureTaskRuntimeGoalContinuationArtifact.kt:176-222) and runtime-engine (GoalRunnerWorkflowFamilyLookup.kt:60-110).
- IdeStatusReadSnapshotConcurrencyTest ownership (checklist item 12).

## Over-engineering register
FeatureTaskRuntimeRunDependencies, ScaffoldNewDependencies, the four alias body copies, GoalRunExecution, the workflowId thunk, the UnsupportedScaffoldGateway port/adapter/binding/accessor, and the two duplicate mappers. Every fix deletes or merges code; none adds a layer.

## What stays unchanged
- **Direct driven-port use by CLI commands** (ScaffoldGateway, WorkflowGitOperations, InstallerProcessPort, ExecutableLookup, RepoValidationGateway and others). This is the SKILL-231 decision; application wrappers would be forwarding layers.
- **runtime-mcp parity tests importing CliRuntime.** Retained by SKILL-375; they are the only cross-surface parity evidence.
- **Internal raw-map presentation payloads.** SKILL-348/371 decision; no public map functions exist.
- **CliRuntimeContext test hooks and OptionalCallbacks.** Retained by SKILL-371; they are the test seam for process-level behavior.
- **Clikt, kotlin-inject and the per-run CliRunState.** Standard choices; no alternative has a demonstrated benefit.
- **The five core command groups plus CliCommandProvider.** 22 top-level items need one holder layer under constructorThreshold 12, and the groups already use the body-list pattern.
- **Exit-code numbers, the watch-loop Thread.sleep and ZoneId presentation.** Wire contract or presentation only.
- **Run preparation stays in the CLI** instead of moving to application. The CLI is its only consumer.
- **No blanket `internal` or explicitApi.** The module is a leaf with no main-source consumer.
- **The deprecated alias stays.** See F-002.

## Coordination with concurrent bundles
No active bundle exists under .feature-specs/. The relevant done bundles are SKILL-229, SKILL-231, SKILL-348, SKILL-371, SKILL-373 and SKILL-375; SKILL-373 F-013 is closed here as keep. Recheck .feature-specs/ for new runtime-cli, runtime-engine goalrunner or runtime-ports scaffold bundles before implementing.

## Limits
Only compiling can confirm:
- kotlin-inject resolution after the bag removal and the accessor deletion (count generated child-component readers of the RuntimeComponent accessor before deleting it);
- detekt thresholds on the new constructors;
- that help and registration order are unchanged.

The investigation text lives in this spec because the runtime writes the bundle from the decomposition package.

## Overview

# SKILL-386: runtime-cli architecture investigation

## Judgment
runtime-cli is a healthy leaf adapter. Its six main edges (application, contracts, core, domain, engine, ports) are all used, and RuntimeModuleCatalog.moduleEdgeExpectations enforces them. There are no main-source consumers. The only cross-module users are runtime-mcp parity tests (runtime-mcp/build.gradle.kts:32; McpSystemToolsTest, ReviewAttributionResolutionParityTest, McpReviewToolsTest), which SKILL-375 retained. Package cycles are guarded by ApplicationPackageAcyclicityArchitectureTest on RUNTIME_CLI_MAIN, and area isolation now covers every area (RuntimeCliAreaIsolationArchitectureTest), which fixes the SKILL-229 system-only limitation. Errors are mapped once in CliRuntime.execute, with cancellation and interrupts rethrown. What remains is composition debt and restated wire vocabulary. Dependency bags keep coming back because data classes are exempt from detekt LongParameterList (ignoreDataClasses default), and runtime-kotlin/ARCHITECTURE.md explicitly forbids hiding dependencies in bags. Nothing here calls for a new layer. Every fix removes or merges code.

## Method and baseline
- Baseline: HEAD dfb489641f6d67a3e3f45e4a67330a3fff798029 on base/SKILL-380-phase-slot-strategies, with a clean tree. .feature-specs/ holds only done/, so there are no untracked sibling bundles.
- The census was done by hand with grep, ls and wc; no review subagents were used. Prior investigations were read: SKILL-229, SKILL-348 and SKILL-371 (runtime-cli), SKILL-373 (runtime-core test ownership), SKILL-375 (runtime-mcp) and SKILL-231 (decision on direct port resolution, decisions.md:730-735).
- Key census: the highest key under .feature-specs/done is SKILL-385, and no local ref names SKILL-386. `git fetch` and `git branch -a` needed approval this session and did not run, so the runtime's key claim is authoritative.

## Census
| Source set | Files | Lines |
|---|---|---|
| main | 116 | 12,369 |
| test | 70 | 14,668 |
| repoTest | 10 | 3,021 |

- Main has 31 leaf packages under 20 areas. The largest files are GoalCliRunCommands.kt (401), FeatureTaskRuntimeCliCommands.kt (362), GoalCliStatusFormatting.kt (349), GoalCliControlCommands.kt (346) and CodeReviewCommand.kt (343).
- Main import counts by namespace: skillbill.cli 372, application 136, ports 96, engine 96, contracts 88, domain namespaces 149, di 7.
- Main declares zero interfaces and zero typealiases, and makes zero echo( calls.
- Raw maps: 131 `Map<String, Any?>` occurrences, 67 internal top-level map functions and no public ones, so the raw-map guard is satisfied.
- Non-private constructor vals on @Inject classes:

| Kind | File | Count |
|---|---|---|
| Bag | FeatureTaskRuntimeRunDependencies | 12 |
| Bag | ScaffoldNewDependencies | 5 |
| Holder | FeatureTaskRuntimeSubcommands.kt | 8 |
| Holder | GoalCliCommands.kt | 16 |
| Holder | InstallCliSubcommandGroups.kt | 17 |
| Holder | NativeAgentCliCommandGroups.kt | 8 |
| Holder | LearningCliCommands.kt | 8 |
| Holder | ReviewCliCommands.kt | 10 |
| Holder | ScaffoldCliSubcommandGroups.kt | 12 |
| Holder | WorkflowCliCommands.kt | 8 |

## SKILL-371 landing check
Landed:
- F-001 guards read runtime-kotlin/runtime-cli with asserted visits.
- F-002 error mapping.
- F-003 experiments deleted.
- F-004 repository identity via port.
- F-005 launch tokens in contracts.
- F-006 typed exit codes, except the pause/resume strings (F-006 below).
- F-007 CLI scaffold parser removed.

Partial:
- F-008: an UninstallCommand residual remains.
- F-009: the alias duplication, the thunk and the duplicate mappers remain.

## Checklist (13 items)
1. **Module edges.** Clean: every edge is used and guarded by RuntimeAdapterDependencyAllowlistTest. The docs have drifted (F-012).
2. **Cycles/SCC.** Clean. The acyclicity guard covers RUNTIME_CLI_MAIN, and there is no reverse edge because the module is a leaf.
3. **Port necessity.** UnsupportedScaffoldGateway does no I/O and returns CLI text (F-005). The other 26 driven-port imports stay: this is the SKILL-231 decision against forwarding wrappers, and SKILL-373 F-013 is closed as keep.
4. **Invariant bypass.** Clean. Add-on selection decode goes through the domain AgentAddonSelection and PersistedAgentAddonSelectionEntry validation (AgentAddonModels.kt:80-108), and repository identity goes through RepositoryEnclosingRootPort.
5. **Adapter logic leakage.** Run preparation (matrix, launcher availability, add-on verification) stays in the CLI because the CLI is its only consumer. Moving it would be speculative.
6. **DI/composition.** Two bags (F-001, F-003), 87 exposed holder vals (F-004), one forwarder (F-007) and one thunk (F-008).
7. **Wire vocabulary.** Stringly pause/resume statuses (F-006), a restated add-on selection version and raw keys (F-010), and a stringly format comparison (F-011).
8. **Error model.** There is one mapping point in CliRuntime.execute. Of 43 catch sites, 42 are typed. WorkflowCliCommands.kt:244 catches Exception (F-011).
9. **State/transaction ownership.** Clean. CliRunState is per run and is provided by CliComponent. The CLI opens no transactions.
10. **Time/IO/JVM typing.** Clean. RuntimeLayerBoundaryArchitectureTest:428-445 bans java.nio.file.Files, java.sql and java.net.http in CLI. ZoneId.systemDefault at WorkCliCommands.kt:106 is presentation. Thread.sleep at GoalCliStatusCommands.kt:240 is the CLI-only watch loop.
11. **Dead code/over-engineering.** Found the alias duplication (F-002), GoalRunExecution (F-007), the thunk (F-008) and duplicate mappers (F-009).
12. **Test ownership.** IdeStatusReadSnapshotConcurrencyTest in runtime-cli tests exercises engine plus infra-sqlite through RuntimeComponent::class.create (line 192) and never uses CliRuntime. This is a follow-up; its feasibility against the runtime-core test classpath was not checked.
13. **Guard validity.** Every CLI guard resolves real roots and asserts that files were contributed: RuntimeEngineInboundApiTest, RuntimeCliAreaIsolationArchitectureTest, ApplicationPackageAcyclicityArchitectureTest, ImplementationOwnershipArchitectureTest:151-175, RuntimeLayerBoundaryArchitectureTest and RuntimeAdapterDependencyAllowlistTest. PlanningProjectionNoopValidatorGuardTest filters missing roots but pins its consumer set with an equality assertion, so it is not vacuous. Gap: InjectConstructorDefaultsArchitectureTest.kt:45-53 scans only RUNTIME_APPLICATION_MAIN (F-004).

## Principles
| Principle | Status |
|---|---|
| SRP | Violated by the bags, which move collaborators rather than owning behavior (F-001, F-003). |
| ISP | Violated by the bag getters, which expose 12 collaborators to every consumer. |
| DIP | Clean. |
| OCP | Clean. |
| LSP | Clean: no inheritance misuse. The alias classes are copies, not subtypes. |
| YAGNI | Violated by the forwarder, the thunk and the no-I/O port. |
| Hexagonal | Violated where CLI-owned text lives behind a driven port with an infra adapter (F-005). |

## Findings
**F-001 (P1). FeatureTaskRuntimeRunDependencies is a dependency bag.**
- Evidence: FeatureTaskRuntimeRunDependencies.kt:17-31 is an @Inject data class with 12 public vals. It is injected into six commands: run, explicit run, resume (FeatureTaskRuntimeControlCliCommands.kt:135) and the three alias commands. It is also threaded as `deps` into resolveRunWorkflowId, executeRuntimeRun and resolveSpecPath (FeatureTaskRuntimeCliCommands.kt:133-272) and into prepareRuntimeRun (FeatureTaskRuntimeRunRequestAssembly.kt:25).
- Fix: move the behavior onto injected CLI classes with private constructor properties.
  - The split follows the existing responsibility boundary, not parameter count. Pre-launch preparation (spec-path resolution, config matrix and compaction, launcher availability, add-on verification) is one class. Workflow execution (open or reuse the workflow id, runOwned, completion and telemetry drain) is the other.
  - A single owner would need 13 constructor parameters, over constructorThreshold 12.
  - Option parsing stays on FeatureTaskRuntimePhaseAgentCommand; parsed values are passed as arguments.

**F-002 (P1). The deprecated `feature-task-runtime` alias duplicates command bodies.**
- Evidence: FeatureTaskRuntimeAliasCliCommands.kt:20-159 re-implements the run, explicit-run, status and resume bodies line for line.
- Keep the alias. SKILL-371 retained it, and CliRuntimeShellCommandsTest.kt:158-179 pins the stderr note. External scripts may depend on it, and no removal decision is recorded.
- Fix: the alias commands delegate to the same code as feature-task and add only hiddenFromHelp and the unchanged note.

**F-003 (P2). ScaffoldNewDependencies is a bag.**
- Evidence: ScaffoldNewCliCommands.kt:27-33 bundles clock, scaffoldGateway, scaffoldCatalogGateway, installAgentService and externalAddonOverlayService. It is used at :39 and :97.
- Fix: inline the collaborators. The resulting constructors have about 7 parameters.

**F-004 (P2). The inject-property rule is not enforced for runtime-cli.**
- Evidence: bags were removed in SKILL-229/348/371 (UninstallDependencies, GoalRunDependencies) and came back.
- Fix: extend the existing `injectConstructorPropertyViolations` call to PrincipleEnforcementInventory.RUNTIME_CLI_MAIN with an empty baseline. Convert holders to the body `val commands: List<CliktCommand>` pattern already used in core/CliCommandGroups.kt. Flatten the goal chain `goalRunSubcommands.controls.flow.pause` (GoalCliCommands.kt:180-195). Keep registration order identical.

**F-005 (P2). UnsupportedScaffoldGateway is a port that does no I/O.**
- Evidence: the chain is port ScaffoldGateways.kt:96, adapter FileSystemUnsupportedScaffoldGateway (FileSystemScaffoldGateway.kt:294-305), AuthoringOperations.kt:254-262, binding RuntimeScaffoldProvides.kt:25 and accessor RuntimeComponent.kt:210.
- It has 7 call sites, all CLI (ScaffoldCliPayloadRuns.kt:107, ScaffoldAuthoringCliCommandRuns.kt:57,75, ScaffoldNewCliCommands.kt:157,209,245, ScaffoldAuthoringCliCommands.kt:187). There are no MCP users and no test references the type.
- The CLI already renders the sibling message in-process at SystemCliCommands.kt:172.
- Fix: delete the whole chain and render the two messages in runtime-cli byte-identically. The texts are `<command> interactive mode was retired in SKILL-32; use` `<replacement>` `instead.` and the same text with `editor mode` in place of `interactive mode`.

**F-006 (P2). Pause/resume statuses are strings.**
- Evidence: GoalRunnerPauseResult.status and GoalRunnerResumeResult.status are String (GoalRunnerControlModels.kt). Their literals are set at GoalRunnerStatusControlVerbs.kt:36,41,55,127,137,141,145, and the CLI restates `not_found` at GoalCliExitCodes.kt:49,51. The sibling GoalRunnerStopStatus is an enum with wireValue.
- Fix: add engine enums with wireValue, pin them in PINNED_ENGINE_INBOUND_API_TYPES, and keep the JSON unchanged.

**F-007 (P3). GoalRunExecution is a pure forwarder.**
- Evidence: GoalCliCommands.kt:94-99. GoalRunner is concrete and already pinned.

**F-008 (P3). A thunk is invoked immediately.**
- Evidence: `workflowId: () -> String` at FeatureTaskRuntimeCliCommands.kt:176 is invoked on the first line (:179).
- Fix: pass the resolved value. Order stays prepare, then resolve id, then run.

**F-009 (P3). Continuation-candidate mappers are duplicated.**
- Evidence: FeatureTaskRuntimeControlCliCommands.kt:79,93 and GoalCliRunCommands.kt:120,165 have identical key sets.
- Fix: keep one mapper per type, in a package both areas may import.

**F-010 (P3). The add-on selection contract version is restated.**
- Evidence: `0.1` appears at AgentRunCommandBuildersLaunch.kt:139, AgentAddonCliCommands.kt:68,120 and AgentAddonSelectionParsing.kt:29. AgentAddonCliCommands also uses raw keys instead of FeatureTaskRuntimeGoalContinuationArtifactPayloadKeys.
- Fix: add one runtime-contracts const, following the existing `*_CONTRACT_VERSION` files. runtime-infra/launcher already depends on runtime-contracts.

**F-011 (P3). Stringly/broad-catch residuals.**
- Evidence: UninstallCommand.kt:86 compares `format.wireName` with a literal, and WorkflowCliCommands.kt:244 has `catch (_: Exception)` around JsonCodec.parseValue.

**F-012 (P3). ARCHITECTURE.md documentation drift.**
- Evidence: runtime-kotlin/ARCHITECTURE.md:483-485 (runtime-cli) and :492-494 (runtime-mcp) omit runtime-engine from the allow-list, while RuntimeModuleCatalog.moduleEdgeExpectations includes it.

## Follow-ups (not in this bundle)
- The add-on selection entry decoders are duplicated in runtime-domain (FeatureTaskRuntimeGoalContinuationArtifact.kt:176-222) and runtime-engine (GoalRunnerWorkflowFamilyLookup.kt:60-110).
- IdeStatusReadSnapshotConcurrencyTest ownership (checklist item 12).

## Over-engineering register
FeatureTaskRuntimeRunDependencies, ScaffoldNewDependencies, the four alias body copies, GoalRunExecution, the workflowId thunk, the UnsupportedScaffoldGateway port/adapter/binding/accessor, and the two duplicate mappers. Every fix deletes or merges code; none adds a layer.

## What stays unchanged
- **Direct driven-port use by CLI commands** (ScaffoldGateway, WorkflowGitOperations, InstallerProcessPort, ExecutableLookup, RepoValidationGateway and others). This is the SKILL-231 decision; application wrappers would be forwarding layers.
- **runtime-mcp parity tests importing CliRuntime.** Retained by SKILL-375; they are the only cross-surface parity evidence.
- **Internal raw-map presentation payloads.** SKILL-348/371 decision; no public map functions exist.
- **CliRuntimeContext test hooks and OptionalCallbacks.** Retained by SKILL-371; they are the test seam for process-level behavior.
- **Clikt, kotlin-inject and the per-run CliRunState.** Standard choices; no alternative has a demonstrated benefit.
- **The five core command groups plus CliCommandProvider.** 22 top-level items need one holder layer under constructorThreshold 12, and the groups already use the body-list pattern.
- **Exit-code numbers, the watch-loop Thread.sleep and ZoneId presentation.** Wire contract or presentation only.
- **Run preparation stays in the CLI** instead of moving to application. The CLI is its only consumer.
- **No blanket `internal` or explicitApi.** The module is a leaf with no main-source consumer.
- **The deprecated alias stays.** See F-002.

## Coordination with concurrent bundles
No active bundle exists under .feature-specs/. The relevant done bundles are SKILL-229, SKILL-231, SKILL-348, SKILL-371, SKILL-373 and SKILL-375; SKILL-373 F-013 is closed here as keep. Recheck .feature-specs/ for new runtime-cli, runtime-engine goalrunner or runtime-ports scaffold bundles before implementing.

## Limits
Only compiling can confirm:
- kotlin-inject resolution after the bag removal and the accessor deletion (count generated child-component readers of the RuntimeComponent accessor before deleting it);
- detekt thresholds on the new constructors;
- that help and registration order are unchanged.

The investigation text lives in this spec because the runtime writes the bundle from the decomposition package.

## Acceptance Criteria

1. SKILL-386: runtime-cli architecture investigation. Spec bundle path: .feature-specs/SKILL-386-runtime-cli-architecture/ (re-run the key census in step 5; if SKILL-386 was claimed meanwhile, take the next free key and adjust the path).

Do a full architectural investigation of runtime-kotlin/runtime-cli and its relationship to
every other module in the hexagonal graph. Deliver a spec bundle only; do not implement,
branch, or edit sources. The bar is clean/hexagonal architecture, SOLID, and YAGNI as practiced
at companies like Reddit, Microsoft, and Meta. Over-engineering is a finding, not a fix: remove
layers rather than add them.

## 0. Context before any census
- Read CLAUDE.md, runtime-kotlin/ARCHITECTURE.md (Design Principles, Gradle Modules, Package
  Ownership, Guardrails), docs/code-principles.md, and runtime-cli's agent/decisions.md and
  agent/history.md.
- Find prior work on this module: `grep -l runtime-cli .feature-specs/done/*/spec.md`. Read the most
  recent investigation in full. Do not re-propose what it fixed; DO check whether its fixes
  actually landed as specified (SKILL-347 asked to remove a dependency bag; the code moved it).
  Keep its retention decisions unless you have new evidence.
- List every untracked bundle in .feature-specs/ (other agents are working in parallel). Read
  any that touch runtime-cli or its seams.
- Note HEAD sha; other sessions commit concurrently.

## 1. Census (scripts, not delegated review)
Do the reading yourself with grep/python. Do NOT launch per-area code-review subagents.
Measure and record numbers:
- files/lines for main, test, testFixtures; per-package file counts
- build.gradle.kts edges per configuration (main, api vs implementation, test, testFixtures)
- symbol-level consumers: which runtime-cli declarations each other module's main/test imports;
  symbols used by exactly one other module (ownership candidates); non-private declarations
  with no cross-module reference (visibility candidates)
- typealiases (esp. re-exports of other modules' types), alias+model duplicate files
- interfaces: implementations count and TEST SUBSTITUTE count (a port with one impl is justified
  by test fakes; an interface with neither is YAGNI)
- @Inject classes exposing constructor params as non-private vals (dependency bags / getters),
  and classes hand-constructing collaborators that the DI container could wire
- test packages that don't exist in main; tests importing a module ABOVE runtime-cli
- literals restating enum wire tokens; Map<String, Any?> in public signatures; JSON parsing sites

## 2. Fixed checklist: answer every item with evidence (file:line + count) or "clean because ..."
1. Dependency direction: main edges inward only? api vs implementation justified?
2. Inbound side: do adapters call use cases directly? Are they forced to reach into a service's
   dependencies (getters) or re-decode domain data?
3. Outbound side: are ports purpose-built, or generic transports (HTTP/SQL/process) with the
   vendor protocol (URLs, headers, JSON field names, error strings like SQLITE_BUSY) living in
   the inner layer? Same port declared in an unrelated area package?
4. Domain richness: are pure rules/invariants of domain aggregates sitting in application/engine
   /adapters as free functions? Duplicated rules across modules (diff them)?
5. Composition: any second composition root, dependency bag, or service locator outside
   runtime-core?
6. Entry-point leakage: CLI/MCP names, flags, or rendered text inside use cases?
7. Ambient effects: System.nanoTime/currentTimeMillis, env, Thread, Instant.now, not injected.
8. State and transactions: mutable fields on long-lived objects; who owns each transaction;
   unit-of-work shape.
9. Error model: typed errors vs strings; broad catches; cancellation propagation.
10. Cohesion and ownership: code or tests used only by another module; the API other modules
    consume (free functions vs services).
11. YAGNI: pass-through aliases, test-only production members (NONE objects), dead code,
    forwarding layers, interfaces without substitutes.
12. Naming and packages: stutter (x.y.x), orphan test packages, sibling-count limits.
13. Guard validity: for every architecture test you cite as evidence or plan to extend, open it
    and confirm its scan root resolves to runtime-kotlin/runtime-cli/... and reads files. Several
    guards in this repo resolve against the repository root and scan nothing.

## 3. Feasibility of each fix before writing it
- For every "move X into module Y": list X's imports and check them against Y's import rules
  (runtime-domain bans all java.nio and must not import skillbill.ports). Narrow the move to what
  compiles.
- For every DI change: confirm how kotlin-inject resolves it (function-type bindings, scopes).
- Acceptance criteria state observable outcomes. Don't dictate incidental implementation
  details such as instance counts for stateless classes.

## 4. Decide what stays
Write an explicit "What stays unchanged" list with a reason per item: ports with test
substitutes, prior retention decisions, things you considered and rejected (inbound use-case
interfaces with no substitute, splitting by file size, enum-typing that ripples through wire
codecs, explicitApi). This list is how a reviewer judges that you avoided over-engineering.

## 5. Write the bundle via bill-feature-spec
- Right before writing: re-run the next-key census over .feature-specs/, .feature-specs/done/,
  `git branch -a` after `git fetch`, and `git log --all`. Take the next free key.
- Shape: investigation.md (judgment, method + baseline sha, measured census tables, principle
  assessment table, numbered findings F-NNN with priority/evidence/fix, over-engineering
  register, what stays, "Coordination with concurrent bundles", limits), spec.md, ordered
  spec_subtask_*.md, decomposition-manifest.yaml.
- Subtask sizing: default one. Split only for a stated reason; the usual valid one here is
  separating semantic changes from a large mechanical move/rename so review stays readable.
- Constraints: no new modules, frameworks, dependency bags, or architecture-test classes;
  extend an existing scanner only when a criterion needs enforcement; no baseline expansion;
  wire output byte-identical.
- Coordination: for each sibling bundle, state overlap, who owns it, and sequencing; don't edit
  other sessions' bundles.
- Validate with `skill-bill goal preflight <KEY>` (read-only).

## 6. Before reporting, do one feasibility pass
Re-check every relocation and every cited guard (steps 2.13 and 3). Do not do another broad pass.
Report findings ranked, the subtask split and why, what stays, overlaps with sibling bundles,
and what only compiling can confirm.

Two notes for running agents in parallel:
- Give each agent a distinct key range, or have them run the key recheck in step 5. Four bundles landed in about five minutes today and two collided.
- Sequence the goals so the module other bundles build on lands first. All of today's siblings already queue behind SKILL-370.

## Constraints

- Runtime decompose planning stop.

## Non-Goals

- None

## Validation Strategy

Planning did not compile or run anything. After the runtime writes the bundle, run `skill-bill goal preflight SKILL-386` (read-only) to validate the manifest. The validate phase of each subtask owns the build and checks. They must show:
- kotlin-inject resolves the CLI and runtime-core components after the deletions;
- detekt passes without new suppressions or baseline entries;
- the extended InjectConstructorDefaultsArchitectureTest and RuntimeEngineInboundApiTest pass;
- the existing CLI and MCP parity tests (CliRuntimeShellCommandsTest, CliAuthoringParityTest, McpSystemToolsTest, McpReviewToolsTest) pass unchanged, which is the evidence for byte-identical output.

Reviewers compare subcommand registration order against dfb489641.
