# SKILL-392 runtime-cli investigation

## Judgment

runtime-cli is still a leaf adapter with clean module edges. SKILL-386 landed: no `@Inject` class exposes a constructor property, the scaffold gateway is gone, and the pause and resume statuses are typed. Three structural problems remain.

1. The feature-task run use case lives in the CLI. `FeatureTaskRuntimeRunExecution` opens the workflow, creates the execution plan, builds the `FeatureTaskExecutionIdentity`, acquires the worker lease, reads the run invariants port and assembles the engine request. The engine then derives the same identity again with a different path algorithm and compares the two. The CLI is the runner's only production caller. For goal parents the engine owns the lease. For feature tasks the adapter does.
2. The dependency bags did not disappear. They moved out of `@Inject` constructors, which the SKILL-386 guard scans, into internal argument data classes, which no guard scans. SKILL-229 recorded that as the preferred pattern in `runtime-cli/agent/history.md`.
3. Two CLI guards report green without enforcing their documented rule. The cycle guard uses first-segment granularity, so it cannot see three intra-area cycles that span ten packages. The area-isolation guard gained a `codereview` exemption that ARCHITECTURE.md does not allow.

Every fix deletes, merges or moves code. None adds a module, a layer or a framework. The only new types are the engine run entry and its input, which replace the CLI execution class. The only new guard rule targets the pattern that has recurred four times.

## Method and baseline

- Baseline: HEAD `ae23f4f28f16d851a0548e8149e0fe6fadbbc612` on `base/SKILL-380-phase-slot-strategies`. It equals `origin/base/SKILL-380-phase-slot-strategies` after `git fetch --all --prune`. The tree was clean.
- I did the census myself with grep and Python: import graphs, exact-package Tarjan SCC, `@Inject` constructor parsing, data-class property parsing, literal-versus-enum-wire matching and body hashing for duplicate commands. No review subagents were used. No build or test was run.
- Context read: AGENTS.md (CLAUDE.md points to it); runtime-kotlin/ARCHITECTURE.md (Design Principles, Gradle Modules, Package Ownership, Guardrails); docs/code-principles.md; runtime-cli/agent/history.md; the CLI entries in runtime-kotlin/agent/decisions.md. runtime-cli has no `../../../agent/decisions.md`.
- Prior work read: SKILL-386 in full (most recent), plus the relevant parts of SKILL-371, SKILL-373 (test ownership rule 8) and SKILL-229 history.
- Sibling bundles read: SKILL-387 and SKILL-388, both committed in bd8ec1350 and both pending. When I wrote this bundle, no untracked bundle existed.
- Key census, run immediately before writing, over `../..`, `.feature-specs/done/`, `git branch -a` and `git log --all`: the highest key in use was SKILL-388. The bundle was first written as SKILL-389. A runtime-core plan run then wrote a second SKILL-389 bundle, and peer sessions claimed SKILL-390 (runtime-engine), SKILL-391 (runtime-contracts) and SKILL-396 (runtime-infra) for in-flight plan runs. This bundle re-keyed to SKILL-395 and then to SKILL-392, because the runtime-mcp session had reserved 395 for its plan run and runtime-domain holds 397.

## Census

### Source sets

| Source set | Files | Lines |
|---|---|---|
| main | 117 | 12,212 |
| test | 70 | 14,668 |
| repoTest | 10 | 3,025 |
| testFixtures | none | none |

Main has 31 packages. Per-package file counts: featuretask 12 (at the sibling limit), scaffold.commands 10, config 7, kernel.cli 7, goal.run 6, core 6, model 6, install.apply 5, workflow 5, and 22 packages with 4 or fewer files.

### Build edges (runtime-cli/build.gradle.kts)

| Configuration | Edges |
|---|---|
| main `implementation` | runtime-application, runtime-contracts, runtime-core, runtime-domain, runtime-engine, runtime-ports; clikt, kotlin-inject runtime, kotlinx-serialization-json |
| main `api` | none (correct for a leaf) |
| `runtimeOnly` / `ksp` | slf4j-nop / kotlin-inject compiler |
| `testImplementation` | testFixtures of application, ports, infra:sqlite; runtime-infra host, contracts, skills, workflow, sqlite |

### Import namespaces in main

| Namespace | Imports | Namespace | Imports |
|---|---|---|---|
| skillbill.cli | 378 | skillbill.install | 57 |
| com.github (clikt) | 206 | me.tatarka (inject) | 48 |
| skillbill.application | 134 | skillbill.workflow | 36 |
| skillbill.engine | 95 | java.nio | 29 |
| skillbill.ports | 92 (23 distinct port types) | skillbill.error | 22 |
| skillbill.contracts | 71 | skillbill.di | 7 |

### Consumers of runtime-cli declarations

| Consumer | Symbols |
|---|---|
| runtime-mcp test (3 files) | `CliRuntime`, `CliRuntimeContext`, `CliExecutionResult` |
| any main source set | none |

No symbol has a single cross-module consumer other than these parity-test entry points, so there is no ownership move. No test imports a module above runtime-cli; there are zero `skillbill.mcp` imports.

### Declarations

