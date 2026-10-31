package skillbill.engine.featuretask.runloop.state

import skillbill.application.review.model.ParallelCodeReviewResult
import skillbill.engine.featuretask.model.phase.FeatureTaskRuntimePhaseStateRequest
import skillbill.engine.featuretask.model.review.ReviewTarget
import skillbill.engine.featuretask.runloop.attempt.settlementCoupling
import skillbill.engine.featuretask.runloop.core.PhaseRun
import skillbill.engine.featuretask.runloop.core.PhaseStateRequestArgs
import skillbill.engine.featuretask.runloop.core.PhaseStateRequestAttachments
import skillbill.engine.featuretask.runloop.core.PhaseStateWriteArgs
import skillbill.engine.featuretask.runloop.phase.FeatureTaskRuntimeRunLoopPhaseBlocking
import skillbill.engine.featuretask.runner.STATUS_COMPLETED
import skillbill.engine.featuretask.runner.STATUS_RUNNING
import skillbill.engine.featuretask.slot.FindingVerificationBindingFamily
import skillbill.engine.featuretask.slot.PhaseBindingFamily
import skillbill.engine.featuretask.slot.RepairReceiptBindingFamily
import skillbill.engine.featuretask.slot.attempt.PhaseAttemptLaunchCollaborationScope
import skillbill.engine.featuretask.slot.attempt.PhaseAttemptRunHost
import skillbill.engine.featuretask.slot.state.PhaseFindingVerificationState
import skillbill.engine.featuretask.slot.state.PhaseRepairReceiptState
import skillbill.engine.featuretask.slot.state.RequiredPhaseWrite
import skillbill.error.core.catchingNonCancellation
import skillbill.goalrunner.model.UnaddressedFinding
import skillbill.review.model.ReviewFindingVerdict
import skillbill.workflow.model.goalreview.FeatureTaskRuntimeRepairReceipt
import skillbill.workflow.model.goalreview.GoalSubtaskReviewState
import skillbill.workflow.model.goalreview.upsertRepairReceipt
import skillbill.workflow.taskruntime.model.feature.FeatureTaskRuntimeVerificationBoundaryHeadingProvenance
import skillbill.workflow.taskruntime.model.handoff.assembly.FeatureTaskRuntimePhaseOutput
import skillbill.workflow.taskruntime.model.handoff.task.NormalizedFeatureTaskRuntimePhaseOutput
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
    requireAcceptedWriter(FindingVerificationBindingFamily)
    return environment.recorder.persistFindingVerificationCheckpoint(workflowId, dispositions)
  }

  override fun verificationBoundarySelection() =
    environment.recorder.loadFindingVerificationBoundarySelection(workflowId)

  override fun persistVerificationBoundarySelection(
    selections: Map<String, List<FeatureTaskRuntimeVerificationBoundaryHeadingProvenance>>,
  ): Boolean {
    requireAcceptedWriter(FindingVerificationBindingFamily)
    return environment.recorder.persistFindingVerificationBoundarySelection(workflowId, selections)
  }

  override fun appendRejectedVerificationFindings(
    passNumber: Int,
    rejected: List<UnaddressedFinding>,
  ) {
    requireAcceptedWriter(FindingVerificationBindingFamily)
    environment.recorder.appendRejectedVerificationFindings(workflowId, passNumber, rejected)
  }

  override fun goalReviewState(): GoalSubtaskReviewState? =
    FeatureTaskRuntimeRunLoopPhaseBlocking.goalReviewStateOrNull(
      environment.request,
      environment.goalContinuationRecorder,
    )

  override fun recordRepairReceipt(receipt: FeatureTaskRuntimeRepairReceipt): Boolean {
    requireAcceptedWriter(RepairReceiptBindingFamily)
    return environment.goalContinuationRecorder.updateReviewState(workflowId) { it.upsertRepairReceipt(receipt) } !=
      null
  }

  private fun requireAcceptedWriter(family: PhaseBindingFamily<*>) {
    bindingCoordinator.requireActiveStepBinding(run, fanOutUnitId)
    check(environment.selectedOwnerOf(run.phaseId)?.stepDescriptor(run.phaseId)?.family == family) {
      "Finding write belongs to the accepted binding family '$family'."
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

internal object FeatureTaskRuntimeRunLoopCarriedForwardReview {
  fun complete(
    environment: PhaseAttemptLaunchCollaborationScope,
    run: PhaseRun,
    iteration: Int,
    output: NormalizedFeatureTaskRuntimePhaseOutput,
  ): String? {
    val phaseState =
      FeatureTaskRuntimeRunLoopPhaseBlocking.phaseStateRequest(
        environment.request,
        environment.settlementCoupling().progress,
        environment.goalContinuationRecorder,
        PhaseStateRequestArgs(
          write = PhaseStateWriteArgs(run, iteration, STATUS_COMPLETED, true, output.canonicalJson),
          extras = PhaseStateRequestAttachments(normalizedOutput = output, repairEvidence = null),
        ),
      )
    val prefix = "Carried-forward goal review could not atomically persist its canonical result."
    return catchingNonCancellation {
      environment.coupledRunTransitions.persistAuthoritativePhaseCompletion(
        recorder = environment.recorder,
        phaseState = phaseState,
        inMemoryOutput = FeatureTaskRuntimePhaseOutput(run.phaseId, iteration, output.canonicalJson, output, null),
      )
    }.fold(
      onSuccess = { persisted -> (persisted as? RequiredPhaseWrite.Rejected)?.message },
      onFailure = { error -> "$prefix ${error.message.orEmpty()}" },
    )
  }

  fun settle(
    environment: PhaseAttemptLaunchCollaborationScope,
    run: PhaseRun,
    reviewPassNumber: () -> Int,
    output: NormalizedFeatureTaskRuntimePhaseOutput,
  ) {
    val phaseId = run.phaseId
    if (environment.progress.phase(phaseId).completed) {
      return
    }
    val reentry = environment.session.activeReentry
    val iteration = environment.progress.phase(phaseId).nextIteration
    val priorRecord = environment.progress.phase(phaseId).record
    val phaseState =
      FeatureTaskRuntimePhaseStateRequest(
        workflowId = environment.request.workflowId,
        phaseId = phaseId,
        status = STATUS_COMPLETED,
        attemptCount = iteration,
        resolvedAgentId = priorRecord?.resolvedAgentId ?: "user-directed",
        finished = true,
        reviewPassNumber = reviewPassNumber(),
        outputArtifact = output.canonicalJson,
        normalizedOutput = output,
        repairEvidence = null,
        loopId = reentry?.loopId,
        edgeIteration = reentry?.edgeIteration,
      )
    val start =
      phaseState.copy(status = STATUS_RUNNING, finished = false, normalizedOutput = null, outputArtifact = null)
    check(environment.recorder.recordRequiredPhaseStart(start) is RequiredPhaseWrite.Acknowledged) {
      "Carried-forward goal review could not acquire its accounting start."
    }
    val persisted =
      environment.coupledRunTransitions.persistCarriedForwardPhaseCompletion(
        recorder = environment.recorder,
        phaseState = phaseState,
        inMemoryOutput = FeatureTaskRuntimePhaseOutput(phaseId, iteration, output.canonicalJson, output, null),
        clearPendingReentry = reentry != null,
      )
    if (!persisted) {
      error("Carried-forward goal review could not atomically persist its canonical result.")
    }
  }
}
