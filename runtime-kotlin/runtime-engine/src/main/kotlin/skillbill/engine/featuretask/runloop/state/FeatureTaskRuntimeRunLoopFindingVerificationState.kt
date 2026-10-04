package skillbill.engine.featuretask.runloop.state

import skillbill.application.review.model.ParallelCodeReviewResult
import skillbill.engine.featuretask.model.review.ReviewTarget
import skillbill.engine.featuretask.runloop.core.PhaseRun
import skillbill.engine.featuretask.runloop.phase.FeatureTaskRuntimeRunLoopPhaseBlocking
import skillbill.engine.featuretask.slot.PhaseExecutionBindingKind
import skillbill.engine.featuretask.slot.attempt.PhaseAttemptLaunchCollaborationScope
import skillbill.engine.featuretask.slot.attempt.PhaseAttemptRunHost
import skillbill.engine.featuretask.slot.state.PhaseFindingVerificationState
import skillbill.engine.featuretask.slot.state.PhaseRepairReceiptState
import skillbill.goalrunner.model.UnaddressedFinding
import skillbill.review.model.ReviewFindingVerdict
import skillbill.workflow.model.goalreview.FeatureTaskRuntimeRepairReceipt
import skillbill.workflow.model.goalreview.GoalSubtaskReviewState
import skillbill.workflow.model.goalreview.upsertRepairReceipt
import skillbill.workflow.taskruntime.model.feature.FeatureTaskRuntimeVerificationBoundaryHeadingProvenance
import skillbill.workflow.taskruntime.model.validation.FeatureTaskRuntimeFindingVerificationDisposition

internal class FeatureTaskRuntimeRunLoopFindingVerificationState(
  private val environment: PhaseAttemptLaunchCollaborationScope,
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
    requireAcceptedWriter(PhaseExecutionBindingKind.FINDING_VERIFICATION)
    return environment.recorder.persistFindingVerificationCheckpoint(workflowId, dispositions)
  }

  override fun verificationBoundarySelection() =
    environment.recorder.loadFindingVerificationBoundarySelection(workflowId)

  override fun persistVerificationBoundarySelection(
    selections: Map<String, List<FeatureTaskRuntimeVerificationBoundaryHeadingProvenance>>,
  ): Boolean {
    requireAcceptedWriter(PhaseExecutionBindingKind.FINDING_VERIFICATION)
    return environment.recorder.persistFindingVerificationBoundarySelection(workflowId, selections)
  }

  override fun appendRejectedVerificationFindings(
    passNumber: Int,
    rejected: List<UnaddressedFinding>,
  ) {
    requireAcceptedWriter(PhaseExecutionBindingKind.FINDING_VERIFICATION)
    environment.recorder.appendRejectedVerificationFindings(workflowId, passNumber, rejected)
  }

  override fun goalReviewState(): GoalSubtaskReviewState? =
    FeatureTaskRuntimeRunLoopPhaseBlocking.goalReviewStateOrNull(
      environment.request,
      environment.goalContinuationRecorder,
    )

  override fun recordRepairReceipt(receipt: FeatureTaskRuntimeRepairReceipt): Boolean {
    requireAcceptedWriter(PhaseExecutionBindingKind.REPAIR_RECEIPT)
    return environment.goalContinuationRecorder.updateReviewState(workflowId) { it.upsertRepairReceipt(receipt) } !=
      null
  }

  private fun requireAcceptedWriter(kind: PhaseExecutionBindingKind) {
    bindingCoordinator.requireActiveStepBinding(run, fanOutUnitId)
    check(environment.selectedOwnerOf(run.phaseId)?.executionBindingKind(run.phaseId) == kind) {
      "Finding write belongs to the accepted binding kind '$kind'."
    }
  }
}

internal fun PhaseAttemptRunHost.recordReviewRunForAcceptedStep(
  reviewRunId: String,
  result: ParallelCodeReviewResult,
  laneTelemetryRecorded: Boolean,
) {
  recordReviewRunForRunStatePorts(reviewRunId, result, laneTelemetryRecorded)
}

internal fun PhaseAttemptRunHost.pinnedReviewTargetForAcceptedStep(resolve: () -> ReviewTarget): ReviewTarget =
  pinnedReviewTargetForRunStatePorts(resolve)
