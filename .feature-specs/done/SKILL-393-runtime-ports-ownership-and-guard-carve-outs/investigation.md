# runtime-ports architecture investigation (SKILL-393)

## Execution rule

This bundle runs on the current tree. It does not wait for another issue. Coordination notes later in this file are overlap context, not prerequisites. If a change these criteria need is missing, make it here. If it is already present, keep it.

## Judgment

`runtime-ports` is still in the right place in the graph, and SKILL-377's contract cleanup landed as its criteria were worded. Two gaps remain: its subtask 3 asked to extend the companion-`NONE` census to ports, and that did not land (F-008). One constant default that predates SKILL-377 was missed (F-006). Keep the module, its edges, and its `UnitOfWork` plus repository shape.

The regression is ownership, and it arrived by two routes after the SKILL-377 baseline:

1. **Contracts that stopped being ports.** SKILL-376 moved goal-runner coordination from `runtime-infra/sqlite` into `runtime-engine`. The store interfaces and request/result models that coordination uses stayed in ports. Now 56 declarations in ports are implemented and consumed only by runtime-engine; core only binds them and CLI only renders four of them. The engine re-exports 36 ports types through public typealiases and pins the alias names as its inbound API. CLI imports `GoalRunnerRepairResult` through both names (F-001).
2. **Behaviour parked in ports behind guard carve-outs.** SKILL-372 planned to put the collapsed goal-parent and decomposition "shells" in engine or application. It put them in ports instead. To make that pass, it added a file exemption to `PortsDeclarationArchitectureTest`, a file exemption to the model-package rule, an FQN entry to the zero-tolerance raw-map scanner, and a row to the shrink-only ports package-cycle baseline (F-002). The same commit moved 12 forwarding validator extensions into ports instead of deleting them as planned (F-004). One port that existed only for the SQLite coordination now has no reason to be an interface (F-003).

Neither is a production incident. Both are the pattern the user flagged: a planned fix that moved code instead of landing. The fix removes layers. It moves engine-owned contracts into engine, puts repository-driving behaviour into application or engine, deletes 36 aliases, 12 forwarders, one interface and four guard carve-outs, and extends one existing scanner so the recurrence fails the build. It adds no module, framework, dependency bag, or architecture-test class, and it grows no baseline.

## Scope and method

- Baseline: `ae23f4f28f16d851a0548e8149e0fe6fadbbc612` (detached HEAD in the `skill-bill-SKILL-380-base` worktree, equal to `base/SKILL-380-phase-slot-strategies`), 2026-09-30. HEAD was unchanged at the final recheck.
- Method: Python and grep censuses written for this investigation (declaration parse, import census per module and source set, supertype-list implementer parse plus SAM constructors, declaration-level movability fixpoint, exact-package Tarjan SCC, interface-default body scan). I read in full every file a finding cites and every guard it cites or extends. No review work was delegated. No build or test ran.
- Context read: `../../../AGENTS.md`, `runtime-kotlin/ARCHITECTURE.md` (Design Principles, Gradle Modules, Package Ownership, Boundary Rules, Architecture Guardrails), `docs/code-principles.md`, `runtime-kotlin/runtime-ports/agent/history.md` (runtime-ports has no `decisions.md`), and the runtime-ports entries in `runtime-kotlin/agent/decisions.md`: SKILL-377 (2026-09-25, two entries), SKILL-372 (2026-09-24), SKILL-376 (2026-09-25 "Adapters hold no port-only coordination"), SKILL-358 (2026-09-18), SKILL-233 subtask 2 (2026-09-06 (a)- (c)), SKILL-231 subtask 3 (2026-09-03).
- Prior work on this module: SKILL-377 "runtime-ports-contract-cleanup" (landed `a89dea7d9`) is the most recent investigation; I read its investigation, spec, and subtasks in full. SKILL-358 precedes it. The SKILL-372 bundle (domain) and SKILL-378 bundle (engine, with its unscheduled follow-up) are read where they moved code into or out of ports.

## Census

### Sizes

| Source set | Files | Lines |
| --- | ---: | ---: |
| main | 251 | 7,516 |
| test | 7 | 474 |
| testFixtures | 42 | 1,703 |

SKILL-377 baseline was 254 main files and 7,351 lines. Since then 41 main files (1,210 lines) were added and 44 deleted (836 lines). Of the additions, 8 files (about 650 lines) are behaviour or engine-owned contracts that this bundle moves out.

Main declares 97 packages. Largest: `review/model` 17, `workflow/gitops/model` 13, `workflow/gitops` 12, `taskruntime` 10. No package is over its sibling limit.

Top-level declarations (553): 251 data classes, 131 interfaces, 37 fun interfaces, 25 sealed interfaces, 31 enums, 58 functions, 16 vals, 2 classes (`GoalParentProjectionWriter`, and the private `ReviewFinishedTelemetryPayloadContract`), 1 sealed class, 1 value class. Zero typealiases and zero objects (SKILL-377 AC 9 and SKILL-358 AC 1 hold). 522 public, 30 private, 1 internal.

### Gradle edges

| Configuration | Edges |
| --- | --- |
| main | `api(:runtime-contracts)`, `api(:runtime-domain)` |
| test | `junit.jupiter`, `kotlin.test` |
| testFixtures | none beyond main (`java-test-fixtures` plugin) |

The kotlinx-serialization dependency SKILL-377 flagged is gone. Ports test and testFixtures import nothing from application, engine, infrastructure, CLI, MCP, or DI (0 imports). Every test and testFixtures package exists in main (7 and 22 packages, 0 orphans).

### Symbol-level consumers

Distinct runtime-ports declarations imported per module:

| Module | main | test / testFixtures / repoTest |
| --- | ---: | ---: |
| runtime-engine | 246 | 206 |
| runtime-application | 184 | 183 |
| runtime-infra:skills | 128 | 29 |
| runtime-infra:workflow | 115 | 43 |
| runtime-core | 98 | 63 |
| runtime-infra:sqlite | 81 | 37 |
| runtime-cli | 64 | 96 |
| runtime-infra:launcher | 37 | 31 |
| runtime-infra:host | 28 | 13 |
| runtime-infra:contracts | 19 | 8 |
| runtime-infra:http | 12 | 10 |
| runtime-mcp | 3 | 7 |

Ownership candidates: declarations whose every importer outside ports is runtime-engine (plus core bindings, and CLI rendering of engine results), and that no declaration staying in ports references. Found by a fixpoint over declaration bodies: 58. Two stay (`GoalParentProjectionWriter` is handled by F-002; `isConfirmedDead` is a rule beside its ports model). That leaves the 56 in F-001. Importers of those 56: 107 engine files, 3 core, 2 CLI, 9 ports.

