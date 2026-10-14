# SKILL-425 investigation

Census taken on 2026-10-13 against `main` at `9e5f26d30`. Paths are under `runtime-kotlin/` unless
they start with another root. Line numbers are anchors at census time; recheck before editing.

## Trigger

SKILL-424 full run, 2026-10-13. Intake turned an operator spec (34 table-form criteria) into a
one-subtask manifest. Goal planning planned subtask 1 with decomposition suppressed. The subtask
agent blocked: "The spec asks for five independently reviewable boundaries ... Doing it as one blind
edit pass would leave a half-migrated, non-compiling tree."

## Skeletons and bindings

`runtime-domain/.../taskruntime/model/skeleton/SkeletonDefinition.kt`:

| Skeleton | Slots | Run state | Standalone |
|---|---|---|---|
| `standalone` | `FEATURE_RUN_SLOTS` (preplan … monitor) | durable | no |
| `goal-child` | `FEATURE_RUN_SLOTS` minus pull_request, monitor | durable | no |
| `plan` | preplan, plan | durable | yes |
| `goal-planning` | preplan, plan | goal_planning | no |
| `review`, `validation`, `pr`, `monitor` | one to three slots | in-memory | yes |

`runtime-engine/.../featuretask/slot/skeleton/SkeletonStrategyBindings.kt` binds the `plan` slot to
`AgentPlanStrategy` for `standalone`, `goal-child` and `plan`, and to `GoalPlanFanOutStrategy` for
`goal-planning` (line 93-99). Both bind `AgentPreplanStrategy` to `preplan`.

## Plan strategy modes

`runtime-engine/.../featuretask/slot/plan/AgentPlanStrategy.kt`:

- `promptSections` (line 59-75) picks one of three tasks from `specBundleRequired` and
  `suppressDecomposition`: `BUNDLE_DIRECTIVE` with the manifest template, the prose `DIRECTIVE` with
  `GOAL_CONTINUATION_CONSTRAINT`, or the prose `DIRECTIVE` alone.
- `specBundleRequirement` (line 203) already requires a non-blank `Implementation Details` section in
  every subtask spec.

