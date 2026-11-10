package skillbill.engine.featuretask.phase.planning

import skillbill.engine.featuretask.model.phase.FeatureTaskRuntimePhaseStateRequest
import skillbill.engine.featuretask.model.phase.PlanBundleFeasibilityStateWrite
import skillbill.engine.featuretask.model.phase.PlanBundleFeasibilityStateWriteResult
import skillbill.workflow.taskruntime.model.planning.FeatureTaskRuntimePlanBundleFeasibilityState
import skillbill.workflow.taskruntime.model.planning.PLAN_BUNDLE_FEASIBILITY_RUNTIME_COMPLETION_VALUE
import skillbill.workflow.taskruntime.model.planning.PlanBundleFeasibilityApprovalCandidate
import skillbill.workflow.taskruntime.model.planning.PlanBundleFeasibilityLaunchKind
import skillbill.workflow.taskruntime.model.planning.PlanBundleFeasibilityLaunchOutcome
import skillbill.workflow.taskruntime.model.planning.PlanBundleFeasibilityMode
import skillbill.workflow.taskruntime.model.planning.PlanBundleFeasibilitySettledLaunch
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
    val existing = checkNotNull(current)
    val runtimeReplayMatches =
      request.state.approvalCandidate?.takeIf { it.reviewerAgentId == "runtime" }?.let { candidate ->
        existing.approvalCandidate == candidate && existing.acceptedLines == request.state.acceptedLines &&
          existing.identity == request.state.identity &&
          existing.settledLaunches.singleOrNull { it.transitionId == candidate.transitionId } == priorSettlement
      } ?: true
    return if (request.state.matchesRetainedContext(priorSettlement) && runtimeReplayMatches) {
      PlanBundleFeasibilityStateWriteResult.Replayed(current, priorSettlement.outcome.wireValue)
    } else {
      PlanBundleFeasibilityStateWriteResult.Refused(
        "transition context conflicts with settled launch",
        current.revision,
      )
    }
  }
  runtimeCompletionWrite(current, request)?.let { return it }
  return advanceResult(current, request)
}

private fun runtimeCompletionWrite(
  current: FeatureTaskRuntimePlanBundleFeasibilityState?,
  request: PlanBundleFeasibilityStateWrite,
): PlanBundleFeasibilityStateWriteResult? {
  val state = request.state
  val candidate = state.approvalCandidate?.takeIf { it.reviewerAgentId == "runtime" } ?: return null
  val previous = current ?: return PlanBundleFeasibilityStateWriteResult.Refused("feasibility artifact is missing")
  val transition =
    state.settledLaunches.lastOrNull() ?: return PlanBundleFeasibilityStateWriteResult.Refused(
      "runtime completion transition is missing",
    )
  val valid =
    runtimeCompletionFenceMatches(previous, request, transition) &&
      runtimeCompletionPreservesState(previous, state, transition) &&
      runtimeCompletionCandidateMatches(candidate, request, transition)
  return if (valid && state.invalidReason() == null) {
    PlanBundleFeasibilityStateWriteResult.Applied(state)
  } else {
    PlanBundleFeasibilityStateWriteResult.Refused(
      "runtime completion transition does not match current evidence",
      previous.revision,
    )
  }
}

private fun runtimeCompletionFenceMatches(
  previous: FeatureTaskRuntimePlanBundleFeasibilityState,
  request: PlanBundleFeasibilityStateWrite,
  transition: PlanBundleFeasibilitySettledLaunch,
): Boolean {
  val nextSequence =
    (previous.settledLaunches.filter { it.cycleId == previous.currentCycleId }.maxOfOrNull { it.sequence } ?: 0) + 1
  val sameCycle = transition.cycleId == previous.currentCycleId && transition.sequence == nextSequence
  val matchingRevision =
    request.expectedRevision == previous.revision && request.state.revision == previous.revision + 1
  val matchingRequest =
    request.transitionId == transition.transitionId && transition.phaseId == request.phaseId &&
      transition.attempt == request.attempt
  return sameCycle && matchingRevision && matchingRequest
}

private fun runtimeCompletionPreservesState(
  previous: FeatureTaskRuntimePlanBundleFeasibilityState,
  state: FeatureTaskRuntimePlanBundleFeasibilityState,
  transition: PlanBundleFeasibilitySettledLaunch,
): Boolean {
  val idleReview =
    previous.activeLaunch == null && previous.mode == PlanBundleFeasibilityMode.REVIEW &&
      previous.conflicts.isEmpty() && state.activeLaunch == null
  val unchangedCounters =
    previous.reviewRoundsStarted == state.reviewRoundsStarted &&
      previous.repairLaunchesStarted == state.repairLaunchesStarted
  val retainedHistory =
    state.settledLaunches == previous.settledLaunches + transition &&
      state.currentCycleId == previous.currentCycleId && state.identity == previous.identity
  return idleReview && unchangedCounters && retainedHistory
}

private fun runtimeCompletionCandidateMatches(
  candidate: PlanBundleFeasibilityApprovalCandidate,
  request: PlanBundleFeasibilityStateWrite,
  transition: PlanBundleFeasibilitySettledLaunch,
): Boolean {
  val completedReview =
    transition.kind == PlanBundleFeasibilityLaunchKind.REVIEW &&
      transition.outcome == PlanBundleFeasibilityLaunchOutcome.COMPLETED &&
      transition.value == PLAN_BUNDLE_FEASIBILITY_RUNTIME_COMPLETION_VALUE
  val matchingCandidate =
    candidate.transitionId == transition.transitionId &&
      candidate.sourceRevision == request.state.revision && candidate.phaseId == request.phaseId &&
      candidate.attempt == request.attempt
  return completedReview && matchingCandidate
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
