package skillbill.engine.featuretask.runloop.state

import skillbill.engine.featuretask.runloop.core.PhaseRun
import skillbill.engine.featuretask.runloop.phase.FeatureTaskRuntimeRunLoopPhaseBlocking
import skillbill.engine.featuretask.slot.attempt.PhaseAttemptRemediationCollaborationScope
import skillbill.engine.featuretask.slot.state.PhaseFindingVerificationState
import skillbill.engine.featuretask.slot.state.PhaseRepairReceiptState
import skillbill.goalrunner.model.UnaddressedFinding
import skillbill.review.model.ReviewFindingVerdict
import skillbill.workflow.model.goalreview.FeatureTaskRuntimeRepairReceipt
import skillbill.workflow.model.goalreview.GoalSubtaskReviewState
import skillbill.workflow.model.goalreview.upsertRepairReceipt
import skillbill.workflow.taskruntime.model.feature.FeatureTaskRuntimeVerificationBoundaryHeadingProvenance
import skillbill.workflow.taskruntime.model.validation.FeatureTaskRuntimeFindingVerificationDisposition
import skillbill.workflow.taskruntime.phase.task.FeatureTaskRuntimePhaseWorkflowDefinition

internal class FeatureTaskRuntimeRunLoopFindingVerificationState(
  private val environment: PhaseAttemptRemediationCollaborationScope,
  private val run: PhaseRun,
  private val fanOutUnitId: Int?,
  private val bindingCoordinator: FeatureTaskRuntimeRunLoopStepBindingCoordinator,
) : PhaseFindingVerificationState,
  PhaseRepairReceiptState {
  private val workflowId = environment.request.workflowId

  override fun unaddressedReviewFindings(): List<UnaddressedFinding> =
    environment.recorder.fetchUnaddressedLedger(workflowId)

  override fun recordedFindingVerdicts(envelope: Map<String, Any?>): List<ReviewFindingVerdict> =
    environment.recorder.recordedFindingVerdicts(envelope)

  override fun findingVerificationCheckpoint(): List<FeatureTaskRuntimeFindingVerificationDisposition>? =
    environment.recorder.loadFindingVerificationCheckpoint(workflowId)

  override fun persistFindingVerificationCheckpoint(
    dispositions: List<FeatureTaskRuntimeFindingVerificationDisposition>,
  ): Boolean {
    requireAcceptedWriter(FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_VERIFY_FINDINGS)
    return environment.recorder.persistFindingVerificationCheckpoint(workflowId, dispositions)
  }

  override fun verificationBoundarySelection() =
    environment.recorder.loadFindingVerificationBoundarySelection(workflowId)

  override fun persistVerificationBoundarySelection(
    selections: Map<String, List<FeatureTaskRuntimeVerificationBoundaryHeadingProvenance>>,
  ): Boolean {
    requireAcceptedWriter(FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_VERIFY_FINDINGS)
    return environment.recorder.persistFindingVerificationBoundarySelection(workflowId, selections)
  }

  override fun appendRejectedVerificationFindings(
    passNumber: Int,
    rejected: List<UnaddressedFinding>,
  ) {
    requireAcceptedWriter(FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_VERIFY_FINDINGS)
    environment.recorder.appendRejectedVerificationFindings(workflowId, passNumber, rejected)
  }

  override fun goalReviewState(): GoalSubtaskReviewState? =
    FeatureTaskRuntimeRunLoopPhaseBlocking.goalReviewStateOrNull(
      environment.request,
      environment.goalContinuationRecorder,
    )

  override fun recordRepairReceipt(receipt: FeatureTaskRuntimeRepairReceipt): Boolean {
    requireAcceptedWriter(FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_IMPLEMENT_FIX)
    return environment.goalContinuationRecorder.updateReviewState(workflowId) { it.upsertRepairReceipt(receipt) } !=
      null
  }

  private fun requireAcceptedWriter(phaseId: String) {
    bindingCoordinator.requireActiveStepBinding(run, fanOutUnitId)
    check(run.phaseId == phaseId) { "Finding write belongs to accepted step '$phaseId'." }
  }
}
