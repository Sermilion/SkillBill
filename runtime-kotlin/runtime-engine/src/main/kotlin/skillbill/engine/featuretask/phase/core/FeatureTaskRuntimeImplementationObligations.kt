package skillbill.engine.featuretask.phase.core

import skillbill.engine.featuretask.model.core.FeatureTaskRuntimeImplementationContinuation
import skillbill.workflow.taskruntime.model.persistence.FeatureTaskRuntimeImplementationAttempt
import skillbill.workflow.taskruntime.model.persistence.FeatureTaskRuntimeImplementationTurnMarker

public data class FeatureTaskRuntimeImplementationObligations(
  public val plannedTaskIds: List<String>,
  public val carriedRepairItemIds: List<String>,
  public val loopId: String?,
  public val edgeIteration: Int? = null,
)

public fun featureTaskRuntimeImplementationContinuationFrom(
  phaseId: String,
  attempts: List<FeatureTaskRuntimeImplementationAttempt>,
  obligations: FeatureTaskRuntimeImplementationObligations,
): FeatureTaskRuntimeImplementationContinuation? {
  val phaseAttempts =
    attempts.filter {
      it.phaseId == phaseId &&
        it.loopId == obligations.loopId &&
        it.edgeIteration == obligations.edgeIteration &&
        !FeatureTaskRuntimeImplementationTurnMarker.isMarker(it.prompt)
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