- Interfaces 0, typealiases 0, abstract or open classes 7 (Clikt bases and two name-parameterized command bases).
- `@Inject` classes 158. Exposed constructor properties 0. Highest arity: `GoalRunCommand` 11, `FeatureTaskRuntimeRunExecution` 9.
- Hand-constructed `@Inject` collaborators 0.
- Internal data classes that carry collaborators: 10 classes, 20 fields (F-002).

### Package graph

- Area-level edges: every area imports only kernel and model, except that goal, operation and phase import `codereview` (F-004).
- Exact-package Tarjan SCC: 3 non-trivial components (F-003).

| SCC | Packages | Cycle edges |
|---|---|---|
| install | core, apply, mcp, nativeagent | core→apply (6 symbols), apply→core (`installPlanPayload`, `windowsPreflightPayload`) |
| goal | core, control, status | core→control (8), control→core (13 helpers), core→status (3), status→core (7 helpers) |
| scaffold | commands, payload, wizard | commands→payload (14), payload→commands (4 args classes), wizard→commands (3) |

### Errors, effects, wire vocabulary

- Catch sites: 44 typed (12 `ShellContentContractException`, 11 `IllegalArgumentException`, 8 `SkillBillRuntimeException`, 13 other single types), 7 `runCatching`, 0 `catch Exception`. `CliRuntime.execute` rethrows `CancellationException` and `InterruptedException`.
- Ambient effects: `System.out`/`System.err` in `Main.kt` (process edge); `System.in` fallback in `CliRunState` (process edge); `ZoneId.systemDefault` in the work table (presentation); `Thread.sleep` in the watch loop (CLI-only); `ProcessHandle.current()` at `goal/core/GoalCliCommands.kt:233` (F-011).
- `Map<String, Any?>` occurrences: 129. The only public signature is `CliExecutionResult.payload`, the retained embedding surface.
- JSON parse sites: 3 (`WorkflowCliCommands.kt:237,252`, `AgentAddonSelectionParsing.kt:15`), all through `JsonCodec` with a typed catch.
- Result settlement outside `CliRunState`: 18 direct `state.result =` writes and 21 `CliExecutionResult(` constructions outside `core` and `model` (F-006).
- Restated tokens: 13 `"not_found"` literals (F-008); `LearningScope` wire names twice; install option token lists twice, with unreachable `else` fallbacks (F-009).

### Tests

- The test package `skillbill.cli` holds 52 CLI-level integration tests. Main has no such package; it is the CLI's integration root. The root package `skillbill` holds 1 file (`TestSupport.kt`). repoTest `skillbill.installer` holds 5 installer shell tests; that placement is a decisions.md entry.
- 14 test files never call `CliRuntime`. All but one are CLI unit tests or support files. The exception is `IdeStatusReadSnapshotConcurrencyTest`, which exercises only engine `IdeStatusService` over real SQLite through `RuntimeComponent` (F-012).

## SKILL-386 landing check

| Criterion | Status | Evidence |
|---|---|---|
| Subtask 1, AC 1-10 (bags, thunk, alias delegation, holders, guard) | Landed | 0 exposed properties; 0 `() -> String` parameters; the alias delegates to `FeatureTaskRuntimeRunPreparation`/`Execution`; `InjectConstructorDefaultsArchitectureTest` includes `RUNTIME_CLI_MAIN` |
| Subtask 2, AC 1-2 (scaffold gateway chain) | Landed | 0 references to `UnsupportedScaffoldGateway` or `retired*Message` |
| AC 3 (pause/resume enums pinned) | Landed | Both enums are in `PINNED_ENGINE_INBOUND_API_TYPES` |
| AC 4 ("no `not_found` string literal remains in runtime-cli main goal packages") | Partial | 11 literals remain in the goal packages, 13 CLI-wide, for absent-projection status (F-008). The history entry self-reports 7/7. |
| AC 5-8 (add-on version constant, mappers, format enum, narrowed catch, docs) | Landed | 0 `"0.1"` literals; one `ContinuationCandidateCliPayloads.kt`; 0 `wireName ==`; 0 `catch Exception` |

SKILL-386 follow-ups: the domain/engine add-on decoder duplicate belongs to the domain and engine owners and is not repeated here. `IdeStatusReadSnapshotConcurrencyTest` is still open and becomes F-012.

## Checklist

1. **Dependency direction.** Clean. The six main edges all point inward and are `implementation`; a leaf needs no `api`. `RuntimeAdapterDependencyAllowlistTest` pins them.
2. **Inbound side.** Mostly direct: commands call application services, pinned engine services and ports without reaching into getters. Violation: the feature-task run command coordinates five engine and port collaborators around a domain aggregate it builds itself (F-001). No re-decoding of domain data remains; the SKILL-386 mappers are shared.
3. **Outbound side.** Clean because the CLI's 23 driven-port types are purpose-built (SKILL-231 decision). The CLI has no git, gh or HTTP argv, URLs or SQLite error strings. SKILL-388 owns application git argv.
4. **Domain richness and duplicated rules.** Violation, three cases:
   - The identity rule has three derivations: CLI `FeatureTaskRuntimeRunExecution.kt:62-79`, engine `FeatureTaskRuntimeExecutionEntry.kt:26-40` (canonical path plus `optionalRealPath`, no `.feature-specs` check) and application `WorkflowServiceIdentity.kt:21-41`.
   - Issue-key normalization is inlined as `trim().uppercase()` at 1 CLI site and 13 engine sites, although `FeatureTaskExecutionIdentityPolicy.normalizeIssueKey` exists. The CLI site goes away in F-001; the engine sites are follow-ups.
   - Initial add-on selection resolution is repeated at `AgentAddonCliCommands.kt:59-67`, `GoalRunInputPreparation.kt:35-44` and engine `GoalPreflightGateBlockBuilder.kt:57-65` (F-010).
