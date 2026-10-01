# SKILL-390 Subtask 2 - feature-task-run-collaborators-and-engine-inject-guard

Parent spec: [.feature-specs/SKILL-390-runtime-engine-architecture/spec.md](./spec.md)
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
6. InjectConstructorDefaultsArchitectureTest declares a runtime-engine method that calls `injectConstructorPropertyViolations` with an empty baseline. Its scan root is a PrincipleEnforcementInventory constant whose value is `runtime-kotlin/runtime-engine/src/main/kotlin`, and it sits beside the existing application and cli methods.
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
