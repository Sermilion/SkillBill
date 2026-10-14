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

## Implementation Details

Paths are under `runtime-kotlin/`. `R-engine` is
`runtime-engine/src/main/kotlin/skillbill/engine`.

### Decisions settled in planning

- **Which runs plan first.** A full run plans first only when the key has no stored durable goal
  and no bundle on disk. A stored goal resumes with no planning (AC-6). A bundle on disk with no
  stored goal goes to `goalRunner.run` and is imported as today (AC-7), even if an incomplete plan
  workflow exists, because `incompletePlanWorkflowId` already returns null when a manifest exists.
  Running hand-written bundles through `plan_subtasks` is parent AC-7 work owned elsewhere. This
  subtask does not touch `PlanDecompositionStop` or `StandalonePlanRun.settleExistingManifest`.
- **Open or resume.** The CLI does not choose between the two. `StandalonePlanRun.run` already
  resumes an incomplete plan workflow and opens one otherwise, so the CLI calls `planLauncher.run`
  once for both AC-1 and AC-4.
- **Admission first.** `goalRunner.admitIntake` still runs before any planning, so a new key with
  no requirements gets the needs-input response before the plan skeleton is touched (AC-8).
- **Plan link.** `WorkflowGoalRunnerManifestLoader.importFromManifestProjection` already links the
  completed plan workflow to the new goal parent (`findCompletedPlanWorkflowId`,
  `importedPlanStepUpdates`, `withImportedPlan`). AC-2 adds no new linking code.
- **Intake hand-off.** If subtask 1's intake slot already exists on the tree, pass the intake to it
  through `StandalonePlanRun`'s existing input. Otherwise keep `seedPlanSpec` inside
  `StandalonePlanRun.open`. Either way, `StandalonePlanRun` needs no new branch for this subtask.
- **`FeatureSpecPreparationWriter.write`.** Following the Dependency Notes, leave `write` and its
  helpers in place even if this subtask removes its last caller. This subtask does not edit
  `FeatureSpecPreparationWriter.kt`.
- **Keep the name `prepare`.** `GoalIntakePreparation.prepare` keeps its name and becomes a read of
  the on-disk bundle. This keeps edits small in a file that subtask 1 also changes.

### Tasks

1. **Strip the one-subtask writer** (AC-5). In `R-engine/goalrunner/intake/GoalIntakePreparation.kt`:
   - Reduce `prepare(request)` so it returns the bundle already on disk as a
     `GoalRunnerManifestState`, or null when there is none.
   - Delete the `existingSpecPath` / `hasAcceptanceList` / `asProse` branch, the
     `specWriter.write(...)` call with its `SINGLE_SPEC` decision, and the hard-coded "Implement the
     requested change" subtask.
   - Delete the private helpers that only that path reached, likely `acceptanceCriteria`,
     `hasAcceptanceList`, `asProse` and `TRACKER_RESOLUTION`. Keep every helper that `seedPlanSpec`
     or admission still uses (`existingSpecPath`, `referencedSpecPath`, `newWorkFeatureName`,
     `newWorkGap`, `VALIDATION`, `issueKeyForExistingSpec`, `issueKeyOf`, `missingNewWorkInput`).
   - Drop constructor dependencies that no remaining code uses. `specWriter` stays, because
     `seedPlanSpec` calls `writeParentSpecOnly`.
   - Implement confirms each helper's remaining callers before deleting it.
2. **Expose a planning check on the goal runner** (AC-1, AC-6, AC-7). Add to
   `R-engine/goalrunner/GoalRunner.kt`:

   ```kotlin
   fun requiresPlanning(request: GoalRunnerRunRequest): Boolean
   ```

   It returns true when `manifestStore.loadDurableByIssueKey(...)` finds no stored goal and
   `intakePreparation.prepare(request)` finds no bundle on disk. Use the same arguments that
   `run()` passes today, so the check and `run()` cannot disagree.
   - `run()` keeps `loadDurableByIssueKey ?: intakePreparation.prepare(request) ?: unknownGoal(...)`.
   - With the writer gone, a direct `run()` on a key with neither a goal nor a bundle returns
     `unknownGoal`. That is intended, because the CLI plans before calling `run`.