5. **Composition.** No second root and no service locator: no command reads `RuntimeComponent`, and only `CliRuntime` and `CliComponent` touch it. Bags remain as argument data classes (F-002), and a port rides on `CliRunInputs` (F-005).
6. **Entry-point leakage.** Clean for the CLI: no Clikt type or CLI flag text exists below runtime-cli. SKILL-388 F-005 owns uninstall text in application.
7. **Ambient effects.** One unjustified site, `ProcessHandle.current()` at `GoalCliCommands.kt:233`, even though `HostPlatformPort` already supplies the neighbouring `jvmClassPath` and `pathSeparator` (F-011). The other sites are at the process edge or presentation-only.
8. **State and transactions.** `CliRunState` and `CliRunInputs` are per run and provided by `CliComponent`. The CLI opens no transaction. It does own the feature-task worker lease lifecycle through `runOwned` (F-001). The public `CliRunState.result` setter lets 18 sites bypass the settlement methods (F-006).
9. **Error model.** There is one mapping point, and cancellation propagates. Two issues:
   - The CLI catches `IllegalArgumentException` at 11 sites because lower layers report malformed input with `require`: `RuntimeOwnedReviewMode.parse` (domain), `decodeScaffoldPayloadObject` (application) and `validateReleaseRef` (infra). The owning modules must fix this; it is recorded as a follow-up.
   - Two sites re-implement `usageError` inline: `FeatureTaskRuntimeRunRequestAssembly.kt:138-145` and `GoalCliRunCommands.kt:91-97` (F-004).
10. **Cohesion and ownership.** `IdeStatusReadSnapshotConcurrencyTest` belongs to runtime-core tests under SKILL-373 rule 8 (F-012). No main code is used by only one other module.
11. **YAGNI.** Findings:
    - `new` and `new-skill` have byte-identical 60-line command bodies (F-007).
    - The scaffold area rebuilds `CliRunState` settlement three times (F-006).
    - The add-on entry map is copied twice (F-010).
    - The install `when` mappings have unreachable fallbacks (F-009).
    - The `featureTaskRuntimeRunOverride` hook threads through production code for one test. It is retained as a SKILL-371 test seam and retyped in F-001.
12. **Naming and packages.** Package roles and cycles:
    - `goal.core` is both the command host and a helper bucket that `control` and `status` import back (F-003).
    - `codereview` acts as a command area and as a shared leaf (F-004).
    - featuretask is at 12/12 siblings, and F-001 removes one file.
    - No `x.y.x` stutter.
13. **Guard validity.** The table below lists each guard.

| Guard | Root resolution | Reads files | Enforces its documented rule |
|---|---|---|---|
| `RuntimeEngineInboundApiTest` | `runtimeArchitectureRoot.resolve("runtime-cli/src/main/kotlin")` | yes, `kotlinFilesUnderWithArchitectureAsserts` | yes |
| `InjectConstructorDefaultsArchitectureTest` (CLI method) | `runtimeRoot.resolve(RUNTIME_CLI_MAIN)`, where `runtimeRoot` is the repo root and `RUNTIME_CLI_MAIN` is `../../../runtime-kotlin/runtime-cli/src/main/kotlin` | yes; a missing root errors | only for `@Inject` constructors; argument bags are out of scope (F-002) |
| `ApplicationPackageAcyclicityArchitectureTest` (runtime-cli census) | same root | yes | no: the default `FIRST_SEGMENT_MUTUAL_PAIR` collapses `goal.core`/`goal.control` into one node (F-003) |
| `RuntimeCliAreaIsolationArchitectureTest` | same root | yes | no: `cliSharedLeafAreas` = {codereview, kernel, model} (`PrincipleEnforcementInventory.kt:102`, added by SKILL-372 in 3973aa265), while ARCHITECTURE.md:2093-2095 and :2198-2201 allow kernel and model only (F-004) |
| `RuntimeAdapterDependencyAllowlistTest` | `RuntimeModuleCatalog` | build files | yes |

The `:runtime-core:repoTest` task declares every `runtime-kotlin/**/src/**` file as an input (runtime-core/build.gradle.kts:10-26), so a build-cache hit cannot hide a source change.

## Principles

