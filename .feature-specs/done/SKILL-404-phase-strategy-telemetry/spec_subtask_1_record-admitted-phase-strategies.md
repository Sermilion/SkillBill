# Record admitted phase strategies

## Scope

Wire the admitted `dispatchStrategyByStep` into `skillbill_feature_task_runtime_finished`, persist the joined map on the feature-task session row, and document the field at the same privacy class as `launched_models`.

Primary files:

- `LifecycleTelemetryPayloadKeys`, next to `LAUNCHED_MODELS`
- `SqliteLifecycleTelemetryMaterializationPayloadKeys`, next to `AGENT_CONTEXT_MEASUREMENT_GRAIN_DISTINCT_PER_RUN`
- The file that already defines `featureTaskRuntimeAgentContext` and `featureTaskRuntimeFinishedPayload` in `LifecycleTelemetryPayloads.kt`
- `FeatureTaskRuntimeRunnerExecute.buildExecutePreparedRunTelemetryContext`
- `FeatureTaskRuntimeFinishedRecord` and `FeatureTaskRuntimeFinishedRequest`
- `ensureColumn` on the feature-task session table, following the `launched_models` path in `ensureFeatureTaskRuntimeSessionAvailabilityColumns` and `DatabaseSchemaStatements.kt`
- `insertFeatureTaskRuntimeFinished` and `updateFeatureTaskRuntimeFinished` in `LifecycleTelemetryRuntimeSave.kt`
- `StaleSessionReconciler.reconcileStaleFeatureTaskRuntimeSessions`
- `../../../docs/telemetry-privacy.md`
- the resolved-agent section of `../../../docs/review-telemetry.md`
- `LifecycleTelemetryTruthfulnessTest`
- `TelemetryAnonymousRedactionTest`
- `../../../runtime-kotlin/runtime-engine/src/test/resources/featuretask/slotbaseline/standalone/feature-task-runtime-finished.json`
- `../../../runtime-kotlin/runtime-engine/src/test/resources/featuretask/slotbaseline/goal-child-validate/feature-task-runtime-finished.json`
- `../../../runtime-kotlin/runtime-engine/src/test/resources/featuretask/slotbaseline/goal-child-build/feature-task-runtime-finished.json`

This list is the intended change, not a closed allowlist. Review and validate may edit production wiring, test setup, formatting, and lint when a required check fails, as long as the acceptance criteria and the architecture rules stay intact.

`dispatchStrategyByStep` is already keyed by step. The join looks up recorded phase ids in that map. It does not map slots to phases, and it does not append `-opus-5-5` itself.

Registered vocabulary the fixtures may copy onto a dispatch entry:

| Slot | Canonical id | Opus id | Revision |
| --- | --- | --- | --- |
| preplan | `agent-preplan` | `agent-preplan-opus-5-5` | 1 |
| plan | `agent-plan`, `goal-plan-fan-out` | same id plus `-opus-5-5` | 1 |
| implementation | `implement-then-simplify` | `implement-then-simplify-opus-5-5` | 1 |
| audit | `acceptance-audit` | `acceptance-audit-opus-5-5` | 3 |
| code_review and standalone_review | `inline`, `delegated` | same id plus `-opus-5-5` | 1 |
| quality_gate | `pack-build`, `pack-validation`, `agent-validate` | same id plus `-opus-5-5` | 1 |
| write_history | `boundary-history` | `boundary-history-opus-5-5` | 1 |
| commit_push | `runtime-commit` | none | 1 |
| pull_request | `pr-description` | `pr-description-opus-5-5` | 1 |

Phase ids the join may see are `preplan`, `plan`, `implement`, `simplify`, `audit_plan_fix`, `audit_implement_fix`, `audit`, `review`, `verify_findings`, `implement_fix`, `build`, `validate`, `write_history`, `commit_push`, `pr`, and `present_findings`. An unselected step is absent from `dispatchStrategyByStep`. `implement` and `simplify` both carry the implementation slot's strategy id when that slot was selected. `inline` and `delegated` stay distinct only by phase id.