Declarations with no cross-module import: 20 of 523 named declarations. By name grep they are 8 role interfaces extended by an aggregate (kept, see What stays), `IdeStatusCandidate` (dead duplicate, F-001), `validateSharedEvidenceProjection` and `listFeatureTaskWorkflowsForParentDiscovery` (removed or made private by F-004/F-002), and in-file or inferred uses (`ReviewNativeAgentAssignment`, `ReviewEvidenceOwner`, `ReviewEvidenceSource`, `IdeStatusProblemDetails`, `mapToRecord`, `preserveArtifactTimestampText`, `DecompositionManifestFileCandidate`, `NativeAgentLinkOverrides`, `GeneratedArtifactFile`).

### Typealiases

Ports main declares none. The engine declares 36 public aliases whose targets are ports types:

- `runtime-engine/.../engine/goalrunner/model/GoalRunnerPersistenceModelAliases.kt:21-37`: 17, all over `skillbill.ports.goalrunner.persistence.model.*`, imported under `P*` names.
- `runtime-engine/.../engine/work/model/IdeStatusModels.kt:26-44`: 19 over `skillbill.ports.idestatus.model.*`, imported under `Port*` names, plus `const val IDE_STATUS_PAUSE_REASON_LABEL_MAX_LENGTH = PORT_IDE_STATUS_PAUSE_REASON_LABEL_MAX_LENGTH` (lines 46-47).

The same file also declares a `data class IdeStatusCandidate` that is byte-identical to ports' `IdeStatusCandidate` (`ports/idestatus/model/IdeStatusModels.kt:200`), which nothing imports.

### Interfaces

168 non-sealed interfaces (131 `interface`, 37 `fun interface`). By location of the production implementation:

| Production implementer | Count | Reading |
| --- | ---: | --- |
| infrastructure or runtime-core | 134 | Real outbound ports. |
| runtime-engine only | 14 | 10 are goal-runner stores and repair/hydration seams consumed only by engine (F-001). 4 are agent-run callbacks the launcher calls back (kept). |
| runtime-application only | 6 | `GoalRunnerSubtaskLauncher`, `TelemetrySettingsProvider`, `TelemetryLevelMutator`, `AgentRunActivityStampSink`, `ReviewStoredHunkBodyExtractor`: each is consumed by a second module (kept). `DecompositionManifestProjectionWriter`: consumed only by engine, no substitute (F-003). |
| application + engine, CLI + engine | 2 | Agent-run probe and output-sink callbacks (kept). |
| ports (via `UnitOfWork`) | 1 | `GoalRunnerPersistenceSession`, a narrower view with consumers in application and engine (kept). |
| no parsed class implementer | 11 | 8 role interfaces extended by an aggregate. 3 bound through a lambda, a provider, or a callback parameter (`AgentRunMcpStartupProbe`, `FeatureTaskRuntimeExecutionPlanValidator` at `di/featuretask/RuntimeFeatureTaskValidatorProvides.kt:17`, `FeatureTaskRuntimeSharedEvidenceDeriver`). |

Implemented interfaces with no test substitute: 20. Each has an infrastructure adapter, which alone justifies a hexagonal port, except `DecompositionManifestProjectionWriter` (F-003).

### Dependency bags and hand-built collaborators

Ports declares no `@Inject` class and imports no `me.tatarka.inject` (0 hits). The one hand-built collaborator on this seam is `WorkflowGoalRunnerManifestStore.kt:67` in engine, which constructs `GoalParentProjectionWriter(engine, decompositionManifestValidator)`. That is engine wiring; F-002 moves the class next to it and allows but does not require injecting it.

### Interface defaults

10 default bodies in ports main. Seven are derived from the same interface's members or are SAM conveniences. `ReviewAttributionPort.knownPackSkillNames` and `knownPlatformSlugs` are derived. `ReviewAttributionPort.composedLaunchPlan` (`ports/review/preparation/ReviewAttributionPort.kt:17`) returns a constant empty plan (F-006).

### Package cycles (exact-package Tarjan SCC over ports main)

| SCC | Edge that closes it | Reading |
| --- | --- | --- |
| `goalrunner`, `persistence`, `workflow.decomposition`, `workflow.decomposition.runtime.model` | `workflow/decomposition/DecompositionManifestProjectionFailurePersistence.kt` imports `persistence.UnitOfWork`; `GoalParentProjectionWriter` and the discovery files add the rest | Created by F-002. Simulated removal of the F-002 files dissolves it. |
| `taskruntime` ↔ `taskruntime.model` | `ValidatedFeatureTaskRuntimeExecutionPlan.read(…, validator)` | Smart constructor. Kept. |
| `review.evidence` ↔ `review.model` | `ReviewEvidenceBrokerBinding.bodyExtractor` | A binding that carries a strategy. Kept. |
| `process` ↔ `process.model` | `InstallerProcessRequest.deadlineSeconds` default | Default constant. Kept. |

The repo guard scans ports with the first-segment mutual-pair algorithm, so it sees only the first as `persistence|workflow`. It sees the other three as self-edges.

### Literals, raw maps, JSON

- Enum wire tokens are owned by `wireValue` on their enums. No restating `setOf`/`mapOf` literal was found in ports main.
- One public raw map in ports main: `GoalParentProjectionWriter.artifacts(existing: Map<String, Any?>): Map<String, Any?>` (`ports/goalrunner/GoalParentProjectionWriter.kt:21-24`). The raw-map scanner passes it only through the FQN allow-list entry at `RuntimeArchitectureTestSupport.kt:467` (F-002).
- JSON parse sites: `ports/workflow/model/WorkflowRecordMapping.kt:106-121`, the persisted row codec behind `toSnapshot`/`toRecord`, through the stdlib-typed `JsonCodec` facade (kept, see What stays).

## SKILL-377 landing check

| SKILL-377 criterion | State at baseline |
| --- | --- |
| 1 Flat `WorkflowGitOperations`, no getters, no extension on it | Landed (0 extensions, 0 getters). |
| 2 No NUL splits, typed git results | Landed (0 `\u0000` in engine/application main). |
| 3 No fake-serving defaults | Landed for the listed rows. `ReviewAttributionPort.composedLaunchPlan` predates SKILL-377 (`4a8d766e7`, 2026-08-06) and was missed (F-006). |
| 4 Family-keyed `WorkflowStateRepository` | Landed (0 `WorkflowFamily` receivers, no batch constant). |
| 5 One review-preparation value | Landed (`ReviewFactPorts` and the six ports: 0 references). |
| 6 Non-null `UnitOfWork`, no `unbindListener` | Landed. |
| 7 Closed `AgentRunTermination`, no `ByteArray` | Landed. |
| 8 Dead ports, adapters, and serialization dependency gone | Landed. |
| 9 No typealias, no `Any` port member | Landed for ports main. The 36 aliases now live in engine (F-001). The 12 validator forwarders take `Any` parameters; they are extensions, not members, so the criterion's wording let them pass (F-004). |
| 10 Live ports guards | Landed: both guards read files and fail on a missing root. The companion-`val` rule still scans only runtime-engine (`PortNullObjectAbsenceArchitectureTest.kt:16`), although subtask 3 asked to extend it to runtime-ports main (F-008). |
| 11 ARCHITECTURE.md and decisions | Landed. |
| 12 No throwing, identity-compared, or unreferenced `NONE` | Landed. The 7 remaining `NONE`s are agent-run default strategies, each with 2 to 7 main references. |
| 13 Typed decode error | Landed (`fromWireValue` returns null). |