| Principle | Assessment |
|---|---|
| SRP | Violated: `FeatureTaskRuntimeRunExecution` combines use-case orchestration with CLI presentation (F-001), and `goal.core` combines command hosting with shared helpers (F-003). |
| OCP | Clean. |
| LSP | Clean: the only inheritance is Clikt bases and name-parameterized commands. |
| ISP | Violated: argument bags hand every free function more collaborators than it uses; `NativeScaffoldRunArgs` carries 5 (F-002). |
| DIP | Violated narrowly: a driven port is passed around as a field of a per-run value object instead of being injected (F-005), and ambient `ProcessHandle` is used (F-011). |
| Hexagonal | Violated: the inbound adapter owns a lease invariant and derives a domain aggregate the engine re-derives (F-001). |
| YAGNI | Violated by duplicate command bodies, parallel settlement builders, copied entry maps and dead `else` branches (F-006, F-007, F-009, F-010). |
| Guard integrity | Violated: two guards pass without enforcing their documented rule (F-003, F-004). |

## Findings

### F-001 (P1). The feature-task run use case is orchestrated in the CLI, and the execution identity is derived twice

**Evidence.**
- `runtime-cli/.../featuretask/FeatureTaskRuntimeRunExecution.kt` does all of the following:
  - calls `workflowService.openRuntimeWorkflowId` (:123) with `executionPlans.resolveCreation` (:142);
  - calls `workerCoordinator.runOwned` (:53), which acquires the lease, starts heartbeats and fences;
  - builds `FeatureTaskExecutionIdentity` inline, including `issueKey.trim().uppercase()` (:62-79);
  - reads the `FeatureTaskRuntimeRunInvariantsSource` port inside the lease (:83);
  - copies admitted review mode and add-on selection into the invariants (:92-97);
  - builds `FeatureTaskRuntimeRunRequest` and calls `runner.run` (:111).
- Engine `FeatureTaskRuntimeRunner.run` (FeatureTaskRuntimeRunner.kt:56) calls `FeatureTaskRuntimeExecutionEntry.admit`, which builds a second expected identity with a different path algorithm (FeatureTaskRuntimeExecutionEntry.kt:26-40). It admits again, then re-checks the issue key with `trim().uppercase()` (FeatureTaskRuntimeRunner.kt:66-71).
- The CLI file is the runner's only production caller. Four pinned engine inbound types are referenced only by this file: `FeatureTaskRuntimeRunner`, `FeatureTaskRuntimeWorkerCoordinator`, `FeatureTaskRuntimeExecutionPlanResolver` and `FeatureTaskRuntimeExecutionPlanCreationRequest`.
- For goal parents the engine owns the same lease pattern (`GoalRunner.kt:57`, `executionCoordinator.runOwned`). For feature tasks the adapter owns it.
- Every goal subtask launches a child through this CLI path.

**Why SKILL-386's retention does not cover this.** SKILL-386 kept "run preparation" in the CLI because the CLI is its only consumer, and it placed workflow execution in a CLI class. That retention stands for preparation: spec-path resolution, matrix and compaction, launcher availability, add-on verification and resume verification. The new evidence concerns execution: the identity is derived twice with different algorithms, and the correctness of the admission comparison depends on those algorithms agreeing. The lease invariant sits outside the module that defines it.

**Fix.** Move the execution sequence into runtime-engine as one `@Inject` entry in `skillbill.engine.featuretask.runner` (9 files, becomes 10). It owns open-or-reuse of the workflow id with its execution plan, one identity derivation, the worker lease, the invariants read, request assembly and `runner.run`, and it returns `FeatureTaskRuntimeRunReport`. `FeatureTaskRuntimeExecutionEntry.admit` uses the same identity derivation.
- The CLI keeps option parsing, preparation, the `UsageError` texts, presentation, exit codes and the telemetry drain.
- The `featureTaskRuntimeRunOverride` seam stays on `CliRuntimeContext` and `CliRunInputs`, retyped to the new entry's input and output. Its only test asserts argv-to-request parsing.
- Pin the entry and its input. Unpin the engine types no consumer main references any more.

**Feasibility.**
- Engine main already depends on runtime-application (`implementation`) and imports `WorkflowService` (`engine/operation/verify/VerifyOperation.kt`). `openFeatureTask` and `resolveFeatureTaskGovernedSpecPath` are public application functions. `FeatureTaskRuntimeRunInvariantsSource` is a port the engine may use.
- The entry needs 6-7 constructor parameters, under `constructorThreshold` 12. kotlin-inject resolves it as an unscoped `@Inject` class. `FeatureTaskRuntimeWorkerCoordinator` stays `@RuntimeSingleton`.
- The identity derivation must reproduce the stored identities. Persisted standalone identities came from the application resolver, through the CLI's `governedSpecPathForCli`, and the engine's admission compares against them today. So the single derivation must produce identical strings for existing rows.
- The goal-child persisted derivation (`GoalRunnerSubtaskLaunchPrepare.governedChildSpecPath`) is not touched.

### F-002 (P2). Dependency bags moved into argument data classes

**Evidence.** 10 internal data classes carry 20 collaborator fields next to values:

| Class | Collaborators |
|---|---|
| `NativeScaffoldRunArgs` (ScaffoldCliArgs.kt:13) | state, clock, scaffoldGateway, externalAddonOverlayService |
| `AssistedScaffoldWizardArgs` (:23) | scaffoldCatalogGateway, installAgentService |
| `ScaffoldWizardArgs` (:29) | scaffoldCatalogGateway |
| `CreateAndFillArgs` (:48) | state, clock, scaffoldGateway |
| `NewAddonPayloadArgs` (:58) | state |
| `EditSkillRunArgs` (ScaffoldAuthoringCliCommandRuns.kt:17) | state, scaffoldGateway |
| `FillSkillRunArgs` (:29) | state, scaffoldGateway |
| `GoalRunInputValidationArgs` (GoalCliRunArgs.kt:9) | executableLookup |
| `GoalRunAgentAddonHydrationArgs` (:20) | agentAddonSelectionPort, externalAgentAddonSourceConfigPort |
| `VerifyRuntimeResumeArgs` (FeatureTaskRuntimeCliFormatting.kt:37) | lookupService, repositoryEnclosingRootPort |

- Commands inject collaborators privately and then repack them into these classes on every call (ScaffoldNewCliCommands.kt:53-61, 115-123).
- runtime-cli/agent/history.md (SKILL-229 subtask 2) records "Prefer a holder over widening a command's parameter list".
- The bags came back after SKILL-229, SKILL-348, SKILL-371 and SKILL-386 each removed some. The SKILL-386 guard scans only `@Inject` constructors.

**Fix.**
- Turn each free-function family into an `@Inject` class that owns its collaborators privately: native scaffold run, scaffold wizards, authoring edit and fill, goal-run input validation and add-on hydration.
- Fold resume verification into `FeatureTaskRuntimeRunPreparation`, which already holds `lookupService`.
- Argument classes keep only values. Where `externalAddonOverlayService` is optional, whether to register external sources becomes a per-call value; the collaborator is not nullable.
- Extend the existing inject-property scanner in `ArchitectureScanGuardSupport.kt` with a data-class rule applied to `RUNTIME_CLI_MAIN`. An `internal data class` must not declare a property of type `CliRunState` or `Clock`, or whose simple type name ends in `Service`, `Port`, `Gateway`, `Lookup`, `Repository`, `Coordinator`, `Runner`, `Launcher` or `Diagnostics`.
- Add the test method, with a synthetic rejection case, to `InjectConstructorDefaultsArchitectureTest`, and remove the history line's advice.

**Feasibility.** Everything stays inside runtime-cli plus one scanner extension. The new classes are unscoped `@Inject`, so each command gets its own instance through the `CliComponent` graph. `CliRunState` and `CliRunInputs` are already `@Provides` there. The data-class rule excludes `CliRunInputs` (per-run values) and public types, so `CliRuntimeContext`, the retained embedding seam, stays out of scope with no exemption list.

### F-003 (P2). Three intra-area package cycles are invisible to the cycle guard

**Evidence.** The exact-package SCCs are listed in the census.
- `ApplicationPackageAcyclicityArchitectureTest.kt:24-35` scans runtime-cli with the default `FIRST_SEGMENT_MUTUAL_PAIR`, which treats `goal.core` and `goal.control` as one node.
- `PrincipleEnforcementInventory.kt:39-44` applies `EXACT_PACKAGE_SCC` only to runtime-domain and runtime-contracts. ARCHITECTURE.md:670-674 names only runtime-domain.
- SKILL-229 history says runtime-cli "has zero package cycles".
- Each cycle comes from helpers declared in a hub package but used by exactly one leaf:
  - goal: `goalPauseExitCode` and 7 sibling exit codes, `goalRepairText`, `toGoalRepairCliMap`, `goalOperatorDecisionText`, `toGoalOperatorDecisionCliMap` and `appendGoalResetSubtaskLines` are used only by `goal.control`. `GoalCliWatchPresentation.kt` and `goalStatusExitCode` are used only by `goal.status`.
  - install: `installPlanPayload` and `windowsPreflightPayload` in `install.core` are used by `install.apply`.
  - scaffold: the argument classes (F-002) and `assistedPlatformProfile` are declared in `scaffold.commands` and used by `payload` and `wizard`.

**Fix.** Move each helper to its only consumer package:
- `GoalCliFormatting.kt` and the control exit codes go to `goal.control` (3 files, becomes 5).
- `GoalCliWatchPresentation.kt` and `goalStatusExitCode` go to `goal.status` (3 files, becomes 5).
- `goalRunExitCode` stays in `goal.core`.
- `InstallCliPayloads.kt` goes to `install.apply` (5 files, becomes 6).
- F-002's classes land in `scaffold.payload` and `scaffold.wizard`. `AssistedPlatformProfile.kt` and `ScaffoldAssistedPlatformProfiles.kt` go to `scaffold.wizard`.

Then run the runtime-cli census with `EXACT_PACKAGE_SCC` against the existing empty `runtime-cli-package-cycle-baseline.txt`, and update the ARCHITECTURE.md sentence.

**Feasibility.** These are file moves inside one module; `internal` visibility still resolves. The core→leaf edges that remain are acyclic, because no leaf imports `core` afterwards.

### F-004 (P2). The area-isolation guard was widened past its documented rule

**Evidence.**
- `cliSharedLeafAreas` includes `codereview` (PrincipleEnforcementInventory.kt:102, SKILL-372). ARCHITECTURE.md allows only kernel and model.
- The exemption exists so three areas can import two helpers: `usageError` (CodeReviewCommand.kt:272), imported by `OperationCommand.kt:10`, `PhaseCommand.kt:11` and `GoalRunInputPreparation.kt:6`; and `namedStandaloneScope` (StandaloneCodeReviewTarget.kt:49), imported by `PhaseCommand.kt:10`.
- The same wrap-and-`initCause` body is re-implemented at `FeatureTaskRuntimeRunRequestAssembly.kt:138-145` and `GoalCliRunCommands.kt:91-97`. Both map `RuntimeOwnedReviewMode.parse` failures to a `UsageError`.

