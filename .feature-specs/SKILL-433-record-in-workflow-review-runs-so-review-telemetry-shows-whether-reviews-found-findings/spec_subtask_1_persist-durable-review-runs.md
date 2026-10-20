# Subtask 1. Persist durable review runs so stats count them

## Scope

Durable `recordReviewRun` must write the same telemetry parent that standalone `phase review` already writes, then the goal ledger may attach unaddressed findings to that parent.

Production shape:

- Override `recordReviewRun` on `FeatureTaskRuntimeRunLoopDurableState` so durable and in-memory share `persistReviewPassClaims` and, when the register is non-empty, `saveImportedReview`. Keep the `PhaseRunState` default as the old no-op only if durable overrides it.
- Reuse `ImportedReview` / `ImportedFinding`. Map `ParallelCodeReviewResult.mergeResult.findings` into `ImportedFinding`. Do not add a third findings store. Do not reshape `InlineReviewEnvelope` unless that mapping cannot be done from `mergeResult.findings`.
- Persist order: parent first (`saveImportedReview` when findings are non-empty, else `persistReviewPassClaims` with `persistEmpty = true`, which `reserveReviewRun`s a zero-finding stub), then claims, then `unaddressed_findings`. `CodeReviewStep` already calls `recordReviewRun` before `completeReview`; keep that order.
- `DelegatedReviewPass.finishReview` already calls `persistReviewPassClaims`. A second call is idempotent today. Do not change that early-return.
- Empty-register passes appear as a `review_runs` row with 0 findings, matching standalone `phase review` NO_FINDINGS.
- Do not change `InlineReviewPass` admission, repair, or severity. Do not change Goal QA criterion display.
- Do not add a foreign key on `unaddressed_findings.review_run_id`. Reject an in-workflow `replaceLedgerForPass` whose `review_run_id` has no `review_runs` parent.

Assumption for implement to confirm: `FeatureTaskRuntimeRunLoopDurableState` can reach the same `persistReviewPassClaims` and `ReviewRepository.saveImportedReview` ports that `InMemoryPhaseRunState` uses. If it cannot, use those existing ports through the narrowest already-used seam. Do not invent a second store.

## Acceptance Criteria

1. Every completed durable feature-task or goal review pass that mints a `review_run_id` also has a `review_runs` parent row, and `findings` rows when the register is non-empty.
2. After that persist, `unaddressed_findings.review_run_id` for the pass references the existing `review_runs` row. In-workflow `replaceLedgerForPass` rejects a missing parent.
3. The durable persist path writes the `review_runs` and `findings` rows that `ReviewStatsRuntime.statsSnapshot` already reads, so a completed durable pass is included in `review_stats` without a run-id, including a zero-finding stub.

## Non-Goals

- Review-phase rows in `feature_task_phase_settlements`.
- Backfill or deletion of the 30 historical orphan `unaddressed_findings` rows.
- Work-detail JSON, telemetry proxy workflow stats, and remote org stats.
- Changing standalone `phase review` recording except matching it from durable review.
- Changing review admission, severity, repair policy, or Goal QA criterion display.

## Dependency Notes

None. This is the first subtask.

## Validation Strategy

The validate phase runs the pack validation gate and the tests listed under Test Obligations. Implement and audit do not compile, test, or run check.

## Test Obligations

1. A durable completed review with an empty register writes a `review_runs` parent, and `review_stats` without a run-id counts that pass as 0 findings. Bug: `FeatureTaskRuntimeRunLoopDurableState.recordReviewRun` stays a no-op, so empty durable passes never appear in stats. Extend the existing durable coverage in `PhaseReviewRunTest.kt` rather than adding a fixture-only `ReviewStatsRuntimeTest` sibling.
2. A durable completed review with a non-empty register writes `findings` rows for those findings, and `review_stats` without a run-id includes that count. Bug: only `reserveReviewRun` stubs an empty `review_runs` row, so stats still report 0 while the register had findings.
3. `replaceLedgerForPass` with a `review_run_id` that has no `review_runs` parent is rejected. Bug: `FeatureTaskRuntimeGoalReviewCompletionRecorder` still writes `unaddressed_findings` orphans. Add this case beside the existing keyed-outcome join in `UnaddressedFindingsRuntimeTest.kt`.
4. A completed durable goal review writes the `review_runs` parent before the ledger replace. Bug: `completeReview` still runs `replaceLedgerForPass` first, so the goal path recreates orphans even when the isolated reject case in obligation 3 passes. There is no existing test for `FeatureTaskRuntimeGoalReviewCompletionRecorder.completeGoalReviewPhase`.