SKILL-377 retention decisions stand, including the `GoalRunnerManifestStore` role split and `WorkflowGitOperationResult` for scalar results.

## Other prior work that landed differently

- **SKILL-372 subtask 2** (`spec_subtask_2_domain-owned-artifacts-and-shared-rules.md:24`): "Collapse the duplicate shells: `GoalParentProjectionWriter`, …, `DecompositionWorkflowRuntimeLookup(+ParentDiscovery)`, `DecompositionManifestProjectionFailurePersistence` … Keep one owner per shell in engine or application." Its task baseline listed "all 12 in `FeatureTaskRuntimeWireArtifactValidatorExtensions.kt`" as forwarding extensions to delete. Commit `3973aa265` renamed the engine, SQLite, and application copies into runtime-ports instead, and renamed the 12 forwarders from domain into ports. The same commit added the four carve-outs in F-002.
- **SKILL-378 follow-up** (`followup_goal-runner-step-classes-and-engine-surface.md:38-45`) planned to delete the 36 engine aliases and "update the pinned inbound API list to engine-declared types only". It was never a subtask of the SKILL-378 manifest and did not run.
- **SKILL-233 decision 2026-09-06 (b)** kept the ports copy of `LoadedDecompositionManifest` and `ValidatedDecompositionManifestYaml` "because `runtime-infra/sqlite` reads it". SQLite no longer imports either; only application does. F-002 supersedes (b) for those two DTOs, with that evidence.

## Principle assessment

| Principle | Assessment | Evidence |
| --- | --- | --- |
| Dependency rule / hexagonal | Holds at module level; violated by content | Edges are inward. 56 declarations are engine-private contracts (F-001). Repository-driving use-case code sits in the port layer (F-002). |
| Single ownership ("a declaration with a single owner lives in that owner's module") | Violated | 56 engine-only declarations; 7 application-only decomposition declarations; 3 adapter-only constants (F-001, F-002, F-007). |
| Interface segregation / Liskov | Mostly holds | Aggregates inherit role interfaces with no getters. One constant default stands in for an implementation (F-006). |
| YAGNI | Pass-through layers | 36 re-export aliases, 12 forwarders, one interface with neither a second implementation nor a substitute, a duplicate data class, a duplicate constant (F-001, F-003, F-004, F-007). |
| Enforcement honesty | Undermined | Four guard carve-outs admitted exactly the code the guards forbid (F-002). The declaration guard has no rule for repository-driving functions, so SKILL-233's cleanup regressed 18 days later (F-005). |
| Closed types, error model, ambient effects | Clean in ports | No ambient reads; 22 `throw`s, typed; 8 narrow catches. |

## Checklist

| # | Item | Answer |
| --- | --- | --- |
| 1 | Dependency direction | Clean at the module level. `api(contracts)` and `api(domain)` are justified: port signatures expose domain models and contract DTOs (for example `WorkflowStateRepository.get` returns `WorkflowStateSnapshot`, and `IdeStatusValidator.toWirePayload` returns `JsonPayloadContract`). Tests import nothing above ports. Content violates it: F-001 and F-002. |
| 2 | Inbound side | Not a ports concern: ports declares no inbound use-case interface. It does host the engine's inbound request/result models: `GoalRunnerRepairRequest/Result/Status` and `IdeStatusRequest/Result` are engine use-case inputs and outputs, and they reach CLI through ports names and engine aliases. The engine inbound API pin (`RuntimeEngineInboundApiTest.kt:152-154,173-176`) names aliases, not declarations (F-001). |
| 3 | Outbound side | Purpose-built. 0 SQL, HTTP, header, or stderr vocabulary in ports main (grep for `sqlite`, `SQLITE_BUSY`, URLs, `Content-Type`, `Authorization`, `"git `, `"gh `). The generic `DiffResolverPort.runProcess` is owned by SKILL-388 F-004. One adapter policy lives in ports: the installer output cap and truncation sentinel (`ports/process/InstallerProcessPort.kt:12-15`), read only by `runtime-infra/host` (F-007). |
| 4 | Domain richness | Parent-discovery ambiguity and stale-lineage rules (`WorkflowStateRepositoryParentDiscovery.kt:17-160`) and archived-manifest filtering (`DecompositionManifestDiscovery.kt:101-110`) are use-case policy that composes repository and file-store ports. They belong to application, not to the port layer or domain (domain may not import ports) (F-002). Duplicates: `IdeStatusCandidate` exists identically in ports and engine (F-001). `REVIEW_EVIDENCE_BATCH_SIZE = 32` exists in ports and launcher (F-007). |
| 5 | Composition | No second root, bag, or locator in ports. `GoalParentProjectionWriter` is hand-built inside `WorkflowGoalRunnerManifestStore.kt:67`. That is engine wiring, and F-002 permits injecting it after the move. `DecompositionManifestProjectionWriter`'s `@Provides` binding (`di/workflow/RuntimeWorkflowProvides.kt:13-16`) exists only to bind the interface F-003 deletes. |
| 6 | Entry-point leakage | Clean. The only CLI text in ports main is KDoc on `ReviewSnapshotGateway.kt:11` that names the operator command; it holds no behaviour. |
| 7 | Ambient effects | Clean. 0 `System.`, `Instant.now`, `Thread`, `currentTimeMillis`, `nanoTime`, `getenv`, or `ProcessHandle` in ports main. |
| 8 | State and transactions | Clean in ports: no mutable state. The moved persistence functions (`persistDecompositionManifestProjectionFailure`, `clearDecompositionManifestProjectionFailure`) run inside the caller's `UnitOfWork` transaction and keep doing so after F-002. The move does not change who opens the transaction. |
| 9 | Error model | 22 `throw`s, all typed. 8 narrow catches (`MalformedJsonTextError`, `DateTimeParseException`, `IllegalArgumentException` in the timestamp mapper, `NoSuchFileException`, `InvalidWorkflowStateSchemaError`). 5 `error(...)` calls, all in F-002 files (parent ambiguity, a missing parent manifest, an unreachable branch); they move unchanged, with messages byte-identical. No cancellation handling is needed. |
| 10 | Cohesion and ownership | F-001 (56 engine-owned), F-002 (7 application-owned decomposition declarations plus behaviour), F-007 (3 adapter-owned constants). |
| 11 | YAGNI | 36 aliases and a duplicate class (F-001), one interface with no substitute (F-003), 12 forwarders with `Any` parameters, 2 of them with no main caller (F-004), a constant default (F-006), a duplicate constant (F-007). |
| 12 | Naming and packages | No stutter and no orphan test packages. `featuretask`/`taskruntime` both spell the feature-task-runtime area (SKILL-377 retention). The `*Port`-suffixed engine interfaces after the move are discussed in What stays. |
| 13 | Guard validity | See the coverage matrix below. Every guard cited here resolves `runtime-kotlin/<module>/src/main/kotlin` through `moduleMainKotlinRoot` or `runtimeArchitectureSourceRoots` and fails on a missing or empty root (`kotlinFilesUnderWithArchitectureAsserts`, `RuntimeArchitectureTestSupport.kt:19-23`). `:runtime-core:repoTest` declares `**/src/**` as inputs (`runtime-core/build.gradle.kts:10-26`), so a cached green run cannot hide a source change. The guards are live but carve-outs defeat them (F-002). |

