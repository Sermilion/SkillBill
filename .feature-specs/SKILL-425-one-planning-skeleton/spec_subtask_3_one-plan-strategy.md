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

## Implementation Details

Paths are under `runtime-kotlin/`. `R-engine` is `runtime-engine/src/main/kotlin/skillbill/engine`,
and `R-domain` is `runtime-domain/src/main/kotlin/skillbill/workflow/taskruntime`. Facts come from the
preplan digest. Where it lacked a fact, the decision below names the assumption for implement to
confirm in code before building on it.

### Settled decisions

- **D1. Strategy name and ids.** Rename `GoalPlanFanOutStrategy` to `PlanFanOutStrategy` and
  `GoalPlanFanOutOpus55Strategy` to `PlanFanOutOpus55Strategy`. This renames existing classes; it
  adds none (AC-2). Keep the persisted strategy ids `goal-plan-fan-out` and its Opus id unchanged,
  so recorded execution plans keep resolving without an id-mapping shim. `AgentPlanStrategy` stays
  as the wrapped bundle author and is bound to no slot. It stays registered in
  `phaseStrategyRegistry` only if recorded execution plans of in-flight `standalone` or `goal-child`
  rows resolve `agent-plan` through the registry. Assumption: implement checks
  `mapRecordedExecutionPlan` and drops the registration when it does not.
- **D2. Steps.** Add `FeatureTaskRuntimePhaseIds.PLAN_SUBTASKS = "plan_subtasks"`. `PhaseSlot.PLAN`
  steps become `[PLAN, PLAN_SUBTASKS]`. Add the step and the transitions `plan -> plan_subtasks ->
  <next>` to `FeatureTaskRuntimePhaseWorkflowDefinition`. Bump the plan strategy's
  `semanticRevision` because recorded step semantics change.
- **D3. Per-subtask records.** Each fan-out unit settles an ordinary durable phase record keyed
  `plan_subtasks:<subtask id>` in the plan workflow's phase-records artifact. Its payload is the
  unit's final prose, as for every phase record. When every unit has settled, the step writes the
  aggregate `plan_subtasks` record listing the unit ids. This needs no schema change. Assumption:
  the phase-records artifact is keyed by string phase id. If its decoder validates keys against the
  workflow definition, extend the decoder to accept the `plan_subtasks:<n>` form, and keep rows
  without it readable.
- **D4. Bundle snapshot.** The `plan` step's record is the bundle record. Next to it, the runtime
  writes a `plan_bundle` workflow artifact holding the bundle origin (`AUTHORED` or `EXISTING`), the
  sha256 of the parent spec and the manifest, and for each subtask its spec path, `scope_sha256`
  (the spec content outside `## Implementation Details`) and, once its unit settles,
  `details_sha256`. Unit settlement, the planning stop, drift detection and hydration all read this
  artifact. It is a new `DurableWorkflowArtifactFamily` entry, not a column. If implement finds that
  artifact families need a schema change, add a named `DatabaseMigration` and bump
  `DesktopReadSchema.VERSION`.
- **D5. Unit completion and resume.** A unit counts as complete only when its
  `plan_subtasks:<id>` record exists and its recorded `scope_sha256` and `details_sha256` still match
  the subtask spec on disk. `pendingUnits()` returns every other manifest subtask, so a resumed plan
  workflow reuses settled units (AC-5) and re-plans drifted ones (AC-11).
- **D6. Existing bundle.** When a decomposition manifest already exists at the governed bundle path
  as the `plan` step launches, the step makes no agent call. The runtime verifies the bundle with
  `decompositionPlanner.verifyAuthoredBundle`, records it as origin `EXISTING`, and completes the
  step. Assumption: `PhasePlanningStepBinding` has no "complete without agent" operation. If so, add
  `settleExistingBundle(...)` to it, implemented in `FeatureTaskRuntimeRunLoopStepBindings`, and
  model it on how `RuntimeCommitStrategy` completes a runtime step.
- **D7. Unit write boundary.** A unit may only add or replace the `## Implementation Details` section
  of its own subtask spec. At unit settlement, the runtime rejects the unit if that spec's
  `scope_sha256` changed or if the section is blank (`governedSubSpecReady`). After each wave, and
  again in the planning stop, the runtime checks that the parent spec, the manifest and every
  subtask's `scope_sha256` match the `plan_bundle` snapshot. Parallel units in one wave write
  different files, so no unit checks a sibling's file in the middle of a wave.
