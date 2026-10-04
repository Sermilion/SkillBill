# SKILL-390 Subtask 2 - feature-task-run-collaborators-and-engine-inject-guard

Parent spec: [.feature-specs/SKILL-390-runtime-engine-architecture/spec.md](spec.md)
Issue key: SKILL-390

## Scope

Covers F-002, F-003 and the engine-internal aliases of F-007 from the parent overview.

Touches runtime-engine main:
- featuretask.phase.core, runner, lifecycle.core, runloop.*, slot.state, slot.attempt, phaserun, prepare and review.finding;
- goalrunner.planning.state (GoalPlanningPhaseRunState).

Also touches runtime-core repoTest InjectConstructorDefaultsArchitectureTest and PrincipleEnforcementInventory, plus the engine test support that builds gates (FeatureTaskRuntimeRunnerTestSupport and the tests that pass `validationGateRunner`).

Changes:
- Delete FeatureTaskRuntimePhaseGates, both FeatureTaskRuntimePhaseGateBoundaries bags and the FeatureTaskRuntimeProbeWriters bag.
- Drop `phaseGates` from PhaseRunState and its implementations (durable, in-memory, goal planning), from the attempt environment and host types, and from the run-loop Args. Each function that read a gate takes the specific collaborator it uses; that is `WorkflowGitOperations` for 51 of the 80 reads.
- Keep one parameter for the wire-artifact validator where two names held the same bound instance, and drop the unread `validationGateRunner` from types that never call it.
- Stop passing FeatureTaskRuntimeRunner into FeatureTaskRuntimeRunLoopDurableState, which takes the collaborators it reads.
- Turn the runner's receiver functions into members, or into @Inject classes with their own collaborators. They live in FeatureTaskRuntimeRunnerExecute, FeatureTaskRuntimeRunnerExecutePrepared, FeatureTaskRuntimeRunnerLaunchOutcomes, FeatureTaskRuntimeAgentContextTelemetry and FeatureTaskRuntimeReviewFixBudget.
- Make the constructor properties of FeatureTaskRuntimeRunner, FeatureTaskRuntimeRunStartup, FeatureTaskRuntimeStatusService, PhaseRunEntry, FeatureTaskRuntimeSpecGate and FeatureTaskRuntimeFindingVerificationBoundaryMemory private, and remove their forwarding getters.
- Delete the PhaseAttemptRunLoopCollaborators and PhaseAttemptRunCollaborationScope aliases.
- Add a runtime-engine method to InjectConstructorDefaultsArchitectureTest.

## Acceptance Criteria

1. runtime-engine main declares none of FeatureTaskRuntimePhaseGates, FeatureTaskRuntimePhaseGateBranchBoundaries, FeatureTaskRuntimePhaseGateValidationBoundaries or FeatureTaskRuntimeProbeWriters, and no declaration in runtime-engine main or test has a property or parameter of those types.
2. PhaseRunState, FeatureTaskRuntimeRunLoopDurableState, InMemoryPhaseRunState, GoalPlanningPhaseRunState, the types in `featuretask/slot/attempt` and the data classes in FeatureTaskRuntimeRunLoopSharedArgs.kt declare no member that returns an object whose purpose is to hand out other collaborators. Every former gate read goes through a parameter or property typed as the collaborator itself.
3. No runtime-engine main type carries two parameters or properties of type FeatureTaskRuntimeWireArtifactValidator. `ValidationGateRunner` appears only in types that call it.
4. FeatureTaskRuntimeRunLoopDurableState has no parameter of type FeatureTaskRuntimeRunner, and no function in runtime-engine main declares FeatureTaskRuntimeRunner as its receiver.
5. FeatureTaskRuntimeRunner, FeatureTaskRuntimeRunStartup, FeatureTaskRuntimeStatusService, PhaseRunEntry, FeatureTaskRuntimeSpecGate, FeatureTaskRuntimeFindingVerificationBoundaryMemory and every @Inject class this subtask adds each have at most 12 constructor parameters. Each parameter is `private val` or a plain parameter, and none of these classes declares a property that returns one of its constructor collaborators.
6. InjectConstructorDefaultsArchitectureTest declares a runtime-engine method that calls `injectConstructorPropertyViolations` with an empty baseline. Its scan root is a PrincipleEnforcementInventory constant whose value is `../../../runtime-kotlin/runtime-engine/src/main/kotlin`, and it sits beside the existing application and cli methods.
7. runtime-engine main declares no `typealias`, except, while SKILL-393 subtask 1 is unlanded, aliases whose target is a `skillbill.ports.*` type. SKILL-393 deletes those, and this subtask does not wait for it.
8. The subtask adds no architecture-test class, baseline row, detekt suppression or module, and it adds no file to a package that already holds 12 or more Kotlin files.

