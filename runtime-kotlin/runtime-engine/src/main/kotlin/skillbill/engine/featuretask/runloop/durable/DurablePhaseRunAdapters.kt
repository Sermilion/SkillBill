package skillbill.engine.featuretask.runloop.durable

import skillbill.application.diagnostics.model.RejectedOutputDiagnosticRequest
import skillbill.engine.featuretask.lifecycle.continuation.FeatureTaskRuntimeGoalContinuationRecorder
import skillbill.engine.featuretask.lifecycle.continuation.GoalContinuationStateRecordRequest
import skillbill.engine.featuretask.lifecycle.continuation.GoalReviewPassCompletionRequest
import skillbill.engine.featuretask.lifecycle.continuation.appendRemediationRollbackDegradationEvidence
import skillbill.engine.featuretask.lifecycle.continuation.lastGoalReviewResult
import skillbill.engine.featuretask.lifecycle.continuation.reviewState
import skillbill.engine.featuretask.lifecycle.remediation.RemediationDegradationSignal
import skillbill.engine.featuretask.lifecycle.subtask.SubtaskCommitPreservationRequest
import skillbill.engine.featuretask.lifecycle.subtask.writeSubtaskCommitPreservingHistory
import skillbill.engine.featuretask.model.execution.AdmittedFeatureTaskRuntimeExecution
import skillbill.engine.featuretask.model.phase.AppendCheckpointIdentityArgs
import skillbill.engine.featuretask.model.phase.FeatureTaskPhaseSettlementEnvelope
import skillbill.engine.featuretask.model.phase.FeatureTaskRuntimePhaseLaunchBriefing
import skillbill.engine.featuretask.model.phase.FeatureTaskRuntimePhaseLedgerRequest
import skillbill.engine.featuretask.model.phase.FeatureTaskRuntimePhaseStateRequest
import skillbill.engine.featuretask.model.phase.FeatureTaskRuntimeProducerOutputRead
import skillbill.engine.featuretask.model.phase.FeatureTaskRuntimeProjectionRejection
import skillbill.engine.featuretask.model.phase.GoalReviewPhaseCompletionRequest
import skillbill.engine.featuretask.model.phase.ProducerOutputQueryArgs
import skillbill.engine.featuretask.model.review.FeatureTaskRuntimeRejectedOutputWrite
import skillbill.engine.featuretask.model.review.GoalSubtaskReviewInputPreparation
import skillbill.engine.featuretask.model.review.GoalSubtaskReviewPassReservation
import skillbill.engine.featuretask.persist.acceptance.AcceptanceCriterionRepairSession
import skillbill.engine.featuretask.phase.core.FeatureTaskPhaseSettlementService
import skillbill.engine.featuretask.phase.record.FeatureTaskRuntimeDecomposeTerminalRecorder
import skillbill.engine.featuretask.phase.record.FeatureTaskRuntimeNoChangePausePort
import skillbill.engine.featuretask.phase.record.FeatureTaskRuntimePhaseRecorder
import skillbill.engine.featuretask.phase.record.requireCurrentWorker
import skillbill.engine.featuretask.slot.state.AcceptanceCriterionRepairCycleRecords
import skillbill.engine.featuretask.slot.state.FeatureTaskRuntimeImplementationTurnWrite
import skillbill.engine.featuretask.slot.state.PhaseImplementationProgressRecords
import skillbill.engine.featuretask.slot.state.PhasePlanRecords
import skillbill.engine.featuretask.slot.state.PhaseRunCheckpoints
import skillbill.engine.featuretask.slot.state.PhaseRunGoal
import skillbill.engine.featuretask.slot.state.PhaseRunRecords
import skillbill.engine.featuretask.slot.state.PhaseRunSettlements
import skillbill.engine.featuretask.slot.state.RequiredPhaseWrite
import skillbill.engine.featuretask.slot.state.RequiredPhaseWriteKind
import skillbill.error.featuretask.InvalidFeatureTaskRuntimeHandoffProjectionContext
import skillbill.goalrunner.model.UnaddressedFinding
import skillbill.ports.db.DatabaseSessionFactory
import skillbill.ports.diagnostics.model.ProducerOutputEvidence
import skillbill.ports.workflow.gitops.WorkflowGitOperations
import skillbill.ports.workflow.gitops.model.WorkflowGitOperationResult
import skillbill.review.model.ReviewFindingVerdict
import skillbill.workflow.model.goalreview.GoalSubtaskReviewState
import skillbill.workflow.taskruntime.model.audit.AcceptanceCriterionRepairOutcomeKind
import skillbill.workflow.taskruntime.model.audit.FeatureTaskRuntimeNoChangePause
import skillbill.workflow.taskruntime.model.audit.FeatureTaskRuntimeQuarantineEntry
import skillbill.workflow.taskruntime.model.core.FeatureTaskRuntimeDecomposeTerminal
import skillbill.workflow.taskruntime.model.core.FeatureTaskRuntimePlanBundle
import skillbill.workflow.taskruntime.model.core.FeatureTaskRuntimeResolvedBranch
import skillbill.workflow.taskruntime.model.feature.FeatureTaskRuntimeVerificationBoundaryHeadingProvenance
import skillbill.workflow.taskruntime.model.handoff.PhaseHandoffProjectionDeclaration
import skillbill.workflow.taskruntime.model.handoff.task.FeatureTaskRuntimeSharedEvidenceMeasurement
import skillbill.workflow.taskruntime.model.persistence.FeatureTaskRuntimeCheckpointIdentity
import skillbill.workflow.taskruntime.model.persistence.FeatureTaskRuntimeImplementationAttempt
import skillbill.workflow.taskruntime.model.persistence.FeatureTaskRuntimeImplementationProgress
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimeDeliveredProjectionRecord
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimePhaseLedgerEntry
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimePhaseRecord
import skillbill.workflow.taskruntime.model.qa.QaRepairState
import skillbill.workflow.taskruntime.model.repair.FeatureTaskRuntimeOperatorBlockRetry
import skillbill.workflow.taskruntime.model.validation.FeatureTaskRuntimeFindingVerificationDisposition
import skillbill.workflow.taskruntime.model.validation.FeatureTaskRuntimeValidationGateProgress
import java.nio.file.Path