## Acceptance Criteria

1. `LifecycleTelemetryPayloadKeys` is the only declaration of the wire names `phase_strategies`, `phase_strategy_availability`, and `phase_strategy_measurement_grain`, placed next to `LAUNCHED_MODELS`. Each nested entry uses `FeatureTaskRuntimeExecutionPlanKeys.STRATEGY_ID` and `FeatureTaskRuntimeExecutionPlanKeys.SEMANTIC_REVISION` for `strategy_id` and `semantic_revision`.
2. `SqliteLifecycleTelemetryMaterializationPayloadKeys` declares the grain wire value `admitted_strategy_per_recorded_phase` once, beside `AGENT_CONTEXT_MEASUREMENT_GRAIN_DISTINCT_PER_RUN`.
3. A pure function beside `featureTaskRuntimeAgentContext` joins the phase ids already collected for `phase_outcomes` to the admitted `dispatchStrategyByStep`. `buildExecutePreparedRunTelemetryContext` passes that admitted map in, and the success finish and `finishedError` both use the resulting `FeatureTaskRuntimeFinishedTelemetryContext`.
4. When every recorded phase has a dispatch entry, `phase_strategy_availability` is `measured` and `phase_strategies` has exactly those phase ids. `implement` and `simplify` share one strategy id when the admitted plan stored one id for both, including `implement-then-simplify-opus-5-5`.
5. A measured goal-child build payload includes `build` with the strategy id the admitted dispatch stored for that step, `pack-build` or `pack-build-opus-5-5`, and has no `validate` key.
6. A recorded `commit_push` entry copies the admitted dispatch, `runtime-commit` at semantic revision 1. A recorded audit entry copies the admitted revision, which is 3 for `acceptance-audit` and `acceptance-audit-opus-5-5`.
7. No phase records, or no admitted dispatch, produces `phase_strategy_availability` `unavailable_no_durable_state` and a null `phase_strategies` value, encoded the same way this event encodes a null `launched_models` value.
8. Any recorded phase missing from dispatch produces `unavailable_incomplete`, a null map, and one diagnostics degradation. The degradation names the finish-join seam, states that an admitted dispatch entry was expected, and includes the missing phase id.
9. The feature-task session table has a nullable `phase_strategies` column added through `ensureColumn`, on the same ensure path that adds `launched_models`. `insertFeatureTaskRuntimeFinished` and `updateFeatureTaskRuntimeFinished` bind the column. `FeatureTaskRuntimeFinishedRecord` and `FeatureTaskRuntimeFinishedRequest` default the new fields to null.
10. `reconcileStaleFeatureTaskRuntimeSessions` emits `phase_strategies` and its availability from the session row. A null availability column on that row surfaces `unknown` and a null map. Reconciliation does not read the workflow artifact and does not backfill the column.
11. `../../../docs/telemetry-privacy.md` and the resolved-agent section of `docs/review-telemetry.md` describe `phase_strategies`, its availability, and its measurement grain at detail levels `anonymous` and `full`.
12. `LifecycleTelemetryTruthfulnessTest` asserts the five cases in Test Obligations. The three slot-baseline `feature-task-runtime-finished.json` files named in Scope include `phase_strategies`, `phase_strategy_availability`, and `phase_strategy_measurement_grain` for the phases those fixtures already record. `TelemetryAnonymousRedactionTest` leaves a strategy id as the raw token at anonymous detail.
13. `FeatureTaskRuntimePhaseRecord` has no strategy field. `feature-task-runtime-execution-plan.yaml`, `FEATURE_TASK_RUNTIME_EXECUTION_PLAN_CONTRACT_VERSION`, and `FEATURE_TASK_RUNTIME_PERSISTENCE_CONTRACT_VERSION` keep their present values. `../../../orchestration/contracts/telemetry-event-schema.yaml` stays at contract `1.12.0` with no `skillbill_feature_task_runtime_finished` branch, and `TELEMETRY_EVENT_CONTRACT_VERSION` stays aligned with that file. `phase_outcomes` values stay status wire values.

