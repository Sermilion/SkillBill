# SKILL-425 One planning skeleton for phase:plan and the full run

## Intended outcome

`skill-bill phase plan <KEY>` and the full run `skill-bill <KEY>` plan the same way. Planning is one
skeleton, `plan`, with three slots: `intake`, `preplan` and `plan`. Each slot has one strategy, and
every skeleton that holds a slot binds the same strategy to it. `phase plan` runs the plan skeleton
and stops. The full run runs the same plan skeleton, then executes each subtask of the bundle that
planning wrote. The only difference between the two is where they stop.

## Model

The runtime composes runs from three parts:

- A **skeleton** (`SkeletonDefinition`) is a named, ordered list of slots plus run settings.
- A **slot** (`PhaseSlot`) is a position in the canonical order. It owns one or more steps.
- A **strategy** (`PhaseStrategy`) fills a slot and runs its steps. `SkeletonStrategyBindings` binds
  one strategy per slot for each skeleton.

Rule for this feature: a strategy's behaviour must not depend on which skeleton holds its slot.

## Problem

On 2026-10-13, `skill-bill .feature-specs/SKILL-424-readian-desktop-architecture-parity` wrote a
manifest with one subtask, "Implement the requested change", that carried all 34 acceptance criteria.
Goal planning planned that subtask and was told not to split it. The subtask agent blocked: the work
needed about five reviewable subtasks and one pass would leave a non-compiling tree. Nothing in the
full run could make a second subtask.

The cause is that planning diverges between `phase plan` and the full run. `investigation.md` has the
census. In short:

1. Intake runs outside any skeleton, in two copies. `GoalIntakePreparation.prepare` (full run) writes
   the parent spec, one subtask spec and the manifest in Kotlin, with no agent.
   `GoalIntakePreparation.seedPlanSpec` (plan skeleton) writes only the parent spec.
2. Two skeletons have identical slots and bind different `plan` strategies. `plan` binds
   `AgentPlanStrategy`, which writes the bundle and may split. `goal-planning` binds
   `GoalPlanFanOutStrategy`, which plans subtasks that already exist and never splits.
3. Strategies switch their task on context flags. `AgentPlanStrategy` and `AgentPreplanStrategy` read
   `specBundleRequired` and `suppressDecomposition`. With `suppressDecomposition` set, the plan prompt
   says "Do not propose a new decomposition in the plan phase."
4. The full run runs the plan skeleton only to finish a plan that already started
   (`GoalRunCommand.finishIncompletePlan`). A new key never gets a split.
5. Each subtask's "how" is written twice. The bundle requirement asks the plan step for a non-blank
   `## Implementation Details` section, and goal planning's fan-out rewrites the same section.
6. Planning state lives in two stores. Plan workflows keep ordinary phase records. Goal planning keeps
   `goal_planning_preparations`, `goal_shared_preplans` and `goal_subtask_plans`, keyed by the goal
   parent.
7. The `standalone` skeleton (`skill-bill feature-task run`) uses a third plan mode: a prose plan with
   no bundle.

## Decisions

- **Intake slot.** Add `PhaseSlot.INTAKE` as the first slot in canonical order, with one step,
  `intake`. A runtime strategy fills it, with no agent, like `RuntimeCommitStrategy` fills
  `commit_push`. It reuses the existing intake code (`GoalIntakePreparation` spec resolution,
  `FeatureSpecPreparationWriter.writeParentSpecOnly`, the prose rule from commit `9e5f26d30`). It
  writes or keeps the parent `spec.md` and nothing else: never a manifest or a subtask spec.
- **Intake rules.** An operator spec with a readable acceptance list is kept byte for byte and stays
  protected from the plan step. An operator spec without one becomes prose intake: the runtime writes
  a spec at the same path and quotes the original text in it. Free text or resolved tracker
  requirements seed a spec. Tracker lookup stays in the dispatcher.
- **Spec path before the spec exists.** The governed spec path `.feature-specs/<KEY>-<slug>/spec.md`
  is derived from the intake before any file is written, and the workflow opens with it. Run
  invariants are read after the intake step completes, not when the workflow opens.
- **One plan strategy, two steps.** The `plan` slot owns two steps: `plan`, then `plan_subtasks`.
  One strategy owns both, composed from the existing strategies. `GoalPlanFanOutStrategy` already
  wraps `AgentPlanStrategy`. Its `plan` step delegates to the wrapped bundle authoring. Its
  `plan_subtasks` step runs the existing fan-out, one unit per manifest subtask, and each unit writes
  that subtask's `## Implementation Details`. The bundle step no longer writes Implementation Details.
  No new plan strategy class is added. The strategy may be renamed to drop "Goal". The Opus 5.5
  variants keep the existing `withOpus` composition.
- **One planning skeleton.** `plan` is `[INTAKE, PREPLAN, PLAN]`, durable and standalone-invocable.
  It is the only skeleton that executes `preplan` or `plan`. The `goal-planning` skeleton, its run
  state and the goal planning sweep are removed.