Do not weaken `DatabaseMigrationsReviewAttributionTest` AC-009. Do not add a foreign-key sibling that would require deleting the 30 historical orphans.

## Implementation Details

Ordered tasks for implement. Validate runs the tests. Implement does not compile, execute tests, or run the pack gate.

### Task 1. Make durable `recordReviewRun` persist telemetry

Serves AC-1 and AC-3.

Override `recordReviewRun(reviewRunId: String, result: ParallelCodeReviewResult, laneTelemetryRecorded: Boolean)` on `FeatureTaskRuntimeRunLoopDurableState`. Today the type implements `PhaseRunState` and inherits the default `Unit` no-op, so `CodeReviewStep.recordAndSettle` records nothing on durable inline reviews. Keep that default on `PhaseRunState` as the documented no-op. Do not change `InlineReviewPass` admission, repair, severity, or `recordsLaneTelemetry`. Do not change `DelegatedReviewPass.finishReview`.

Paths and symbols:

- `runtime-kotlin/runtime-engine/src/main/kotlin/skillbill/engine/featuretask/runloop/durable/FeatureTaskRuntimeRunLoopDurableState.kt`
- `runtime-kotlin/runtime-engine/src/main/kotlin/skillbill/engine/featuretask/slot/state/PhaseRunState.kt`
- `runtime-kotlin/runtime-engine/src/main/kotlin/skillbill/engine/featuretask/slot/codereview/CodeReviewStep.kt` (`recordAndSettle` already calls `state.recordReviewRun` before settle; keep that call)
- `runtime-kotlin/runtime-engine/src/main/kotlin/skillbill/engine/featuretask/phaserun/InMemoryPhaseRunState.kt` (reference for `persistReviewPassClaims`; do not change its body)

Assumption for implement to confirm: durable state can reach the same `ParallelCodeReviewRunnerResultAssembly.persistReviewPassClaims` seam `InMemoryPhaseRunState` already calls, and the same `ReviewRepository.saveImportedReview` port `ReviewService.importReview` already uses. If it cannot, route through the narrowest already-used seam. Do not invent a second findings store.

Leave `InMemoryPhaseRunState` as `persistReviewPassClaims` only. Standalone `phase review` already records through that override plus `ReviewService.importReview`. Adding `saveImportedReview` there would change standalone recording, which is a non-goal.

Tests: Test Obligation 1 and 2 in `PhaseReviewRunTest.kt`.

### Task 2. Write the parent from the register, then claims

Serves AC-1 and AC-3.

Inside the durable override, persist from `result.mergeResult.findings`. Do not reshape `InlineReviewEnvelope` or reread `produced_outputs`.

Non-empty register:

1. Map `ParallelCodeReviewResult.mergeResult.findings` into `ImportedFinding` and call `ReviewRepository.saveImportedReview` with `ImportedReview(reviewRunId, reviewSessionId, rawText, routedSkill, detectedScope, detectedStack, executionMode, specialistReviews, findings, ...)`. That upserts `review_runs` and `replaceFindings`.
2. Then call `persistReviewPassClaims(reviewRunId, result.mergeResult.findings, persistEmpty = true)` so claims land after the parent.

Empty register:

1. Call `persistReviewPassClaims(reviewRunId, result.mergeResult.findings, persistEmpty = true)`. That `reserveReviewRun`s `INSERT OR IGNORE INTO review_runs (review_run_id, review_session_id, raw_text) VALUES (?, ?, '')`, which is the existing standalone NO_FINDINGS stub. Stats read `findings`, so a stub with no finding rows is a 0-count pass.

Prefer this order in the override. `saveImportedReview` upserts a full `review_runs` row; `reserveReviewRun` is `INSERT OR IGNORE` with empty `raw_text`. If claims run first, IGNORE keeps the stub. Delegated `finishReview` already calls `persistReviewPassClaims` and returns early when existing claims equal the union, so a second call from the override is safe. Do not change that early-return.

Assumption for implement to confirm: fields `ImportedReview` needs that `mergeResult` does not carry (session id, raw text, routed skill, scope, stack, execution mode, specialist reviews) use the same empty or already-populated values `reserveReviewRun` and `persistImportedReview` already accept for a stub or import. Do not invent new wire keys. Construct the typed `ImportedReview` rather than a payload map. If a key is required at a governed seam, use the owning `*Keys` object.

Paths and symbols:

- `runtime-kotlin/runtime-application/src/main/kotlin/skillbill/application/review/parallel/runner/ParallelCodeReviewRunnerResultAssembly.kt` (`persistReviewPassClaims`)
- `ReviewRepository.saveImportedReview` / `persistImportedReview` / `upsertReviewRun` / `replaceFindings`
- `ImportedReview`, `ImportedFinding`
- `runtime-kotlin/runtime-infra/sqlite/src/main/kotlin/skillbill/infrastructure/sqlite/review/stage/ReviewLaneAttribution.kt` (`reserveReviewRun`, `recordReviewPassClaims`)
- `runtime-kotlin/runtime-infra/sqlite/src/main/kotlin/skillbill/infrastructure/sqlite/review/stats/ReviewStatsRuntime.kt` and `ReviewFindingStats.kt` (read `findings`, not `unaddressed_findings`; do not change them)
- CLI `ReviewStatsCommand` and MCP `reviewStats` stay as `ReviewService.reviewStats`. Do not change the telemetry proxy or remote org stats.

Tests: Test Obligation 1 (empty stub counted as 0) and Test Obligation 2 (non-empty count) on a durable completed review in `PhaseReviewRunTest.kt`. Drive stats from that persist, not a new fixture-only `ReviewStatsRuntimeTest` sibling. Do not add tests in `ParallelCodeReviewRunnerTest.kt`. Its `recordReviewPassClaims` stub stays.

### Task 3. Reject in-workflow ledger rows without a parent

Serves AC-2.

`CodeReviewStep` already calls `recordReviewRun` before `completeReview`. Keep that order so Task 2 writes the parent in the same process before `FeatureTaskRuntimeGoalReviewCompletionRecorder.persistUnaddressedFindings` / `UnaddressedFindingsLedgerRuntime.replaceLedgerForPass`. Do not reorder `FeatureTaskRuntimeRunLoopStepBindings.completeReview` or `FeatureTaskRuntimeRunLoopReviewCompletion`.

`unaddressed_findings.review_run_id` is nullable and has no foreign key. `findings` already has `FOREIGN KEY (review_run_id) REFERENCES review_runs(review_run_id) ON DELETE CASCADE` with `PRAGMA foreign_keys = ON`. Do not add a foreign key on `unaddressed_findings.review_run_id`. That would fail migrate on operator databases that already hold the 30 orphans from 13–20 Oct 2026. Those leftovers belong to subtask 2.

At `replaceLedgerForPass`, reject a write whose `review_run_id` is non-null and has no `review_runs` row. Assumption for implement to confirm: fail loudly with an existing sqlite or review `RuntimeFailureCode` / `SkillBillRuntimeException` family, not a swallowed skip and not a new contract schema. Gate the writer so every in-workflow caller is covered, not only `completeGoalReviewPhase`.

Paths and symbols:

- `runtime-kotlin/runtime-engine/src/main/kotlin/skillbill/engine/featuretask/review/goal/FeatureTaskRuntimeGoalReviewCompletionRecorder.kt`
- `UnaddressedFindingsLedgerRuntime.replaceLedgerForPass`
- `FeatureTaskRuntimeRunLoopStepBindings.completeReview`, `FeatureTaskRuntimeRunLoopReviewCompletion.persistGoalReviewCompletion`
- `DatabaseSchemaStatements.kt` `CREATE TABLE unaddressed_findings` (leave schema as-is)

Tests: Test Obligation 3 in `UnaddressedFindingsRuntimeTest.kt` (dangling `review_run_id` rejected). Test Obligation 4: a completed durable goal review writes the `review_runs` parent before the ledger replace. There is no existing test for `FeatureTaskRuntimeGoalReviewCompletionRecorder.completeGoalReviewPhase`; add that parent-before-ledger case here. Settlement rows are subtask 2. Do not weaken `DatabaseMigrationsReviewAttributionTest` AC-009. Do not add a foreign-key assertion that would require deleting the 30 historical orphans. `UnaddressedFindingsLedgerServiceTest.kt` covers ledger reads and does not need a sibling unless the reject moves to that service.

### Constraints

- Reuse `review_runs`, `findings`, and pass claims. Do not invent a second findings store.
- Do not change review admission, severity, or repair policy. Do not change Goal QA criterion display.
- Do not change work-detail JSON, `orchestration/contracts/work-detail-schema.yaml`, `WorkDetailPayloadMapping.kt`, `WorkCliCommands.kt`, or IdeStatusService work-detail pagination.
- Do not teach the telemetry proxy `feature-task-runtime` stats or backfill remote org telemetry.
- No `//` or block comments in runtime-kotlin. KDoc only on interfaces and members.
- Wire keys stay in owning `*Keys` objects. Do not restate wire strings.
- Do not run pack validation, `./gradlew check`, or compile in implement or audit. Validate owns those commands.

## Next Path

```bash
skill-bill goal SKILL-433
```
