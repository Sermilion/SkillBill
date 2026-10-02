# SKILL-401 Subtask 3 - engine-ports-contracts

Parent spec: [.feature-specs/SKILL-401-defect-exception-control-flow/spec.md](./spec.md)
Issue key: SKILL-401

## Scope

Remove the IAE/ISE control flow at the 6 runtime-engine sites, the runtime-ports site and the runtime-contracts site, including the `FeatureTaskRuntimeRunInvariantsSource` port result.

The engine sites are in `runtime-engine/.../engine/featuretask/`.

- **`lifecycle/execution/FeatureTaskRuntimeExecutionPlanDecode.kt:72`**: use `ValidationDepth.fromWireOrNull` and the other sources the census finds. Keep `"execution plan settings are invalid: <message>"`.
- **`definition.traversal(...)` callers.** Add a non-throwing form next to the existing extension, either `traversalOrViolation` or a nullable result plus its message. Callers:
  - `lifecycle/execution/FeatureTaskRuntimeExecutionPlanCompatibility.kt:85` goes to `incompatible()`.
  - `slot/PhaseStrategyLookup.kt:144` goes to `invalidComposition("definition … has incoherent traversal: <message>")`.
  - `lifecycle/execution/FeatureTaskRuntimeExecutionPlanCodec.kt:30` keeps its own handling.
  - The throwing `traversal` stays for callers handled only at the edge.
  - It is a shared skeleton helper with one generic path; no phase-specific branch (`runtime-kotlin/agent/decisions.md#01a41a7ed8b3`).
- **`lifecycle/execution/FeatureTaskRuntimeExecutionPlanCodec.kt:114`**: make the IAE sources of `decodeExecutionPlan` non-throwing, and throw `"execution plan cannot be reconstructed"` from the result.
- **`review/core/FeatureTaskRuntimeSharedReviewEvidenceResolver.kt:84`**
  - Add a non-throwing `ReviewDiffEvidence` parse in `runtime-application/.../application/reviewevidence/`, for example `parseOrRejection(diff)` returning the evidence or the `require` message, such as `"The authoritative review diff contains no attributable diff records."`.
  - `recordParseDegradation` must emit the same record text as before. If it took a `Throwable`, give it a message overload.
- **`phaserun/PhaseRunIntakeResolver.kt:79` and the `FeatureTaskRuntimeRunInvariantsSource` port**
  - Change `FeatureTaskRuntimeRunInvariantsSource.read(specPath)` (`runtime-ports/.../ports/taskruntime/`) to return a sealed `FeatureTaskRuntimeRunInvariantsRead { Read(invariants); Rejected(reason) }` in `ports/taskruntime/model`.
  - In `FileSystemFeatureTaskRuntimeRunInvariantsSource` (`runtime-infra/workflow`), its three path `require`s return `Rejected` with the same text.
  - `PhaseRunIntakeResolver` maps `Rejected` to `null`.
  - `goalrunner/planning/outcome/GoalPlanningSubtaskPlanProduction.kt:39` and `goalrunner/planning/context/GoalPlanningSharedPreplanProduction.kt:52` map `Rejected(reason)` to the stop reason they produce today. `invariantReadReason` takes the message, so the output is still `"…run-invariants could not be read: <reason>"`.
  - Their touched `runCatching` keeps catch-all behaviour for I/O through the cooperative rethrow (ground rule 7).
  - Update the test fakes `FakeInvariantsSource` and the `GoalRunnerTestFactory` source.
- **`runtime-ports/.../ports/workflow/model/WorkflowArtifactTimestampMapping.kt:68`**: use `parsePersistedInstantOrNull`, and keep `"Workflow artifact contains an invalid timestamp."`.
- **`runtime-contracts/.../contracts/JsonCodec.kt:111`**: drop the IAE arm. `parseToJsonElement` reports malformed text as `SerializationException`, which is already caught (kind C).

## Acceptance Criteria

1. No main source in runtime-engine, runtime-ports or runtime-contracts catches or `is`-checks `IllegalArgumentException` or `IllegalStateException`.
2. `FeatureTaskRuntimeRunInvariantsSource.read` returns `FeatureTaskRuntimeRunInvariantsRead`; `PhaseRunIntakeResolver` and both goal-planning producers branch on it with today's stop reasons; the test fakes are updated.
3. Execution-plan decode, compatibility and codec messages are unchanged.

## Non-Goals

runtime-application and runtime-infra sites other than `FileSystemFeatureTaskRuntimeRunInvariantsSource`.

## Test obligations

- `FeatureTaskRuntimeRunInvariantsRead.Rejected`: `PhaseRunIntakeResolver` returns null for an unreadable spec token.

## Shared Rules

Apply `spec.md` "Shared validators", "Ground rules", "Site classification", "Test rules" and "Execution Rule". If a shared validator this subtask needs is missing, add it as written there.

## Common Acceptance Criteria

- Every user-visible message and every persisted byte is unchanged. Existing tests pass with only exception-type assertion edits where a validator now returns a value.
- The exception type that reaches `CliRuntime.run` or `McpToolDispatcher.dispatch` for a given input is unchanged, or is a code on the MCP no-capture list.
- `TypedParseBoundaryArchitectureTest` and detekt pass. No `ParseBoundarySite` entry is dropped.
- Sites outside this subtask's scope are unchanged, except for callers of a port or validator this subtask changed.

## Validation Strategy

Goal gates: build, unit tests, detekt and the runtime-core repoTest suite. Add only the tests listed under Test obligations, and only where no existing test already drives the branch. Run nothing in implement; build, tests, detekt and repoTest belong to the build and validate phases.

## Next Path

skill-bill goal SKILL-401

## Spec Path

.feature-specs/SKILL-401-defect-exception-control-flow/spec_subtask_3_engine-ports-contracts.md