- **Planning state on the plan workflow.** The preplan record, the bundle record and one plan record
  per subtask are ordinary durable phase records of the plan workflow. Fan-out runs on the durable run
  state. The goal parent links the plan workflow through the existing plan-workflow artifact
  (SKILL-410). Goal children hydrate their preplan and plan from the linked plan workflow.
- **Full run plans first.** A full run on a key with no stored goal runs the plan skeleton with the
  intake, opening it or resuming it, then imports the bundle and executes it in the same invocation.
  A bundle written by hand, with no plan workflow, also goes through the plan skeleton: intake keeps
  the operator spec, the bundle step accepts the existing bundle unchanged, and `plan_subtasks` plans
  each subtask.
- **Splitting is never suppressed.** No strategy reads `suppressDecomposition`, `specBundleRequired`
  or goal continuation to change its task. The goal-continuation plan prompt is removed.
- **Goal children keep their slot list.** `goal-child` still lists `preplan` and `plan`. They are
  hydrated as completed imports, as today, and never executed. Only the hydration source changes.
- **Legacy planning tables.** Goals planned before this change keep hydrating from the `goal_*`
  planning tables, read-only. Nothing new writes them. Dropping the tables is out of scope.
- **Retire the standalone skeleton.** No command opens a new `standalone` workflow. The
  `feature-task run` commands are removed. Existing `standalone` rows stay readable for status, list
  and abandon, and their resume is refused with a message naming `skill-bill <KEY>`. The route scope
  named `standalone` stays, because plan workflows use it.

## Acceptance Criteria

1. `PhaseSlot.INTAKE` is the first slot in canonical order. The `plan` skeleton is
   `[INTAKE, PREPLAN, PLAN]`, and no other skeleton executes `preplan` or `plan`.
2. One runtime strategy fills `intake` in every skeleton that holds it. It writes or keeps only the
   parent spec, following the intake rules. No code outside a skeleton writes a parent spec,
   subtask spec or manifest from intake.
3. The `plan` slot owns the steps `plan` and `plan_subtasks`. Every skeleton binds the same strategy
   to it, composed from the existing `AgentPlanStrategy` and `GoalPlanFanOutStrategy`.
4. No strategy changes its task based on `suppressDecomposition`, `specBundleRequired` or goal
   continuation, and those inputs are gone from prompt composition.
5. `skill-bill phase plan <KEY> <intake>` runs the plan skeleton and stops with a verified bundle in
   which every subtask spec has Implementation Details written by `plan_subtasks`.
6. `skill-bill <KEY> <intake>` with no stored goal runs the same plan skeleton, then executes the
   bundle in the same invocation. Planning decides how many subtasks there are.
7. A bundle written by hand gets its subtask plans from the plan skeleton without any change to its
   parent spec, manifest, or subtask scopes and criteria.
8. Goal children hydrate preplan and plan from the linked plan workflow. Legacy goals hydrate from
   the `goal_*` planning tables, and nothing writes those tables.
9. The `goal-planning` skeleton, `SkeletonRunStateKind.GOAL_PLANNING`, `GoalPlanningPhaseRunState`
   and `DefaultGoalPlanningSweep` are removed. Goal status reports planning progress from the plan
   workflow, and from the legacy tables for legacy goals.
10. No command opens a `standalone` workflow, and the `feature-task run` commands are gone. Existing
    `standalone` rows stay readable and abandonable.
11. The dispatcher skill text, `../../../runtime-kotlin/ARCHITECTURE.md`, user docs and
    `runtime-kotlin/agent/decisions.md` describe one planning skeleton.
12. `./gradlew check` passes.

## Constraints

- Reuse existing strategies and intake code. The only new strategy class is the one for the new
  `intake` slot, and it wraps existing code.
- Keep the generic run loop and the single runner. Add no planning-only run path.
- Persisted rows written before this change must stay readable. Add a named migration for any new
  column, and bump `DesktopReadSchema` when a migration is added.
- Authored Kotlin follows `../../../AGENTS.md`: no line comments, KDoc only on interfaces.
- Changing `../../../skills/skill-bill/content.md` requires `./install.sh`.

## Non-goals

- No change to execution after planning: implementation, audit, review, validation, commit and PR.
- No just-in-time re-planning during execution, and no re-splitting of a launched subtask.
- No drop of the legacy `goal_*` planning tables.
- No change to tracker lookup in the dispatcher.
- No change to the execution identity contract or route scopes.

## Execution rule

Every subtask runs on the current tree and has no dependency on another subtask. Each one makes the
changes its criteria need, keeps a change that is already present, and applies its rule to whatever
anchors exist where they now live. When two subtasks touch one file, whichever lands second keeps
both changes.

## Validation strategy

- The build phase runs the pack build command.
- The validate phase runs the pack validation gate and `./gradlew check`.
- Run Gradle from a local clone, not a linked `git worktree`.
- If spotless reports a stale JVM-local cache, rerun with `--no-configuration-cache`.

## Next path

```bash
skill-bill SKILL-425
```
