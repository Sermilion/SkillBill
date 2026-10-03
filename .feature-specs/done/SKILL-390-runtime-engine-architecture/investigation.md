# SKILL-390 runtime-engine architecture investigation

## Judgment
runtime-engine's dependency direction is clean. No main or test source in runtime-application, runtime-domain, runtime-ports, runtime-contracts or runtime-infra imports `skillbill.engine`. The three `api` edges (contracts, domain, ports) are the types engine signatures expose. The defects are inside the module:
- **Collaborator bags.** Collaborators are still grouped in `@Inject data class *Boundaries` bags. SKILL-238 introduced them when it collapsed interface/binding pairs (runtime-kotlin/agent/decisions.md, 2026-09-14). They now mainly keep constructors under detekt's `constructorThreshold` of 12.
- **Getter forwarding.** Several classes re-expose their collaborators as public getters so that extension functions in other files can read them.
- **Service locator.** `FeatureTaskRuntimePhaseGates` is carried through per-run state as a service locator.

SKILL-378 targeted exactly this (AC-4, AC-5, AC-10, AC-11), but its two follow-up specs never ran. Every fix below deletes a type, a getter, a forwarder, an import or a baseline row. The only additions are one domain function that replaces 20 inline copies, one guard method, and one test for a named bug.

## Method
- Baseline: `ae23f4f28f16d851a0548e8149e0fe6fadbbc612` ([SKILL-386] Runtime Cli Architecture #417).
- The planner ran the census by grep and find over runtime-kotlin. There was no delegated review and nothing was compiled.
- KSP output is absent from the tree, so generated kotlin-inject readers were not counted.

| Source set | Files | Lines | @Test |
|---|---|---|---|
| main | 507 | 65,532 | - |
| test | 252 | 67,469 | 1,502 |
| testFixtures | 19 | 1,391 | - |

## SKILL-378 landing
| AC | State | Evidence |
|---|---|---|
| 1 experiments deleted | landed | - |
| 2 ambient effects | partial | The engine ambient-clock baseline is empty. `AMBIENT_CLOCK_FORMS` (ArchitectureScanGuardSupport.kt:27-37) omits `System.nanoTime`, so GoalPlanningPhaseAttemptGateBurstCap.kt:51,61 slipped through (F-006). |
| 4 no bags or behaviour objects | not landed | F-001, F-002 |
| 5 engine inject-property rule | not landed | InjectConstructorDefaultsArchitectureTest.kt:46,56 cover only application and cli (F-003). |
| 7 durable sequence allocation | landed | - |
| 8 read-only phase query | landed | - |
| 9 typed silent reads | landed; residue not re-audited | 7 `runCatching{}.getOrNull()` remain. |
| 10 no cross-module aliases | not landed | 36 aliases over ports types (owned by SKILL-393) plus 3 engine-internal aliases (F-007). |
| 11 includeDefaultPublic | not landed and not feasible as written | F-008 |
| 12 raw-map guard over engine | not landed | RuntimeRawMapArchitectureTest.kt:105-115 scans application, domain and ports only; deferred. |

## Checklist
1. **Dependency direction.** Clean. Engine `api` depends on contracts, domain and ports, whose types appear in engine signatures. `implementation` depends on application and kotlin-inject. There are 0 inward imports of `skillbill.engine`.
2. **Inbound side.** core, cli and mcp main import 127 distinct engine symbols. None reads engine collaborator getters: a grep for 12 getter names over core, cli and mcp main returns 0 hits. SKILL-392 F-001 owns the CLI's run orchestration.
3. **Outbound side.** Driven access goes through ports, and no engine main file imports a JSON library. SKILL-393 F-001 owns engine-only ports declarations.
4. **Domain richness and duplicated rules.** Violation (F-005): the canonical issue key is restated at 20 sites. The add-on initial-resolution sequence is also copied at GoalPreflightGateBlockBuilder.kt:57-66 (deferred; see What stays unchanged).
5. **Composition.** runtime-core is the only root. Bags and locators remain inside the engine (F-001, F-002).
6. **Entry-point leakage.** There are 7 `skill-bill ...` command literals in 5 files. They are persisted goal recovery remedies (the runtime's own recovery contract), not CLI rendering, so they stay.
7. **Ambient effects.** F-006. The engine ambient-environment baseline has 1 row, not re-audited.
8. **State and transactions.** No `@Inject` class has a mutable class-body field. The 20 files with class-level `var` are per-run state or local variables. FeatureTaskRuntimeRunState stays the single per-run owner, and DatabaseSessionFactory/UnitOfWork are retained.
9. **Error model.** There are 0 `catch (e: Exception)` or `catch (e: Throwable)` blocks, and 127 `runCatching` calls in 68 files. The silent reads were typed under SKILL-378 AC-9.
10. **Cohesion and ownership.** 92 test files sit in packages that main does not declare (F-009).
11. **YAGNI.** Forwarders and aliases (F-007), the dead `validationGateRunner` getter, and two field names for one bound validator (F-002).
12. **Naming and packages.** No stutter. `goalrunner.model` holds 14 files; as a model package, it stays. Orphan test packages: F-009.
13. **Guard validity.**
    - RuntimeEngineInboundApiTest and RuntimeEngineBoundaryArchitectureTest read engine main through `kotlinFilesUnderWithArchitectureAsserts`. However, the default-public visibility method of the latter is inert (F-008).
    - The InjectConstructorDefaults property rule uses the non-asserting `kotlinFilesUnder`, so the new engine method must name a constant that resolves (subtask 2).
    - Package-cycle drift reads the 2-row engine baseline.
    - The ambient-clock drift runs over every declared module except the umbrella runtime-infra.

## Findings
**F-001 (P1). Goal-runner collaborator bags and receiver-extension locators.**
- Evidence:
  - `GoalRunnerRunBoundaries`, `GoalRunnerSubtaskLaunchBoundaries` and `GoalRunnerFinalizationBoundaries` (GoalRunnerBoundaries.kt:18,31,39).
  - `GoalPlanningSweepCheckpointBoundaries` and `GoalPlanningSweepLaunchBoundaries` (GoalPlanningSweepBoundaries.kt:22,32).
  - `GoalRunnerStatusProjectionDataSources` and `GoalRunnerStatusProjectionValidationDependencies` (GoalRunnerStatusProjectionAssembler.kt:58,67).
  - DefaultGoalPlanningSweep (GoalPlanningSweep.kt:37-58) unpacks two bags into 13 public getters and passes `this` to free functions. About 30 functions in 10 files under planning.context, planning.attempt, planning.outcome and planning.state take it as receiver or parameter.
  - GoalRunnerFinalization.kt:44-55 exposes 6 collaborators, read by 10 receiver functions in the same file.
  - GoalRunnerStatusProjectionAssembler.kt:73-88 exposes 9 public constructor vals plus 5 forwarders, read by 16 receiver functions in the same file.
- Fix: subtask 1.

**F-002 (P1). A feature-task gate locator is threaded through per-run state.**
- Evidence:
  - FeatureTaskRuntimePhaseGates exposes 17 getters over two bags (FeatureTaskRuntimePhaseGateBoundaries.kt:22,32).
  - PhaseRunState.kt:65 exposes it to every step. 33 main files reference it with 80 reads, 51 of them `gitOperations`.
  - No main file reads `validationGateRunner`.
  - `planningProjectionValidator` and `buildReceiptValidator` share the type FeatureTaskRuntimeWireArtifactValidator, which is bound once (RuntimeFeatureTaskValidatorProvides.kt:25).
  - FeatureTaskRuntimeRunner.kt:25-41 has 11 public constructor vals and 4 forwarders, read by about 15 receiver functions in 6 files. The runner itself is handed to FeatureTaskRuntimeRunLoopDurableState.kt:40, which reads recorder, phaseGates, strategies and clock from it.
  - Other exposed constructor properties: FeatureTaskRuntimeRunStartup (3), FeatureTaskRuntimeStatusService (2), PhaseRunEntry (7 internal), FeatureTaskRuntimeSpecGate (2), FeatureTaskRuntimeFindingVerificationBoundaryMemory (2).
  - FeatureTaskRuntimeProbeWriters is a 2-field bag.
  - FeatureTaskRuntimeRunLoopSharedArgs.kt:122,194 carry the gates.
- Fix: subtask 2.

**F-003 (P2). No engine inject-property rule.**
- Fix: subtask 2 adds a method to the existing test class.

**F-004 (P2). Engine package cycles are baselined.**
- Evidence: runtime-engine-package-cycle-baseline.txt holds `featuretask|goalrunner` and `featuretask|work`.
  - featuretask to goalrunner has 11 imports:
    - 6 import `goalrunner.status.completed`, which no featuretask file calls. The `completed` identifiers there are properties and companion members; ktlint keeps imports by name.
    - 4 import `protectedBranchName` (GoalRunnerTickProgressReader.kt:34).
    - 1 imports `GOAL_CHILD_REPAIR_EVIDENCE_ARTIFACT_KEY` (GoalRunnerChildRepairOperations.kt:18).
  - featuretask to work has 18 imports, all `IdeStatusCurrentPhaseExecution[Kind]` aliases. SKILL-393 subtask 1 replaces them with direct ports imports.
- Fix: subtask 1.

**F-005 (P2). The canonical issue key is restated at 20 sites.**
- Evidence: 12 `.trim().uppercase()` and 8 `?.trim()?.uppercase()` sites, in:
  - GoalRunnerSubtaskLaunchPrepare, GoalRunnerPurgeCoordinator (2), GoalRunnerResetReplanCoordinator, GoalPlanningStatusReasonCoherence, GoalPlanningSharedPreplanProduction (3) and GoalPlanningSweep;
  - IdeStatusRepositoryCorrelation (3) and IdeStatusLivenessAnchors;
  - FeatureTaskRuntimeRunner, FeatureTaskRuntimeExecutionEntry, FeatureTaskRuntimeCrashReconciler (2), FeatureTaskRuntimeExecutionAdmission and FeatureTaskContinuationLookupService (2).
- The form is owned only inside the validating `FeatureTaskExecutionIdentityPolicy.normalizeIssueKey` (FeatureTaskExecutionIdentityPolicy.kt:44). That function throws on malformed keys, so the sites cannot call it without a behaviour change.
- Fix: subtask 1 adds one non-validating canonical function in the same object.

**F-006 (P2). Ambient monotonic clock.**
- Evidence: GoalPlanningPhaseAttemptGateBurstCap.kt:51,61. The sweep already injects `Clock`.
- Fix: subtask 1.

**F-007 (P3). Forwarding layers.**
- goalrunner/persist/DurableChildRecoveryClass.kt is an internal alias plus 4 forwarders over `skillbill.engine.recovery`, a leaf package, with 6 importers. Fix: subtask 1.
- PhaseAttemptEnvironment.kt:235,375 declares two engine-internal aliases. Fix: subtask 2.

**F-008 (P2). Inert visibility guard.**
- Evidence:
  - RuntimeEngineBoundaryArchitectureTest.kt:65-97 passes `includeDefaultPublic = false`, so it flags only an explicit `public` keyword, of which there is none.
  - Its two fixtures (:99-137) test `includeDefaultPublic = true`, a mode the rule never uses.
  - Flipping the flag is not feasible: 641 default-public top-level declarations sit outside the model packages, and kotlin-inject's generated component in runtime-core must name every `@Inject` class and constructor type.
  - RuntimeEngineInboundApiTest already enforces the boundary on the consumer side.
- Fix: subtask 1 deletes the rule and its fixtures. This supersedes SKILL-378 AC-11.

**F-009 (P3). 92 orphan-package test files.**
- Evidence: `skillbill.engine` (70), `skillbill.engine.featuretask.slotbaseline` (17), `skillbill.engine.operation` (3), `skillbill.application` (1), `skillbill.engine.featuretask.lifecycle` (1). The SKILL-378 investigation said none existed.
- Rule: decisions.md 2026-09-25, a test package names a production package; that decision was scoped to infra.
- Fix: subtask 3.

## Over-engineering register
Each item is removed, none is added:
- the 10 bag types;
- the PhaseGates locator and its per-run exposure;
- 5 forwarding classes' public getters;
- receiver-extension families standing in for classes;
- DurableChildRecoveryClass forwarders;
- 3 engine-internal aliases;
- the duplicate validator field and the dead gate getter;
- the inert visibility rule and its two fixtures;
- 6 dead imports and the 2 cycle-baseline rows.

Step classes replace receiver-function families 1:1 with the same code and their own constructors. They are not new layers.

## What stays unchanged
| Item | Reason |
|---|---|
| Engine fun-interface seams with test substitutes; no step interfaces, framework or per-run DI subcomponents; no inbound use-case interfaces; no explicitApi; no FeatureTaskRuntime* rename; no file-size splitting | SKILL-378 retention decisions. |
| FeatureTaskRuntimeRunState as single per-run owner; FeatureTaskRuntimePhaseRecorder as a concrete facade; in-memory loop-count cache; `NONE` event sinks; DatabaseSessionFactory/UnitOfWork; launcher observer callbacks; untyped DecompositionSubtask.status | SKILL-378 retention decisions. |
| One generic PhaseRunner, slot grouping with no slot-strategy interface, and the PhaseRunState role members (records, goal, settlements, checkpoints, collaborators) | SKILL-380 and SKILL-384 designs. Only the `phaseGates` catch-all goes. |
| Nullable `phaseQuery` and `unaddressedFindingsLedgerService` constructor params | SKILL-238 decision 2. |
| About 70 internal `*Args` value classes | They carry per-run values and per-run emitters (DriveGoalLoopArgs and siblings) to stay under detekt's `functionThreshold` of 6. Only the two that carry the gates change (subtask 2). |
| Engine producer-side visibility rule | Deleted, not flipped (F-008); the consumer-side pin list stays the guard. |
| Public raw `Map<String, Any?>` in engine (about 60 declarations in 43 files) | Mostly persistence artifact codecs and phase-envelope reads. SKILL-387 rewrites envelope admission, so a typed pass before it lands would be redone. Follow-up after SKILL-387: census again and extend the inner-layer raw-map list to runtime-engine. |
| `System.nanoTime` in AMBIENT_CLOCK_FORMS | The forms are shared by every module scan case, and 7 runtime-infra main files use nanoTime legitimately at the process edge; adding it would need baseline rows. |
| Preflight add-on resolution (GoalPreflightGateBlockBuilder.kt:57-66) | The engine holds one copy, and SKILL-392 F-010 folds the CLI copies into one owner. A single cross-module owner needs `AgentAddonSelectionPort.resolveInitial` to read the configured external sources itself: a ports and infra-skills signature change with 7 AgentAddonSelectionResolverTest calls and the GoalPreflightServiceTest substitute. Recorded for the ports and infra owners (SKILL-393, SKILL-396). |
| contracts `normalizeIssueKey` (IssueKeys.kt:12, trim-only input validation) | A different rule under a similar name. Renaming it is the contracts owner's call (SKILL-391). |
| `skill-bill ...` recovery command literals | Persisted recovery contract; wire output must stay byte-identical. |
| `goalrunner.model` at 14 files | Model package; no guard. SKILL-392 uses a 20-file bound for engine model packages. |

## Coordination with concurrent bundles
| Bundle | Overlap | Resolution |
|---|---|---|
| SKILL-387 prose phase output | Rewrites `featuretask/slot` attempt code (PhaseOutputGate, PhaseAttemptOnce, PhaseAttemptEnvironment), GoalPlanningPhaseAttemptGate and GoalPlanningSubtaskPlanProduction, which hold gate reads and DefaultGoalPlanningSweep receiver functions. | Land after it; recount before implementing. |
| SKILL-388 application | DiffResolverPort purpose-built queries change the `phaseGates.diffResolver` caller. | Land after it. |
| SKILL-389 core | Edits ArchitectureScanSupport, RuntimeRawMapArchitectureTest and inventories. | Land after it; this bundle edits RuntimeEngineBoundaryArchitectureTest, InjectConstructorDefaultsArchitectureTest, PrincipleEnforcementInventory and the engine cycle baseline. |
| SKILL-392 cli | Subtask 2 adds the engine run entry, derives identity once (FeatureTaskRuntimeRunner:69, FeatureTaskRuntimeExecutionEntry:35) and prunes pins. Subtask 1 edits InjectConstructorDefaultsArchitectureTest and PrincipleEnforcementInventory. | Land after both. Its engine hand-offs are taken here: issue-key sites (F-005) and Runner/Startup vals (F-002). The add-on copy is deferred with the reason above. |
| SKILL-393 ports | Subtask 1 deletes all 36 engine aliases over ports types and moves 56 engine-only declarations into engine under the same names. | Preferred after it, not required. This bundle adds no alias. If 393 subtask 1 is unlanded, subtask 1 here deletes only the two `IdeStatusCurrentPhaseExecution*` aliases, which point at types 393 keeps in ports, and the featuretask/work row goes either way. |
| SKILL-397 domain | FeatureTaskExecutionIdentityPolicy lives in runtime-domain. | This bundle adds one function there; the second lander keeps it. |
| SKILL-391 contracts | F-007 moves 28 operation errors into engine `skillbill.engine.operation.core` (one new file) and rewrites imports in 12 engine/operation main files and 6 engine/operation tests. None of those files is edited by this bundle's subtasks 1 and 2. | Either order; subtask 3 moves the test files present, and whichever lands second keeps the new imports. |
| SKILL-395 mcp, SKILL-396 infra | No engine main edits. 396 subtask 2 rewrites `workflow.git.workflow` imports in 11 engine tests. | Whichever lands second rewrites the import lines in the files present. |

Preferred global order (each bundle runs on the current tree and waits for none): SKILL-388, SKILL-387, SKILL-389, SKILL-392, SKILL-393, then SKILL-390.

## Limits
- Nothing was compiled.
- Generated KSP readers were not counted.
- Receiver-function and raw-map counts come from line greps.
- The ambient-environment baseline row, the file-IO guard coverage and residual `runCatching` sites were not re-audited.