internal class DurableOwnedNoChangePause(
  private val recorder: FeatureTaskRuntimePhaseRecorder,
  private val admitted: AdmittedFeatureTaskRuntimeExecution,
) : FeatureTaskRuntimeNoChangePausePort {
  override fun loadNoChangePause(workflowId: String): FeatureTaskRuntimeNoChangePause? =
    recorder.loadNoChangePause(workflowId)

  override fun persistNoChangePause(
    workflowId: String,
    pause: FeatureTaskRuntimeNoChangePause,
  ) {
    admitted.ownedWrite({ recorder.persistOwnedNoChangePause(workflowId, pause, it) }) {
      recorder.persistNoChangePause(workflowId, pause)
    }
  }
}

internal class DurableImplementationProgressRecords(
  private val recorder: FeatureTaskRuntimePhaseRecorder,
  private val admitted: AdmittedFeatureTaskRuntimeExecution,
) : PhaseImplementationProgressRecords {
  override fun loadImplementationAttempts(workflowId: String): List<FeatureTaskRuntimeImplementationAttempt>? =
    recorder.loadImplementationAttempts(workflowId)

  override fun loadImplementationProgress(workflowId: String): FeatureTaskRuntimeImplementationProgress? =
    recorder.loadImplementationProgress(workflowId)

  override fun recordImplementationTurn(write: FeatureTaskRuntimeImplementationTurnWrite): RequiredPhaseWrite =
    admitted.ownedWrite({ recorder.recordOwnedImplementationTurn(write, it) }) {
      recorder.recordImplementationTurn(write)
    }
}

