# SKILL-404 Phase strategy telemetry

## Outcome

A finished durable feature-task run reports the slot strategy that owned each recorded phase. The finish event `skillbill_feature_task_runtime_finished` carries that admitted strategy id and semantic revision per phase.

Selection already lives on the admitted execution plan. This feature reads `dispatchStrategyByStep` at finish and stores the join. It does not choose a strategy again.

## Scope

One subtask lands the payload join, the session column, the privacy and review-telemetry docs, and the truthfulness cases in one commit. The primary surfaces are the finish payload in `LifecycleTelemetryPayloads.kt`, the finish closure in `FeatureTaskRuntimeRunnerExecute.buildExecutePreparedRunTelemetryContext`, session insert and update in `LifecycleTelemetryRuntimeSave.kt`, stale reconciliation, and the two docs named in the subtask.

Review and validate may repair production wiring, test setup, formatting, and lint that required checks uncover. Those repairs stay inside the observable behavior and the architecture rules this spec names.

## Acceptance Criteria

1. `skillbill_feature_task_runtime_finished` includes `phase_strategies`, `phase_strategy_availability`, and `phase_strategy_measurement_grain`. When every recorded phase has an admitted dispatch entry, availability is `measured` and `phase_strategies` maps each of those phase ids to the admitted `strategy_id` and `semantic_revision`.
2. A run with no phase records or no admitted dispatch reports `unavailable_no_durable_state` and a null map. A recorded phase missing from dispatch reports `unavailable_incomplete`, a null map, and one diagnostics degradation naming the seam, the expected dispatch entry, and the missing phase id. A session row written before the column existed reports `unknown` and a null map.
3. Finish insert and update bind a nullable `phase_strategies` column, and `reconcileStaleFeatureTaskRuntimeSessions` emits the value stored on the session row.
4. The field is present at telemetry detail `anonymous` and `full`, and a strategy id is left as the registered configuration token. `phase_outcomes` maps each phase id to its status wire value.
5. `FeatureTaskRuntimePhaseRecord` has no strategy field. `feature-task-runtime-execution-plan.yaml`, `FEATURE_TASK_RUNTIME_EXECUTION_PLAN_CONTRACT_VERSION`, and `FEATURE_TASK_RUNTIME_PERSISTENCE_CONTRACT_VERSION` keep their present values, so current plans stay on execution-plan contract `0.2` and contract `0.1` plans stay canonical. `../../../orchestration/contracts/telemetry-event-schema.yaml` stays at `1.12.0` with no branch for `skillbill_feature_task_runtime_finished`. `TELEMETRY_EVENT_CONTRACT_VERSION` stays aligned with that schema file.

## Decomposition

One subtask. The payload, the column, and the tests describe the same finish fact. A commit that adds the column without the payload, or the payload without the column, does not stand alone. Nothing in the change has to land before the rest can be specified.

## Non-Goals

- Strategy selection, Opus directive text, and the profile check that switches a slot to its Opus id.
- The admission log line `launch strategy selected`.
- Review telemetry events, quality-check telemetry events, and in-memory phase runs from `SkeletonDefinition`, `PhaseRunEntry`, and `InMemoryPhaseRunState`.
- Aggregate `feature-task-stats` and `FeatureTaskRuntimeWorkflowStats`.
- Backfill of old session rows, and rewriting payloads already queued.
- Loading the workflow artifact inside stale reconciliation.

## Validation Strategy

Implement and audit inspect the tree. They do not run the build, the tests, or a generator.

The build phase runs the pack `validation_gate.build_command`. That compile is what shows existing `FeatureTaskRuntimeFinishedRecord` call sites still build with the new defaulted fields.

The validate phase runs the pack validation gate `validation_gate.collect_all_full_gate_command` and the rest of the repository check suite. Coverage that this spec relies on is `LifecycleTelemetryTruthfulnessTest`, `StaleSessionReconcilerTest`, `TelemetryReliabilityContractTest.featureTaskRuntimeFinishedEnvelope`, `ReviewStatsRuntimeTest`, `TelemetryAnonymousRedactionTest`, and the slot-baseline comparison of the three `feature-task-runtime-finished.json` snapshots named in the subtask.

## Next Path

```bash
skill-bill goal SKILL-404
```