## Non-Goals

- Changing `PhaseStrategySelection`, `PhaseStrategyBinding`, `PhaseStrategyLookup`, or the Opus directive applied inside a strategy.
- Changing the `RuntimeDiagnostics` admission log `launch strategy selected`.
- Putting a strategy id, a model profile, or a semantic revision into `phase_outcomes`.
- Emitting this finish event from in-memory `review`, `validation`, `plan`, `pr`, or `goal-planning` skeletons, or from `PhaseRunEntry` and `InMemoryPhaseRunState`.
- Adding the strategy to `feature-task-stats` or teaching `ReviewWorkflowStats` to read it.
- Hashing or stripping strategy ids in anonymous redaction.
- Bumping the execution-plan, phase-record persistence, or telemetry event schema contracts.
- Backfilling pre-column rows or rewriting outbox payloads that were queued before the field existed.

## Dependency Notes

No earlier subtask. The SKILL-403 selectors are already in the checkout. Finish reads the plan those selectors already wrote onto `ResolvedPhaseExecutionPlan.selectedStrategies` and `dispatchStrategyByStep`.

A contract `0.1` plan reports the canonical ids the historical reader admitted, because the join copies the admitted map. Resume reports the plan that was recorded, because the finish closure reads the admitted plan rather than the current config. Neither case needs a branch in the join.

## Validation Strategy

Implement and audit check the criteria by reading the tree. They do not run `./gradlew`, the pack build command, the pack validation gate, or the snapshot generator.

The build phase runs `validation_gate.build_command`. Compilation of `StaleSessionReconcilerTest`, `TelemetryReliabilityContractTest.featureTaskRuntimeFinishedEnvelope`, and `ReviewStatsRuntimeTest` is the check that the new record fields default to null.

The validate phase runs `validation_gate.collect_all_full_gate_command` and the repository check suite. The tests that lock this subtask are `LifecycleTelemetryTruthfulnessTest`, `StaleSessionReconcilerTest`, `TelemetryReliabilityContractTest`, `ReviewStatsRuntimeTest`, `TelemetryAnonymousRedactionTest`, and the existing slot-baseline snapshot comparison for the three finished payloads in Scope.

`tests_executed` on any receipt from implement or audit stays empty.

## Implementation Details

The preplan digest is the source for every task below. It left no open question. Implement follows these tasks and does not rediscover the tree. Implement and audit inspect the tree. They leave `tests_executed` empty. The build phase owns `validation_gate.build_command`. The validate phase owns the pack validation gate and the repository check suite. This plan runs no install, uninstall, or install-sync command.

Production files stay under the 1200-line ceiling. New helpers sit beside the functions named below. Add no new production file and no new test class. Authored Kotlin under `runtime-kotlin` gets no line comments and no block comments.

1. Declare the three payload keys and the grain token. Serves criteria 1 and 2.

   In `../../../runtime-kotlin/runtime-contracts/src/main/kotlin/skillbill/contracts/telemetry/LifecycleTelemetryPayloadKeys.kt`, declare `phase_strategies`, `phase_strategy_availability`, and `phase_strategy_measurement_grain` once each, next to `LAUNCHED_MODELS` and `LAUNCHED_MODEL_AVAILABILITY`. Name the constants `PHASE_STRATEGIES`, `PHASE_STRATEGY_AVAILABILITY`, and `PHASE_STRATEGY_MEASUREMENT_GRAIN`. `payloadKeyValues` already reflects every string constant on that object into the sqlite materialization seam, so this needs no inventory edit.

   In `SqliteLifecycleTelemetryMaterializationPayloadKeys.kt`, declare the grain value `admitted_strategy_per_recorded_phase` once, as a top-level const beside `AGENT_CONTEXT_MEASUREMENT_GRAIN_DISTINCT_PER_RUN`. Keep that const out of the `SqliteLifecycleTelemetryMaterializationPayloadKeys` object so reflection does not treat the grain token as a payload key.

   Nested entry keys are `FeatureTaskRuntimeExecutionPlanKeys.STRATEGY_ID` and `FeatureTaskRuntimeExecutionPlanKeys.SEMANTIC_REVISION`. Governed seams use those constants. They do not inline the wire strings at `get`, `put`, `mapOf`, or bracket access.

   Tests: none. The existing reflection seam is the guard. An empty test list is the outcome for this task.