internal class DurablePhaseRunRecords(
  private val recorder: FeatureTaskRuntimePhaseRecorder,
  decomposeTerminalRecorder: FeatureTaskRuntimeDecomposeTerminalRecorder,
  private val admitted: AdmittedFeatureTaskRuntimeExecution,
  private val acceptance: AcceptanceCriterionRepairSession? = null,
) : PhaseRunRecords,
  PhasePlanRecords by DurablePhasePlanRecords(decomposeTerminalRecorder, admitted),
  AcceptanceCriterionRepairCycleRecords by (
    acceptance ?: object : AcceptanceCriterionRepairCycleRecords {
      override fun requiresExplicitStart(phaseId: String): Boolean = false

      override fun isRepairStep(phaseId: String): Boolean = false

      override fun settleUnsuccessfulRepair(
        phase: FeatureTaskRuntimePhaseStateRequest,
        kind: AcceptanceCriterionRepairOutcomeKind,
      ): Boolean? = null
    }
  ),
  FeatureTaskRuntimeNoChangePausePort by DurableOwnedNoChangePause(recorder, admitted),
  PhaseImplementationProgressRecords by DurableImplementationProgressRecords(recorder, admitted) {
  override fun recordRejectedOutput(
    request: RejectedOutputDiagnosticRequest,
    producerGeneration: Int,
  ): FeatureTaskRuntimeRejectedOutputWrite =
    admitted.ownedWrite({ recorder.recordOwnedRejectedOutput(request, producerGeneration, it) }) {
      recorder.recordRejectedOutput(request, producerGeneration)
    }

  override fun retainProducerOutput(evidence: ProducerOutputEvidence) =
    admitted.ownedWrite({ recorder.retainOwnedProducerOutput(evidence, it) }) {
      recorder.retainProducerOutput(evidence)
    }

  override fun producerOutput(args: ProducerOutputQueryArgs): FeatureTaskRuntimeProducerOutputRead =
    recorder.producerOutput(args)

  override fun recordPhaseState(request: FeatureTaskRuntimePhaseStateRequest): Boolean =
    admitted.ownedWrite({ recorder.recordOwnedPhaseState(request, it) }) { recorder.recordPhaseState(request) }

  override fun recordRequiredPhaseStart(request: FeatureTaskRuntimePhaseStateRequest): RequiredPhaseWrite =
    when (val started = acceptance?.start(request)) {
      null ->
        admitted.ownedWrite({ recorder.recordOwnedRequiredPhaseStart(request, it) }) {
          recorder.recordRequiredPhaseStart(request)
        }
      RequiredPhaseWrite.Acknowledged ->
        if (recorder.bindOwnedPhaseCoordinates(request, admitted)) {
          started
        } else {
          RequiredPhaseWrite.Rejected(
            RequiredPhaseWriteKind.START,
            request.workflowId,
            request.phaseId,
            request.attemptCount,
          )
        }
      else -> started
    }

  override fun recordCompletedPhase(request: FeatureTaskRuntimePhaseStateRequest): RequiredPhaseWrite =
    acceptance?.complete(request)
      ?: if (
        admitted.ownedWrite({ recorder.recordOwnedCompletedPhase(request, it) }) {
          recorder.recordCompletedPhase(request)
        }
      ) {
        RequiredPhaseWrite.Acknowledged
      } else {
        RequiredPhaseWrite.Rejected(
          RequiredPhaseWriteKind.COMPLETION,
          request.workflowId,
          request.phaseId,
          request.attemptCount,
          detail = "The authoritative workflow record is missing. The producer output is retained.",
        )
      }

  override fun recordIncompleteImplementationAttempt(request: FeatureTaskRuntimePhaseStateRequest): Boolean =
    admitted.ownedWrite({ recorder.recordOwnedIncompleteImplementationAttempt(request, it) }) {
      recorder.recordIncompleteImplementationAttempt(request)
    }

  override fun loadPhaseRecords(workflowId: String): Map<String, FeatureTaskRuntimePhaseRecord>? =
    recorder.loadPhaseRecords(workflowId)

  override fun loadQaRepairState(workflowId: String): QaRepairState? = recorder.loadQaRepairState(workflowId)

  override fun loadOperatorBlockRetry(workflowId: String): FeatureTaskRuntimeOperatorBlockRetry? =
    recorder.loadOperatorBlockRetry(
      workflowId,
    )

  override fun completeGoalReviewPhase(completion: GoalReviewPhaseCompletionRequest): Boolean =
    admitted.ownedWrite({ recorder.completeOwnedGoalReviewPhase(completion, it) }) {
      recorder.completeGoalReviewPhase(completion)
    }

  override fun persistReviewGenerationInvalidation(
    workflowId: String,
    reviewStepId: String,
  ): Int? =
    admitted.ownedWrite({ recorder.persistOwnedReviewGenerationInvalidation(workflowId, reviewStepId, it) }) {
      recorder.persistReviewGenerationInvalidation(workflowId, reviewStepId)
    }

  override fun invalidateQuarantinedProducerRecord(
    workflowId: String,
    producerPhaseId: String,
    loopId: String,
    edgeIteration: Int,
  ): Boolean =
    admitted.ownedWrite({
      recorder.invalidateQuarantinedProducerRecord(
        workflowId,
        producerPhaseId,
        loopId,
        edgeIteration,
        it,
      )
    }) {
      recorder.invalidateQuarantinedProducerRecord(
        workflowId,
        producerPhaseId,
        loopId,
        edgeIteration,
      )
    }

  override fun recordedFindingVerdicts(output: Map<String, Any?>): List<ReviewFindingVerdict> =
    recorder.recordedFindingVerdicts(output)

  override fun fetchUnaddressedLedger(workflowId: String): List<UnaddressedFinding> =
    recorder.fetchUnaddressedLedger(workflowId)

  override fun appendRejectedVerificationFindings(
    workflowId: String,
    passNumber: Int,
    rejected: List<UnaddressedFinding>,
  ) = admitted.ownedWrite({
    recorder.appendOwnedRejectedVerificationFindings(workflowId, passNumber, rejected, it)
  }) {
    recorder.appendRejectedVerificationFindings(workflowId, passNumber, rejected)
  }

  override fun loadFindingVerificationCheckpoint(
    workflowId: String,
  ): List<FeatureTaskRuntimeFindingVerificationDisposition>? = recorder.loadFindingVerificationCheckpoint(workflowId)

  override fun loadFindingVerificationBoundarySelection(
    workflowId: String,
  ): Map<String, List<FeatureTaskRuntimeVerificationBoundaryHeadingProvenance>>? =
    recorder.loadFindingVerificationBoundarySelection(workflowId)

  override fun persistFindingVerificationBoundarySelection(
    workflowId: String,
    selections: Map<String, List<FeatureTaskRuntimeVerificationBoundaryHeadingProvenance>>,
  ): Boolean =
    admitted.ownedWrite({
      recorder.persistOwnedFindingVerificationBoundarySelection(workflowId, selections, it)
    }) {
      recorder.persistFindingVerificationBoundarySelection(workflowId, selections)
    }

  override fun persistFindingVerificationCheckpoint(
    workflowId: String,
    dispositions: List<FeatureTaskRuntimeFindingVerificationDisposition>,
  ): Boolean =
    admitted.ownedWrite({ recorder.persistOwnedFindingVerificationCheckpoint(workflowId, dispositions, it) }) {
      recorder.persistFindingVerificationCheckpoint(workflowId, dispositions)
    }

  override fun recordPhaseBriefing(
    workflowId: String,
    briefing: FeatureTaskRuntimePhaseLaunchBriefing,
    sharedEvidenceMeasurement: FeatureTaskRuntimeSharedEvidenceMeasurement?,
    attempt: Int,
  ): RequiredPhaseWrite =
    admitted.ownedWrite({
      recorder.recordOwnedPhaseBriefing(workflowId, briefing, sharedEvidenceMeasurement, attempt, it)
    }) {
      recorder.recordPhaseBriefing(workflowId, briefing, sharedEvidenceMeasurement, attempt)
    }

  override fun recordProjectionRejection(
    context: InvalidFeatureTaskRuntimeHandoffProjectionContext,
    repositoryCheckpointFingerprint: String?,
  ): Boolean =
    admitted.ownedWrite({
      recorder.recordOwnedProjectionRejection(context, repositoryCheckpointFingerprint, it)
    }) {
      recorder.recordProjectionRejection(context, repositoryCheckpointFingerprint)
    }

  override fun recordProjectionRejection(rejection: FeatureTaskRuntimeProjectionRejection): Boolean =
    admitted.ownedWrite({ recorder.recordOwnedProjectionRejection(rejection, it) }) {
      recorder.recordProjectionRejection(rejection)
    }

  override fun validateHandoffDeclarations(declarations: List<PhaseHandoffProjectionDeclaration>) =
    recorder.validateHandoffDeclarations(declarations)

  override fun loadDeliveredProjections(
    workflowId: String,
  ): Map<String, FeatureTaskRuntimeDeliveredProjectionRecord>? = recorder.loadDeliveredProjections(workflowId)

  override fun loadValidationGateProgress(workflowId: String): FeatureTaskRuntimeValidationGateProgress? =
    recorder.loadValidationGateProgress(workflowId)

  override fun persistValidationGateProgress(
    workflowId: String,
    progress: FeatureTaskRuntimeValidationGateProgress,
  ) {
    admitted.ownedWrite({ recorder.persistOwnedValidationGateProgress(workflowId, progress, it) }) {
      recorder.persistValidationGateProgress(workflowId, progress)
    }
  }

  override fun loadBuildGateProgress(workflowId: String): FeatureTaskRuntimeValidationGateProgress? =
    recorder.loadBuildGateProgress(workflowId)

  override fun persistBuildGateProgress(
    workflowId: String,
    progress: FeatureTaskRuntimeValidationGateProgress,
  ) {
    admitted.ownedWrite({ recorder.persistOwnedBuildGateProgress(workflowId, progress, it) }) {
      recorder.persistBuildGateProgress(workflowId, progress)
    }
  }

  override fun appendLedgerEntry(request: FeatureTaskRuntimePhaseLedgerRequest): Boolean =
    admitted.ownedWrite({ recorder.appendOwnedLedgerEntry(request, it) }) { recorder.appendLedgerEntry(request) }

  override fun loadPhaseLedger(workflowId: String): List<FeatureTaskRuntimePhaseLedgerEntry>? =
    recorder.loadPhaseLedger(workflowId)

  override fun appendQuarantineEntry(
    workflowId: String,
    entry: FeatureTaskRuntimeQuarantineEntry,
  ): Boolean =
    admitted.ownedWrite({ recorder.appendOwnedQuarantineEntry(workflowId, entry, it) }) {
      recorder.appendQuarantineEntry(workflowId, entry)
    }

  override fun loadQuarantinedRecords(workflowId: String): List<FeatureTaskRuntimeQuarantineEntry>? =
    recorder.loadQuarantinedRecords(workflowId)

  override fun loadResolvedBranch(workflowId: String): FeatureTaskRuntimeResolvedBranch? =
    recorder.loadResolvedBranch(workflowId)

  override fun loadGoalStartResolvedBranch(parentWorkflowId: String): FeatureTaskRuntimeResolvedBranch? =
    recorder.loadGoalStartResolvedBranch(parentWorkflowId)

  override fun appendCheckpointIdentity(args: AppendCheckpointIdentityArgs): Boolean =
    admitted.ownedWrite({ recorder.appendOwnedCheckpointIdentity(args, it) }) {
      recorder.appendCheckpointIdentity(args)
    }

  override fun loadCheckpointIdentities(workflowId: String): List<FeatureTaskRuntimeCheckpointIdentity>? =
    recorder.loadCheckpointIdentities(workflowId)

  override fun recordWorkflowOwnedPaths(
    workflowId: String,
    ownedPaths: List<String>,
  ): Boolean =
    admitted.ownedWrite({ recorder.recordOwnedWorkflowOwnedPaths(workflowId, ownedPaths, it) }) {
      recorder.recordWorkflowOwnedPaths(workflowId, ownedPaths)
    }
}