3. **Plan before the goal in the CLI** (AC-1 to AC-4, AC-6 to AC-8). In
   `runtime-cli/.../cli/goal/core/GoalCliCommands.kt`, `GoalRunCommand.run()`:
   - Replace `finishIncompletePlan` with a step that, after `admitIntake` produces `runIssueKey`,
     checks `goalRunner.requiresPlanning(request)`.
   - When it is true, call `planLauncher.run(runIssueKey, intake, repoRoot, agentId, inputFor)`.
     Use the same arguments and timeout that `finishIncompletePlan` uses today
     (`resumedPlanTimeout()`, renamed to `planTimeout()`).
   - On `Completed`, continue to `goalRunner.run(request.copy(intake = intake))`.
   - On `Blocked` or `Refused`, print `planReportText` (block reason plus plan workflow id) and
     exit 1 without calling `goalRunner.run`. This is today's non-`Completed` handling.
   - When it is false, call `goalRunner.run` directly.
4. **Remove the incomplete-only lookup** (AC-5's third bullet in Scope). Once the CLI stops calling
   `incompletePlanWorkflowId`, remove it from
   `runtime-cli/.../kernel/plan/StandalonePlanLauncher.kt` and from
   `R-engine/goalrunner/plan/StandalonePlanRun.kt`, but only if nothing else calls it. Implement
   confirms there is no other caller first. Otherwise leave both.
5. **Tests** (AC-9). Extend the existing `GoalRunCommand` tests that cover `finishIncompletePlan`;
   implement locates them under `runtime-cli/src/test`. Use the fakes or real components those
   tests already use. Extend `GoalIntakePreparationTest` and `GoalRunnerTest`. Write one test per
   rule:
   - CLI, new key with intake and no bundle: the plan launcher runs with the supplied intake before
     the goal runner, and a `Completed` plan leads to `goalRunner.run` (AC-1, AC-2 ordering). Bug
     caught: the full run skips planning and executes nothing, or executes before planning.
   - CLI, plan `Blocked`: exit 1, output names the block reason and plan workflow id, and the goal
     runner is not called (AC-3).
   - CLI, incomplete plan workflow with no bundle: the launcher is called and resumes that
     workflow id (AC-4). Adapt today's incomplete-plan test rather than adding a sibling.
   - CLI or goal runner, stored goal: the plan launcher is not called and the goal resumes (AC-6).
     Bug caught: re-planning a running goal.
   - CLI or goal runner, bundle on disk with no stored goal: the plan launcher is not called and
     the bundle is imported (AC-7). Bug caught: the run is refused with "decomposition manifest
     already exists".
   - CLI, new key with no requirements: the needs-input response is returned and the plan launcher
     is not called (AC-8). Extend the existing admission test with that assertion.
   - `GoalIntakePreparationTest`: `prepare` on a key with an intake but no bundle returns null and
     writes no manifest and no subtask spec under `.feature-specs` (AC-5). Delete the tests for the
     one-subtask path and the prose fallback.
   - `GoalRunnerTest`: with a completed plan workflow and its bundle on disk, `run` creates a goal
     parent whose plan-workflow link (`planWorkflowId` / `plan_workflow` artifact) names that
     workflow (AC-2). If the fixtures cannot build a completed plan workflow, assert the link at
     the CLI level instead.

### Constraints

- No line comments. KDoc goes only on interfaces.
- No change to `PhaseCommand` or `runDurablePlan`, to the plan skeleton's slots and strategies, to
  `StandalonePlanPreparation.capture`, to `PlanDecompositionStop`, or to goal execution after
  import.
- Do not edit `FeatureSpecPreparationWriter.kt`.
- Implement and audit run no builds or tests. The build and validate phases own them.

### Assumptions for implement to confirm

- `GoalIntakePreparation.prepare`'s first branch reads the bundle on disk through
  `fileStore: DecompositionManifestStore`. The durable goal lookup is `GoalRunner`'s
  `manifestStore.loadDurableByIssueKey`.
- `skill-bill <KEY>` and `skill-bill goal ...` both enter through `GoalRunCommand.run()`. If
  another entry point calls `goalRunner.run` for a new key, it must call `requiresPlanning` and
  the plan launcher the same way.
- `planReportText` already includes the plan workflow id for `Blocked` results. If it does not, add
  the id there in `StandalonePlanLauncher`.