### Guard coverage matrix for runtime-ports main

| Rule | Guard | Scan root | Reads ports main | Carve-out on ports |
| --- | --- | --- | --- | --- |
| No top-level object / non-DTO class / `(this as` / throwing or constant default | `PortsDeclarationArchitectureTest` | `moduleMainKotlinRoot("runtime-ports")` (line 8) | yes | File exemption for `GoalParentProjectionWriter.kt` (line 128) |
| Public models in `model` packages | `RuntimeLayerBoundaryArchitectureTest` "public model declarations live in model packages" | `sourceFiles()` filtered on `../../../runtime-kotlin/runtime-ports` (lines 327-333) | yes | File exemption for `DecompositionManifestProjectionFailurePersistence.kt` (lines 336-341) |
| No public raw map | `RuntimeRawMapArchitectureTest` (lines 105-116) | `moduleMainKotlinRoot` | yes | FQN allow-list entry `skillbill.ports.goalrunner.GoalParentProjectionWriter.artifacts` (`RuntimeArchitectureTestSupport.kt:454-468`, consulted at :497) |
| No adapter imports | `RuntimeContractModuleImportRulesTest` (lines 50-51, prefixes :77-83) | `moduleMainKotlinRoot` | yes | none |
| No null-object substitutes | `PortNullObjectAbsenceArchitectureTest` case 1 | every module main root | yes | none |
| No test-only companion `NONE` | same test, case 3 | `COMPANION_VAL_MODULE = "runtime-kotlin/runtime-engine"` (line 16) | **no** | not scanned (F-008) |
| Package cycles | `ApplicationPackageAcyclicityArchitectureTest` via `moduleArchitectureScanCases` | ports main | yes | Baseline row `persistence|workflow` added by `3973aa265` (F-002) |
| Ambient clock / environment, inject defaults | module scan cases | ports main | yes | Baselines empty |

## Findings

Priority reflects enforcement loss, correctness risk, and change cost. None is a production incident.

### F-001. High. Engine-owned goal-runner and ide-status contracts live in runtime-ports, and the engine re-exports them through 36 aliases

Evidence:

- The fixpoint census finds 56 declarations whose only importers outside ports are runtime-engine, core bindings, and CLI rendering, and that no staying ports declaration references:
  - Store interfaces: `GoalRunnerManifestStore` and its five roles (`GoalRunnerManifestQueries`, `…ExecutionCommands`, `…ControlWrites`, `…StateWrites`, `…PurgeCommands`), `GoalRunnerWorkflowOutcomeStore` and its roles (`GoalRunnerTerminalOutcomeStore`, `GoalRunnerReviewOutcomeStore`, `GoalRunnerWorkflowOutcomeMutationStore`), `GoalRunnerAttemptLedgerStore`, `GoalRunnerWorkflowProgressStore`, `GoalRunnerWorkflowLedgerWriteStore`, `GoalRunnerChildRepairStore`, `GoalRunnerChildRepairRunnerPort`, `GoalChildPlanningHydratorPort`.
  - Their models: 13 in `goalrunner/runner/model` (`GoalRunnerManifestState`, `…CompletionPersistenceResult`, `…ScopedReplanWriteResult`, `…ScopedReplanOptions`, `…PausePersistenceResult`, `…LaunchAuthorization`, `…ReconcileGate`, `GoalRunnerWorkflowProgress`, `GoalProgressEventDraft`, `GoalAttemptLedgerEntryDraft`, `GoalRunnerProgressEventRecordRequest`, `GoalRunnerAttemptLedgerRecordRequest`, `GoalRunnerLedgerSequenceWatermarks`), plus `GoalRunnerChildExecutionPlanAdmission`, `GoalRunnerChildWorkflowSetup`, and `GoalChildPlanningHydrationRequest`. Every file under `goalrunner/persistence/model` (18 declarations), plus `GoalRunnerResetSubtaskSnapshot` and `GoalPlanningPreparationProgress`.
  - Ide-status: `IdeStatusRequest`, `IdeStatusResult`, `IdeStatusRepositoryResolution`, `IdeStatusSelectionTier`.