private class DurablePhasePlanRecords(
  private val decomposeTerminalRecorder: FeatureTaskRuntimeDecomposeTerminalRecorder,
  private val admitted: AdmittedFeatureTaskRuntimeExecution,
) : PhasePlanRecords {
  override fun loadPlanBundle(workflowId: String): FeatureTaskRuntimePlanBundle? =
    decomposeTerminalRecorder.loadPlanBundle(workflowId)

  override fun recordPlanBundle(
    workflowId: String,
    bundle: FeatureTaskRuntimePlanBundle,
  ): Boolean = decomposeTerminalRecorder.recordPlanBundle(workflowId, bundle)

  override fun recordPlanBundleWithUnits(
    workflowId: String,
    bundle: FeatureTaskRuntimePlanBundle,
    unitRecords: Map<Int, FeatureTaskRuntimePhaseRecord>,
  ): Boolean = decomposeTerminalRecorder.recordPlanBundleWithUnits(workflowId, bundle, unitRecords)

  override fun loadDecomposeTerminal(workflowId: String): FeatureTaskRuntimeDecomposeTerminal? =
    decomposeTerminalRecorder.loadDecomposeTerminal(workflowId)

  override fun recordDecomposeTerminal(
    workflowId: String,
    terminal: FeatureTaskRuntimeDecomposeTerminal,
    planStepId: String,
  ): Boolean =
    admitted.ownedWrite({
      decomposeTerminalRecorder.recordOwnedDecomposeTerminal(workflowId, terminal, planStepId, it)
    }) {
      decomposeTerminalRecorder.recordDecomposeTerminal(workflowId, terminal, planStepId)
    }
}