**Fix.** Move `usageError` and `StandaloneCodeReviewTarget.kt` to `skillbill.cli.kernel.cli` (7 files, becomes 8). Replace the two inline copies with `usageError`. Remove `codereview` from `cliSharedLeafAreas`.

**Feasibility.** Kernel may import runtime-application's `ParallelReviewScope`, because area isolation constrains only `skillbill.cli.*` imports. `codereview` keeps `CodeReviewCommand` and imports kernel.

### F-005 (P2). A driven port is carried on the per-run value object

**Evidence.**
- `CliRunInputs.repositoryEnclosingRootPort` (CliRunInputs.kt:13) is set from `runtimeComponent.repositoryEnclosingRootPort` (CliRuntime.kt:44).
- It is read through `inputs.` at 8 sites (featuretask execution, control commands, preparation) and passed on through `VerifyRuntimeResumeArgs`.
- `RuntimeComponent` already has `@Provides fun repositoryEnclosingRootPort()` (RuntimeComponent.kt:143-145), which the child `CliComponent` can resolve, and CLI commands already inject other ports that way.
- 4 tests construct `CliRunInputs` with the port.

**Fix.** Inject `RepositoryEnclosingRootPort` where it is used and delete the field.

**Feasibility.** kotlin-inject resolves parent `@Provides` for `CliComponent`, because the parent is declared as `@Component val runtimeComponent`. The binding is unscoped and currently returns the `CanonicalRepositoryRoot` object, so behavior is identical.

### F-006 (P3). Results settle outside `CliRunState`

**Evidence.**
- `CliRunState.result` has a public setter. There are 18 direct writes: `scaffold/commands` 11, `scaffold/payload` 2, `install/nativeagent` 3, `skillremove` 1, `system` 1.
- There are 21 `CliExecutionResult(` constructions outside `core` and `model`. `errorResult`, `authoringResult` and `unsupportedNativeScaffoldResult` (ScaffoldCliPayloadRuns.kt:141-228) re-implement `CliRunState.complete`, and they drop accumulated stderr and the completion kind.

**Fix.** Settle through `complete`, `completeText`, `completeRaw` and `completeEmpty`, and make the setter private.

**Feasibility.** `emitCliProcessStdout` treats `TEXT` and `IMPLICIT` identically. None of these paths appends stderr, so stdout, stderr and exit bytes stay identical. No test asserts `IMPLICIT` for these commands; `CliProcessStdoutTest` constructs its own values.

### F-007 (P3). The `new` and `new-skill` command bodies are duplicated

**Evidence.** `NewSkillCommand` (ScaffoldNewCliCommands.kt:27-87) and `NewCommand` (:89-149) hash identical apart from the name. Both are registered (ScaffoldCliSubcommandGroups.kt:28-29).

**Fix.** Use one name-parameterized base with two thin registered subclasses, the pattern `WorkflowGetCommand` already uses. Help text and order stay unchanged.

### F-008 (P3). The SKILL-386 AC 4 literal clause did not land

**Evidence.** 13 `"not_found"`/`"ok"` absent-projection status literals remain:
- `FeatureTaskRuntimeStatusPresentation.kt:53,101`
- `GoalCliWatchPresentation.kt:29,40,51`
- `GoalCliStatusFormatting.kt:82,176,178,247`
- `GoalCliControlFormatting.kt:44,65,212,250`

**Fix.** Declare the CLI-owned lookup status vocabulary once, in `skillbill.cli.kernel.payload`, and reference it from all 13 sites. Payload and text bytes stay unchanged.

### F-009 (P3). Option tokens are restated beside their enums

**Evidence.**
- `LearningCliCommands.kt:113-115,141-143` restate `LearningScope.wireName`.
- `InstallRequestCommand.kt` declares `.choice(...)` token lists (:43-60, :93-100) and then maps the same strings again in `when` blocks. Their `else` fallbacks are unreachable (:183-213). `InstallTelemetryLevel` already has wire values.

**Fix.** Build each choice map once from the owning enum's wire values, or from one CLI map where the enum has no wire value. Drop the `when` blocks. Help output is unchanged because the choice keys and their order are the same.

### F-010 (P3). Add-on selection resolution and entry rendering are repeated

**Evidence.**
- The same `resolveInitial` plus `readExternalAgentAddonSources` sequence appears at `AgentAddonCliCommands.kt:59-67` and `GoalRunInputPreparation.kt:35-44`, and in the engine at `GoalPreflightGateBlockBuilder.kt:57-65`.
- The entry payload map is copied at `AgentAddonCliCommands.kt:70-81` and :123-134.

**Fix.** Within runtime-cli, make one `kernel.agent` owner for initial resolution with configured external sources, and one entry mapper. The engine copy is a follow-up for its owner.

### F-011 (P3). The goal-run provenance reads the ambient JVM process

