# Subtask 2. Settle review phases and name leftover orphans

## Scope

After subtask 1's durable persist exists, completed durable `review` phases must leave the same `feature_task_phase_settlements` trail as `implement`, `audit`, and `verify_findings`. Historical ledger orphans from 13-20 Oct 2026 stay in place as unrestorable leftovers. Work-detail JSON stays unchanged.

Production shape:

- After a successful durable review persist, call `FeatureTaskPhaseSettlementService.complete` internally with `phaseId` `FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_REVIEW`, attempt equal to the step iteration, and `value` from the envelope summary. `CodeReviewStep.complete` currently returns `PhaseOutcome.completed` without that call.
- Do not add a second settlement table. Do not change `agentSettledPhaseIds`. Do not require an agent MCP `featureTaskPhaseComplete` call for runtime-owned review.
- Leftover policy, settled from preplan evidence: the 30 `unaddressed_findings` rows from 13-20 Oct 2026 are unrestorable leftovers. Their 10 `review_run_id`s exist only on that ledger. Inventing empty `review_runs` parents would make stats report 0 findings for runs whose ledger has 30 findings. Deleting the ledger rows is forbidden. Leave the rows in place. Stats keep reading `findings`, so they exclude the leftovers honestly. Do not add a foreign key on `unaddressed_findings.review_run_id`.
- Do not edit `orchestration/contracts/work-detail-schema.yaml`, `WorkDetailPayloadMapping.kt`, `WorkCliCommands.kt`, or IdeStatusService work-detail pagination. Remote proxy workflow support and stats auth stay out of scope. They are not needed for local `review_stats`.

Assumption for implement to confirm: `FeatureTaskPhaseSettlementService.complete` is reachable from the durable review complete path without a new settlement type. If that call would create a dependency cycle, use the narrowest already-used settlement port and keep writing `feature_task_phase_settlements`.

Assumption for leftover artifacts: `GOAL_SUBTASK_REVIEW_RESULTS` stores `rawReviewResult` keyed by pass number, and `retainReviewOutput` writes producer output evidence, but those artifacts are not confirmed to contain the 10 historical `review_run_id`s in a form that maps to `findings` rows. Default is no backfill. If implement finds retained artifacts for those exact ids and can map them to `ImportedFinding` rows, it may backfill real `review_runs` plus `findings` instead of leaving leftovers. Empty stub parents are still forbidden.

This spec does not freeze production wiring, test setup, formatting, or lint repairs outside the work-detail files named above.

## Acceptance Criteria

1. Completed durable review phases have a `feature_task_phase_settlements` row for the review phase, written internally after a successful durable persist.
2. The 30 existing orphan `unaddressed_findings` rows from 13-20 Oct 2026 across 10 `review_run_id`s (`rvw-20261016` through `rvw-20261019`) and issues SKILL-419, SKILL-431, SKILL-422, SKILL-428, 0AC-54, 0AC-63, 0AC-65 remain stored as unrestorable leftovers. No empty `review_runs` parents are invented for them. Stats continue to exclude them. No foreign key is added on `unaddressed_findings.review_run_id`.
3. Work-detail JSON is unchanged: `orchestration/contracts/work-detail-schema.yaml`, `WorkDetailPayloadMapping.kt`, `WorkCliCommands.kt`, and IdeStatusService work-detail pagination are not edited for this feature.

## Non-Goals

- Teaching the telemetry proxy `feature-task-runtime` stats or backfilling remote org telemetry.
- Changing standalone `phase review` beyond matching its recording.
- Changing Goal QA criterion display, review admission, severity, or repair policy.
- Inventing a second findings store or a second settlement table.
- Silently dropping the 30 historical orphan rows, or adding a schema foreign key that would fail migrate on databases that still hold them.

## Dependency Notes

Depends on subtask 1. Settlement is after a successful durable review persist. Leftover policy must not add a foreign key or empty-parent backfill that fights subtask 1's write-path reject.

## Validation Strategy

The validate phase runs the pack validation gate and the tests listed under Test Obligations. Implement and audit do not compile, test, or run check. Audit inspects the named work-detail files for this feature's diff; later review or validate repairs may still touch production wiring, tests, formatting, or lint outside those files.

## Test Obligations

1. After a durable review completes, `feature_task_phase_settlements` contains a row for phase `review`. Bug: `CodeReviewStep.complete` still skips `FeatureTaskPhaseSettlementService.complete`, so completed review passes leave zero settlement rows. Drive this from durable completion, not a second isolated `complete()` insert in `SqliteFeatureTaskPhaseSettlementRepositoryTest`.
2. Starting from a store that already has `unaddressed_findings` rows whose `review_run_id` is absent from `review_runs`, migrate and startup leave those rows in place. Bug: a schema change adds a foreign key or a cleanup delete and drops the historical leftovers.

