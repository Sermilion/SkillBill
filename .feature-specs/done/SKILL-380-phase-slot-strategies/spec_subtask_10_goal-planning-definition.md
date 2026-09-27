# SKILL-380 Subtask 10 - Goal planning runs the goal-planning definition

Parent spec: [spec.md](spec.md)
Issue key: SKILL-380

## Scope

The goal planning sweep (`skillbill.engine.goalrunner.planning`, about 4,500 lines) runs
preplan and plan outside the run loop and launches them itself
(`GoalPlanningPhaseAttemptGateLaunch` → `GoalRunnerSubtaskLauncher`). That is a second
way to run a phase. After this subtask, goal planning is a skeleton run like every other.

**Definition `goal-planning` (runtime-domain):** the `preplan` and `plan` slots.

**Strategies (selection entries for `goal-planning`):**
- `preplan` → `agent-preplan`, run once per goal with goal-scope facts: the shared
  context packet and the included subtasks. Its value is today's shared preplan.
- `plan` → `goal-plan-fan-out`, a new `plan` strategy that runs the `agent-plan` plan
  step once per active subtask, each through its own `PhaseRunner` instance, with
  per-subtask facts and the shared preplan as input. It reuses the `agent-plan` step
  class and directive; it adds only the fan-out: today's plan waves
  (`GoalPlanningPlanWaveDispatch`), burst caps and schedule, attempt gate, and planning
  budget, moved unchanged.

**State.** A third `PhaseRunState` implementation, the goal-planning state, over today's
stores: the shared preplan checkpoint and its provenance, per-subtask plan records, the
planning attempt log, the rejection recorder, and refresh liveness. It supports today's
planning recovery and resume. It writes no `feature_task_workflows` row, exactly as the
sweep does today.

**Entry.** `DefaultGoalPlanningSweep.prepare` keeps its signature and outcome type. It
builds the `goal-planning` definition, the goal-planning state, and the facts, calls the
run loop, and maps the loop's result to today's `GoalPlanningSweepOutcome` (prepared
all, stopped, halted). Shared-context gathering, provenance checks, and child hydration
(`GoalChildPlanningHydrator`) stay where they are.

**Guard.** Extend the subtask 5 launch-port rule to `skillbill.engine.goalrunner.planning`:
no class there depends on `GoalRunnerSubtaskLauncher`.

## Verification ownership

Implement and audit can't run builds, tests, generators, or the fixture capture
(added 2026-09-27). Audit checks each behavioural criterion against the code and the
tests that assert it, and does not list a criterion as remaining just because nothing
has run.
- Validate runs the capture command
  `cd runtime-kotlin && SKILL_BILL_SLOTBASELINE_CAPTURE=1 ./gradlew :runtime-engine:test --tests skillbill.engine.featuretask.slotbaseline.SlotBaselineCaptureTest`,
  confirms the goal-planning fixtures are byte-identical, then runs `./gradlew check`.

## Acceptance Criteria

1. Goal planning runs the `goal-planning` definition through the one run loop. No class under `skillbill.engine.goalrunner.planning` depends on `GoalRunnerSubtaskLauncher`, and the extended guard fails on a synthetic violation.
2. The shared preplan runs once per goal through `agent-preplan`, and each active subtask's plan runs through `goal-plan-fan-out`, which composes the `agent-plan` step without copying it.
3. This subtask's diff changes no goal-planning fixture file (composed prompts, shared preplan checkpoint, plan records, planning attempt log, `skill-bill goal planning-log` output), and `SlotBaselineFixtureTest` compares each of them.
4. Wave order, burst caps, the attempt gate, and the planning budget keep their behaviour: the existing goal planning suites lose no assertion and are not weakened.
5. Tests assert that planning recovery and resume after an interrupted sweep behave as before, and that goal children still hydrate their preplan and plan records.

## Non-goals

- Changing goal planning's shared-preplan design, wave policy, provenance, or hydration.
- Other goal-runner code (SKILL-378.3 territory).
- A durable resume model for other phase runs.

## Dependency notes

- Depends on subtask 9 (the `plan` definition and its selection entries) and subtask 7
  (the loop runs over `PhaseRunState`). Recheck the sweep's anchors at start; SKILL-378.3
  may have moved them.

## Validation strategy

Catch: a planning launch outside `PhaseRunner`; the shared preplan running per subtask;
a wave or burst-cap change; a recovery path lost; prompt bytes changing. Cover with the
guard, the goal planning suites over real SQLite, the prompt and record fixtures, and a
resume test after an interrupted wave. Run `cd runtime-kotlin && ./gradlew check` plus
the engine, core, and infra-sqlite suites. Run `bill-unit-test-value-check` on changed
tests.

## Next path

Continue to `spec_subtask_11_pr-and-history-rules.md`.

## Spec Path

.feature-specs/SKILL-380-phase-slot-strategies/spec_subtask_10_goal-planning-definition.md