- **D8. Planning stop.** Remove `PlanDecompositionStop.requiresBundle` and its
  `specBundleRequired` input. `PlanStepHooks` always enforces the bundle on the `plan` step. The
  stopper (`FeatureTaskRuntimePlanningStopper.resolve`) runs after `plan_subtasks` for every run
  that executed the `plan` slot. It verifies the final bundle against D7 and checks that every
  subtask has a non-blank Implementation Details section, then persists the
  `FeatureTaskRuntimeDecomposeTerminal`. It keeps its goal-continuation `Proceed` branch, because
  goal children never execute the slot. That branch is run-loop code, not a strategy or prompt
  input. Until subtask 4 lands, a `standalone` run that reaches the slot stops `Decomposed` with
  `skill-bill <KEY>` as its next path.
- **D9. Pre-change plan workflows.** Refuse to resume a non-terminal plan workflow whose recorded
  execution plan has no `plan_subtasks` step. The message names `skill-bill goal purge <KEY>`
  followed by `skill-bill phase plan <KEY>`. A completed pre-change plan workflow has no
  `plan_subtasks:<id>` records. For hydration it counts as "no usable plan workflow" (D10), so a goal
  on that bundle either uses its legacy rows or goes through the plan skeleton under the
  bundle-without-plan rule.
- **D10. Hydration source order per subtask.**
  1. Use the linked plan workflow (`WorkflowStepState.planWorkflowId` or the `plan_workflow`
     artifact) when it carries a matching `plan_subtasks:<id>` record.
  2. Otherwise use the legacy `goal_*` rows, read-only.
  3. Otherwise hydration is missing, and `GoalRunner` runs the plan skeleton for the key. It goes
     through `StandalonePlanRun` and the generic `FeatureTaskRuntimeRunEntry`, so there is no
     planning-only path. It then links the completed plan workflow and continues.
- **D11. Spec-drift recovery.** `GoalRunner.refreshSpecPlanning` compares each pending subtask's
  scope sha with the linked plan workflow's `plan_bundle` entry. On drift it calls
  `StandalonePlanRun.replanSubtasks(planWorkflowId, repoRoot, …)`. That call reopens the completed
  plan workflow at `plan_subtasks` and runs it through the durable runner, where D5's
  `pendingUnits()` yields only the drifted units. It then re-runs the planning stop. Assumption: the
  workflow state store can set a terminal workflow back to running at a given step. If it cannot,
  add a narrow `reopenPlanWorkflowAtStep` operation beside `PlanWorkflowLinkage.kt`. Legacy goals
  with no linked plan workflow re-plan through the same call after D10 step 3 opens a plan workflow
  for them.
- **D12. Goal-child slot.** `goal-child` keeps `preplan` and the `plan` slot, which now covers `plan`
  and `plan_subtasks`. Hydration writes completed imported records for `preplan`, `plan` (the
  subtask's `plan_subtasks:<id>` payload) and `plan_subtasks` (a short marker naming the source
  record). It keeps `executionOrigin = GOAL_PLANNING_HYDRATED`, `resolvedAgentId =
  "goal-planning-import"` and `currentStepId = "implement"`. The
  `FEATURE_TASK_RUNTIME_GOAL_PLANNING_IMPORT` provenance payload gains an optional `source`
  (`plan_workflow` with workflow id and `details_sha256`, or `legacy_tables`). Rows without the
  field read as `legacy_tables`.
- **D13. Prompt flags.** Remove `suppressDecomposition` and `specBundleRequired` from
  `FeatureTaskRuntimePhasePromptComposeInputs`. Keep `specRewritable`, which describes the spec and
  not the skeleton. `PHASE_FEASIBILITY_CONSTRAINT` joins the common plan prompt. The
  install-command prohibition from `GOAL_CONTINUATION_CONSTRAINT` moves into one shared planning
  directive constant that both steps' directives append.

### Ordered tasks

1. **Slot and step ids** (AC-1).
   - Add `PLAN_SUBTASKS` to `FeatureTaskRuntimePhaseIds` and to `PhaseSlot.PLAN` in
     `R-domain/model/skeleton/PhaseSlot.kt`.
   - Add the step and transitions to `FeatureTaskRuntimePhaseWorkflowDefinition` (D2), keeping any
     `INTAKE` slot already on the tree.
   - Tests: update `PhaseSlotTest` and `SkeletonDefinitionTest` assertions on the PLAN slot's
     steps. Add no new test.
