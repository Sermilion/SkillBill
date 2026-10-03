# SKILL-390 - runtime-engine-architecture

## Mode

decomposed

## Intended Outcome

runtime-engine keeps its clean inward dependency direction and loses the in-module scaffolding that SKILL-378 meant to remove: collaborator bags (`*Boundaries`, `FeatureTaskRuntimePhaseGates`, `FeatureTaskRuntimeProbeWriters`), public collaborator getters read by receiver-extension functions, forwarding aliases, the restated issue-key canonicalisation, the ambient `System.nanoTime`, the two engine package-cycle baseline rows, the inert producer-side visibility rule, and 92 test files in packages main does not declare. Every change deletes a type, getter, forwarder, import or baseline row. The only additions are one non-validating issue-key function in `FeatureTaskExecutionIdentityPolicy`, one engine method in `InjectConstructorDefaultsArchitectureTest`, and one test for the planning-attempt duration.

Evidence, census tables, findings F-001 to F-009, the over-engineering register, what stays unchanged and coordination: [investigation.md](investigation.md). Baseline sha: `ae23f4f28f16d851a0548e8149e0fe6fadbbc612`.

## Subtasks

1. `spec_subtask_1`: goal-runner collaborators and engine repairs (F-001, F-004, F-005, F-006, F-007 recovery, F-008).
2. `spec_subtask_2`: feature-task run collaborators and the engine inject-property guard (F-002, F-003, F-007 aliases). Depends on subtask 1.
3. `spec_subtask_3`: engine test packages mirror main (F-009, mechanical). Depends on subtask 2.

Split reason: two independent collaborator graphs of 30 or more files each (goal-runner/goal-planning, and the feature-task run loop threaded through PhaseRunState), plus a 92-file mechanical test move. The engine inject-property guard lands in subtask 2 because it can pass only once both graphs are clean. The mechanical move stays out of the semantic diffs so each review stays readable.

## Acceptance Criteria

Each subtask spec holds the detailed, checkable criteria. The feature is done when all of them hold, which means:

1. runtime-engine main declares no `*Boundaries` type, no `FeatureTaskRuntimePhaseGates`, no `FeatureTaskRuntimeProbeWriters`, and no `typealias` other than aliases over `skillbill.ports.*` types while SKILL-393 subtask 1 is unlanded (subtask 1 AC 1, subtask 2 AC 1 and 7).
2. The goal-runner, goal-planning and feature-task run classes named in the subtask specs have at most 12 constructor parameters, all `private val` or plain, and no property returning a constructor collaborator; no runtime-engine main function takes DefaultGoalPlanningSweep, GoalRunnerFinalization, GoalRunnerStatusProjectionAssembler or FeatureTaskRuntimeRunner as receiver (subtask 1 AC 2-3, subtask 2 AC 2-5).
3. runtime-engine main has no inline `trim().uppercase()` issue-key canonicalisation and no `System.nanoTime`; an engine test proves the planning attempt `durationMs` comes from the injected `Clock` (subtask 1 AC 4-6).
4. `runtime-engine-package-cycle-baseline.txt` is empty, `DurableChildRecoveryClass.kt` is gone, and no featuretask main file imports `goalrunner` or `work` (subtask 1 AC 7-9).
5. RuntimeEngineBoundaryArchitectureTest no longer carries the inert default-public visibility method and its fixtures, and InjectConstructorDefaultsArchitectureTest enforces the inject-property rule over `../../../runtime-kotlin/runtime-engine/src/main/kotlin` with an empty baseline (subtask 1 AC 10, subtask 2 AC 6).
6. Every runtime-engine test package is declared by main unless it ends in `testsupport` or `testing`, and every engine test path in PrincipleEnforcementInventory exists (subtask 3 AC 1-3).
7. Persisted artifacts and CLI/MCP output are byte-identical to the baseline.

## Constraints

- No new module, framework, dependency bag, architecture-test class, detekt suppression, typealias or baseline row.
- No file added to a package that already holds 12 or more Kotlin files.
- Extend existing guards only where a criterion needs enforcement.
- Persisted bytes and CLI/MCP output stay byte-identical.
- This bundle runs on the current tree and waits for no other issue. Where SKILL-387, SKILL-388, SKILL-389, SKILL-392 or SKILL-393 has landed, rebase onto it and recount sites; where not, implement against the current tree under the second-lander rules in the subtask specs. The one cross-bundle edit this bundle may make, the two `IdeStatusCurrentPhaseExecution*` aliases, is defined in subtask 1.

## Non-Goals

- Items listed under "What stays unchanged" in investigation.md, including: inbound use-case interfaces, explicitApi, step interfaces or per-run DI subcomponents, file-size splitting, FeatureTaskRuntime* renames, and flipping the engine visibility rule to include default-public declarations.
- Typing engine `Map<String, Any?>` signatures before SKILL-387 lands.
- Unifying preflight add-on resolution across modules (needs a ports/infra signature change; recorded for SKILL-393 and SKILL-396).
- Moving ports declarations or deleting engine aliases over ports types (SKILL-393).

## Suggested landing order

Across the concurrent runtime architecture bundles, as of 2026-10-01:

1. SKILL-387 (prose phase output) and SKILL-388 (runtime-application): already launched, both blocked at audit on 2026-10-01. Unblock them first if possible.
2. SKILL-389 (runtime-core)
3. SKILL-393 (runtime-ports)
4. SKILL-395 (runtime-mcp)
5. SKILL-391 (runtime-contracts)
6. SKILL-392 (runtime-cli)
7. SKILL-396 (runtime-infra)
8. SKILL-397 (runtime-domain)
9. SKILL-390 (runtime-engine) **(this bundle)**

This order keeps rebases small. SKILL-390 goes last because it touches the most engine files. It is a preference, not a prerequisite: this bundle waits for no other issue and follows the second-lander rules in its Dependency Notes. Run the goals one at a time per checkout, because each one switches branches.

## Validation Strategy

Each subtask's build compiles runtime-domain, runtime-engine, runtime-core, runtime-cli and runtime-mcp, which proves that kotlin-inject resolves every unpacked constructor. Validate runs the full project check:
- the goal-runner, goal-planning, feature-task runner, run-loop, phaserun and slot suites;
- the slotbaseline capture suites, as evidence of byte-identical persisted artifacts;
- runtime-core repoTest, including package-cycle drift against the emptied engine baseline, RuntimeEngineBoundaryArchitectureTest, RuntimeEngineInboundApiTest, ambient-clock drift and InjectConstructorDefaultsArchitectureTest with the engine method;
- the runtime-cli and runtime-mcp parity tests;
- detekt and spotless.
