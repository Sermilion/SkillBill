# SKILL-409: Goal purge removes all runtime state except the spec

## Problem

`skill-bill goal purge <issue-key>` is documented as "Delete decomposed goal runtime state and
restore the feature-spec tree to an unlaunched shape". A purge of goal WE-5006 (parent workflow
`wftr-20261008-142052-s1rk`, subtask workflow `wftr-20261008-142301-1jr1`, repository identity
`repo-root-realpath-v1:/Users/braian.gapur/IdeaProjects/webapp`) left almost every piece of
runtime state in place:

- `goal status` and `goal planning-log` still reported the goal and both planning attempts.
- `review-metrics.db` still held WE-5006 rows in the goal tables, the planning tables,
  `feature_task_workflows`, `feature_task_runtime_sessions`, `feature_task_execution_identities`,
  `worktree_edit_journal`, `producer_output_evidence`, `rejected_output_diagnostics` and
  `agent_activity_stamps`.
- `<repo>/.skill-bill/feature-task-tracking/<workflow-id>/` and
  `<repo>/.skill-bill/run-evidence/<workflow-id>/` remained.
- `decomposition-manifest.yaml` kept `status: in_progress`, `workflow_id`,
  `last_resumable_step` and `current_subtask_intent`, and the generated `spec_subtask_1_*.md`
  remained.

The next full-run launch then failed at `seam=goal_planning_migration` with `unsafe_import`.

## Root-cause findings (from preplan)

The purge chain is `GoalPurgeCommand` → `GoalRunnerStatusService.purge` →
`GoalRunnerPurgeCoordinator.purge` → `WorkflowGoalRunnerManifestStore.purgeDecomposedGoal` →
`SQLiteUnitOfWork.purgeDecomposedGoal`. Its defects:

1. `SQLiteUnitOfWork.purgeDecomposedGoal` never touches `feature_task_runtime_sessions`,
   `worktree_edit_journal`, `producer_output_evidence`, `rejected_output_diagnostics` or
   `agent_activity_stamps`. Leases and execution identities rely on `ON DELETE CASCADE`, which
   only works when `PRAGMA foreign_keys` is on. Nobody has verified that it is.
2. The coordinator never deletes repo-local `feature-task-tracking` or `run-evidence`
   directories.
3. `loadDurableByIssueKey(issueKey)` runs without the repo root, so parent discovery is not
   repo-scoped. It also silently skips rows whose `decompositionRuntime()` fails to decode, and
   in that case the DB step is skipped with no message.
4. Spec restore only writes *missing* files, so an existing generated `spec_subtask_N_*.md`
   survives.
5. The result discards counts (the git checkpoint prune count is dropped with `record = {}`). It
   reports success whenever nothing was refused, so leftovers are invisible.
6. A second purge with nothing left hits `missingPurgeResult`, sets `refusalReason` and exits 1.
   That breaks idempotence.
7. The purge refusal for LIVE/UNKNOWN liveness fits the observed fully intact state best. Nobody
   has confirmed which liveness a paused `operator_stop` goal resolves to.

**Secondary (same-version `unsafe_import`).** Same-version records are already accepted as-is:
when no record is historical, `GoalPlanningMigration` takes the `validateCurrent` path and returns
CURRENT. The reported `unsafe_import` can only come from the historical branch, most likely
`validateTopology`'s provenance comparison. A clean purge makes that state unreachable, because
admission then finds no shared preplan and no plans. This task leaves migration semantics
unchanged. If the seam diagnostic should name the failing check, that belongs in a separate issue.

## Decisions

- **Ownership.** The goal owns its parent workflow (discovered by issue key *and* repository
  identity) and every child workflow linked to that parent, plus any subtask `workflow_id`
  recorded in the goal's manifest. Purge must not silently skip a row that matches the issue key
  and repository identity but fails to decode. Purge either removes that row or reports it as a
  leftover.
- **Order.** Collect owned ids, then delete repo-local directories, then run the DB transaction,
  then reset the spec bundle, then verify. Every partial failure stays re-runnable because the
  ids remain discoverable until the last step.
- **Runtime sessions.** Delete a `feature_task_runtime_sessions` row only when no surviving
  workflow references it.
- **Leases and execution identities.** Delete them explicitly. Do not rely on FK cascade.
- **Spec bundle.** `spec.md` (the manifest's parent spec path) is never written, restored or
  deleted. If the manifest is untracked at HEAD, the launch generated it: purge deletes the
  manifest and every untracked subtask spec the manifest names. A fresh `skill-bill <spec.md>`
  launch regenerates both, as the WE-5006 run did. If the manifest is tracked at HEAD (a
  pre-authored bundle), purge keeps today's hard reset. The written YAML carries no runtime
  fields, and tracked subtask specs keep today's restore-missing-from-HEAD behaviour.
- **Telemetry outbox.** Purge keeps `telemetry_outbox` rows as anonymised history. Relaunch does
  not read them. The purge help text and purge output say so. Purge does not emit a new
  telemetry event.
- **Idempotence.** A purge that finds nothing owned exits 0 and reports "nothing to remove".
- **Reporting.** The result and CLI payload carry per-store removed counts, removed paths,
  spec-bundle actions and leftovers. Any leftover means exit code 1.
- **Liveness.** The refusal policy for genuinely LIVE goals is unchanged. A goal paused with
  `operator_stop` and no live lease holder must be purgeable.

## Acceptance Criteria

1. Goal purge deletes every goal-owned row, for the parent and every child workflow, from the
   goal tables, the planning tables, `feature_task_workflows`, `feature_task_runtime_sessions`
   (when no surviving workflow references them), leases, `feature_task_execution_identities`,
   `worktree_edit_journal`, `producer_output_evidence`, `rejected_output_diagnostics` and
   `agent_activity_stamps`. It also deletes the repo-local `feature-task-tracking` and
   `run-evidence` directories of those workflows.
2. Purge leaves `spec.md` byte-for-byte unchanged and resets the generated planning artifacts to
   the unlaunched shape described in Decisions.
3. Purge discovers ownership with the issue key and the repository identity of the repo root, so
   other goals, other repository identities and unrelated workflows are untouched.
4. A second purge on purged or partially purged state succeeds and reports nothing left, or
   removes what remained.
5. Purge output lists per-store counts, removed paths and leftovers. Purge exits non-zero when
   any owned state survives.
6. The purge help text documents that `telemetry_outbox` rows are retained.
7. Tests cover the WE-5006 regression shape, relaunch without a planning-migration refusal,
   isolation from other goals and other repository identities, and leftover reporting with a
   non-zero exit.

## Non-Goals

- Changing `GoalPlanningMigration` conversion semantics or the seam diagnostic text.
- Emitting a new telemetry event for purge, or deleting `telemetry_outbox` rows.
- Changing the LIVE-goal refusal policy.
- Cleaning git branches, worktrees or checkpoints beyond what purge already does.

## Subtasks

1. `spec_subtask_1_purge-all-goal-owned-state.md`: the whole fix in one commit. The DB coverage,
   directory deletion, ownership scoping, spec-bundle reset, reporting and tests only make sense
   together. A partial commit would report counts for stores it does not yet purge.

## Next Path

```bash
skill-bill goal SKILL-409
```
