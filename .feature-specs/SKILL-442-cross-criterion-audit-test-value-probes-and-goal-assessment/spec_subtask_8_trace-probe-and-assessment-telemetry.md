# SKILL-442 subtask 8 - trace, probe, and assessment telemetry

## Scope

The telemetry and `goal-stats` parts of parent criteria AC-002 (scenarios traced, broken, repaired, unknown per subtask), AC-004 (probes killed, survived, repaired, not_run), and AC-005 (findings by severity, confirmed and refuted counts, rounds, exit reason, findings left open). They land together because each one touches the same event schema, sqlite tables, and stats aggregation, and every fact they count already exists after subtasks 2 to 7.

Counting rules, used by every criterion below. Per subtask, from the final repair state: `traced` is the number of distinct refs in `traced_scenario_refs`, `broken_scenario_refs`, `repaired_scenario_refs`, and `unknown_scenario_refs` together; `broken` is the distinct count of `broken_scenario_refs` plus `repaired_scenario_refs`; `repaired` counts `repaired_scenario_refs`; `unknown` counts `unknown_scenario_refs`. Per subtask, from the last goal-child validate output's `test_probes`: `killed`, `survived`, and `not_run` count items by `result`, and `repaired` counts items with `repaired: true`. Missing sources count as 0. Per goal, from the assessment record: findings by severity across all rounds, `confirmed` and `refuted` by `verification`, `rounds` as the number of rounds, `exit_reason`, and `open_findings` as the length of `open_finding_refs`. With no record, counts are 0 and `exit_reason` is null.

## Acceptance Criteria

1. The telemetry events carry the new fields and the goal runner emits them.
   `orchestration/contracts/telemetry-event-schema.yaml` adds integer fields with minimum 0 to the goal subtask finished event: `interaction_scenarios_traced`, `interaction_scenarios_broken`, `interaction_scenarios_repaired`, `interaction_scenarios_unknown`, `test_probes_killed`, `test_probes_survived`, `test_probes_repaired`, and `test_probes_not_run`. It adds these to the goal finished event: `goal_assessment_blocker_findings`, `goal_assessment_major_findings`, `goal_assessment_minor_findings`, `goal_assessment_confirmed`, `goal_assessment_refuted`, `goal_assessment_rounds`, `goal_assessment_open_findings`, all integers with minimum 0, and `goal_assessment_exit_reason`, null or one of the subtask 5 exit reasons. The schema's own version moves forward if its versioning rule requires it.
   Every key lives in `GoalTelemetryPayloadKeys`. `GoalRunnerTelemetryEmitter` and `GoalTelemetryEmit` fill them with the Scope counting rules. Audit confirms it by reading the schema, the keys, and the emitter.
2. The sqlite telemetry tables store the new fields.
   New column migrations in `runtime-infra/sqlite/.../core/migration/` (`DatabaseColumnMigrationsConditional.kt`, `DatabaseMigrationEntries.kt`) add one column per criterion 1 field: the subtask fields to `goal_subtask_events`, the goal fields to the goal run events table. Integer columns default to 0 and the exit reason column defaults to null, so earlier rows read as zero. Each migration takes the next free version and renumbers none.
   `GoalTelemetrySave` writes the columns from the event payload. Audit confirms it by reading the migrations and the save code.
3. `goal-stats` reports the totals.
   `buildGoalStats` in `runtime-infra/sqlite/.../review/stats/GoalWorkflowStats.kt` sums the subtask fields across subtasks and the goal fields across goals, and counts goals per assessment exit reason. `skillbill.review.model.GoalWorkflowStats` carries them. `toGoalStatsPayload` in `runtime-application`, which the CLI and MCP `goal_stats` both use, adds `interaction_scenarios` (`traced`, `broken`, `repaired`, `unknown`), `test_probes` (`killed`, `survived`, `repaired`, `not_run`), and `goal_assessment` (`findings_by_severity` with `blocker`, `major`, `minor`; `confirmed`; `refuted`; `rounds`; `open_findings`; `exit_reasons`, a map from reason to goal count).
   If a contract governs the goal-stats payload, it follows the AGENTS.md sequence with its parity test. Audit confirms it by reading the aggregation, the model, and the payload builder.

## Criterion Sizing

