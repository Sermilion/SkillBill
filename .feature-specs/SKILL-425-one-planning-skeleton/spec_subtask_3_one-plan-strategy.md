# SKILL-425 Subtask 3 - One plan strategy and planning state on the plan workflow

Parent spec: [spec.md](./spec.md). Read its Model, Decisions and Execution rule first.
`investigation.md` has the goal planning census.
Issue key: SKILL-425

## Scope

Give the `plan` slot one strategy and two steps everywhere, keep its state on the plan workflow,
and remove the separate goal planning skeleton.

The `plan` slot owns two steps: `plan`, then `plan_subtasks`. One strategy fills it in every
skeleton, composed from the existing strategies. Do not add a new plan strategy class.
`GoalPlanFanOutStrategy` already wraps `AgentPlanStrategy`; extend it to own both steps:

- `plan` delegates to the wrapped `AgentPlanStrategy` and authors the bundle: parent spec when it is
  runtime-seeded, subtask specs and `decomposition-manifest.yaml`. It may split. It no longer writes
  `## Implementation Details`.
- `plan_subtasks` runs the existing fan-out, one unit per manifest subtask in bounded waves. Each unit
  writes `## Implementation Details` into its subtask spec only, under today's `PLAN_SPEC_DIRECTIVE`
  rules.

The strategy may be renamed to drop "Goal". Keep the Opus 5.5 variant through the existing
`withOpus` composition. Bind it to the `plan` slot for every skeleton that holds the slot.

Remove the context switches. No strategy reads `suppressDecomposition`, `specBundleRequired` or goal
continuation to change its task. Remove those inputs from `FeatureTaskRuntimePhasePromptComposeInputs`,
and remove `GOAL_CONTINUATION_CONSTRAINT`. Keep `PHASE_FEASIBILITY_CONSTRAINT` and the bundle
verification in `PlanDecompositionStop`, which now runs after `plan_subtasks`. Keep the install-command
prohibition, moved into the planning directive.

Move planning state to the plan workflow. Support fan-out on the durable run state, which today
throws `PhaseRunFanOutUnsupportedError`. The preplan record, the bundle record and one plan record per
subtask are ordinary durable phase records of the plan workflow, and resume reuses completed units.
The goal parent links the plan workflow through the existing plan-workflow artifact. Goal children
hydrate preplan and their subtask's plan from the linked plan workflow. `goal-child` keeps `preplan`
and `plan` in its slot list as hydrated imports.

Handle bundles without a plan workflow. A bundle written by hand, or imported before this change,
goes through the plan skeleton before execution. The `plan` step accepts the existing bundle without
changing the parent spec, manifest, or subtask scopes and criteria, and `plan_subtasks` plans each
subtask. Change the pre-launch refusal (`FeatureTaskRuntimeRunLoopHookViews.kt:57`) so it allows this
case.

Remove the separate goal planning path: `SkeletonDefinition.GOAL_PLANNING`,
`SkeletonRunStateKind.GOAL_PLANNING`, `GoalPlanningPhaseRunState`, `DefaultGoalPlanningSweep` and its
callers in `GoalRunner` and `GoalRunnerGoalLoop`, plus code left without callers. Spec-drift recovery
re-plans changed subtasks through the plan workflow.

Keep legacy goals working. Goals planned before this change keep hydrating from
`goal_planning_preparations`, `goal_shared_preplans` and `goal_subtask_plans`, read-only. Nothing
writes those tables. Goal status, the work list and the desktop read planning progress from the plan
workflow, and from the legacy tables for legacy goals.

Owned paths (elided segments are `...`):

- `runtime-engine/.../featuretask/slot/plan/`, `slot/preplan/AgentPreplanStrategy.kt`, `slot/skeleton/`, `slot/state/`
- `runtime-engine/.../featuretask/runloop/` (state bindings, planning stop, hook views)
- `runtime-engine/.../featuretask/phase/prompt/compose/`
- `runtime-engine/.../goalrunner/planning/`, `goalrunner/GoalRunner.kt`, `goalrunner/execution/core/`
- `runtime-engine/.../goalplanning/`
- `runtime-application/.../workflow/decomposition/PlanWorkflowLinkage.kt`
- `runtime-domain/.../taskruntime/model/skeleton/`
- `runtime-infra/sqlite/.../workflow/goalrunner/planning/`, `worklist/`, `core/schema/DesktopChangeRevisions.kt`
- `runtime-core/.../di/featuretask/`, `di/goal/`

## Acceptance Criteria

1. `PhaseSlot.PLAN` owns the steps `plan` and `plan_subtasks`, and every skeleton that holds the slot binds the same strategy to it.
2. That strategy is composed from the existing `AgentPlanStrategy` and `GoalPlanFanOutStrategy`, and no new plan strategy class exists.
3. The `plan` step authors the bundle and may produce more than one subtask, and `plan_subtasks` writes `## Implementation Details` into each subtask spec and nothing else.
4. No strategy or prompt input reads `suppressDecomposition`, `specBundleRequired` or goal continuation, and `GOAL_CONTINUATION_CONSTRAINT` is gone.
5. Fan-out runs on the durable run state, and a resumed plan workflow reuses completed `plan_subtasks` units.
6. The preplan, bundle and per-subtask plan records are durable phase records of the plan workflow, and goal children hydrate from the linked plan workflow.
7. A hand-written bundle runs through the plan skeleton without changes to its parent spec, manifest, or subtask scopes and criteria, and every subtask then has Implementation Details.
8. `SkeletonDefinition.GOAL_PLANNING`, `SkeletonRunStateKind.GOAL_PLANNING`, `GoalPlanningPhaseRunState` and `DefaultGoalPlanningSweep` are removed.
9. Legacy goals hydrate from the `goal_*` planning tables, and no code writes those tables.
10. Goal status, the work list and the desktop report planning progress for new goals from the plan workflow and for legacy goals from the legacy tables.
11. Spec-drift recovery re-plans changed subtasks through the plan workflow.
12. Tests cover criteria 1 to 11, including a resumed fan-out and a legacy goal fixture.

## Non-Goals

- No drop of the legacy `goal_*` planning tables.
- No change to `goal-child` execution after hydration.
- No re-planning or re-splitting of a launched subtask.
- No change to the intake step. Subtask 1 owns it.

## Dependency Notes

None. This subtask runs on the current tree. It applies to whatever planning skeleton exists:
two slots or three, and run by `phase plan` alone or by the full run too. If the full run does not
yet run the plan skeleton for new keys, imported bundles still reach it through the bundle-without-plan
rule above. Record the decision in `runtime-kotlin/agent/decisions.md`; it supersedes the SKILL-387
planning handoff entry and SKILL-410's "no goal planning sweep at plan time".

## Validation Strategy

- The build phase runs the pack build command.
- The validate phase runs the pack validation gate and `./gradlew check`.
- A new migration needs a named `DatabaseMigration` and a `DesktopReadSchema` version bump.
- Implement and audit do not run builds or tests.

## Next Path

Complete the goal and prepare its pull request.
