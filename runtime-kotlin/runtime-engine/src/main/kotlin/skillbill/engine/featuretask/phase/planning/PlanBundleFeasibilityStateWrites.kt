package skillbill.engine.featuretask.phase.planning

import skillbill.engine.featuretask.model.phase.FeatureTaskRuntimePhaseStateRequest
import skillbill.engine.featuretask.model.phase.PlanBundleFeasibilityStateWrite
import skillbill.engine.featuretask.model.phase.PlanBundleFeasibilityStateWriteResult
import skillbill.workflow.taskruntime.model.planning.FeatureTaskRuntimePlanBundleFeasibilityState
import skillbill.workflow.taskruntime.model.planning.PlanBundleFeasibilityApprovalCandidate
import skillbill.workflow.taskruntime.model.planning.PlanBundleFeasibilityLaunchKind
import skillbill.workflow.taskruntime.model.planning.isPlanBundleFeasibilityLifecycleTransitionValid

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
  val transitionLaunch =
    current.activeLaunch ?: request.state.activeLaunch
      ?: return "active transition context is missing"
  val activeTransitionMatches = activeTransitionMatches(current, request)
  val launchMatchesPhase = transitionLaunch.phaseId == request.phaseId
  val newAdmission = current.activeLaunch == null && request.state.activeLaunch != null
  val countersValid = launchCountersValid(current, request, newAdmission)
  val modeValid = isPlanBundleFeasibilityLifecycleTransitionValid(current, request.state, transitionId)
  return when {
    newAdmission && !hasRepositoryBaseline(request) ->
      "new feasibility launch lacks its repository baseline"
    transitionId.isNullOrBlank() || !activeTransitionMatches || !launchMatchesPhase ->
      "active transition context conflicts with retained launch"
    !request.state.matchesRetainedContext(transitionLaunch) ->
      "active transition context conflicts with retained launch"
    !request.state.preservesLaunchHistoryFrom(current, transitionId) -> "settled launch history changed"
    !countersValid -> "launch counters do not match the admitted transition"
    !modeValid -> "lifecycle mode does not match the transition"
    else -> null
  }
}

private fun launchCountersValid(
  current: FeatureTaskRuntimePlanBundleFeasibilityState,
  request: PlanBundleFeasibilityStateWrite,
  newAdmission: Boolean,
): Boolean {
  return if (newAdmission) {
    val admitted = checkNotNull(request.state.activeLaunch)
    val reviewIncrement =
      if (admitted.kind == PlanBundleFeasibilityLaunchKind.REVIEW) 1 else 0
    val repairIncrement =
      if (admitted.kind == PlanBundleFeasibilityLaunchKind.REPAIR) 1 else 0
    admitted.phaseId == request.phaseId && admitted.attempt == request.attempt &&
      admitted.cycleId == request.state.currentCycleId &&
      admitted.sequence == current.reviewRoundsStarted + current.repairLaunchesStarted + 1 &&
      request.state.reviewRoundsStarted == current.reviewRoundsStarted + reviewIncrement &&
      request.state.repairLaunchesStarted == current.repairLaunchesStarted + repairIncrement
  } else {
    request.state.reviewRoundsStarted == current.reviewRoundsStarted &&
      request.state.repairLaunchesStarted == current.repairLaunchesStarted
  }
}

internal fun feasibilityApprovalCandidateMatchesRequest(
  candidate: PlanBundleFeasibilityApprovalCandidate,
  request: FeatureTaskRuntimePhaseStateRequest,
): Boolean {
  val matchesIdentity =
    candidate.phaseId == request.phaseId && candidate.attempt == request.attemptCount &&
      candidate.sourceRevision == request.feasibilityApprovalRevision &&
      candidate.transitionId == request.feasibilityApprovalTransitionId
  val matchesOutput =
    candidate.reviewerAgentId == request.resolvedAgentId &&
      candidate.normalizedEnvelope == (request.normalizedOutput?.canonicalJson ?: request.outputArtifact)
  val matchesFiles =
    candidate.fileManifestBefore == request.fileManifestBefore &&
      candidate.fileManifestAfter == request.fileManifestAfter &&
      candidate.fileManifestIntroduced == request.fileManifestIntroduced
  return matchesIdentity && matchesOutput && matchesFiles
}

private fun hasRepositoryBaseline(request: PlanBundleFeasibilityStateWrite): Boolean =
  request.state.activeLaunch?.repositoryBaseline != null

private fun activeTransitionMatches(
  current: FeatureTaskRuntimePlanBundleFeasibilityState,
  request: PlanBundleFeasibilityStateWrite,
): Boolean =
  current.activeLaunch?.transitionId == request.transitionId ||
    (current.activeLaunch == null && request.state.activeLaunch?.transitionId == request.transitionId)
