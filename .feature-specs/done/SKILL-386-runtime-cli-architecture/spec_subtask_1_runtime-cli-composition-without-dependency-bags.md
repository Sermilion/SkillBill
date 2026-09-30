# SKILL-386 Subtask 1 - runtime-cli composition without dependency bags

Parent spec: [.feature-specs/SKILL-386-runtime-cli-architecture/spec.md](spec.md)
Issue key: SKILL-386

## Scope

Covers F-001, F-002, F-003, F-004, F-007 and F-008, all in runtime-kotlin/runtime-cli/src/main/kotlin plus one test method in runtime-kotlin/runtime-core/src/repoTest/kotlin/skillbill/architecture/InjectConstructorDefaultsArchitectureTest.kt.

Changes:
- Replace FeatureTaskRuntimeRunDependencies with injected CLI classes that own feature-task run behavior. Pre-launch preparation (spec-path resolution, config matrix and compaction, launcher availability, add-on verification) and workflow execution (workflow-id open or reuse, runOwned, completion and telemetry drain) are split on that responsibility boundary. All constructor properties are private.
- Pass the resolved workflow id instead of a thunk.
- Make the deprecated feature-task-runtime alias commands delegate to the same code as feature-task.
- Inline ScaffoldNewDependencies.
- Delete GoalRunExecution.
- Convert every subcommand holder to the body `commands` list pattern used in core/CliCommandGroups.kt.
- Run injectConstructorPropertyViolations against PrincipleEnforcementInventory.RUNTIME_CLI_MAIN with an empty baseline.

## Acceptance Criteria

1. runtime-kotlin/runtime-cli/src/main/kotlin declares no type named FeatureTaskRuntimeRunDependencies or ScaffoldNewDependencies and no `@Inject data class`.
2. prepareRuntimeRun, resolveRunWorkflowId, executeRuntimeRun and resolveSpecPath (or their replacements) take no parameter that bundles injected collaborators. Their behavior lives on @Inject classes in skillbill.cli.featuretask whose constructor parameters are all private, split between pre-launch preparation and workflow execution.
3. No function in runtime-cli main declares a workflow-id parameter of function type (`() -> String`). The run path still prepares first, then resolves or opens the workflow id, then executes.
4. The classes in FeatureTaskRuntimeAliasCliCommands.kt contain no copy of spec-path resolution, run preparation, workflow-id resolution, resume verification or status rendering. Each calls the same code its feature-task counterpart uses, and adds only hiddenFromHelp on the parent and the deprecation note, whose text is unchanged from the baseline.
5. GoalRunExecution no longer exists, and GoalRunCommand holds GoalRunner directly as a private constructor parameter.
6. InjectConstructorDefaultsArchitectureTest contains a test that calls injectConstructorPropertyViolations with scanRoot PrincipleEnforcementInventory.RUNTIME_CLI_MAIN and an empty baseline, next to the existing runtime-application test.
7. Every subcommand holder in FeatureTaskRuntimeSubcommands.kt, GoalCliCommands.kt, InstallCliSubcommandGroups.kt, NativeAgentCliCommandGroups.kt, LearningCliCommands.kt, ReviewCliCommands.kt, ScaffoldCliSubcommandGroups.kt and WorkflowCliCommands.kt exposes its commands through a body property built from private constructor parameters. GoalRunCommand registers its subcommands without chained holder access such as `controls.flow.pause`.
8. For every command group, the order of commands passed to subcommands(...) and of the body command lists is the same as at dfb489641.
9. Every @Inject class changed in this subtask has at most 12 constructor parameters. No detekt suppression, detekt baseline entry, architecture baseline entry or new architecture test class is added.
10. CliRuntimeShellCommandsTest still asserts the deprecated-alias stderr note for run, status and resume. No new test only restates the structure the extended guard already enforces.

## Non-Goals

- Changing any CLI output bytes, exit codes, option names, help text or help order.
- Deleting the deprecated feature-task-runtime alias or changing its note.
- Moving run preparation into runtime-application or runtime-engine.
- Changing CliRuntimeContext, CliRunState, CliComponent or the five core command groups.
- Touching modules other than runtime-cli main and the one guard test method.

## Dependency Notes

Depends on: none
No dependencies. This subtask leaves the UnsupportedScaffoldGateway parameter in place wherever ScaffoldNewDependencies used to carry it; subtask 2 deletes it.

## Validation Strategy

The validate phase compiles runtime-cli and runtime-core, runs detekt and the architecture repoTests (including the extended InjectConstructorDefaultsArchitectureTest), and runs the runtime-cli and runtime-mcp test suites unchanged as evidence of byte-identical output.

## Next Path

skill-bill goal SKILL-386

## Spec Path

.feature-specs/SKILL-386-runtime-cli-architecture/spec_subtask_1_runtime-cli-composition-without-dependency-bags.md