2. Add the pure join. Serves criteria 3, 4, 5, 6, 7, and 8.

   Put `featureTaskRuntimePhaseStrategies` in `../../../runtime-kotlin/runtime-engine/src/main/kotlin/skillbill/engine/featuretask/lifecycle/core/FeatureTaskRuntimeAgentContextTelemetry.kt`, beside `featureTaskRuntimeAgentContext`. Inputs are the ordered phase-outcome keys and `Map<String, ResolvedPhaseStrategyDispatch>?`. The return carries availability, the map or null, and the missing phase ids. The function returns those facts and does not touch diagnostics.

   An empty phase-id list, a null dispatch, or an empty dispatch yields `unavailable_no_durable_state` and a null map. An empty map is not an admitted dispatch. `ResolvedPhaseExecutionPlan` requires `dispatchStrategyByStep.isNotEmpty()`.

   A non-empty dispatch whose keys cover every recorded phase id yields `measured`. Object keys are exactly those phase ids, in phase-outcome order. Extra dispatch entries for unrecorded steps stay out. Each entry copies `strategyId` and `semanticRevision` only. `slot` stays off the payload. A contract `0.1` plan and a resumed plan need no branch. The join copies the admitted map.

   Any missing recorded phase yields `unavailable_incomplete`, a null map, and the missing ids. Several missing ids stay one result, listed in phase-outcome order, comma-separated. The function does not emit a partial object.

   `runtime-infra/sqlite` does not depend on `runtime-engine`, so `LifecycleTelemetryTruthfulnessTest` cannot call this function. Extend `FeatureTaskRuntimeAgentContextTelemetryTest` with the join cases in Test Obligations 1 through 4.

3. Close the finish path over the admitted map. Serves criteria 3 and 8.

   `FeatureTaskRuntimeRunnerExecute.executePreparedRun` already takes `requireNotNull(runRequest.admittedExecution).plan` before `buildExecutePreparedRunTelemetryContext`. Pass `executionPlan.dispatchStrategyByStep` into `buildExecutePreparedRunTelemetryContext`. The closure closes over that map.

   `FeatureTaskRuntimeFinishedTelemetryContext` gains a sibling lambda beside `phaseOutcomes`. `phaseOutcomes` stays `recorder.loadPhaseRecords(workflowId).orEmpty().mapValues { (_, record) -> record.status.wireValue }`. The new lambda joins those keys to the closed-over dispatch by calling `featureTaskRuntimePhaseStrategies`.

   Evaluate the join once while building the context, store that result in the lambda, and record the degradation there when the result is `unavailable_incomplete`. Use the runner's `RuntimeDiagnostics.warning`. The warning text is `degraded telemetry.phase_strategies.dispatch_join; expected=admitted dispatch entry; used=<phase ids>`, with seam `telemetry.phase_strategies.dispatch_join`, expected `admitted dispatch entry`, and the missing phase ids in the `used` text. `recordDegradedValue` in `SqliteDiagnosticRecords.kt` stays sqlite-internal. The engine module does not call it and does not add a `RuntimeFailureCode`.

   The `runCatching` that calls `lifecycleTelemetry.finishedError` starts only after this context exists. Success calls `lifecycleTelemetry.finished` with the same context. `resolvedFeatureTaskRuntimeTelemetryPayload` remains the single loader for `emitFeatureTaskRuntimeFinished` and `emitFeatureTaskRuntimeFinishedError`, so both requests carry the same result. A crash before admission never builds this context and never calls `finishedError`.

   Give the new lambda a default of no admitted dispatch. `FeatureTaskRuntimeTerminalFailureReasonTest` constructs the context with only `phaseOutcomes` and `reviewFixIterationCount`. That call site keeps compiling, and a default invocation reports `unavailable_no_durable_state`.

   Tests: no second test that the runner passed the map. The default is a compile check for the build phase.