private fun <T> AdmittedFeatureTaskRuntimeExecution.ownedWrite(
  owned: (AdmittedFeatureTaskRuntimeExecution) -> T,
  unowned: () -> T,
): T = if (workerOwnership != null) owned(this) else unowned()

internal class DurablePhaseRunGoal(
  private val recorder: FeatureTaskRuntimeGoalContinuationRecorder,
  private val admitted: AdmittedFeatureTaskRuntimeExecution,
) : PhaseRunGoal {
  override fun recordGoalContinuationState(request: GoalContinuationStateRecordRequest): Boolean =
    recorder.recordGoalContinuationState(request, admitted)

  override fun reserveGoalReviewPass(workflowId: String): GoalSubtaskReviewPassReservation =
    recorder.reserveGoalReviewPass(workflowId, admitted)

  override fun updateReviewState(
    workflowId: String,
    transform: (GoalSubtaskReviewState) -> GoalSubtaskReviewState,
  ): GoalSubtaskReviewState? = recorder.updateReviewState(workflowId, admitted, transform)

  override fun completeGoalReviewPass(request: GoalReviewPassCompletionRequest): GoalSubtaskReviewState? =
    recorder.completeGoalReviewPass(request, admitted)

  override fun buildGoalReviewInput(
    workflowId: String,
    gitOperations: WorkflowGitOperations,
    repoRoot: Path,
    scopedUntrackedExclusions: List<String>?,
    ownedPathspec: List<String>,
  ): GoalSubtaskReviewInputPreparation =
    recorder.buildGoalReviewInput(
      workflowId = workflowId,
      gitOperations = gitOperations,
      repoRoot = repoRoot,
      scope = FeatureTaskRuntimeGoalContinuationRecorder.GoalReviewInputScope(scopedUntrackedExclusions, ownedPathspec),
      admitted = admitted,
    )

  override fun reviewState(workflowId: String): GoalSubtaskReviewState? = recorder.reviewState(workflowId)

  override fun lastGoalReviewResult(workflowId: String): String? = recorder.lastGoalReviewResult(workflowId)

  override fun appendRemediationRollbackDegradationEvidence(
    workflowId: String,
    signal: RemediationDegradationSignal,
  ) {
    recorder.appendRemediationRollbackDegradationEvidence(workflowId, signal, admitted)
  }
}

internal class DurablePhaseRunSettlements(
  private val database: DatabaseSessionFactory,
  private val admitted: AdmittedFeatureTaskRuntimeExecution,
  private val service: FeatureTaskPhaseSettlementService,
) : PhaseRunSettlements {
  override fun findEnvelope(
    workflowId: String,
    phaseId: String,
    attempt: Int,
  ): FeatureTaskPhaseSettlementEnvelope? = service.findEnvelope(workflowId, phaseId, attempt)

  override fun clear(
    workflowId: String,
    phaseId: String,
    attempt: Int,
  ): Boolean =
    database.transaction { unitOfWork ->
      if (!requireCurrentWorker(admitted, unitOfWork.workflowStates, workflowId)) {
        return@transaction false
      }
      unitOfWork.featureTaskPhaseSettlements.delete(workflowId, phaseId, attempt)
      true
    }
}

internal class DurablePhaseRunCheckpoints(
  private val gitOperations: WorkflowGitOperations,
) : PhaseRunCheckpoints {
  override fun commitSubtask(request: SubtaskCommitPreservationRequest): WorkflowGitOperationResult =
    gitOperations.writeSubtaskCommitPreservingHistory(request)
}