`GoalPlanFanOutStrategy.kt` wraps a private `AgentPlanStrategy()`, borrows its steps and policies,
replaces the directive with `PLAN_SPEC_DIRECTIVE` ("add a non-blank `## Implementation Details`
section"), and runs units in bounded waves through `PhasePlanningStepBinding.fanOut`.

`AgentPreplanStrategy.kt:51` drops the feature-spec intake context when `suppressDecomposition` is
set.

Sources of the flags:

| Symbol | Main files | Sites |
|---|---|---|
| `suppressDecomposition` | 5 | `GoalPlanningPhaseAttemptGateLaunch.kt:102` (true), `PhaseLaunchPreparation.kt:414` (goal continuation), prompt inputs, two strategies |
| `specBundleRequired` | 5 | `FeatureTaskRuntimeRunFacts.kt:78`, `PlanDecompositionStop.kt:38,204`, `PhaseLaunchPreparation.kt:415`, `AgentPlanStrategy.kt` |
| `isGoalContinuationRun` | 12 | run loop, launch preparation, planning stop |

## Fan-out support

- `featuretask/slot/state/PhaseRunState.kt:78`: default `fanOut` throws
  `PhaseRunFanOutUnsupportedError`.
- `goalrunner/planning/state/GoalPlanningPhaseRunState.kt:70`: the only implementation.
- `featuretask/runloop/state/FeatureTaskRuntimeRunLoopStepBindings.kt:369`: the durable binding
  implements `PhasePlanningStepBinding` by delegating to the backing run state, so a durable run
  reaches the throwing default today.

## Intake outside skeletons

| Path | Writes | Caller |
|---|---|---|
| `GoalIntakePreparation.prepare` | parent spec (unless kept), one subtask spec, manifest | `GoalRunner.run` before any skeleton |
| `GoalIntakePreparation.seedPlanSpec` | parent spec only | `StandalonePlanRun.open:201` before the workflow opens |

`FeatureTaskRuntimeRunEntry` reads run invariants from the spec at open (line ~124, review mode) and
before the runner starts (line 85). An intake slot needs both reads to move after the intake step.

## Full-run routing

`runtime-cli/.../goal/core/GoalCliCommands.kt`: `GoalRunCommand.run` calls `finishIncompletePlan`
(line 234). It returns early when `planLauncher.incompletePlanWorkflowId` is null (line 269), so a
new key goes straight to `goalRunner.run` and intake writes the one-subtask manifest.

`StandalonePlanRun.incompletePlanWorkflowId` returns null whenever a manifest exists.
`FeatureTaskRuntimeRunLoopHookViews.kt:57` refuses a plan pre-launch when a manifest already exists.

## Goal planning machinery

| Area | Files | Lines |
|---|---|---|
| `runtime-engine/.../goalrunner/planning` | 47 | 5,199 |
| `runtime-engine/.../goalplanning` (preparation records, migration) | 8 | 1,536 |
| `runtime-infra/sqlite/.../goalrunner/planning` | 8 | 1,113 |

- Sweep: `DefaultGoalPlanningSweep.prepare` runs the `goal-planning` skeleton through
  `FeatureTaskRuntimeRunLoopEntry` with `GoalPlanningPhaseRunState`. Called from `GoalRunner.kt:195`
  and `GoalRunnerGoalLoop.kt:106`.
- Identity: `GoalPlanningIdentity(parentGoalWorkflowId, normalizedIssueKey, repositoryIdentity)`.
- Tables: `goal_planning_preparations`, `goal_shared_preplans`, `goal_subtask_plans`.
- Child hydration: `GoalChildPlanningHydrator.loadRequiredPreparation` reads the shared preplan and
  the subtask plan from those tables. Callers: `WorkflowGoalRunnerManifestStore`,
  `WorkflowGoalRunnerChildWorkflowPersistence`.
- Other readers of the tables: `GoalPlanningStatusProjectionSql`, `WorkGoalParentStore`,
  `SQLiteWorkListRepository`, `WorkflowStateSqlReads`, `DesktopChangeRevisions`, schema and migration
  files.
- Plan linkage: `runtime-application/.../decomposition/PlanWorkflowLinkage.kt` records the plan
  workflow id on the goal parent and on its imported `preplan` and `plan` step records.

## Standalone skeleton

- Opened by `skill-bill feature-task run` (`FeatureTaskRuntimeRunCommand`,
  `FeatureTaskRuntimeExplicitRunCommand`, alias `FeatureTaskRuntimeDeprecatedRunCommand`).
- `SkeletonDefinition.forRun(false)` returns it, and `admittedForRun(false)` admits it with `plan`.
- `FeatureTaskContinuationLookupQuery.admittedDefinition` defaults to it.
- 12 test files reference `SkeletonDefinition.STANDALONE`.
- The route scope `FeatureTaskRouteScope.STANDALONE` ("standalone") is separate, and plan workflows
  use it.
- No caller in `intellij-plugin` or `desktop-tray`.

## Prior decisions this supersedes

- SKILL-410 (`.feature-specs/done/SKILL-410-durable-standalone-plan/spec.md`): "Nothing creates the
  goal parent at plan time and no goal planning sweep runs." The full run resumed only an incomplete
  plan.
- SKILL-387 (`runtime-kotlin/agent/decisions.md`, "make the governed spec artifact the planning
  handoff"): standalone plan authors the bundle; goal planning writes each assigned subtask spec.
- SKILL-65.1 introduced `suppressDecomposition` so goal children would not author nested bundles.

## Documentation that describes planning

- `skills/skill-bill/content.md` lines 74-78 (`phase:plan` paragraph).
- `runtime-kotlin/ARCHITECTURE.md` lines ~347-353 (goal planning, child hydration).
- `README.md`, `docs/getting-started.md`, `docs/runtime-command-guidance.md` (feature-task commands).