- Every production implementation is in engine: `WorkflowGoalRunnerManifestStore` (`engine/goalrunner/manifest`), `WorkflowGoalRunnerOutcomeStore` and `WorkflowGoalRunnerProgressRecording` (`engine/goalrunner/persist`), `WorkflowGoalRunnerChildRepairStore` and `GoalRunnerChildRepairOperations` (`engine/goalrunner/repair`), `GoalChildPlanningHydratorPortAdapter` (`engine/goalrunner/planning/hydration`). Core binds them in `di/goal/RuntimeGoalRunnerStoreProvides.kt:15-25` and `RuntimeGoalRunnerLaunchProvides.kt:16-19`. The test substitutes are in engine test and testFixtures, plus two ports testFixtures files used only by engine and core tests (`GoalRunnerManifestStoreDefaults`, `NoopGoalRunnerAttemptLedgerStore`).
- They were ports while the implementations lived in `runtime-infra/sqlite`. The 2026-09-25 decision "Adapters hold no port-only coordination" moved the implementations to engine and left the contracts behind. A contract that one module both implements and consumes is not a boundary.
- The engine re-exports 36 ports types through public typealiases (census above) and pins five alias names as inbound API (`RuntimeEngineInboundApiTest.kt:152-154,173-176`: `GoalRunnerRepairRequest/Result/Status`, `IdeStatusRequest/Result`, and also `IdeStatusSnapshot` and `IdeStatusProblemCode`).
- CLI main imports one type through both names: `cli/goal/core/GoalCliFormatting.kt:5` imports `skillbill.engine.goalrunner.model.GoalRunnerRepairResult`, while `cli/goal/core/GoalCliExitCodes.kt:15-16` imports `skillbill.ports.goalrunner.persistence.model.GoalRunnerRepairResult` and `GoalRunnerRepairStatus`. `GoalCliFormatting.kt:6-9` imports four more ports-named engine results (`GoalRunnerResetSubtaskSnapshot`, `GoalRunnerAppliedRepair`, `GoalRunnerChildWedgeDiagnosis`, `GoalRunnerWedgeFinding`), which the inbound-API guard cannot see because it checks only `skillbill.engine.*` references.
- `IdeStatusCandidate` in ports (`ports/idestatus/model/IdeStatusModels.kt:200-211`) is byte-identical to engine's `engine/work/model/IdeStatusModels.kt` copy and has 0 importers.
- 16 of the 19 ide-status aliases target the `IdeStatusSnapshot` tree, which must stay in ports: `IdeStatusValidator` (`ports/idestatus/IdeStatusValidator.kt`) takes it, and `runtime-infra/contracts` implements that port.

Fix:

- Move the 56 declarations into runtime-engine, and delete them from ports.
  - Declare each persistence and repair model under the name and package its alias uses today (`skillbill.engine.goalrunner.model`), and the four ide-status ones in `skillbill.engine.work.model`. Engine files that import the alias names then compile unchanged.
  - Put the store interfaces beside their implementations or in an existing engine goal-runner package. Placement must satisfy `PackageSiblingCountArchitectureTest` (12 files, 20 for `model`).
- Delete `GoalRunnerPersistenceModelAliases.kt` and the 19 aliases and the re-declared constant in `engine/work/model/IdeStatusModels.kt`. Engine code that used an alias for a type staying in ports imports the ports name directly. Delete ports' `IdeStatusCandidate`.
- Move `GoalRunnerManifestStoreDefaults` and `NoopGoalRunnerAttemptLedgerStore` from runtime-ports testFixtures to runtime-engine testFixtures, under the package of the interface they implement.
- CLI imports the engine names. Update `PINNED_ENGINE_INBOUND_API_TYPES`:
  - The three repair and two ide-status entries now name real declarations.
  - Add the four CLI-rendered repair results.
  - Remove `IdeStatusSnapshot` and `IdeStatusProblemCode`. After the aliases go they name no engine declaration, and no application, CLI, or MCP main file references them.
- Keep every interface. The aggregates have 4 to 8 test substitutes each, and the role split is a SKILL-377 and 2026-09-06 (a) retention.

Feasibility:
- The moved declarations import only contracts, domain, and ports types that stay, plus each other. Engine has `api` edges to all three.
- The fixpoint confirmed that no staying ports declaration references a moved one.
- runtime-core has `api(:runtime-engine)` (`runtime-core/build.gradle.kts:40`), and runtime-cli has `implementation(:runtime-engine)`. Core tests already use `testFixtures(project(":runtime-engine"))`.
- `RuntimeEngineBoundaryArchitectureTest` "new public top-level engine declarations stay within inbound api and model packages" (lines 67-97) flags only explicit `public` declarations outside model packages (`includeDefaultPublic = false`), so default-public moved interfaces pass. Models land in exempt model packages.
- The `RuntimeComponent` accessors `goalRunnerManifestStore` and `goalRunnerWorkflowOutcomeStore` (`di/core/RuntimeComponent.kt:183-184`) only change their import. SKILL-389 deletes them.

### F-002. High. SKILL-372 parked repository-driving behaviour in ports behind four guard carve-outs

Evidence (commit `3973aa265`, 2026-09-24):

| File moved into ports | From | Lines | Main importers now |
| --- | --- | ---: | --- |
| `ports/goalrunner/GoalParentProjectionWriter.kt` (non-DTO public class, public `Map<String, Any?>` member) | engine `goalrunner/manifest` | 58 | engine only (`WorkflowGoalRunnerManifestStore.kt:67`, `…ManifestLoader.kt:38,132`, `…ManifestProjectionPersistence.kt:36,84`, `reset/WorkflowGoalRunnerChildWorkflowPersistence.kt:49`) |
| `ports/workflow/decomposition/DecompositionManifestDiscovery.kt` (`loadDecompositionManifest`, `findMatchingDecompositionManifests`, `resolveDecompositionManifest`) | new; application and SQLite copies collapsed | 110 | application, engine |
| `ports/workflow/decomposition/WorkflowStateRepositoryParentDiscovery.kt` (5 public `WorkflowStateRepository` extensions with ambiguity and stale-lineage rules) | application `workflow/decomposition/DecompositionWorkflowRuntimeLookupParentDiscovery.kt` | 160 | application, engine |
| `ports/workflow/decomposition/DecompositionManifestProjectionFailurePersistence.kt` (a public enum outside `model`, and 2 functions taking `WorkflowEngine` and `UnitOfWork`) | SQLite `goalrunner/manifest` | 82 | application, engine |
| `ports/workflow/decomposition/runtime/model/*` (3 files, 7 DTOs) | application | 116 | application only |

The same commit opened four carve-outs so these pass:

1. `PortsDeclarationArchitectureTest.kt:128`: `if (!fileName.endsWith("skillbill/ports/goalrunner/GoalParentProjectionWriter.kt"))` skips the non-DTO-class rule for that file.
2. `RuntimeLayerBoundaryArchitectureTest.kt:336-341`: the model-package rule skips `…/ports/workflow/decomposition/DecompositionManifestProjectionFailurePersistence.kt`.
3. `RuntimeArchitectureTestSupport.kt:454-468`: the `rawMapBoundaryAccessors` FQN set includes `skillbill.ports.goalrunner.GoalParentProjectionWriter.artifacts`, and `:497` returns no violation for it. ARCHITECTURE.md Boundary Rule 11: "There is no curated FQN allow-list … `RuntimeRawMapArchitectureTest` fails on any new public raw-map surface in those modules."
4. `baselines/runtime-ports-package-cycle-baseline.txt` gained `persistence|workflow`. The only `workflow -> persistence` import in ports is the projection-failure file. ARCHITECTURE.md: "Module baselines recorded here are shrink-only ceilings."

The SKILL-372 spec (subtask 2 scope, line 24) said "Keep one owner per shell in engine or application; engine may call application." Decision 2026-09-06 (c), not superseded for this part: "Behaviour that is not a DTO extension leaves `runtime-ports` even when it is small." Decision 2026-09-25 (SKILL-376): "Coordination that merely composes ports moves to `runtime-engine`."