2. **Composed plan strategy** (AC-1, AC-2, AC-3).
   - In `R-engine/featuretask/slot/plan/`, rename the fan-out strategy and its Opus wrapper (D1).
   - Set `steps = [plan, plan_subtasks]` and `entryStep = plan`.
   - For the `plan` step, delegate `policyFor`, `directiveFor`, `promptSections`, hooks and
     `runStep` to the wrapped `AgentPlanStrategy`, and add the D6 existing-bundle short-circuit.
   - For the `plan_subtasks` step:
     - `directiveFor` gives `PLAN_SPEC_DIRECTIVE` plus the shared install prohibition.
     - `promptSections` overrides `taskDirective` as today.
     - `runStep` uses today's wave loop: `fanOut(run.phaseId)`, `authorizeFanOutWave`,
       `pendingUnits()` in `planFanOutCap` waves through `fanOutPort.runBounded`, and
       `runAgentStep` on `unitState`.
   - Make `plansInFanOut` step-scoped, true only for `plan_subtasks`, and update its readers.
   - Keep `PhaseExecutionBindingKind.PLANNING` for both steps through the slot mapping.
   - Extend `PlanResumeRules` so that a resumed `plan_subtasks` completion is dropped while `plan`
     is not completed.
   - Tests: update `PhaseStrategyCompositionTest` and `TestPhaseStrategies`. Add no new test here;
     task 11 covers the behaviour.
3. **Bindings and DI** (AC-1, AC-2).
   - `SkeletonStrategyBindings`: bind `PhaseStrategyBinding.Fixed(goal-plan-fan-out).withOpus(...)`
     to PLAN in `sharedBindings()` and in the `PLAN` skeleton, and delete the `GOAL_PLANNING` entry.
   - `runtime-core/.../di/featuretask/RuntimeFeatureTaskSlotProvides.kt`: update the providers for
     the renamed classes and apply D1 to the `agent-plan` registration.
   - Tests: one assertion in `RuntimeFeatureTaskSlotProvidesTest` that every skeleton holding PLAN
     resolves the same strategy id, and the same Opus id. This catches a skeleton silently bound to
     a different plan strategy.
4. **Remove the context switches** (AC-4).
   - `AgentPlanStrategy.promptSections` always emits `featureSpecDirective`,
     `specBundleRequirement(specRewritable)` without the Implementation Details sentence,
     `MANIFEST_TEMPLATE` and `PHASE_FEASIBILITY_CONSTRAINT`.
   - `AgentPlanStrategy` always uses `BUNDLE_DIRECTIVE` plus the install prohibition. Delete the
     prose `DIRECTIVE` and `GOAL_CONTINUATION_CONSTRAINT` once nothing reads them.
   - `AgentPreplanStrategy` always sets `stepContext = featureSpecIntake`.
   - Remove the two fields from `FeatureTaskRuntimePhasePromptComposeInputs` and their setters at
     `slot/attempt/PhaseLaunchPreparation.kt:414-415`.
   - Remove `SkeletonDefinition.requiresSpecBundle` and `FeatureTaskRuntimeRunFacts.specBundleRequired`
     (line 78), and replace their remaining readers per D8.
   - Tests: update `FeatureTaskRuntimePhasePromptComposeContentTest` and its `TestSupport`.
     - One test: the plan prompt carries the bundle directive and manifest template and no
       goal-continuation text.
     - One test: the `plan_subtasks` prompt carries the assigned-spec-only directive and the install
       prohibition.
5. **Durable fan-out** (AC-5, AC-6).
   - Add a `PhaseRunFanOut` implementation to the durable run state
     (`runloop/durable/FeatureTaskRuntimeRunLoopDurableState.kt` and a sibling file), so the
     durable state overrides `fanOut` instead of throwing `PhaseRunFanOutUnsupportedError`.
   - Port the logic of `GoalPlanningPlanFanOut` and `GoalPlanningUnitRunState` onto durable records:
     - one unit per manifest subtask;
     - `pendingUnits` per D5;
     - `unitState` binds with `stepBinding.beginStepBinding(run, unitId)` and
       `FeatureTaskRuntimeRunLoopStepBindings.create(..., unitId, bindingCoordinator = …)`;
     - `settleUnit` applies the D7 check, writes the `plan_subtasks:<id>` record and the
       `plan_bundle` unit entry;
     - `completed` writes the aggregate record;
     - `pauseBefore` reuses the durable state's existing pause and budget policy.
   - Assumption: unit attempts are keyed by step id plus `fanOutUnitId`. If the durable attempt
     store keys only by step id, key unit attempts by `plan_subtasks:<id>`.
   - Tests: task 11.
