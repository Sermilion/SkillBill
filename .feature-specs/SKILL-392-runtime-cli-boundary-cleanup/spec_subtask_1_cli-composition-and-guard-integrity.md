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

## Next Path

skill-bill goal SKILL-392

## Spec Path

.feature-specs/SKILL-392-runtime-cli-boundary-cleanup/spec_subtask_1_cli-composition-and-guard-integrity.md