Fix:

- `GoalParentProjectionWriter` moves to `skillbill.engine.goalrunner.manifest`, where it lived before `3973aa265`. It becomes `internal`, because its callers and its one test (`engine/src/test/.../FeatureTaskExecutionPlanCreationTest.kt:536`) are in engine. Engine is not in the raw-map scan, and an internal member is not public in any case.
- The discovery, parent-discovery, and projection-failure functions move to runtime-application: `application/decomposition` and `application/workflow/decomposition`. Engine already imports application in 168 places, and engine→application is `implementation`.
  - Fold the projection-failure functions into the existing `application/decomposition/DecompositionManifestProjectionFailurePersistence.kt`, which already wraps them.
  - The enum and the seven DTOs go to `application/decomposition/model`.
  - `listFeatureTaskWorkflowsForParentDiscovery` has no caller outside its file and becomes private.
- Delete the four carve-outs, leaving the ports cycle baseline empty. The other eleven `rawMapBoundaryAccessors` entries target domain declarations, and the domain owner handles them (see Coordination).
- Record in `../../../runtime-kotlin/agent/decisions.md` that 2026-09-06 (b) is superseded for `LoadedDecompositionManifest` and `ValidatedDecompositionManifestYaml`.
- Function bodies, error messages, and transaction extents are unchanged.

Feasibility:
- Every moved function imports only contracts, domain, and ports types plus `java.nio.file.Path` and `java.nio.file.NoSuchFileException`. Application main already imports `NoSuchFileException` (`application/decomposition/DecompositionManifestRuntimeState.kt`, `DecompositionManifestFileWrites.kt`). The application guards ban `java.nio.file.Files` and filesystem calls, not `Path` values or exception types (ARCHITECTURE.md rule 12).
- Package counts after the move: `application/decomposition` goes from 11 to 12 files (limit 12; the discovery file is the one addition), `application/decomposition/model` from 3 to 7, and `application/workflow/decomposition` from 7 to 8.
- The raw-map scanner already passes these signatures in ports, where no suffix exemption applies. Application's scan is not stricter.
- No moved code imports engine, so no application→engine edge appears.
- A simulated removal of the five files dissolves the 4-package SCC. Only the three parent↔model pairs kept below remain.

### F-003. Medium. `DecompositionManifestProjectionWriter` is an interface with no boundary and no substitute

Evidence:

- `ports/decomposition/DecompositionManifestProjectionWriter.kt` has one implementation, application's `@Inject class DecompositionManifestWriter` (`application/decomposition/DecompositionManifestWriter.kt:31`).
- Its consumers are three engine classes (`WorkflowGoalRunnerManifestProjectionPersistence.kt:38`, `WorkflowGoalRunnerManifestStore.kt:60`, `WorkflowGoalRunnerChildRepairStore.kt:27`) and engine testFixtures.
- Every test passes the real writer (`ApplicationTestHarness.kt:33`, `testDecompositionManifestWriter = DecompositionManifestWriter()`). There are 0 substitutes.
- Two other classes that need the same collaborator already inject the concrete class: application `WorkflowService.kt:72` and engine `featuretask/prepare/FeatureSpecPreparationWriter.kt:34`.
- The interface existed so SQLite-resident coordination could call an application class. SKILL-376 moved that coordination to engine, which depends on application.

Fix: delete the interface and its `@Provides` binding (`di/workflow/RuntimeWorkflowProvides.kt:13-16`). The three engine classes take `DecompositionManifestWriter`.

Feasibility:
- `DecompositionManifestWriter` is `@Inject` with no constructor parameters, so kotlin-inject constructs it without a binding.
- `RuntimeCompositionGuardArchitectureTest` censuses concrete types from `@Provides` parameter types. Removing the provider shrinks that census, and no main site outside `skillbill.di` constructs the writer.
- The provider name appears in no guard inventory (grep of `runtime-core/src/repoTest`: 0).

### F-004. Medium. Twelve forwarding validator extensions with `Any` parameters moved into ports instead of being deleted

Evidence:

- `ports/taskruntime/FeatureTaskRuntimeWireArtifactValidatorExtensions.kt` (136 lines) declares 12 public extensions. Each has the form `fun FeatureTaskRuntimeWireArtifactValidator.validateX(payload: Any, sourceLabel: String) = validate(KIND, FeatureTaskRuntimeWorkflowArtifactMap.from(payload), sourceLabel)`.
- They have 29 main call sites, in engine plus one in application. `validatePlanningProjection` has only test callers, and `validateSharedEvidenceProjection` has none.
- SKILL-372's task baseline listed all 12 for deletion ("Callers of extensions → direct `validate(kind, typedCarrier, label)`"). `3973aa265` renamed the file from domain into ports.
- SKILL-377 AC 9 ("no `Any`-typed member in a port contract") passes only because these are extensions, not members.

Fix: delete the file. Each call site calls `validate(FeatureTaskRuntimeWireArtifactKind.X, FeatureTaskRuntimeWorkflowArtifactMap.from(payload), sourceLabel)`. The validator interface and its adapter do not change.

### F-005. Medium. The ports declaration guard cannot see repository-driving functions, so this regression recurred

Evidence:

- `PortsDeclarationArchitectureTest` bans top-level objects, non-DTO classes, `(this as` casts, and throwing or constant defaults. It has no rule for top-level functions.
- The SKILL-233 subtask 2 decision (2026-09-06) moved "24 non-interface behaviour files (1,541 lines)" out of ports. SKILL-358 kept that rule. `3973aa265` brought four repository-driving files back 18 days later, and no guard failed except the three it exempted.
- ARCHITECTURE.md Boundary Rule 4 names only objects, classes, casts, and defaults.

Fix: extend the existing declaration scan in `PortsDeclarationArchitectureTest`, adding no new class. Two rules:

- A top-level function in runtime-ports main must not take a parameter of type `UnitOfWork`, `GoalRunnerPersistenceSession`, `DatabaseSessionFactory`, `WorkflowEngine`, or a type whose simple name ends in `Repository` or `Store`.
- A top-level function must not have a receiver whose simple name ends in `Repository`.

Delete the file exemption at line 128. Add synthetic fixtures for a rejected parameter and a rejected receiver, and an accepted fixture for a derived extension over the receiver's own members (`TelemetryConfigStore.writeTelemetryLevel` is that shape). Extend ARCHITECTURE.md Boundary Rule 4 to name the rule.