## Non-Goals

- Replacing the PhaseRunState role members (records, goal, settlements, checkpoints, collaborators), the generic PhaseRunner, or the slot grouping.
- Adding step interfaces, a run-loop framework or per-run DI subcomponents.
- Changing the ~70 value-only `*Args` classes, other than removing the gate-typed fields from the two that carry them.
- Typing raw maps in phase-envelope reads (after SKILL-387).
- Changing persisted bytes, CLI or MCP output, or FeatureTaskRuntime* names.

## Dependency Notes

Depends on: 1
Depends on subtask 1, which leaves the goal-planning run state and shared test factories in the shape this subtask edits. This subtask waits for no other issue. SKILL-387 rewrites PhaseOutputGate, PhaseAttemptOnce, PhaseAttemptEnvironment and the slot strategies. If it has landed, rebase onto it and recount the gate reads; otherwise implement against the current tree, and whichever lands second keeps both edits.

SKILL-392 subtask 2 adds the engine run entry that injects FeatureTaskRuntimeRunner. Its constructor is already required to be private (its AC 9), so the new guard passes. SKILL-392 subtask 1 edits InjectConstructorDefaultsArchitectureTest and PrincipleEnforcementInventory; add the engine method and constant beside its cli entries.

## Validation Strategy

Build compiles runtime-engine, runtime-core, runtime-cli and runtime-mcp, which proves kotlin-inject resolution after the gates and bags are gone and FeatureTaskRuntimeRunner's collaborators are unpacked. Validate runs the full check:
- the FeatureTaskRuntime runner, run-loop, phaserun, slot and slotbaseline suites (byte-identical persisted artifacts);
- the goal-planning suites that exercise GoalPlanningPhaseRunState;
- the pack-gate and validation-gate dispatch tests;
- runtime-core repoTest, including InjectConstructorDefaultsArchitectureTest with the new engine method and RuntimeEngineInboundApiTest;
- the runtime-cli feature-task suites and the runtime-mcp parity tests;
- detekt and spotless.

## Next Path

skill-bill goal SKILL-390

## Spec Path

.feature-specs/SKILL-390-runtime-engine-architecture/spec_subtask_2_feature-task-run-collaborators-and-engine-inject-guard.md

## Implementation Details

This plan uses only the upstream preplan digest. The existing title, scope, acceptance criteria, dependency notes, validation strategy and next path remain unchanged. AC references below refer to the numbered acceptance criteria in this file. `E` means `../../../runtime-kotlin/runtime-engine/src/main/kotlin/skillbill/engine`, `T` means `runtime-kotlin/runtime-engine/src/test/kotlin/skillbill/engine/`, and `A` means `runtime-kotlin/runtime-core/src/repoTest/kotlin/skillbill/architecture/`.

Use the digest's current inventory when implementing. The locator has 16 fields and 77 direct reads across 22 main files, including 51 git-operation reads. The scope's older 80-read figure is retained as supplied text, not used as the implementation census. The duplicate planning-projection validator field and all ports-target aliases are already gone. Subtask 1 owns the recovery alias; this subtask owns the two attempt aliases. Assume subtask 1's declared dependency has delivered its goal-planning and factory changes. Implement confirms the resulting signatures without executing or repairing dependency work as a separate task. No user decision remains open.

