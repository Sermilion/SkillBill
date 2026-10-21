# SKILL-433 Record in-workflow review runs so review telemetry shows whether reviews found findings

## Mode

decomposed

## Intended Outcome

Durable feature-task and goal review passes that mint a `review_run_id` show up in review telemetry the same way a recorded standalone review does. `skill-bill stats` and MCP `review_stats` count the pass and its findings, including zero. `feature_task_phase_settlements` records completed review phases. An operator asking whether reviews found anything can answer from stats, not by querying `unaddressed_findings`.

## Scope

From 13-20 Oct 2026, durable feature-task and goal `review` passes produced findings, and `skill-bill stats` / MCP `review_stats` reported zero reviews and zero findings.

Observed on `~/.skill-bill/review-metrics.db` after that week:

- `unaddressed_findings` has 30 rows across 10 `review_run_id`s (`rvw-20261016` through `rvw-20261019`) and 7 issues (SKILL-419, SKILL-431, SKILL-422, SKILL-428, 0AC-54, 0AC-63, 0AC-65). All 30 have non-null `review_run_id` and `finding_id`. Severity: 27 minor, 3 nit.
- Those 10 ids are absent from `review_runs`. The only `review_runs` rows were two standalone imports from 20 Oct, both with 0 findings.
- `feature_task_phase_settlements` had 35 `verify_findings` rows and 0 `review` rows, even though `review` is in `agentSettledPhaseIds`.

`PhaseRunState.recordReviewRun` defaults to a no-op. `FeatureTaskRuntimeRunLoopDurableState` does not override it. Durable reviews still write `unaddressed_findings` through the goal review ledger, so ledger rows exist without a `review_runs` parent. Stats read the `findings` table, not `unaddressed_findings`. `CodeReviewStep.complete` does not call `FeatureTaskPhaseSettlementService.complete`.

This feature records new durable review passes into the existing `review_runs`, `findings`, and pass-claims store, records review-phase settlements in `feature_task_phase_settlements`, and names the 30 historical ledger orphans as unrestorable leftovers. Work-detail JSON stays unchanged.

Subtask 1 records new durable passes so stats count them and the ledger cannot land without a parent. Subtask 2 writes review settlements, applies the leftover policy, and leaves work-detail files untouched.

Affected areas: runtime-kotlin review persistence (`CodeReviewStep`, `PhaseRunState.recordReviewRun`, `ParallelCodeReviewRunner`, UnaddressedFindings ledger), sqlite `review_runs` / `findings` / `unaddressed_findings`, `review_stats` aggregation, `feature_task_phase_settlements` for the review phase.

## Acceptance Criteria

1. Every completed durable feature-task or goal review pass that mints a `review_run_id` has a `review_runs` parent row, and `findings` rows when the register is non-empty, so `skill-bill stats` and MCP `review_stats` count that pass. Empty-register passes appear as a run with 0 findings.
2. New `unaddressed_findings.review_run_id` values always reference an existing `review_runs` row.
3. Completed durable review phases have rows in `feature_task_phase_settlements` for the review phase.
4. The 30 orphan `unaddressed_findings` rows from 13-20 Oct 2026 are not deleted. They are unrestorable leftovers. Stats still exclude them because they have no `review_runs` or `findings` parents.
5. Work-detail JSON is unchanged. Remote proxy workflow support and stats auth stay out of scope.

## Constraints

- Reuse `review_runs`, `findings`, and pass claims. Do not invent a second findings store.
- Do not change review admission, severity, or repair policy.
- No `//` or block comments in runtime-kotlin. KDoc only on interfaces and members.
- Wire keys stay in owning `*Keys` objects.
- Do not add a foreign key on `unaddressed_findings.review_run_id`. That would fail migrate on operator databases that already hold the 30 orphans.
- Supplied requirements in this bundle are authoritative. Linear `get_issue` for SKILL-433 was not found in the connected workspace. There are no tracker comments.

## Non-Goals

- Teaching the telemetry proxy `feature-task-runtime` stats.
- Backfilling remote org telemetry.
- Changing standalone `phase review` except where durable review must match recording it already does.
- Changing Goal QA criterion display.
- Inventing empty `review_runs` parents for the 10 historical orphan ids.
- Deleting the 30 historical `unaddressed_findings` rows.

## Dependency Notes

Subtask 1 lands recording and the ledger parent invariant. Subtask 2 depends on it: settlement runs after a successful durable persist, leftover policy must not fight that write path, and the work-detail freeze applies to the same feature.

## Validation Strategy

The validate phase runs the pack validation gate. Implement and audit do not compile, test, or run check. Required tests live on the subtask specs under Test Obligations.

## Next Path

```bash
skill-bill goal SKILL-433
```