Feasibility: after F-002 and F-004, the remaining top-level functions are these:
- `toPath` and `toFileLocation`.
- `WorkflowStateSnapshot.toRecord`, `toSnapshot`, and `mapToRecord`.
- `withBoundedLaneProgress`, `exitCode`, `reviewProcessOutcome`, `isConfirmedDead`, and `evidenceKey`.
- `parseFeatureTaskRuntimeWorkerLeaseInstant` and `toReviewFinishedTelemetryPayload`.
- `DecompositionManifestValidator.decodeManifest` and `encodeManifestWireMap`, whose receiver is not a `Repository`.
- `TelemetryConfigStore.writeTelemetryLevel`, whose receiver is a `Store` and whose parameters are strings.

None has a banned parameter or a `Repository` receiver, so the rule starts with an empty violation list.

### F-006. Low. `ReviewAttributionPort.composedLaunchPlan` is a constant default that stands in for an implementation

Evidence:

- `ports/review/preparation/ReviewAttributionPort.kt:17` defaults to `ReviewLaunchPlan(routedPackSlug, emptyList())`.
- The only production adapter, `FileSystemReviewAttribution.kt:21` (`runtime-infra/workflow`), overrides it, as do the three handwritten test fakes (`ReviewServicePreviewImportTest.kt:82` and `ApplicationPersistencePortTestSupport.kt:579,602`).
- Only the testFixtures `EmptyReviewAttributionPort` relies on the default.
- This is the SKILL-377 F-004 shape. The regex guard misses it because the constant is a constructor call with an empty-collection argument, which a regex cannot tell apart from derived defaults such as `GoalSubtaskPlanRepository.boundedStatus`.

Fix: make the member abstract, and have `EmptyReviewAttributionPort` return the empty plan. Move the KDoc's no-pack policy sentence to the adapter's decision record if it is still wanted, since KDoc stays on the interface member. No scanner change.

### F-007. Low. Adapter-only constants and a duplicate owner in ports

Evidence:

- `REVIEW_EVIDENCE_BATCH_SIZE = 32` is declared public in `ports/review/model/ReviewEvidenceSourceModels.kt:7` and `internal` in launcher `review/GovernedReviewEvidenceRequestParsing.kt:6`. The same launcher file imports the ports constant at line 4, which shadows its own declaration, and `GovernedReviewEvidenceCodecWireSchemas.kt:4` imports the ports one too. The launcher is the only reader.
- `INSTALLER_PROCESS_OUTPUT_CAP_BYTES` and `INSTALLER_OUTPUT_TRUNCATION_SENTINEL` (`ports/process/InstallerProcessPort.kt:12-15`) are read only by `runtime-infra/host` (`BoundedExternalProcessRunner.kt:249`, `BoundedExternalProcessOutput.kt:15`, and host tests). They are adapter policy.

Fix: the launcher's internal constant becomes the one owner, and the two imports go. Move the installer cap and sentinel into `runtime-infra/host` as `internal`. `DEFAULT_INSTALLER_PROCESS_DEADLINE_SECONDS` stays, because the port model `InstallerProcessRequest` uses it as a default. Wire bytes are unchanged (`maxItems` stays 32, and the sentinel text is identical).

### F-008. Low. The companion-`NONE` census still skips runtime-ports

Evidence: `PortNullObjectAbsenceArchitectureTest.kt:16` hard-codes `COMPANION_VAL_MODULE = "runtime-kotlin/runtime-engine"`, and case 3 scans only that root. SKILL-377 subtask 3 scope: "Extend the companion-`val` rule … to runtime-ports main as well." The seven ports `NONE`s each have 2 to 7 main references today, so the extended scan passes.

Fix: scan both roots. The constant becomes a list, and the census body is unchanged.

## Over-engineering register

| Item | Kind | Action |
| --- | --- | --- |
| 36 engine typealiases over ports types, plus a re-declared constant | Pass-through layer | Delete (F-001) |
| `IdeStatusCandidate` in ports | Duplicate | Delete (F-001) |
| `DecompositionManifestProjectionWriter` and its binding | Interface without a boundary or substitute | Delete (F-003) |
| 12 validator forwarders | Forwarding layer | Delete (F-004) |
| Four guard carve-outs (two file exemptions, one FQN entry, one baseline row) | Enforcement bypass | Delete (F-002) |
| `REVIEW_EVIDENCE_BATCH_SIZE` in ports | Duplicate owner | Delete (F-007) |
| `composedLaunchPlan` default | Fake-serving default | Make abstract (F-006) |

Estimate: about 450 production lines leave ports (the five F-002 files, the forwarders, the interface, the duplicate class and constant). About 320 lines move into engine, and about 360 into application. Engine loses about 60 alias lines. This is an estimate from file sizes, not a measured diff.

## What stays unchanged

