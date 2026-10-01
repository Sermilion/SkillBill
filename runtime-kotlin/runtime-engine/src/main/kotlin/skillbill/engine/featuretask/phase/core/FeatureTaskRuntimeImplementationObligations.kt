package skillbill.engine.featuretask.phase.core

import skillbill.engine.featuretask.model.core.FeatureTaskRuntimeImplementationContinuation
import skillbill.workflow.taskruntime.model.persistence.FeatureTaskRuntimeImplementationAttempt

internal data class FeatureTaskRuntimeImplementationObligations(
  val plannedTaskIds: List<String>,
  val carriedRepairItemIds: List<String>,
  val loopId: String?,
  val edgeIteration: Int? = null,
)

internal fun featureTaskRuntimeImplementationContinuationFrom(
  phaseId: String,
  attempts: List<FeatureTaskRuntimeImplementationAttempt>,
  obligations: FeatureTaskRuntimeImplementationObligations,
): FeatureTaskRuntimeImplementationContinuation? {
  val phaseAttempts =
    attempts.filter {
      it.phaseId == phaseId && it.loopId == obligations.loopId && it.edgeIteration == obligations.edgeIteration
    }
  if (phaseAttempts.isEmpty()) return null
  val priorSegments = phaseAttempts.map { it.value }
  val latest = phaseAttempts.maxByOrNull { it.sequenceNumber } ?: return null
  return FeatureTaskRuntimeImplementationContinuation(
    phaseId = phaseId,
    segmentNumber = phaseAttempts.size + 1,
    priorValueSegments = priorSegments,
    latestPrompt = latest.prompt,
    failureDisposition = latest.failureDisposition?.wireValue,
  )
}
