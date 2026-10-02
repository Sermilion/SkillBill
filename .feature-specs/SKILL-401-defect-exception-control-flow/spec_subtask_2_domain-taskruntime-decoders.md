# SKILL-401 Subtask 2 - domain-taskruntime-decoders

Parent spec: [.feature-specs/SKILL-401-defect-exception-control-flow/spec.md](./spec.md)
Issue key: SKILL-401

## Scope

Remove the IAE/ISE control flow at the 16 runtime-domain sites under `workflow/taskruntime/model/` (kinds A and D).

**Method for each site.** Replace the `try`/`catch (IllegalArgumentException)` with explicit checks before construction:

1. Call `violation(...)` and the `…OrNull` helpers.
2. On failure, throw the schema failure the catch used to throw, with byte-identical text (ground rule 2).
3. Then construct the value. Its `require` is now an invariant that no decoded input can break.

Where the body builds a value only from runtime-produced data (a kind-D site), drop the catch and keep the `require`. For example, check the `fromMeasurements`-style factory at `FeatureTaskRuntimeValidationGateExecutionEvidence.kt:87`.

Sites, all under `runtime-domain/.../`:

- `workflow/taskruntime/model/phase/FeatureTaskRuntimePhaseLedgerPersistenceModels.kt:170`. Includes `parsePersistedInstant` at `:153` and `FeatureTaskRuntimePhaseExecutionOrigin.fromWireValue`.
- `workflow/taskruntime/model/phase/FeatureTaskRuntimePhaseRecord.kt:218`. Includes `parsePersistedInstant` at `:183` and `:184`. Failures still go to `incompatiblePhaseRecord()`.
- `workflow/taskruntime/model/core/FeatureTaskRuntimeResolvedBranch.kt:70`
- `workflow/taskruntime/model/handoff/task/FeatureTaskRuntimeHandoffEnvelope.kt:62`
- `workflow/taskruntime/model/audit/FeatureTaskRuntimeQuarantineModels.kt:130`
- `workflow/taskruntime/model/persistence/FeatureTaskRuntimeRunInvariantsPersistence.kt:48`, `:93` and `:145`. `:145` uses `fromWireOrNull`. Its message `"... must be one of auto, inline, delegated."` is unchanged.
- `workflow/taskruntime/model/persistence/FeatureTaskRuntimeCheckpointIdentityModels.kt:154`
- `workflow/taskruntime/model/persistence/FeatureTaskRuntimeGoalContinuationArtifact.kt:145`, via `ValidationDepth.fromWireOrNull`.
- `workflow/taskruntime/model/validation/FeatureTaskRuntimeValidationGateProgressModels.kt:173`, `:211`
- `workflow/taskruntime/model/validation/FeatureTaskRuntimeReadinessEvidence.kt:181`
- `workflow/taskruntime/model/validation/FeatureTaskRuntimeValidationGateExecutionEvidence.kt:87`, `:117`, `:137`
- `workflow/taskruntime/model/validation/FeatureTaskRuntimeValidationEvidence.kt:117`

## Acceptance Criteria

1. No main source under `runtime-domain/.../workflow/taskruntime/` catches or `is`-checks `IllegalArgumentException` or `IllegalStateException`.
2. Each decoder throws the same schema failure, with byte-identical text, from an explicit validator check; phase-record failures still go to `incompatiblePhaseRecord()`.
3. Kind-D arms are dropped and their `require`s kept as invariants.

## Non-Goals

The `goalrunner/`, `review/` and `workflow/model/` decoders (subtask 1).

## Test obligations

None beyond the shared test rules: add an invalid-input test only for a validator whose invalid branch no existing decoder test drives.

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

.feature-specs/SKILL-401-defect-exception-control-flow/spec_subtask_2_domain-taskruntime-decoders.md