6. **Bundle record and planning stop** (AC-3, AC-7).
   - Write the `plan_bundle` artifact family and its writer (D4).
   - `PlanDecompositionStop`: replace `existingBundleReason`'s "never overwrites a plan" refusal
     with the D6 accept path. Keep `protectedSpecRejection`, `PlanBundleAuthorization` and
     `verifyAuthoredBundle` for authored bundles.
   - The stopper applies D7 and D8 after `plan_subtasks`.
   - `runloop/attempt/FeatureTaskRuntimeRunLoopHookViews.kt:57`: drop the pre-launch refusal of an
     existing manifest for the PLAN definition. `StandalonePlanRun`'s lookup still refuses running
     or ambiguous plan workflows.
   - `goalrunner/plan/StandalonePlanRun.settleExistingManifest`: when no completed plan workflow
     with `plan_subtasks` records exists, `open` the plan skeleton instead of refusing. Subtask 2
     may already have changed this method; keep both changes.
   - Add the D9 refusal for pre-change in-flight plan workflows.
7. **Remove the goal planning path** (AC-8).
   - Delete `SkeletonDefinition.GOAL_PLANNING` and `SkeletonRunStateKind.GOAL_PLANNING`.
   - Delete in `goalrunner/planning/`: `state/GoalPlanningPhaseRunState.kt` (with
     `GoalPlanningPlanFanOut`, `GoalPlanningUnitRunState` and `GoalPlanningStepAttempts`),
     `GoalPlanningRunProgress`, `GoalPlanningRunFacts`, `sweep/GoalPlanningSweep.kt` (with
     `DefaultGoalPlanningSweep` and `GoalPlanningLaunchResolution`) and
     `attempt/GoalPlanningPhaseAttemptGateLaunch.kt`.
   - Delete whatever in `planning/context|outcome|recovery|remedies` and `GoalRunnerSharedArgs`
     is left without callers.
   - Delete `runtime-core/.../di/goal/RuntimeGoalPlanningSweepProvides.kt` and its
     `RuntimeComponent.kt:29,104` references.
   - In `GoalRunner.runPrepared`, replace the sweep call with D10 hydration resolution. In
     `GoalRunnerGoalLoop.kt:102`, replace `planningHydrationMissing` and
     `DriveGoalLoopArgs.planning` with a per-subtask hydration source, also used by
     `GoalRunnerSubtaskLaunchPrepare.kt:237`.
   - Tests: delete `GoalPlanningSweepTest`, `RejectingPlanningRunLoopEntry`,
     `GoalPlanningSweepFixture` and `SlotBaselineGoalPlanningCapture`, together with any
     slot-baseline resources they own. Update `StrategyCapabilityBoundaryArchitectureTest`,
     `RuntimeEngineInboundApiTest` and `PortNullObjectAbsenceArchitectureTest` references.
8. **Hydration from the plan workflow** (AC-6, AC-9).
   - `goalrunner/planning/hydration/GoalChildPlanningHydrator.kt` gets two sources behind one
     selection (D10, D12):
     - the plan workflow source reads the linked workflow's `preplan`, `plan_subtasks:<id>` and
       `plan_bundle`;
     - the legacy source keeps the existing table reads, with their sha256 and provenance checks.
   - `requireMatchingImport` validates against the recorded source.
   - Update `GoalChildPlanningHydrationRequest` (`GoalRunnerChildWorkflowModels.kt:26,44`) and its
     consumer `WorkflowGoalRunnerChildWorkflowPersistence.kt:132,173` for the source field.
   - Keep `WorkflowGoalRunnerManifestLoader.importFromManifestProjection`'s plan-workflow link
     (`findCompletedPlanWorkflowId`, `importedPlanStepUpdates`, `withImportedPlan`). Change
     `findCompletedPlanWorkflowId` in `PlanWorkflowLinkage.kt` to prefer the newest completed plan
     workflow that has `plan_subtasks` records.
9. **Stop writing the legacy tables** (AC-9).
   - Delete `StandalonePlanPreparation.capture` and its two `Completed` call sites in
     `StandalonePlanRun`.
   - Delete `StandalonePlanCheckpointImport` and its call at
     `WorkflowGoalRunnerManifestLoader.kt:139`.
   - Delete the writer methods of `GoalPlanningPreparationCheckpoint` in `goalplanning/`.
   - Remove `markPrepared`, `checkpoint*`, `replace*` and `advance*` from the
     `GoalPlanningPreparationRepository` port and from its sqlite implementations under
     `runtime-infra/sqlite/.../workflow/goalrunner/planning/`.
   - Keep the reads, the migrations and `deleteByGoal` for purge and reset.
   - Remove `runPreparation.admitPlanningMigration` and `GoalPlanningMigration*` if they write
     those tables. Keep them only if they are read-only.
   - Tests: delete `StandalonePlanPreparationTest`. The removal itself is proved by compilation.