4. Write the three fields on the finish payload. Serves criteria 4, 5, 6, 7, and the anonymous and full presence in criterion 11.

   `featureTaskRuntimeFinishedPayload` calls a new helper beside `agentContextFields`. The detail-level branch only adds `resolved_branch` at `full`, so fields added beside `agentContextFields` are present at `anonymous` and `full`.

   The helper always writes the grain const. It writes `row.availability(PHASE_STRATEGY_AVAILABILITY).wireValue`. It writes the parsed object only when availability is `measured`. Otherwise the map value is null and the key is present, the same shape as a null `launched_models` (`LAUNCHED_MODELS in payload` and `assertNull`). `Map.availability` calls `TelemetryMeasurementAvailability.fromWireOrUnknown`. A blank or null column is `unknown`.

   After JSON parsing, `semantic_revision` is a `Number` (`Long`). Compare it with `toInt()`. Strategy ids skip `redactIssueKey` and `redactIssueKeyReferences`. They are registered configuration tokens, the same class as `launched_models`.

   The runner stores `measured`, `unavailable_no_durable_state`, or `unavailable_incomplete`, and stores JSON only for `measured`.

   Tests: the stored-row half of Test Obligations 1 through 5, asserted by `LifecycleTelemetryTruthfulnessTest`.

5. Store the map and its availability on the session row. Serves criteria 9 and 10.

   `launched_models` is a nullable `TEXT` column added in `ensureFeatureTaskRuntimeSessionAvailabilityColumns` and is absent from `CREATE TABLE feature_task_runtime_sessions` in `DatabaseSchemaStatements.kt`. Add `phase_strategies` and `phase_strategy_availability` on that same ensure path, each `TEXT`, nullable, with no default. Phase strategies cannot use the `launched_models` `nameList` derivation. Both `unavailable_no_durable_state` and `unavailable_incomplete` store a null map, and a row from before the columns existed must surface `unknown`. The grain stays payload-only, like `distinct_resolved_agents_per_run`.

   `FeatureTaskRuntimeFinishedRecord` and `FeatureTaskRuntimeFinishedRequest` gain two nullable fields at the end, defaulting to null: a JSON string for the map, and a string for the availability wire. `FeatureTaskRuntimeFinishedRequest.toRecord` copies them. `insertFeatureTaskRuntimeFinished` and `updateFeatureTaskRuntimeFinished` bind both. Bind a null availability as SQL NULL. Leave `availabilityWire()` unused for this column. It turns null into `unavailable_no_durable_state`. Leave `namesJson()` unused for this map.

   `saveFeatureTaskRuntimeStarted` does not write the columns. There is no backfill and no rewrite of queued outbox payloads.

   `reconcileStaleFeatureTaskRuntimeSessions` marks the session stale and calls `emitFeatureTaskRuntimeFinished`, which `SELECT *`s the session row and builds the payload. It does not load the workflow artifact and does not backfill the column. A start-only row and a pre-column row both have a null availability column and emit `unknown`.

   Existing constructors in `LifecycleTelemetryTruthfulnessTest.finishedRuntimeSession`, `TelemetryReliabilityContractTest.featureTaskRuntimeFinishedEnvelope`, `ReviewStatsRuntimeTest`, and `StaleSessionReconcilerTest` keep compiling and persist NULL. The payload reads that NULL as `unknown` with a null map. Those three tests stay behaviorally the same. Their compile is the build phase's check for the defaulted fields.

   `ReviewWorkflowStats.phaseOutcomeCounts` keeps counting `phase_outcomes` values that are status wires. Leave that counter, `feature-task-stats`, and `FeatureTaskRuntimeWorkflowStats` unchanged.

   Tests: Test Obligation 5. No new reconciler test. The existing reconciler test remains the behavioral guard that reconciliation still emits from the session row.