1. Replace locator-bearing state and attempt contracts with direct collaborator types. Serves AC-001, AC-002, AC-003 and AC-007.

   Edit `Efeaturetask/slot/state/PhaseRunState.kt`, `Efeaturetask/phaserun/InMemoryPhaseRunState.kt`, `Egoalrunner/planning/state/GoalPlanningPhaseRunState.kt` and its delegated planning unit state, `Efeaturetask/slot/attempt/PhaseAttemptEnvironment.kt`, and `Efeaturetask/runloop/core/FeatureTaskRuntimeRunLoopSharedArgs.kt`. Remove `phaseGates`. Preserve the records, goal, settlements, checkpoints, collaborators and step-binding roles. Give `RepositoryCheckpointResolutionArgs` and `UnownedWorktreeCommitShaArgs` a `WorkflowGitOperations` collaborator instead of the locator.

   Keep `PhaseAttemptEnvironment` limited to request facts. Replace locator members in `PhaseOutputSettlementContext`, `PhaseCheckpointRemediationContext`, `PhaseAttemptLaunchRuntimeContext`, `PhaseQualityGateCycleContext`, `PhaseRuntimeFinalizationContext` and `PhaseAttemptTraversalRuntimeContext` with the specific collaborator each context uses. Remove the settlement scope's locator access through `attemptRunHost()` without exposing the host or unrelated authority. Delete `PhaseAttemptRunLoopCollaborators` and `PhaseAttemptRunCollaborationScope`; reference `PhaseRunLoopAttemptCollaborators` and `PhaseAttemptRemediationCollaborationScope` directly.

   Preserve accepted-step authority through existing runtime contexts. Ordinary strategies must not receive git writers directly or recover authority through constructors, conversions, state properties or aliases. Retain the existing `StrategyCapabilityBoundaryArchitectureTest` synthetic acceptance and rejection cases. The realistic bug these cases catch is a strategy reaching unauthorized mutation through a transitive collaborator path after a superficially narrow signature change. No new test is needed for the alias substitutions themselves.

2. Move runner execution and preparation into existing behaviour owners or private members. Serves AC-001, AC-003, AC-004 and AC-005.

   Refactor `Efeaturetask/runner/FeatureTaskRuntimeRunner.kt` and its `FeatureTaskRuntimeRunnerExecute.kt`, `FeatureTaskRuntimeRunnerExecutePrepared.kt` and `FeatureTaskRuntimeRunnerLaunchOutcomes.kt` families, plus `Efeaturetask/lifecycle/core/FeatureTaskRuntimeAgentContextTelemetry.kt` and `Efeaturetask/review/core/FeatureTaskRuntimeReviewFixBudget.kt`. Convert every runner receiver function into a member or an injected behaviour owner in an existing file. Each owner injects only the dependencies its operations call. The runner coordinates those operations and no longer exposes its ten collaborators or four forwarding getters. Move hand-built preparation into the appropriate existing owner rather than unpacking the whole gate into the runner.

   Keep each named and newly injected class at 12 constructor parameters or fewer, using only `private val` or plain parameters. Do not create a factory, facade or argument object whose purpose is to distribute collaborators. Preserve `FeatureTaskRuntimeRunEntry`, worker coordination and execution-entry identity derivation. Retain the single existing validator binding in `RuntimeFeatureTaskValidatorProvides.kt`. Use one `FeatureTaskRuntimeWireArtifactValidator` field per owner and retain `ValidationGateRunner` only where an operation invokes it.

   Preserve existing runner, measured-facts, agent-context telemetry and review-fix-budget outcome assertions. Their realistic risks are changed launch outcomes, lost telemetry facts and altered retry budgets. Constructor rewrites alone do not justify new tests.

