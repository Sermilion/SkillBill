package skillbill.engine.featuretask.phaserun.planning

import skillbill.engine.featuretask.model.phase.FeatureTaskRuntimePhaseStateRequest
import skillbill.engine.featuretask.phase.planning.feasibilityApprovalCandidateMatchesRequest
import skillbill.workflow.model.WorkflowStepStatus
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimePhaseRecord
import skillbill.workflow.taskruntime.model.planning.FeatureTaskRuntimePlanBundleFeasibilityState
import skillbill.workflow.taskruntime.model.planning.isPublishedPlanBundleFeasibilityApprovalReplay
import skillbill.workflow.taskruntime.model.planning.publishPlanBundleFeasibilityApproval

internal sealed interface InMemoryFeasibilityCompletion {
  data object Refused : InMemoryFeasibilityCompletion

  data object Replayed : InMemoryFeasibilityCompletion

  data class Pending(val publishedState: FeatureTaskRuntimePlanBundleFeasibilityState?) : InMemoryFeasibilityCompletion
}

internal class InMemoryPlanningRecordsState(
  val phaseRecords: LinkedHashMap<String, FeatureTaskRuntimePhaseRecord>,
  private val unitRecordOwner: Pair<String, String>?,
) {
  val feasibilityStates = mutableMapOf<String, FeatureTaskRuntimePlanBundleFeasibilityState>()

  private fun ownsUnitRecord(
    workflowId: String,
    phaseId: String,
  ): Boolean = unitRecordOwner == (workflowId to phaseId)

  fun prepareCompletion(
    request: FeatureTaskRuntimePhaseStateRequest,
    completedRecordMatches: Boolean,
  ): InMemoryFeasibilityCompletion {
    if (!request.feasibilityApprovalRequired ||
      ownsUnitRecord(request.workflowId, request.phaseId)
    ) {
      return InMemoryFeasibilityCompletion.Pending(null)
    }
    val previous = phaseRecords[request.phaseId]?.takeIf { it.status == WorkflowStepStatus.COMPLETED }
    return if (previous != null) {
      if (replayMatches(request, previous, completedRecordMatches)) {
        InMemoryFeasibilityCompletion.Replayed
      } else {
        InMemoryFeasibilityCompletion.Refused
      }
    } else {
      proposedCompletion(request)
    }
  }

  private fun replayMatches(
    request: FeatureTaskRuntimePhaseStateRequest,
    previous: FeatureTaskRuntimePhaseRecord,
    completedRecordMatches: Boolean,
  ): Boolean {
    val state = feasibilityStates[request.workflowId] ?: return false
    val candidate = state.approvalCandidate ?: return false
    val expectedRevision = request.feasibilityApprovalExpectedRevision ?: return false
    val matchingSource =
      candidate.sourceRevision == request.feasibilityApprovalRevision &&
        candidate.transitionId == request.feasibilityApprovalTransitionId
    return matchingSource && completedRecordMatches && candidate.matchesRecord(previous) &&
      isPublishedPlanBundleFeasibilityApprovalReplay(
        state,
        request.phaseId,
        request.attemptCount,
        expectedRevision,
        candidate.transitionId,
      )
  }

  private fun proposedCompletion(request: FeatureTaskRuntimePhaseStateRequest): InMemoryFeasibilityCompletion {
    val state = feasibilityStates[request.workflowId] ?: return InMemoryFeasibilityCompletion.Refused
    val expectedRevision = request.feasibilityApprovalExpectedRevision ?: return InMemoryFeasibilityCompletion.Refused
    val published =
      state.approvalCandidate?.takeIf { feasibilityApprovalCandidateMatchesRequest(it, request) }?.let {
        publishPlanBundleFeasibilityApproval(state, request.phaseId, request.attemptCount, expectedRevision)
      }
    return published?.let(InMemoryFeasibilityCompletion::Pending) ?: InMemoryFeasibilityCompletion.Refused
  }
}