1. About 150 production lines, 4 files, 3 areas (`orchestration/contracts`, `runtime-engine` goalrunner telemetry, `runtime-infra/sqlite` telemetry keys and emit). Fits.
2. About 90 production lines, 3 files, 1 area (`runtime-infra/sqlite`). Fits.
3. About 120 production lines, 3 files, 3 areas (`runtime-infra/sqlite` stats, `runtime-domain` review model, `runtime-application` review stats). Fits.

## Interaction Scenarios

1. IS-001. Criteria: AC-001, AC-002, AC-003.
   Entry point: `skill-bill goal-stats` after a goal finishes.
   Preconditions: one subtask's trace broke IS-001, the repair fixed it, and the re-trace held; validate recorded one `survived` probe; assessment exited `converged` with one refuted Major.
   Steps: the goal finishes and telemetry is saved; the operator runs goal-stats.
   Outcome: goal-stats shows `interaction_scenarios.broken` 1, `repaired` 1, `test_probes.survived` 1, `goal_assessment.refuted` 1, and `exit_reasons.converged` 1.
2. IS-002. Criteria: AC-002, AC-003.
   Entry point: the same command on a database with goals recorded before the migration.
   Preconditions: older rows have no values for the new columns.
   Steps: the migration runs; goal-stats aggregates.
   Outcome: older goals contribute 0 to every new total and are not counted under any exit reason.

## Non-Goals

- New telemetry for anything outside the AC-002, AC-004, and AC-005 counts.
- Remote telemetry dashboards.

## Dependency Notes

Depends on subtasks 3, 4, and 7, which complete every counted fact. Runs last so its migrations take the final version numbers.

## Test Obligations

- Stats test: a goal with one broken-then-repaired scenario, one survived probe, and one refuted Major reports those counts through `toGoalStatsPayload`. Bug caught: a field is emitted but never saved or summed, so goal-stats shows 0 against the GV-1 baseline. Extend `CliRuntimeGoalStatsTest` or `GoalStatsLogicalGoalGrainTest`.
- Telemetry schema validation tests already cover emitted events; update fixtures for the new fields.

## Validation Strategy

The validate phase runs the runtime-kotlin Gradle `check`. Nothing runs here.

## Implementation Details

### Evidence and ownership

This plan uses the upstream preplan digest. Targeted source reads at checkout revision `5c27271b9d4f5af618a78e00220cf0606f2e3360` resolve the telemetry transport and aggregation details that the digest left open. No parent or sibling spec was read or changed.

- `runtime-engine/.../goalrunner/telemetry/GoalRunnerTelemetryEmitter.kt` sends typed `GoalSubtaskFinishedRequest` and `GoalFinishedRequest` values. `runtime-application/.../telemetry/lifecycle/GoalTelemetryRecordMappers.kt` converts them into the records in `runtime-domain/.../telemetry/model/LifecycleTelemetryModels.kt`. These types currently lack the new counters. AC-001 therefore needs the requests, records, and mappings as well as the named emitter.
- `runtime-infra/sqlite/.../telemetry/goal/GoalTelemetrySave.kt` saves typed records. `GoalTelemetryEmit.kt` reloads rows, and `GoalTelemetryPayloads.kt` builds the outgoing payloads. `LifecycleTelemetryGoalSessionAdapter` calls save and emit in its existing session. AC-002 must extend both terminal update and terminal insert paths, and AC-001 must extend the row-to-payload builders. Saving an event payload directly is not the current production path.
- The actual goal run table is `goal_run_sessions`. `GoalWorkflowStats.kt` reads that table and `goal_subtask_events`. Its `parentWorkflowId` distinguishes a logical goal from invocation segments. `GoalStatsLogicalGoalGrainTest` already protects that distinction. AC-003 must preserve existing invocation statistics while counting assessment facts once per logical goal.
- `runtime-application/.../review/stats/ReviewStatsContractMappers.kt::toGoalStatsPayload` wraps `GoalWorkflowStats.toPayload` in `ReviewStatsContractWorkflowPayloadMappers.kt`. The latter owns the nested output additions. The canonical schema's `goalStatsEvent` describes request filters, not the returned stats payload. No separate goal-stats response schema was found in the targeted contract lookup; extend the existing output boundary without inventing a new response contract.
- `telemetry-event-schema.yaml` pins version `1.12.0` at the root and on every event branch. Its version-parity rule names `TelemetryEventSchemaValidator.kt::TELEMETRY_EVENT_CONTRACT_VERSION` and `TelemetryEventSchemaContractVersionTest`. `GoalTelemetryEmissionEventParityTest` covers strict emission branches. Use this existing contract and malformed-event failure path.