3. Assemble durable run ownership without the runner parameter. Serves AC-001, AC-002, AC-003, AC-004 and AC-005.

   Refactor `Efeaturetask/runloop/durable/FeatureTaskRuntimeRunLoopDurableState.kt`. Replace its runner dependency with the actual recorder, terminal recorder, goal-continuation recorder, settlement service, git operations, clock, diagnostics, strategies, branch setup and probe-writing collaborators required by the existing operations. Assemble the existing durable record, goal, settlement and checkpoint owners through their actual dependencies. Keep concrete run state as the per-run owner. Route probe-writing operations to their actual consumers before deleting the probe-writer bag. Do not replace the runner with a dependency-access object or make mutable run state an injectable singleton.

   Preserve admission before parent mutation, acknowledged required phase writes before execution, coupled transitions under one run and session owner, and the prohibition on replaying finalization to repair uncertain receipts. Constructor design must satisfy detekt as well as the injected-class parameter limit. The digest does not enumerate every final owner signature, so implement confirms those signatures against actual reads and reuses existing ownership boundaries instead of inventing another grouping.

   Retain `Tfeaturetask/phaserun/RequiredPhasePersistenceTest.kt` and existing durable recovery and slotbaseline assertions. They catch execution before durable acknowledgement, lost resume state and duplicate finalization side effects. Add a boundary regression only if an uncovered sequencing branch changes, naming that concrete failure before adding the test.

4. Rewire every remaining gate consumer and delete the bags. Serves AC-001, AC-002, AC-003, AC-007 and AC-008.

   Update the digest's gate-reading families in phaserun entry; runner execution and launch outcomes; slot attempt output gate, environment and gate effects; and runloop hook views, checkpoint, checkpoint remediation, upstream-head recovery, subtask commit, durable state, commit cycle, repair receipt, output verification, decomposition stop, agent validation cycle, pack build cycle, validation scope, step bindings and transition owner. Pass collaborators through the narrowed runtime contexts established above. Preserve existing git authority boundaries.

   Replace the non-git reads with their existing decomposition planner, decomposition terminal recorder, lifecycle telemetry, spec gate, finding-verification boundary memory, spec-intent projection resolver, branch setup, readiness coordinator, shared-evidence resolver, diff resolver, validation coordinator, build coordinator, validation resolver and build-receipt validator types. Remove the unread locator `validationGateRunner` and do not restore the already removed duplicate validator. Delete `Efeaturetask/phase/core/FeatureTaskRuntimePhaseGates.kt`, `Efeaturetask/phase/core/FeatureTaskRuntimePhaseGateBoundaries.kt` and `Efeaturetask/lifecycle/core/FeatureTaskRuntimeProbeWriters.kt` once consumers use direct types.

   Confirm the AC-007 end state after deleting the two attempt aliases. Do not recreate aliases, dependency bags or forwarded accessors under new names. Preserve pack-build and validation-gate dispatch outcomes through their existing tests. The realistic bug is dispatching a gate to the wrong coordinator or dropping receipt validation during rewiring.

5. Close the remaining injected collaborator exposure. Serves AC-002 and AC-005.

   Make constructor collaborators private in `Efeaturetask/runner/FeatureTaskRuntimeRunStartup.kt`, `FeatureTaskRuntimeStatusService.kt`, `Efeaturetask/phaserun/PhaseRunEntry.kt`, `Efeaturetask/prepare/FeatureTaskRuntimeSpecGate.kt` and `Efeaturetask/review/finding/FeatureTaskRuntimeFindingVerificationBoundaryMemory.kt`. Replace startup reads of its crash reconciler, execution entry and invariants store with owned operations or direct injection into the actual consumer. Remove class-body properties that return constructor collaborators. Keep the already-private execution entry unchanged unless consumer wiring requires a signature adjustment.

   Check the runner and all new injected owners against the same private-property and 12-parameter requirements. Guard scans do not detect forwarding getters or receiver locators, so later audit and review must inspect those explicitly. Preserve existing startup, status, spec-admission and finding-verification tests. No new tests should merely assert constructor layout or repeat scanner implementation.