Work-detail unchanged has no test. Audit reads the named files. `ReviewStatsRuntime` already ignores `unaddressed_findings`; do not add a sibling that only re-asserts that SQL.

## Implementation Details

Subtask 1 already makes durable `recordReviewRun` write `review_runs`, `findings` when the register is non-empty, and pass claims, and rejects a ledger write whose parent is missing. This subtask starts after that persist. Order: successful durable persist, then settlement, then leave historical leftovers untouched. Do not run install, uninstall, or install-sync.

### Task 1. Write a review-phase settlement after durable persist

Serves: Acceptance Criterion 1 (parent AC-4).

`review` is already in `FeatureTaskRuntimePhaseWorkflowDefinition.agentSettledPhaseIds` (`definition.stepIds` minus `runtimeSettledPhaseIds` of `intake`, `commit_push`, `monitor`, plus `PHASE_QA`). `FeatureTaskPhaseSettlementService.isSettleablePhase` uses that set. `verify_findings`, `implement`, and `audit` settle through `runAgentStep` plus MCP `featureTaskPhaseComplete`. Review is runtime-owned: `CodeReviewStep.complete` returns `PhaseOutcome.completed` without calling `FeatureTaskPhaseSettlementService.complete`. `persistGoalReviewCompletion` and `persistStandaloneReviewCompletion` write workflow phase records and the unaddressed ledger, not `feature_task_phase_settlements`.

After a successful durable review persist, call `FeatureTaskPhaseSettlementService.complete` internally with `FeatureTaskPhaseSettlementCompleteRequest(workflowId, phaseId, attempt, value, prompt, summary, verdict, noChange)`:
- `phaseId` = `FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_REVIEW`
- `attempt` = the step iteration
- `value` from the envelope summary

Store writer: `SqliteFeatureTaskPhaseSettlementStore` INSERT INTO `feature_task_phase_settlements`. Reuse that service. Do not add a second settlement table. Do not change `agentSettledPhaseIds`. Do not require an agent MCP call. The MCP handler in `McpFeatureTaskSettlementHandlers.kt` stays the agent path for other phases.

Assumption (Scope): `FeatureTaskPhaseSettlementService.complete` is reachable from the durable review complete path without a new settlement type. If that call would create a dependency cycle, use the narrowest already-used settlement port and keep writing `feature_task_phase_settlements`.

Assumption: settlement is for durable feature-task and goal review only. Standalone `phase review` is not a feature-task workflow phase and already records through `InMemoryPhaseRunState`; do not insert `feature_task_phase_settlements` rows for it.

Assumption for implement to confirm: `workflowId`, `attempt`, `prompt`, `summary`, `verdict`, and `noChange` are available on the durable complete path (`CodeReviewStep.complete` / `completeReview` / `persistGoalReviewCompletion`). If a field is missing, take it from the same envelope `InlineReviewEnvelope.assemble` already puts on `produced_outputs`, without reshaping that envelope.

Paths and symbols:
- `runtime-kotlin/runtime-engine/src/main/kotlin/skillbill/engine/featuretask/slot/codereview/CodeReviewStep.kt` (`complete`, `recordAndSettle`)
- `runtime-kotlin/runtime-engine/src/main/kotlin/skillbill/engine/featuretask/runloop/state/FeatureTaskRuntimeRunLoopStepBindings.kt` (`completeReview`)
- `runtime-kotlin/runtime-engine/src/main/kotlin/skillbill/engine/featuretask/review/goal/FeatureTaskRuntimeGoalReviewCompletionRecorder.kt` (`persistGoalReviewCompletion` / `completeGoalReviewPhase`)
- `FeatureTaskPhaseSettlementService.complete`, `FeatureTaskPhaseSettlementCompleteRequest`
- `FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_REVIEW`, `agentSettledPhaseIds`
- `SqliteFeatureTaskPhaseSettlementStore`
- `runtime-kotlin/runtime-mcp/src/main/kotlin/skillbill/mcp/featuretask/McpFeatureTaskSettlementHandlers.kt` (do not require this tool)

Tests:
- Extend `FeatureTaskPhaseSettlementServiceTest.kt` so a durable completed review leaves a `feature_task_phase_settlements` row for phase `review`. Realistic bug: `CodeReviewStep.complete` still skips `complete()`, matching last week's 35 `verify_findings` rows and 0 `review` rows. Drive this from durable completion, not a second isolated `complete()` insert in `SqliteFeatureTaskPhaseSettlementRepositoryTest.kt`.
- Add a test around `FeatureTaskRuntimeGoalReviewCompletionRecorder.completeGoalReviewPhase` (none exists today): a completed durable goal review writes that settlement row. Parent-before-ledger belongs to subtask 1; this task asserts the settlement row.