The prerequisite facts belong to the already-declared subtasks 3, 4, and 7. Their final repair state, last goal-child validate output, and durable assessment record are authorized local inputs, not remote capabilities. This subtask reads them through their owning runtime accessors. It does not recompute audit verdicts, run probes, or launch assessors. Absence uses the Scope defaults; malformed records retain their owners' typed rejection and diagnostic behavior rather than becoming silent zero values.

### Ordered tasks

1. Extend the canonical telemetry contract and wire vocabulary. Serves AC-001 and establishes the field definitions for AC-002 and AC-003.
   - Add the eight subtask counters and seven goal counters with integer type and minimum 0 to the two existing event branches in `orchestration/contracts/telemetry-event-schema.yaml`. Add the nullable assessment exit reason using exactly the exit-reason vocabulary owned by subtask 5. Include all new fields in runtime-produced events, including zero values and a null reason when no record exists.
   - Advance the telemetry contract to the next minor version, `1.13.0` if `1.12.0` remains current. Update the root, every branch constant, and the Kotlin version constant together. Preserve the existing validator and typed malformed-telemetry failure code; this is an extension of that contract, not a new parse seam.
   - Declare every new telemetry key once in `runtime-infra/sqlite/.../telemetry/goal/GoalTelemetryPayloadKeys.kt`. That owner remains SQLite-local because the engine and application carry typed fields. Reuse the assessment enum's `wireValue`; do not copy its tokens into another Kotlin collection.
   - Test obligations for later validation: extend `TelemetryEventSchemaContractVersionTest` and `GoalTelemetryEmissionEventParityTest`. The realistic bugs are schema/runtime version divergence, rejection of emitted counters by a strict branch, acceptance of negative counts, and drift in the allowed exit reasons. Use representative valid envelopes and focused rejection cases rather than one test per field.

2. Carry authoritative counts through the typed lifecycle path. Serves AC-001 and supplies AC-002.
   - Extend `LifecycleTelemetryRequests.kt`, `LifecycleTelemetryModels.kt`, and `GoalTelemetryRecordMappers.kt` with typed counter fields and nullable exit reason. Keep zero/null defaults for callers without these facts. Prefer cohesive typed count values if that avoids repeating a long constructor argument list; do not introduce a service bag or raw-map API.
   - In `GoalRunnerTelemetryEmitter`, obtain per-subtask facts by the subtask workflow identity and assessment facts by the parent workflow identity. Extend the existing `GoalRunnerWorkflowOutcomeStore` read surface and `WorkflowGoalRunnerOutcomeStore` implementation only where their upstream accessors do not already expose the required typed results. Update `GoalRunner.kt` construction with that specific dependency. Keep artifact decoding with its existing owner.
   - Apply Scope exactly. Use set unions for traced and broken scenario counts, so a ref present in broken and repaired contributes once. Use only the last goal-child validate output, count its probe items by final result, and count `repaired: true` independently. Count assessment finding observations across rounds, their verification states, the round count, and the final open-ref list without deduplicating findings across rounds.
   - Sample these facts before terminal emission. Preserve the current per-segment subtask emission guard and first-terminal persistence behavior. Resumption must not add probe attempts or scenario traces to counters that represent final state.
   - Test obligations for later validation: add one engine-owned boundary case through terminal emission with overlapping scenario ref sets and an earlier validate output superseded by a later output. Bug caught: the engine double-counts repaired scenarios or includes stale probe results even though the storage path faithfully saves its request. Assert emitted typed values, not mock call order.

