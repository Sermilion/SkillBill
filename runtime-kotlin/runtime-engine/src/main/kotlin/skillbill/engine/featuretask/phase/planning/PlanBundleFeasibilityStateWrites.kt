package skillbill.engine.featuretask.phase.planning

import skillbill.engine.featuretask.model.phase.PlanBundleFeasibilityStateWrite
import skillbill.engine.featuretask.model.phase.PlanBundleFeasibilityStateWriteResult
import skillbill.workflow.taskruntime.model.planning.FeatureTaskRuntimePlanBundleFeasibilityState

internal fun planBundleFeasibilityWriteResult(
  current: FeatureTaskRuntimePlanBundleFeasibilityState?,
  request: PlanBundleFeasibilityStateWrite,
): PlanBundleFeasibilityStateWriteResult {
  if (request.expectedRevision == null && request.transitionId == null) {
    return initializationResult(current, request.state)
  }
  val priorSettlement = current?.settledLaunches?.firstOrNull { it.transitionId == request.transitionId }
  if (priorSettlement != null) {
    return if (request.state.matchesRetainedContext(priorSettlement)) {
      PlanBundleFeasibilityStateWriteResult.Replayed(current, priorSettlement.outcome.wireValue)
    } else {
      PlanBundleFeasibilityStateWriteResult.Refused(
        "transition context conflicts with settled launch",
        current.revision,
      )
    }
  }
  return advanceResult(current, request)
}

private fun initializationResult(
  current: FeatureTaskRuntimePlanBundleFeasibilityState?,
  proposed: FeatureTaskRuntimePlanBundleFeasibilityState,
): PlanBundleFeasibilityStateWriteResult {
  if (current != null) return PlanBundleFeasibilityStateWriteResult.Existing(current)
  val invalid = proposed.initializationInvalidReason() ?: proposed.invalidReason()
  return if (invalid == null) {
    PlanBundleFeasibilityStateWriteResult.Applied(proposed)
  } else {
    PlanBundleFeasibilityStateWriteResult.Refused(invalid)
  }
}

private fun advanceResult(
  current: FeatureTaskRuntimePlanBundleFeasibilityState?,
  request: PlanBundleFeasibilityStateWrite,
): PlanBundleFeasibilityStateWriteResult {
  if (current == null || request.expectedRevision != current.revision ||
    request.state.revision != current.revision + 1
  ) {
    return PlanBundleFeasibilityStateWriteResult.Refused(
      "expected revision does not match current state",
      current?.revision,
    )
  }
  val invalid = transitionInvalidReason(current, request) ?: request.state.invalidReason()
  return if (invalid == null) {
    PlanBundleFeasibilityStateWriteResult.Applied(request.state)
  } else {
    PlanBundleFeasibilityStateWriteResult.Refused(invalid, current.revision)
  }
}

private fun transitionInvalidReason(
  current: FeatureTaskRuntimePlanBundleFeasibilityState,
  request: PlanBundleFeasibilityStateWrite,
): String? {
  val transitionId = request.transitionId
  val transitionLaunch = current.activeLaunch ?: request.state.activeLaunch
  val activeTransitionMatches =
    current.activeLaunch?.transitionId == transitionId ||
      (current.activeLaunch == null && request.state.activeLaunch?.transitionId == transitionId)
  val launchMatchesAttempt =
    transitionLaunch?.let { it.phaseId == request.phaseId && it.attempt == request.attempt } == true
  return when {
    transitionId.isNullOrBlank() || !activeTransitionMatches || !launchMatchesAttempt ->
      "active transition context conflicts with retained launch"
    !request.state.matchesRetainedContext(transitionLaunch) ->
      "active transition context conflicts with retained launch"
    !request.state.preservesLaunchHistoryFrom(current, transitionId) -> "settled launch history changed"
    else -> null
  }
}