Constraints:
- Do not change `agentSettledPhaseIds` or force review through MCP.
- Do not change review admission, severity, repair policy, or Goal QA criterion display.
- No `//` or block comments in runtime-kotlin; KDoc only on interfaces and members.
- Wire keys stay in owning `*Keys` objects.

### Task 2. Leave 13-20 Oct 2026 ledger orphans as unrestorable leftovers

Serves: Acceptance Criterion 2 (parent AC-5).

Ten `review_run_id`s exist only on `unaddressed_findings` (30 rows, 27 minor and 3 nit, issues SKILL-419, SKILL-431, SKILL-422, SKILL-428, 0AC-54, 0AC-63, 0AC-65, ids `rvw-20261016` through `rvw-20261019`). Inventing empty `review_runs` parents would make `review_stats` report 0 findings for runs whose ledger has 30 findings. Deleting the ledger rows is forbidden. Name them unrestorable leftovers and leave them in place.

Stats stay honest without a code change: `ReviewStatsRuntime.statsSnapshot` builds `ReviewRepositoryStatsSnapshot` from `summarizeFindingRows(queryLatestFindingOutcomes)`, SQL `FROM findings f LEFT JOIN feedback_events` and `review_finding_outcomes`. It never reads `unaddressed_findings`. Do not point stats at the ledger.

Do not add a foreign key on `unaddressed_findings.review_run_id`. `CREATE TABLE unaddressed_findings` in `DatabaseSchemaStatements.kt` has nullable `review_run_id` and no FOREIGN KEY to `review_runs`. `CREATE TABLE findings` and `review_run_pass_claims` already FK to `review_runs`. A new ledger FK would fail migrate on the operator database that motivated this issue. Subtask 1's write-path reject covers new in-workflow orphans; this task must not add a schema constraint that fights the 30 existing rows.

No backfill by default. `GOAL_SUBTASK_REVIEW_RESULTS` stores `rawReviewResult` keyed by pass number and `retainReviewOutput` writes producer output evidence, but those artifacts are not confirmed to contain the 10 historical ids in a form that maps to `ImportedFinding` rows. If implement finds retained artifacts for those exact ids and can map them to real `review_runs` plus `findings`, it may backfill. Empty stub parents (`reserveReviewRun` INSERT OR IGNORE with empty `raw_text`) remain forbidden for leftover ids.

Paths and symbols:
- `runtime-kotlin/runtime-infra/sqlite/src/main/kotlin/skillbill/infrastructure/sqlite/DatabaseSchemaStatements.kt` (`CREATE TABLE unaddressed_findings`) — do not add FK
- Database migrations / `DatabaseRuntime` — no cleanup delete, no new FK migration
- `ReviewStatsRuntime.statsSnapshot`, `ReviewFindingStats.kt` — do not start reading `unaddressed_findings`
- Do not call `reserveReviewRun` or `saveImportedReview` for the 10 historical ids unless a real findings backfill is possible

Tests:
- Starting from a store that already has `unaddressed_findings` rows whose `review_run_id` is absent from `review_runs`, migrate and startup leave those rows in place. Realistic bug: a schema FK or cleanup delete drops the historical leftovers. `UnaddressedFindingsRuntimeTest.kt` currently inserts a `review_runs` row before a keyed outcome; this test is the inverse fixture. A sibling assertion on `unaddressed_findings` in `DatabaseMigrationsReviewAttributionTest` AC-009 is optional and must not delete leftovers. Do not weaken AC-009 (no `findings` row without a `review_runs` parent).
- Do not add a sibling `ReviewStatsRuntimeTest` that only re-asserts stats ignore `unaddressed_findings`.

Constraints:
- Do not silently drop the 30 rows.
- Do not add the FK unless a backfill also creates parents (default: no FK, no backfill).
- Do not invent empty `review_runs` stubs for leftover ids.
- Leftover policy must not fight subtask 1's write-path reject for new in-workflow rows.

### Task 3. Keep work-detail JSON unchanged

Serves: Acceptance Criterion 3 (parent AC-6).

Do not edit `orchestration/contracts/work-detail-schema.yaml`, `WorkDetailPayloadMapping.kt`, `WorkCliCommands.kt`, or IdeStatusService work-detail pagination. Remote proxy workflow support and stats auth stay out of scope; they are not needed for local `review_stats`. Teaching the telemetry proxy `feature-task-runtime` stats and backfilling remote org telemetry remain non-goals.

Tests: none. Work-detail freeze is a do-not-touch constraint. Audit reads the named files. No test obligation.

Constraints:
- This spec does not freeze production wiring, test setup, formatting, or lint repairs outside those work-detail files.

## Next Path

```bash
skill-bill goal SKILL-433
```
