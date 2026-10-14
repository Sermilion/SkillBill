# SKILL-425 Subtask 2 - Full run plans first

Parent spec: [spec.md](./spec.md). Read its Model, Decisions and Execution rule first.
Issue key: SKILL-425

## Scope

Make the full run plan through the `plan` skeleton before it executes. A full run
(`skill-bill <KEY> [intake]`, `skill-bill goal ...`) on a key with no stored goal runs the plan
skeleton with the intake. It opens a new plan workflow or resumes the existing one, as
`skill-bill phase plan` does today, then imports the bundle and runs the goal in the same invocation.
A plan that blocks stops the invocation with the plan's block reason, the plan workflow id and
exit 1, as `finishIncompletePlan` does today.

Remove the code that writes a bundle outside the skeleton:

- the one-subtask writer path in `GoalIntakePreparation.prepare` (the "Implement the requested
  change" subtask and its manifest);
- the prose fallback added in commit `9e5f26d30` to that path (`hasAcceptanceList`, `asProse`), since
  intake handling belongs to the intake step of the plan skeleton;
- the special case that runs the plan skeleton only for an incomplete plan
  (`GoalRunCommand.finishIncompletePlan` returning early when `incompletePlanWorkflowId` is null).

Keep these as they are:

- A stored goal resumes without planning.
- A bundle on disk with no stored goal is imported, as today.
- Admission still asks for requirements when a new key has none (`GoalRunner.admitIntake`,
  `GoalIntakeMissingInput`).

Owned paths (elided segments are `...`):

- `runtime-cli/.../goal/core/GoalCliCommands.kt`
- `runtime-cli/.../kernel/plan/StandalonePlanLauncher.kt`
- `runtime-engine/.../goalrunner/GoalRunner.kt`
- `runtime-engine/.../goalrunner/intake/GoalIntakePreparation.kt`
- `runtime-engine/.../goalrunner/plan/StandalonePlanRun.kt`
- `runtime-engine/.../featuretask/prepare/FeatureSpecPreparationWriter.kt` (only callers that this removes)

## Acceptance Criteria

1. A full run on a key with no stored goal and no bundle runs the `plan` skeleton with the supplied intake before any goal execution.
2. When that plan completes, the same invocation imports the bundle and runs the goal, and the goal parent links the plan workflow.
3. When that plan blocks, the invocation exits 1 with the plan's block reason and plan workflow id.
4. A full run on a key with an incomplete plan workflow resumes it, as today.
5. No full-run code path writes a manifest or subtask spec outside a skeleton: the one-subtask writer path in `GoalIntakePreparation.prepare` and its prose fallback are removed.
6. A full run on a key with a stored goal resumes the goal without running the plan skeleton.
7. A full run on a key whose bundle is on disk with no stored goal imports it as today.
8. A new key with no requirements still gets the needs-input response from admission.
9. Tests cover criteria 1 to 8 at the goal-runner or CLI level.

## Non-Goals

- No change to what the plan skeleton's slots do. Subtasks 1 and 3 own them.
- No change to goal execution after import.
- No change to dispatcher issue resolution.

## Dependency Notes

None. This subtask runs on the current tree. It runs whatever the `plan` skeleton is when it lands,
with two or three slots. If the intake step already exists, pass the intake to it. Otherwise keep
today's `seedPlanSpec` call inside `StandalonePlanRun`. If `FeatureSpecPreparationWriter.write` has
no other caller once the one-subtask path is removed, leave it for the subtask that removes its last
caller.

## Validation Strategy

- The build phase runs the pack build command.
- The validate phase runs the pack validation gate and `./gradlew check`.
- Implement and audit do not run builds or tests.

## Next Path

Complete the goal and prepare its pull request.