10. **Planning progress readers** (AC-10).
    - `GoalRunnerStatusSubtaskReader.kt:53,171` reads progress from the linked plan workflow when one
      exists: `preplan` status, and the count of `plan_subtasks:<id>` records against the manifest
      subtasks. Otherwise it reads the legacy tables.
    - Apply the same split in `runtime-infra/sqlite/.../workflow/goalrunner/planning/`
      (`GoalPlanningStatusProjectionSql`) and `worklist/`.
    - Make `core/schema/DesktopChangeRevisions.kt` count writes to the plan workflow's artifacts as
      planning-progress changes.
    - Keep the status, work list and desktop wire shapes unchanged. A migration is added only per D4,
      with a `DesktopReadSchema` bump.
11. **Tests** (AC-12). Each test names the bug it would catch.
    - **Durable fan-out resume** (AC-5). Settle unit 1, simulate a crash, resume the plan workflow,
      and assert that only unit 2 runs. Bug caught: resume re-plans settled subtasks or loses them.
    - **Drift re-plan** (AC-11). Change one pending subtask's scope after planning, and assert that
      `replanSubtasks` runs only that unit and updates its record. Bug caught: drift ignored, or
      every subtask re-planned.
    - **Unit write boundary** (AC-3). A unit that edits its acceptance criteria or a sibling spec is
      rejected. Bug caught: fan-out silently rewrites scope.
    - **Hand-written bundle** (AC-7), in `PhasePlanRunTest` or a new engine-level `StandalonePlanRun`
      test. A bundle with no plan workflow runs the plan skeleton. Assert that the parent spec and
      manifest bytes are unchanged, that subtask scope shas are unchanged, and that every subtask
      has a non-blank Implementation Details section.
    - **Authored bundle with two or more subtasks** (AC-3, AC-5 from the parent spec). The plan step
      splits, and `plan_subtasks` writes details into each subtask spec.
    - **Hydration** (AC-6, AC-9), in `GoalRunnerTest` or the hydrator test. One test: a child
      hydrates from a linked plan workflow. One test: a legacy goal fixture with `goal_*` rows
      hydrates from the tables, and its provenance reads `legacy_tables`.
    - **Status** (AC-10). The reader reports progress from a plan workflow, and from legacy rows for
      a legacy goal.
    - **Pre-change workflow** (D9). Resuming an in-flight plan workflow without `plan_subtasks` is
      refused with the purge-then-plan message.
    - AC-1, AC-2, AC-4 and AC-8 are covered by the updated assertions in tasks 1 to 4 and by
      compilation.
    - Fixtures to update: `GoalRunnerTestFactory`, `GoalRunnerSharedTestFactory`,
      `WorkflowGoalRunnerStoreTestFixtures`, `WorkflowGoalRunnerStoreTestNoops`,
      `TestExecutionPlan`, `PhaseModelProfileSelectionTest` and
      `FeatureTaskRuntimeExecutionPlanResolverTest`.
12. **Decision record.**
    - Add an entry to `runtime-kotlin/agent/decisions.md`: one plan strategy with two steps,
      planning state on the plan workflow, legacy tables read-only, and D9 to D11. The entry
      supersedes the SKILL-387 planning handoff entry and SKILL-410's "no goal planning sweep at
      plan time".

### Constraints

- Authored Kotlin follows `AGENTS.md`: no line comments, and KDoc only on interfaces.
- Keep the generic run loop and the single runner. All plan execution, including re-plan and the
  bundle-without-plan path, goes through `StandalonePlanRun` and `FeatureTaskRuntimeRunEntry`.
- Persisted rows stay readable: old provenance payloads, recorded strategy ids and phase-record
  artifacts without `plan_subtasks` keys.
- Do not touch the intake step (subtask 1) or the CLI plan-first chain (subtask 2). Where this
  subtask edits `StandalonePlanRun`, keep any change already on the tree.
- Implement and audit do not compile, build or run tests. The build and validate phases own those.
- Do not run installer, uninstall or install-sync commands as part of this subtask.