6. Extend the existing inject-property guard without exemptions. Serves AC-005, AC-006 and AC-008.

   Add `RUNTIME_ENGINE_MAIN` to `APrincipleEnforcementInventory.kt` with value `../../../runtime-kotlin/runtime-engine/src/main/kotlin`. Add a runtime-engine property method beside the application and CLI methods in `AInjectConstructorDefaultsArchitectureTest.kt`. It calls `ArchitectureScanSupport.injectConstructorPropertyViolations(baseline = emptySet(), scanRoot = PrincipleEnforcementInventory.RUNTIME_ENGINE_MAIN)` and follows the existing outcome assertion pattern.

   The realistic bug this guard catches is an injected engine class gaining an exposed constructor collaborator, including an internal property, while other module scans pass. Preserve the existing synthetic rejection fixture and scanner in `AArchitectureScanGuardSupport.kt`; plain parameters remain valid. The digest establishes that repoTest inputs already cover `**/src/**`, so no build-file change is planned. Add no architecture-test class, baseline row or scanner exemption.

7. Adapt test construction and prepare evidence for later phases. Serves AC-001 through AC-008.

   Replace bag construction in `TFeatureTaskRuntimeRunnerTestSupport.kt`, `FeatureTaskRuntimePackGateDispatchTest.kt`, `FeatureTaskRuntimeMeasuredFactsTest.kt`, `FeatureTaskRuntimeValidationGateDispatchTest.kt`, `Tfeaturetask/slotbaseline/SlotBaselineFullRunCapture.kt`, `Tfeaturetask/phaserun/RequiredPhasePersistenceTest.kt`, `PhaseValidationRunTest.kt`, `Tfeaturetask/slot/codereview/DelegatedReviewRunLoopTest.kt` and `Tfeaturetask/slot/pullrequest/PrDescriptionRunSupport.kt`, including their affected callers and existing shared factories. Preserve test bodies' observable boundaries while adapting constructors. Keep `RuntimeGoalPlanningSweepProvides.kt` binding the concrete sweep to `GoalPlanningSweep` and do not create another validator binding. Leave the mechanical package moves to subtask 3.

   Preserve `../../../runtime-kotlin/runtime-engine/src/test/resources/featuretask/slotbaseline` and its golden bytes. Do not regenerate captures to accept a behavioural difference. Later validation runs the existing feature-task runner, runloop, phaserun, slot, goal-planning, pack-gate and validation-gate dispatch suites, slotbaseline captures, core repoTest including the inject guard, strategy-capability guard and inbound API guard, CLI/MCP parity, detekt and Spotless. Persisted bytes, CLI output and MCP output must remain identical.

   Before any new behavioural test, identify a wrong observable result that existing coverage misses and tie it to the affected AC. The planned new test obligation is only the required engine scan method in step 6. Existing regression and validator-backed coverage remains mandatory. Buildability proof belongs to the later build phase, and all test execution and full project checks belong to validate. This plan phase runs none of them.

Across all steps, apply A1 and A2 for ownership, A3 for injected collaborators, A5 and P3 for deleting bags and forwarders, A9 and A10 for package structure, and G1 through G7 for effective guards. Later review applies the architecture-guidelines section 5 checklist. Prefer editing or deleting existing files. Do not add a file to a package already holding 12 Kotlin files, especially slot, slot attempt, slot state, runloop core and runloop state. Add no module, suppression, baseline row, framework or per-run DI subcomponent. Use owning wire-key constants and imports rather than inline wire literals or fully qualified references. Add no Kotlin line or block comments; KDoc remains limited to interfaces and their members. No schema migration, feature flag, install refresh or release ceremony is needed.
