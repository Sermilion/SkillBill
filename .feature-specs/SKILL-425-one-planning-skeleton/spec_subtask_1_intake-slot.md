# SKILL-425 Subtask 1 - Intake slot

Parent spec: [spec.md](./spec.md). Read its Model, Decisions and Execution rule first.
Issue key: SKILL-425

## Scope

Move intake into a slot. Add `PhaseSlot.INTAKE` as the first slot in canonical order with one step,
`intake`, and make the `plan` skeleton `[INTAKE, PREPLAN, PLAN]`. Fill the slot with a runtime
strategy that runs no agent, following the pattern of `RuntimeCommitStrategy`. It reuses the existing
intake code: spec resolution in `GoalIntakePreparation`, `FeatureSpecPreparationWriter.writeParentSpecOnly`,
and the prose rule from commit `9e5f26d30`.

The strategy writes or keeps the parent `spec.md` and nothing else:

- An operator spec with an acceptance list that `GovernedSpecSectionParser` can read is kept byte for
  byte. Its SHA-256 stays the protected spec hash, so the plan step may not change it.
- An operator spec without such a list becomes prose intake. The runtime writes a spec at the same
  path and quotes the original text in it.
- Free text or resolved tracker requirements seed a spec at `.feature-specs/<KEY>-<slug>/spec.md`.

The workflow must open before the spec exists. Derive the governed spec path from the intake before
writing any file, open the workflow with that path, and read run invariants after the intake step
completes. Today `FeatureTaskRuntimeRunEntry` reads them at open (review mode) and before the runner
starts.

Remove the pre-skeleton seed call in `StandalonePlanRun.open` (`intakePreparation.seedPlanSpec`).
Keep the plan seed artifact (`FEATURE_TASK_RUNTIME_PLAN_SEED`): the intake step records the spec
origin and hashes that `open` records today. Keep refusing a supplied spec that is not the governed
spec for the key.

Owned paths (elided segments are `...`):

- `runtime-domain/.../taskruntime/model/skeleton/PhaseSlot.kt`, `SkeletonDefinition.kt`
- `runtime-domain/.../taskruntime/model/core/FeatureTaskRuntimePhaseIds.kt`
- `runtime-engine/.../featuretask/slot/` (new intake strategy package), `slot/skeleton/SkeletonStrategyBindings.kt`
- `runtime-engine/.../featuretask/runner/FeatureTaskRuntimeRunEntry.kt`
- `runtime-engine/.../goalrunner/plan/StandalonePlanRun.kt`
- `runtime-engine/.../goalrunner/intake/GoalIntakePreparation.kt` (only the code the strategy reuses)
- `runtime-core/.../di/featuretask/RuntimeFeatureTaskSlotProvides.kt`

## Acceptance Criteria

1. `PhaseSlot.INTAKE` exists, is first in canonical order, and owns one step, `intake`.
2. `SkeletonDefinition.PLAN` is `[INTAKE, PREPLAN, PLAN]`, and `SkeletonStrategyBindings` binds the intake strategy to `INTAKE` for it.
3. The intake strategy runs no agent and writes or keeps only the parent `spec.md`. It never writes a manifest or a subtask spec.
4. An operator spec with a readable acceptance list is unchanged after intake, and a plan step that changes its bytes is refused as today.
5. An operator spec without a readable acceptance list is replaced by a runtime spec at the same path that contains the original text as a quote and an acceptance list the run-invariants reader accepts.
6. Free-text intake produces a seeded spec at `.feature-specs/<KEY>-<slug>/spec.md`, using today's slug rule.
7. A plan workflow opens before its spec exists, and run invariants are read only after the intake step completes.
8. `StandalonePlanRun` no longer calls `seedPlanSpec` before the workflow opens, and the plan seed artifact still records spec origin and hashes.
9. Resuming a plan workflow that stopped after intake does not rerun intake or change the spec.
10. Tests cover criteria 3 to 9, including a table-form acceptance section like SKILL-424's.

## Non-Goals

- No change to the full run's intake (`GoalIntakePreparation.prepare`). Subtask 2 owns it.
- No change to the `plan` slot's strategy or steps. Subtask 3 owns it.
- No tracker lookup in the runtime.

## Dependency Notes

None. This subtask runs on the current tree. If the `plan` slot already owns two steps, add `INTAKE`
in front of it unchanged. If the full run already runs the plan skeleton, intake applies there too
through the same skeleton.

## Validation Strategy

- The build phase runs the pack build command.
- The validate phase runs the pack validation gate and `./gradlew check`.
- Implement and audit do not run builds or tests.

## Next Path

Complete the goal and prepare its pull request.