**Evidence.** `ProcessHandle.current().info().command()` at `GoalCliCommands.kt:233`, next to `hostPlatform.jvmClassPath` and `hostPlatform.pathSeparator` from `HostPlatformPort` (HostPlatformPort.kt:5-8).

**Fix.** Add the java command to `HostPlatformPort`, implemented by `JdkHostPlatformPort`, and read it there.

**Feasibility.** Four handwritten substitutes implement the interface: `UninstallMutationFailurePolicyTest`, `CliRunInputsRuntimeTest`, application `SkillBillUninstallCooperativeCancellationTest`, and `CodexConfigRootsTest` by delegation. The first three need the member.

### F-012 (P3). A test lives in the wrong module

**Evidence.** `IdeStatusReadSnapshotConcurrencyTest` (runtime-cli test, 278 lines) never touches `CliRuntime`. It builds `RuntimeComponent`, a real SQLite session factory and `CanonicalRepositoryRoot` around engine `IdeStatusService` (lines 190-214).

**Fix.** Move it to runtime-core `src/test` under `skillbill.di.*`. It meets SKILL-373 rule 8: it constructs the component and uses two real adapters.

**Feasibility.** runtime-core's test classpath has engine (`api`), infra host and sqlite (`implementation`) and sqlite testFixtures (runtime-core/build.gradle.kts:38-58).

## Over-engineering register

- The 10 argument bags and the free-function families they feed (F-002).
- The CLI-side execution class that re-derives what the engine derives (F-001).
- Three parallel settlement builders plus 18 direct result writes (F-006).
- The duplicate `new` body (F-007).
- The copied add-on entry map and resolution sequence (F-010).
- The two inline `usageError` copies (F-004).
- Dead `else` fallbacks behind `.choice` (F-009).
- The `codereview` guard exemption (F-004).

Every fix removes or merges code. The only additions are the engine run entry and its input (F-001), which replace `FeatureTaskRuntimeRunExecution`'s orchestration, and one CLI status vocabulary (F-008), which replaces 13 literals.

## What stays unchanged

| Item | Reason |
|---|---|
| Direct driven-port use by CLI commands (23 port types) | SKILL-231 decision; application wrappers would be forwarding layers. |
| Run preparation in the CLI (spec path, matrix, launcher availability, add-on verification, resume verification) | SKILL-386 retention; the CLI is its only consumer. Only execution moves (F-001). |
| `CliRuntimeContext`, `OptionalCallbacks` and the run-override seam | SKILL-371 retention: embedding and test seam. The override is retyped, not removed. |
| runtime-mcp parity tests importing `CliRuntime` | SKILL-375 retention: the only cross-surface parity evidence. |
| Internal raw-map presentation and the public `CliExecutionResult.payload` | SKILL-348/371 decision; no other public map function exists. |
| Clikt, kotlin-inject, the per-run `CliRunState`/`CliRunInputs`, the five core command groups | Standard choices with no measured problem. |
| The deprecated `feature-task-runtime` alias | SKILL-371/386 retention; it already delegates. |
| No inbound use-case interfaces for the new engine entry | It has one implementation and no test substitute. Tests use the real entry or the retained CLI override. |
| No `explicitApi` or blanket `internal` | Leaf module with no main-source consumer (SKILL-386). |
| No split of files by size (the largest main file is 368 lines) | Size is a signal, not a cohesion rule; all files are under the ceiling. |
| The 52 `skillbill.cli` integration tests stay in their package | It is the CLI integration root; moving them by area is churn that catches no regression. |
| repoTest `skillbill.installer` | decisions.md: launcher shell tests run in `:runtime-cli:repoTest`. |
| The `System.in` fallback in `CliRunState`, `Thread.sleep` in watch, `ZoneId.systemDefault` in the work table | Process edge or presentation only. |
| Typing the CLI's own report statuses (`complete`/`blocked`/`paused`/`decomposed`) | CLI-owned presentation that maps sealed report types exhaustively; an enum would add a layer without closing a gap. |
| The 11 `IllegalArgumentException` catches | The CLI correctly maps what lower layers throw. The fix belongs to the throwing owners (follow-up); changing only the CLI would drop messages. |
| Engine `trim().uppercase()` sites, the engine add-on resolution copy, `FeatureTaskRuntimeRunner`'s exposed `val` getters | Engine-owned; recorded for the engine owner, not planned here. |

## Coordination with concurrent bundles