3. Persist the counts and emit them from saved rows. Serves AC-002 and completes AC-001.
   - Add append-only column migrations in `DatabaseColumnMigrationsConditional.kt` and register them in `DatabaseMigrationEntries.kt`. Add all eight subtask columns to `goal_subtask_events`, all seven assessment integer columns to `goal_run_sessions`, and a nullable reason column to `goal_run_sessions`. Integer columns use non-null zero defaults; reason defaults to null. Choose versions after all dependency migrations, including composed migration lists. Renumber no existing entry.
   - Extend `GoalTelemetrySave.kt` insert and update bindings for these fields. Keep saves, outbox enqueue, and emission markers under the existing transaction owner in `LifecycleTelemetryGoalSessionAdapter`. A duplicate terminal call must neither add counts nor enqueue another event.
   - Extend `goalFinishedPayload` and `goalSubtaskFinishedPayload` in `GoalTelemetryPayloads.kt`, reached through `GoalTelemetryEmit.kt`, to read persisted columns with the canonical keys. Preserve existing redaction and telemetry-level behavior. Counters contain no source prose or finding evidence.
   - Test obligations for later validation: extend the existing SQLite telemetry store coverage with a duplicate terminal write and outbox assertions. Bug caught: an update path omits counters, or a duplicate delivery increments them. Add an upgrade case starting from a pre-change database with real legacy rows, apply supported migrations, reopen, and verify preserved rows contribute zero counts and no exit reason. This protects AC-002 and IS-002; do not simulate migration by supplying missing keys to a map.

4. Aggregate and expose the nested stats payload. Serves AC-003 and IS-001, after tasks 2 and 3.
   - Extend the typed `GoalRunRow` and `GoalSubtaskRow` parsing in `runtime-infra/sqlite/.../review/stats/GoalWorkflowStats.kt` and the model in `runtime-domain/.../review/model/ReviewStatsModels.kt`. Sum scenario and probe fields across saved subtask events. Use non-negative numeric reads and existing typed row failures for corrupt values; migration defaults handle legacy rows.
   - Sum assessment counters across logical goals, with one authoritative assessment snapshot per nonblank `parentWorkflowId`, rather than summing the same cumulative record from every resumed segment. Use the latest finished segment carrying a non-null assessment exit reason for that goal. A legacy row without a parent identity and without assessment data contributes zero and no exit reason. Preserve all current invocation and identity-availability fields. This selection rule applies only to the new assessment totals.
   - Extend `ReviewStatsContractWorkflowPayloadMappers.kt::GoalWorkflowStats.toPayload` with exactly the `interaction_scenarios`, `test_probes`, and `goal_assessment` shapes specified in AC-003. Count non-null exit reasons once per selected goal; do not create a null or empty-string bucket. Keep counts as integer values and use a sufficiently wide sum type to avoid overflow across many events.
   - Keep new stats output keys in an application-owned key object if constants are needed across its mapper helpers. They are presentation keys, separate from the SQLite telemetry keys. CLI and MCP continue using their existing shared `toGoalStatsPayload` boundary, with no duplicate formatter.
   - Test obligations for later validation: extend `CliRuntimeGoalStatsTest` with one stored goal containing the IS-001 facts and assert the nested payload values after persistence. Include all counter families with distinct values so swapped or omitted mappings fail. Extend `GoalStatsLogicalGoalGrainTest` with resumed segments carrying the same assessment snapshot and assert one goal's totals and exit-reason count. Bug caught: assessment totals inflate on every resume. These tests complement the upgrade and engine cases rather than retesting their algorithms.

### Constraints and phase handoff

All three criteria have a concrete local production path. AC-001 follows authoritative runtime facts through typed requests and records into schema-valid emitted payloads. AC-002 follows those records into migrated tables under the existing transaction. AC-003 follows stored rows through typed aggregation and the shared CLI/MCP payload. Tasks 1 to 4 preserve that order; later validation observes the outcomes through emission, durable reads, and the public stats result.

Apply architecture rules A1, A2, A6, A7, A10, A11, and A12 to these changes. Keep pure counting with the owning domain models, runtime coordination in the engine, SQL and wire emission in SQLite, and output formatting in application. Shared declarations need their actual cross-module consumers. Use narrow visibility, imports instead of inline fully qualified names, and no authored Kotlin comments outside permitted interface KDoc. Do not widen guards, baselines, or exemptions. Split cohesive production responsibilities only when needed to stay within existing file and package limits.

Implementation may repair affected wiring, fixtures, formatting, or lint while preserving production behavior and assertions. It must preserve other changes already present in shared files. The plan adds no external service, remote dashboard, desktop feature, or new workflow phase. Review owns the section 5 architecture checklist and cites applicable rule IDs. Validation owns all test execution and the full repository gate, including contract parity, migration coverage, telemetry store coverage, CLI stats, logical-goal aggregation, and architecture guards. This planning phase ran no tests, checks, builds, or compilation. History, commits, publication, and other runtime phases remain with their owners.

## Next Path

```bash
skill-bill goal SKILL-442
```
