# SKILL-386 Subtask 2 - cross-module contract ownership seen from the CLI

Parent spec: [.feature-specs/SKILL-386-runtime-cli-architecture/spec.md](spec.md)
Issue key: SKILL-386

## Scope

Covers F-005, F-006, F-009, F-010, F-011 and F-012.

Changes:
- Delete the UnsupportedScaffoldGateway chain (runtime-ports port, runtime-infra/skills adapter and AuthoringOperations message functions, runtime-core binding, accessor and any inventory entry) and render both retired-mode messages in runtime-cli.
- Type the GoalRunnerPauseResult and GoalRunnerResumeResult statuses as runtime-engine enums with wire values.
- Add one runtime-contracts constant for the agent add-on selection contract version, and use the contract payload keys in AgentAddonCliCommands.
- Keep one CLI mapper per continuation-candidate type.
- Compare the format enum in UninstallCommand, and narrow the step-updates parse catch.
- Add runtime-engine to the runtime-cli and runtime-mcp allow-list sentences in runtime-kotlin/ARCHITECTURE.md.

## Acceptance Criteria

1. UnsupportedScaffoldGateway, FileSystemUnsupportedScaffoldGateway, its RuntimeScaffoldProvides binding and RuntimeComponent accessor, and any PrincipleEnforcementInventory entry naming that accessor no longer exist. AuthoringOperations.retiredInteractiveMessage and retiredEditorMessage are removed, since the deleted adapter is their only caller.
2. runtime-cli main produces the retired interactive message as `<command> interactive mode was retired in SKILL-32; use` followed by the backticked replacement and ` instead.`, and the editor message identically with `editor mode`, exactly as AuthoringOperations.kt:254-262 did at dfb489641. No scaffold command or args type carries a gateway just to produce these messages.
3. GoalRunnerPauseResult.status and GoalRunnerResumeResult.status are enum types declared in skillbill.engine.goalrunner.model. The pause values carry wire values not_found, paused and requested; the resume values carry not_found, not_paused and resumed. GoalRunnerStatusControlVerbs assigns enum values, and both enum types are listed in RuntimeEngineInboundApiTest.PINNED_ENGINE_INBOUND_API_TYPES.
4. GoalCliExitCodes and GoalRunPresenter compare pause/resume statuses against enum values and emit the enum wire value in payloads and text, so no `not_found` string literal remains in runtime-cli main goal packages.
5. runtime-contracts declares one const for the agent add-on selection contract version with value `0.1`. AgentRunCommandBuildersLaunch.kt, AgentAddonCliCommands.kt and AgentAddonSelectionParsing.kt reference it and contain no `0.1` literal for that payload. AgentAddonCliCommands builds entries using the FeatureTaskRuntimeGoalContinuationArtifactPayloadKeys constants for entries, slug, source_identity and content_sha256.
6. runtime-cli main has exactly one map-rendering function for GoalContinuationCandidate and one for FeatureTaskContinuationCandidate. Both the featuretask and goal areas use them, from a package that RuntimeCliAreaIsolationArchitectureTest allows both areas to import. Key names and key order match the baseline.
7. UninstallCommand compares the parsed format against the CliFormat enum rather than its wire name. parseStepUpdatesStrict in WorkflowCliCommands.kt catches only the exception type or types JsonCodec.parseValue raises for malformed input, not Exception.
8. In runtime-kotlin/ARCHITECTURE.md, the runtime-cli and runtime-mcp main-source allow-list sentences name runtime-engine alongside runtime-application, runtime-contracts, runtime-core, runtime-domain and runtime-ports, matching RuntimeModuleCatalog.moduleEdgeExpectations.
9. No new module, port, adapter, architecture test class or baseline entry is added. The only additions to PINNED_ENGINE_INBOUND_API_TYPES are the two new status enums.

## Non-Goals

- Wrapping other driven ports used directly by CLI commands in application services (SKILL-231 decision).
- Deduplicating the add-on selection entry decoders in runtime-domain and runtime-engine (a recorded follow-up).
- Relocating IdeStatusReadSnapshotConcurrencyTest (a recorded follow-up).
- Changing any JSON key, value, exit code or message text.

## Dependency Notes

Depends on: 1
Depends on subtask 1. Both edit ScaffoldNewCliCommands.kt, and subtask 1's removal of ScaffoldNewDependencies determines where the deleted gateway parameter currently lives. Before deleting the RuntimeComponent accessor, count the kotlin-inject generated child-component readers of it.

## Validation Strategy

The validate phase compiles every touched module (runtime-ports, runtime-infra/skills, runtime-infra/launcher, runtime-core, runtime-engine, runtime-contracts, runtime-cli), runs detekt and the architecture repoTests (RuntimeEngineInboundApiTest, RuntimeCliAreaIsolationArchitectureTest), and runs the CliAuthoringParityTest, goal control tests and runtime-mcp parity tests unchanged as evidence of byte-identical output.

## Next Path

skill-bill goal SKILL-386

## Spec Path

.feature-specs/SKILL-386-runtime-cli-architecture/spec_subtask_2_cross-module-contract-ownership-seen-from-the-cli.md