| Bundle | Overlap | Owner | Sequencing |
|---|---|---|---|
| SKILL-388 runtime-application boundary repairs (pending) | Its F-005 moves uninstall text into `skillbill.cli.system`; this bundle does not edit `UninstallCommand.kt`. Both extend repoTest scanner support: SKILL-388 changes the typed-parse and wire-vocabulary inventories, this bundle changes the inject-property scanner, the CLI cycle granularity and `cliSharedLeafAreas`. | SKILL-388 owns application and uninstall text. This bundle owns CLI composition and CLI guards. | Land SKILL-388 first. SKILL-388's own note sequences it after SKILL-386, which has landed. Rule: whichever lands second rebases onto the other's `PrincipleEnforcementInventory.kt` and `ArchitectureScanGuardSupport.kt` without dropping either change. |
| SKILL-387 prose phase output (pending) | No shared symbol. It rewrites engine phase-output admission; subtask 2 here changes engine `featuretask.runner` entry and `lifecycle.execution` identity derivation. | SKILL-387 owns phase output. This bundle owns the run entry. | Independent. Rule: whichever lands second rechecks `FeatureTaskRuntimeRunner.run`, `FeatureTaskRuntimeRunRequest` and `FeatureTaskRuntimeExecutionEntry` against the other's change. |
| SKILL-389 runtime-core composition and guard regressions (pending) | Its subtask 1 edits `RuntimeComponent` accessors and `PrincipleEnforcementInventory` rows; its subtask 2 moves runtime-core tests under `skillbill.di.*`. This bundle's F-012 adds one test under `skillbill.di.*`, consistent with that rule. F-005 stops reading the `repositoryEnclosingRootPort` accessor and uses the `@Provides` function, which that bundle keeps. | SKILL-389 owns runtime-core composition and test placement. This bundle owns CLI composition and CLI guard rows. | Land SKILL-389 first; it sequences itself after SKILL-388 and SKILL-387. Rule: whichever lands second rebases onto `PrincipleEnforcementInventory.kt` and keeps both changes. If SKILL-389 deletes the `repositoryEnclosingRootPort` accessor, that is compatible once F-005 lands. |
| SKILL-390 runtime-engine (plan run in flight) | Its owner took this bundle's engine follow-ups: the 13 `trim().uppercase()` sites, the `FeatureTaskRuntimeRunner`/`FeatureTaskRuntimeRunStartup` constructor vals, and `GoalPreflightGateBlockBuilder:57`. Subtask 2 here changes `featuretask.runner` and `lifecycle.execution`. | SKILL-390 owns engine internals. This bundle owns the run entry it moves out of the CLI. | SKILL-390 sequences itself after this bundle's subtask 2. |
| SKILL-396 runtime-infra and SKILL-391 runtime-contracts (plan runs in flight) | runtime-infra acknowledged F-011's `HostPlatformPort` java-command member as owned here, and sequences around it. | Their owners. | Recheck both bundles before implementing subtask 2. |
| SKILL-393 runtime-ports ownership (pending) | It edits `PINNED_ENGINE_INBOUND_API_TYPES`: it adds `GoalRunnerAppliedRepair`, `GoalRunnerChildWedgeDiagnosis`, `GoalRunnerWedgeFinding` and `GoalRunnerResetSubtaskSnapshot` at engine paths and drops the alias-only `IdeStatusSnapshot` and `IdeStatusProblemCode`. It switches CLI imports of those types and of `GoalRunnerRepairResult`/`GoalRunnerRepairStatus` from ports to engine, in `goal/core/GoalCliFormatting.kt` and `goal/core/GoalCliExitCodes.kt`, the files F-003 moves to `goal.control`. It deletes the engine `work.model` typealiases. | SKILL-393 owns the ports-to-engine type moves. This bundle owns the CLI file moves and its own pin edits (subtask 2). | No hard order. Rules: whichever lands second keeps both sets of pin edits, and applies the other's import changes to the files where they now live. If SKILL-393 lands first, the test F-012 moves imports `IdeStatusProblemCode` from `skillbill.ports.idestatus.model`. |
| SKILL-386 (done) | This bundle closes its AC 4 residue (F-008) and its test-ownership follow-up (F-012). | Archived. | None. |

Global order: SKILL-388, then SKILL-387, then SKILL-389, then SKILL-392 (subtask 1, then 2), then SKILL-390 (runtime-engine). Subtask 1 is runtime-cli-internal and may land earlier than SKILL-387 under the rebase rules above. Subtask 2 waits for SKILL-387 and SKILL-389.

Parallel sessions were starting on other modules when I wrote this bundle. Before implementing, recheck `../..` for new runtime-engine `featuretask.runner`/`lifecycle.execution`, runtime-ports `HostPlatformPort` or repoTest scanner bundles.

## Follow-ups (not in this bundle)

- Engine: route the 13 `trim().uppercase()` sites through `FeatureTaskExecutionIdentityPolicy.normalizeIssueKey`; make `FeatureTaskRuntimeRunner` and `FeatureTaskRuntimeRunStartup` constructor properties private; unify the preflight add-on resolution with the CLI owner (F-010).
- Domain, application and infra: return typed failures instead of `IllegalArgumentException` from `RuntimeOwnedReviewMode.parse`, `decodeScaffoldPayloadObject` and `validateReleaseRef`, so the 11 CLI catches can narrow.

## Limits

Only compiling can confirm:
- kotlin-inject resolution of the new engine entry, the new CLI classes that replace the argument bags, and `RepositoryEnclosingRootPort` injected from the parent component;
- detekt thresholds on the new constructors;
- that the relocated helpers keep `internal` visibility working across the moved packages.

Only tests can confirm:
- byte-identical CLI output, through `CliRuntimeShellCommandsTest`, `CliAuthoringParityTest`, the goal CLI suites and the runtime-mcp parity tests;
- that the single identity derivation reproduces persisted identities, through the existing admission and worker-coordinator suites;
- that the extended scanners reject their synthetic cases.

No build, test or architecture test was run during this investigation.
