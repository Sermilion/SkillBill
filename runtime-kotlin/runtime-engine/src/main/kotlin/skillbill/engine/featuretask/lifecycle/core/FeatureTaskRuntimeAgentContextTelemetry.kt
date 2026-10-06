package skillbill.engine.featuretask.lifecycle.core

import me.tatarka.inject.annotations.Inject
import skillbill.application.telemetry.model.FeatureTaskRuntimeAgentContext
import skillbill.contracts.telemetry.TelemetryMeasurementAvailability
import skillbill.contracts.workflow.identity.task.FeatureTaskRuntimeExecutionPlanKeys
import skillbill.engine.featuretask.model.core.FeatureTaskRuntimePhaseStrategies
import skillbill.engine.featuretask.phase.record.FeatureTaskRuntimePhaseRecorder
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimePhaseRecord
import skillbill.workflow.taskruntime.model.skeleton.ResolvedPhaseStrategyDispatch

fun featureTaskRuntimeAgentContext(
  records: Map<String, FeatureTaskRuntimePhaseRecord>?,
): FeatureTaskRuntimeAgentContext {
  val phaseRecords = records ?: return FeatureTaskRuntimeAgentContext()
  return FeatureTaskRuntimeAgentContext(
    resolvedAgentIds = phaseRecords.values.distinctNames { it.resolvedAgentId },
    launchedModels = phaseRecords.values.distinctNames { it.launchedModel },
  )
}

fun featureTaskRuntimePhaseStrategies(
  phaseIds: Collection<String>,
  dispatch: Map<String, ResolvedPhaseStrategyDispatch>?,
): FeatureTaskRuntimePhaseStrategies {
  if (phaseIds.isEmpty() || dispatch.isNullOrEmpty()) {
    return FeatureTaskRuntimePhaseStrategies(
      availability = TelemetryMeasurementAvailability.UNAVAILABLE_NO_DURABLE_STATE,
      values = null,
    )
  }
  val missing = phaseIds.filter { it !in dispatch }
  if (missing.isNotEmpty()) {
    return FeatureTaskRuntimePhaseStrategies(
      availability = TelemetryMeasurementAvailability.UNAVAILABLE_INCOMPLETE,
      values = null,
      missingPhaseIds = missing,
    )
  }
  return FeatureTaskRuntimePhaseStrategies(
    availability = TelemetryMeasurementAvailability.MEASURED,
    values =
      phaseIds.associateWith { phaseId ->
        val admitted = dispatch.getValue(phaseId)
        linkedMapOf(
          FeatureTaskRuntimeExecutionPlanKeys.STRATEGY_ID to admitted.strategyId,
          FeatureTaskRuntimeExecutionPlanKeys.SEMANTIC_REVISION to admitted.semanticRevision,
        )
      },
  )
}

private fun Collection<FeatureTaskRuntimePhaseRecord>.distinctNames(
  select: (FeatureTaskRuntimePhaseRecord) -> String?,
): List<String>? =
  mapNotNull { select(it)?.takeIf(String::isNotBlank) }
    .distinct()
    .sorted()
    .takeIf { it.isNotEmpty() }

@Inject
class FeatureTaskRuntimeAgentContextTelemetry(
  private val recorder: FeatureTaskRuntimePhaseRecorder,
) {
  fun context(workflowId: String): FeatureTaskRuntimeAgentContext =
    featureTaskRuntimeAgentContext(recorder.loadPhaseRecords(workflowId))
}
