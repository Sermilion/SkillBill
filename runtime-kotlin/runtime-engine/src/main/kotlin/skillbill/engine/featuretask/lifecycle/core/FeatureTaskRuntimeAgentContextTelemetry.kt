package skillbill.engine.featuretask.lifecycle.core

import me.tatarka.inject.annotations.Inject
import skillbill.application.telemetry.model.FeatureTaskRuntimeAgentContext
import skillbill.engine.featuretask.phase.record.FeatureTaskRuntimePhaseRecorder
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimePhaseRecord

fun featureTaskRuntimeAgentContext(
  records: Map<String, FeatureTaskRuntimePhaseRecord>?,
): FeatureTaskRuntimeAgentContext {
  val phaseRecords = records ?: return FeatureTaskRuntimeAgentContext()
  return FeatureTaskRuntimeAgentContext(
    resolvedAgentIds = phaseRecords.values.distinctNames { it.resolvedAgentId },
    launchedModels = phaseRecords.values.distinctNames { it.launchedModel },
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