| Item | Reason |
| --- | --- |
| Module, position, `api(contracts)` and `api(domain)` | Port signatures expose both. No new module. |
| `UnitOfWork` exposing repositories, and `DatabaseSessionFactory.read`/`transaction` | Standard unit-of-work shape (SKILL-377 retention). |
| Role splits: the `GoalRunnerManifestStore` roles, `GoalRunnerWorkflowOutcomeStore` roles, `SharedGoalPreplanRepository`/`GoalSubtaskPlanRepository`/`LegacyGoalPlanningPreparationRepository`, and `DecompositionManifestPersistencePort` | Decision 2026-09-06 (a) and SKILL-377: flattening needs a detekt `TooManyFunctions` suppression the gate forbids. The manifest and outcome roles move with F-001 and keep their split. |
| Goal-runner store interfaces as interfaces after the move | Test substitutes: `GoalRunnerManifestStore` 7, `GoalRunnerWorkflowOutcomeStore` 8, `GoalRunnerAttemptLedgerStore` 4, `GoalRunnerChildRepairStore` 3, the hydrator and repair runner 2 each. |
| `*Port` names on `GoalChildPlanningHydratorPort` and `GoalRunnerChildRepairRunnerPort` after they move | A rename is churn with no boundary gain. Considered and rejected. |
| `WorkflowStateRecord` and `toSnapshot`/`toRecord` in ports | Decision 2026-09-06 (b). Application, engine, and SQLite main all call the one mapper (26, 20, and 10 call sites). No inner site re-decodes JSON. A snapshot-only port would need a new typed row-metadata model (issue key, mode, implementation skill, state-entered timestamps) and would touch 46 inner call sites, with no defect to fix. Considered and deferred. |
| `GoalRunnerSubtaskLauncher`, `TelemetrySettingsProvider`, `TelemetryLevelMutator`, `ReviewStoredHunkBodyExtractor`, `AgentRunActivityStampSink`, and the agent-run callbacks | Implemented in application or engine but consumed by a second module (engine, CLI, launcher, workflow, or skills), each with test substitutes (up to 38). Moving `GoalRunnerSubtaskLauncher` into application would churn 38 substitutes for no boundary gain. |
| `GoalRunnerPersistenceSession` | A narrower `UnitOfWork` view with application and engine consumers (SKILL-377 retention). |
| The 7 agent-run `NONE` defaults | Real default strategies with main references (SKILL-358 and SKILL-377 retention). |
| `withBoundedLaneProgress`, `READ_ONLY_PHASE_PROGRESS_IDLE_TIMEOUT_MINUTES` | Decision 2026-09-27 (SKILL-380 subtask 6) places lane bounding in ports. Application and engine read it. |
| `decodeManifest`, `encodeManifestWireMap`, `writeTelemetryLevel`, `evidenceKey`, `isConfirmedDead`, `reviewProcessOutcome` | Derived from the receiver's own members plus a domain rule, with no second port and no adapter vocabulary. Most have consumers on both sides of the boundary (for example `decodeManifest` in `runtime-infra/contracts` and application testFixtures). |
| `parseFeatureTaskRuntimeWorkerLeaseInstant` and `FeatureTaskRuntimeWorkerOwnership` | Lease parsing beside its model with a typed error (SKILL-377 retention). |
| `ReviewFinishedTelemetryPayload` projection | SKILL-358 and SKILL-377 retention. Application and SQLite consume it. |
| Three parent↔model package pairs (`taskruntime`, `review.evidence`/`review.model`, `process`) | A smart constructor, a binding that carries a strategy, and a default constant. Splitting them would put public models outside `model` packages (rule 13). The ports cycle census keeps the first-segment algorithm. Switching it to exact-package SCC mode would force baseline rows for these pairs. |
| `DEFAULT_INSTALLER_PROCESS_DEADLINE_SECONDS`, `TELEMETRY_DELIVERY_ATTEMPT_BUDGET` | Port-model defaults. |
| `java.nio.file.Path` in port signatures, 1-file packages, the `featuretask`/`taskruntime` pair | SKILL-377 retention. Package granularity follows the sibling limits. |
| Considered and rejected | `explicitApi()` and blanket visibility narrowing (ports is a public contract module). Splitting by file size (`IdeStatusModels.kt` 270 lines). Enum-typing `WorkflowStateRecord` string fields (it ripples through the wire codec). Typing the `error(...)` ambiguity failures in the moved discovery code (a separate error-model decision; messages stay byte-identical). A new guard that pins ports ownership by consumer count (a census, not a rule, and it would need an exception list). |

## Coordination with concurrent bundles

Keys observed at write time: SKILL-387 and SKILL-388 (committed, pending), SKILL-389 (core), SKILL-392 (CLI), and SKILL-395 (MCP) on disk. SKILL-390 (engine), SKILL-391 (contracts), SKILL-396 (infra), and SKILL-397 (domain) were claimed in peer messages but not yet on disk.

| Bundle | Overlap | Owner | Sequencing |
| --- | --- | --- | --- |
| SKILL-389 runtime-core | Deletes the `goalRunnerManifestStore` and `goalRunnerWorkflowOutcomeStore` accessors whose types F-001 moves. It edits `RuntimeRawMapArchitectureTest.kt`, but not the `rawMapBoundaryAccessors` set in `RuntimeArchitectureTestSupport.kt`. | 389 owns the accessors; 393 owns the ports allow-list entry | Independent. If 393 lands first, the accessors import engine types until 389 deletes them. |
| SKILL-392 runtime-cli | Subtask 2 adds a java-command member to ports `HostPlatformPort`, edits `PINNED_ENGINE_INBOUND_API_TYPES`, and moves `IdeStatusReadSnapshotConcurrencyTest` (which imports the engine aliases `IdeStatusProblemCode`, `IdeStatusRequest`, and `IdeStatusResult`) into runtime-core. | 392 owns the port member and the test move; 393 owns the ide-status and repair pins | Whichever lands second keeps both pin edits and rewrites the moved test's imports: `IdeStatusProblemCode` from ports, `IdeStatusRequest` and `IdeStatusResult` from `skillbill.engine.work.model`. |
| SKILL-390 runtime-engine (claimed) | F-001 adds about 56 declarations and 2 testFixtures files to engine goal-runner and work packages, and F-002 returns `GoalParentProjectionWriter`. If 390 moves or renames goal-runner packages, the moved declarations follow them. | 393 | 393 subtask 1 does not wait. Whichever lands second places the moved declarations in the current engine packages under the sibling limits, and adds no alias. |
| SKILL-397 runtime-domain (claimed) | The other eleven `rawMapBoundaryAccessors` entries name domain declarations (`skillbill.workflow.*`, `skillbill.goalrunner.*`). They are the same kind of bypass. | 397, or whichever domain owner takes it | 393 deletes only the ports entry. |
| SKILL-388 runtime-application | F-002 adds one file to `application/decomposition` (11 to 12, at the limit) and four to its `model` package. SKILL-388 F-006 deletes helpers in `DecompositionManifestWriterPaths.kt` and narrows visibility in the same package. SKILL-388 owns `DiffResolverPort`. | Each owns its own files | Whichever lands second rechecks the `application/decomposition` file count. |
| SKILL-387 prose phase output | None found. It edits validator providers and planning, not ports signatures. | 387 | Independent. |
| SKILL-391 contracts, SKILL-395 MCP, SKILL-396 infra | F-007 moves two constants into `runtime-infra/host` and makes the launcher constant the owner. If SKILL-396 edits `BoundedExternalProcessRunner` or the launcher review codec, those are shared files. | 393 owns the constants | Whichever lands second keeps both edits. |

Global order: 393 does not block and is not blocked. Suggested order within this bundle: subtask 1 (mechanical move), then subtask 2 (behaviour, guards, and docs), so the extended guard goes live on a tree that no longer needs its exemption.

## Limits

- This is a static census. Consumer counts come from explicit imports plus a name grep for zero-import declarations. Inferred-type uses (a caller that never names a returned type) are invisible to an import census. The fixpoint only guarantees that no staying ports declaration references a moved one.
- Implementer detection parsed supertype lists and SAM constructors. It missed at least one multi-supertype adapter (`FeatureTaskRuntimeExecutionPlanSchemaValidator`), which I confirmed by hand. Every implementer a finding relies on was confirmed by a direct grep.
- Only compiling can confirm:
  - that kotlin-inject resolves `DecompositionManifestWriter` directly in the three engine classes;
  - that no inferred-type caller of a moved ports model exists outside engine;
  - that no engine file shadows a moved name;
  - that CLI's generated child component reads nothing from the moved types beyond the accessors SKILL-389 removes;
  - that the application package counts hold once SKILL-388 lands.
- No build, test, or architecture suite ran during preparation.
