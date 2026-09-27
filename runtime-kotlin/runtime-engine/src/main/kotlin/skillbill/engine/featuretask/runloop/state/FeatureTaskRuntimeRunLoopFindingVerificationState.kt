package skillbill.engine.featuretask.runloop.state

import skillbill.engine.featuretask.runloop.phase.FeatureTaskRuntimeRunLoopPhaseBlocking
import skillbill.engine.featuretask.slot.attempt.PhaseAttemptEnvironment
import skillbill.engine.featuretask.slot.state.PhaseFindingVerificationState
import skillbill.goalrunner.model.UnaddressedFinding
import skillbill.review.model.ReviewFindingVerdict
import skillbill.workflow.model.goalreview.FeatureTaskRuntimeRepairReceipt
import skillbill.workflow.model.goalreview.GoalSubtaskReviewState
import skillbill.workflow.model.goalreview.upsertRepairReceipt
import skillbill.workflow.taskruntime.model.feature.FeatureTaskRuntimeVerificationBoundaryHeadingProvenance
import skillbill.workflow.taskruntime.model.validation.FeatureTaskRuntimeFindingVerificationDisposition

internal class FeatureTaskRuntimeRunLoopFindingVerificationState(
  private val environment: PhaseAttemptEnvironment,
) : PhaseFindingVerificationState {
  private val workflowId = environment.request.workflowId

  override fun unaddressedReviewFindings(): List<UnaddressedFinding> =
    environment.recorder.fetchUnaddressedLedger(workflowId)

  override fun recordedFindingVerdicts(envelope: Map<String, Any?>): List<ReviewFindingVerdict> =
    environment.recorder.recordedFindingVerdicts(envelope)

  override fun findingVerificationCheckpoint(): List<FeatureTaskRuntimeFindingVerificationDisposition>? =
    environment.recorder.loadFindingVerificationCheckpoint(workflowId)

  override fun persistFindingVerificationCheckpoint(
    dispositions: List<FeatureTaskRuntimeFindingVerificationDisposition>,
  ): Boolean = environment.recorder.persistFindingVerificationCheckpoint(workflowId, dispositions)

  override fun verificationBoundarySelection() =
    environment.recorder.loadFindingVerificationBoundarySelection(workflowId)

  override fun persistVerificationBoundarySelection(
    selections: Map<String, List<FeatureTaskRuntimeVerificationBoundaryHeadingProvenance>>,
  ): Boolean = environment.recorder.persistFindingVerificationBoundarySelection(workflowId, selections)

  override fun appendRejectedVerificationFindings(
    passNumber: Int,
    rejected: List<UnaddressedFinding>,
  ) {
    environment.recorder.appendRejectedVerificationFindings(workflowId, passNumber, rejected)
  }

  override fun goalReviewState(): GoalSubtaskReviewState? =
    FeatureTaskRuntimeRunLoopPhaseBlocking.goalReviewStateOrNull(
      environment.request,
      environment.goalContinuationRecorder,
    )

  override fun recordRepairReceipt(receipt: FeatureTaskRuntimeRepairReceipt): Boolean =
    environment.goalContinuationRecorder.updateReviewState(workflowId) { it.upsertRepairReceipt(receipt) } != null
}