6. Document the fields. Serves criterion 11.

   In the `skillbill_feature_task_runtime_finished` table of `../../../docs/telemetry-privacy.md`, and in the resolved-agent section of `docs/review-telemetry.md`, describe `phase_strategies`, `phase_strategy_availability`, and `phase_strategy_measurement_grain`. They are registered configuration tokens, the same class as `launched_models`, present at `anonymous` and `full`, unhashed, null unless availability is `measured`, and `unknown` when the availability column is null. State the grain `admitted_strategy_per_recorded_phase`. `runtime-kotlin/agent/decisions.md#13a9e72e3604` is the settings-load switch for optional telemetry and does not define this availability vocabulary.

   Tests: Test Obligation 6.

7. Update the three slot-baseline snapshots in place. Serves criterion 12.

   `SlotBaselineNormalizer.normalizeMap` sorts keys, and `SlotBaselineDurableBundle.fromRun` copies the latest outbox payload. Their current dispatches are canonical, not Opus. Omit `audit_plan_fix`, `audit_implement_fix`, and `implement_fix`. Those ids are in `dispatch_ownership` and absent from `phase_outcomes`. Every included entry uses sorted keys `semantic_revision` then `strategy_id`. Availability is `measured`. The grain is `admitted_strategy_per_recorded_phase`.

   `../../../runtime-kotlin/runtime-engine/src/test/resources/featuretask/slotbaseline/standalone/feature-task-runtime-finished.json` records `audit` `acceptance-audit` 3, `commit_push` `runtime-commit` 1, `implement` and `simplify` `implement-then-simplify` 1, `plan` `agent-plan` 1, `pr` `pr-description` 1, `preplan` `agent-preplan` 1, `review` and `verify_findings` `inline` 1, `validate` `agent-validate` 1, `write_history` `boundary-history` 1.

   `../../../runtime-kotlin/runtime-engine/src/test/resources/featuretask/slotbaseline/goal-child-build/feature-task-runtime-finished.json` uses that same set with `build` `pack-build` 1, and it has no `validate` and no `pr`.

   `../../../runtime-kotlin/runtime-engine/src/test/resources/featuretask/slotbaseline/goal-child-validate/feature-task-runtime-finished.json` uses that same set with `validate` `agent-validate` 1, and it has no `build` and no `pr`.

   Tests: the existing slot-baseline comparison. Add no new snapshot test.

8. Leave the excluded contracts and behaviors unchanged. Serves criterion 13 and the Non-Goals.

   `FeatureTaskRuntimePhaseRecord` has no strategy field. Its `requireCompatibleShape` allow-list would reject an unknown key. `FEATURE_TASK_RUNTIME_PERSISTENCE_CONTRACT_VERSION` stays `0.2` in `FeatureTaskRuntimeContractVersions.kt`. `FEATURE_TASK_RUNTIME_EXECUTION_PLAN_CONTRACT_VERSION` stays `0.2` and `FEATURE_TASK_RUNTIME_EXECUTION_PLAN_PREVIOUS_CONTRACT_VERSION` stays `0.1` in `FeatureTaskRuntimeExecutionPlanContract.kt`. `../../../orchestration/contracts/feature-task-runtime-execution-plan.yaml` stays `const: "0.2"`. `orchestration/contracts/telemetry-event-schema.yaml` stays `1.12.0` with no `skillbill_feature_task_runtime_finished` branch. `TELEMETRY_EVENT_CONTRACT_VERSION` in `TelemetryEventSchemaValidator.kt` stays `1.12.0`. `phase_outcomes` values stay status wire values: `pending`, `running`, `completed`, `failed`, `blocked`, `skipped`, or `paused`.

   `TelemetryReliabilityContractTest` duration-checks `featureTaskRuntimeFinishedEnvelope` and does not pass that envelope to `TelemetryEventSchemaValidator`.

   Leave unchanged: `PhaseStrategySelection`, `PhaseStrategyBinding`, `PhaseStrategyLookup` selection, Opus directive text, the `launch strategy selected` diagnostic, in-memory review, validation, plan, pr, and goal-planning skeletons, `PhaseRunEntry`, `InMemoryPhaseRunState`, aggregate stats, and already queued payloads.

   Tests: none. These are audit-visible diffs. Version constants and the telemetry schema file are not new tests.

## Test Obligations

`LifecycleTelemetryTruthfulnessTest` asserts cases 1 through 5 from stored session rows. It does not call `featureTaskRuntimePhaseStrategies`. `runtime-infra/sqlite` does not depend on `runtime-engine`. `FeatureTaskRuntimeAgentContextTelemetryTest` asserts the join for cases 1 through 4, which is the producer those stored rows record. Case 6 is the anonymous redaction case. One test per case in each class that the case names. No further tests.

1. Shared slot id. Realistic bug: the join reports a per-step model profile, gives `implement` and `simplify` different strategy ids, writes semantic revision 1 on an audit entry whose dispatch says 3, or invents an Opus id for `commit_push`. The engine case stores `implement-then-simplify-opus-5-5` at revision 1 on both `implement` and `simplify`, `runtime-commit` at revision 1 on `commit_push`, and `acceptance-audit` at revision 3 on `audit`. The measured object matches those entries and leaves `slot` off. Truthfulness round-trips that same JSON object with the key present, and compares `semantic_revision` with `toInt()`, so a `Long` from JSON fails an equality check that expects the revision int. Criteria 4, 6, and 12.
2. Unselected validate. Realistic bug: a goal-child build plan emits `validate`, or emits a build strategy id the dispatch did not store. The engine case records `build` and omits `validate`, with `build` mapped to `pack-build` or `pack-build-opus-5-5`. The measured object has `build` and no `validate` key. Truthfulness reads a stored measured object with that same shape and reports `build` present and `validate` absent. Criterion 5.
3. Missing plan. Realistic bug: a missing plan becomes a measured empty object, or the payload omits the key. The engine case covers three inputs: no phase records, a null dispatch, and an empty dispatch. Each yields `unavailable_no_durable_state` and a null map. Truthfulness stores explicit `unavailable_no_durable_state` and emits a present null map, the same key presence as a null `launched_models`. Criterion 7.
4. Incomplete dispatch. Realistic bug: the join emits a partial map and drops a missing phase. The engine case has two recorded phase ids absent from dispatch. Availability is `unavailable_incomplete`, the map is null, and the missing ids are listed in phase-outcome order, comma-separated. The returned facts name seam `telemetry.phase_strategies.dispatch_join`, expected `admitted dispatch entry`, and those phase ids. Truthfulness stores explicit `unavailable_incomplete` and emits a present null map. Criterion 8.
5. Legacy row. Realistic bug: a pre-column finish is reported as a measured empty object or as `unavailable_no_durable_state`. Truthfulness follows `clearAvailabilityColumns`. A null availability column emits `unknown` and a null map, with the key present. Criterion 10.
6. Anonymous token. Realistic bug: anonymous redaction hashes or strips the strategy id the way it hashes issue keys. `TelemetryAnonymousRedactionTest` keeps a raw strategy id such as `implement-then-simplify-opus-5-5` at anonymous detail. Criteria 11 and 12.

Constructor defaults are a compile check, owned by build and validate, at `StaleSessionReconcilerTest`, `TelemetryReliabilityContractTest.featureTaskRuntimeFinishedEnvelope`, and `ReviewStatsRuntimeTest`. The three slot-baseline snapshots are the existing runner-path comparison. Update those files in task 7. Add no second test that the runner passed the map. In-memory phase runs and `feature-task-stats` stay on their current behavior. `ReviewStatsRuntimeTest` keeps passing because `phase_outcomes` stays status values and the new fields default to null.

## Next Path

```bash
skill-bill goal SKILL-404
```
