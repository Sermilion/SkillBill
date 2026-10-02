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
import skillbill.engine.featuretask.phase.core.FeatureTaskPhaseSettlementService
import skillbill.engine.featuretask.phase.record.FeatureTaskRuntimeDecomposeTerminalRecorder
import skillbill.engine.featuretask.phase.record.FeatureTaskRuntimePhaseRecorder
import skillbill.engine.featuretask.slot.state.PhaseRunCheckpoints
import skillbill.engine.featuretask.slot.state.PhaseRunGoal
import skillbill.engine.featuretask.slot.state.PhaseRunRecords
import skillbill.engine.featuretask.slot.state.PhaseRunSettlements
import skillbill.engine.featuretask.slot.state.RequiredPhaseWrite
import skillbill.error.shellcontent.InvalidFeatureTaskRuntimeHandoffProjectionError
import skillbill.goalrunner.model.UnaddressedFinding
import skillbill.ports.diagnostics.model.ProducerOutputEvidence
import skillbill.ports.workflow.gitops.WorkflowGitOperations
import skillbill.ports.workflow.gitops.model.WorkflowGitOperationResult
import skillbill.review.model.ReviewFindingVerdict
import skillbill.workflow.model.goalreview.GoalSubtaskReviewState
import skillbill.workflow.taskruntime.model.audit.FeatureTaskRuntimeQuarantineEntry
import skillbill.workflow.taskruntime.model.core.FeatureTaskRuntimeDecomposeTerminal
import skillbill.workflow.taskruntime.model.core.FeatureTaskRuntimeResolvedBranch
import skillbill.workflow.taskruntime.model.feature.FeatureTaskRuntimeVerificationBoundaryHeadingProvenance
import skillbill.workflow.taskruntime.model.handoff.PhaseHandoffProjectionDeclaration
import skillbill.workflow.taskruntime.model.handoff.task.FeatureTaskRuntimeSharedEvidenceMeasurement
import skillbill.workflow.taskruntime.model.persistence.FeatureTaskRuntimeCheckpointIdentity
import skillbill.workflow.taskruntime.model.persistence.FeatureTaskRuntimeImplementationAttempt
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimeDeliveredProjectionRecord
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimePhaseLedgerEntry
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimePhaseRecord
import skillbill.workflow.taskruntime.model.validation.FeatureTaskRuntimeFindingVerificationDisposition
import skillbill.workflow.taskruntime.model.validation.FeatureTaskRuntimeValidationGateProgress
import java.nio.file.Path

internal class DurablePhaseRunRecords(
  private val recorder: FeatureTaskRuntimePhaseRecorder,
  private val decomposeTerminalRecorder: FeatureTaskRuntimeDecomposeTerminalRecorder,
  private val admitted: AdmittedFeatureTaskRuntimeExecution? = null,
) : PhaseRunRecords {
  override fun recordRejectedOutput(
    request: RejectedOutputDiagnosticRequest,
    producerGeneration: Int,
  ): FeatureTaskRuntimeRejectedOutputWrite = recorder.recordRejectedOutput(request, producerGeneration)

  override fun retainProducerOutput(evidence: ProducerOutputEvidence) = recorder.retainProducerOutput(evidence)

  override fun producerOutput(args: ProducerOutputQueryArgs): FeatureTaskRuntimeProducerOutputRead =
    recorder.producerOutput(args)

  override fun recordPhaseState(request: FeatureTaskRuntimePhaseStateRequest): Boolean =
    recorder.recordPhaseState(request)

  override fun recordRequiredPhaseStart(request: FeatureTaskRuntimePhaseStateRequest): RequiredPhaseWrite =
    recorder.recordRequiredPhaseStart(request)

  override fun recordCompletedPhase(request: FeatureTaskRuntimePhaseStateRequest): Boolean =
    recorder.recordCompletedPhase(request)

  override fun recordIncompleteImplementationAttempt(request: FeatureTaskRuntimePhaseStateRequest): Boolean =
    recorder.recordIncompleteImplementationAttempt(request)

  override fun loadImplementationAttempts(workflowId: String): List<FeatureTaskRuntimeImplementationAttempt>? =
    recorder.loadImplementationAttempts(workflowId)

  override fun loadPhaseRecords(workflowId: String): Map<String, FeatureTaskRuntimePhaseRecord>? =
    recorder.loadPhaseRecords(workflowId)

  override fun completeGoalReviewPhase(completion: GoalReviewPhaseCompletionRequest): Boolean =
    recorder.completeGoalReviewPhase(completion)

  override fun persistReviewGenerationInvalidation(
    workflowId: String,
    reviewStepId: String,
  ): Int? = recorder.persistReviewGenerationInvalidation(workflowId, reviewStepId)

  override fun invalidateQuarantinedProducerRecord(
    workflowId: String,
    producerPhaseId: String,
    loopId: String,
    edgeIteration: Int,
  ): Boolean =
    recorder.invalidateQuarantinedProducerRecord(
      workflowId,
      producerPhaseId,
      loopId,
      edgeIteration,
      admitted,
    )

  override fun recordedFindingVerdicts(output: Map<String, Any?>): List<ReviewFindingVerdict> =
    recorder.recordedFindingVerdicts(output)

  override fun fetchUnaddressedLedger(workflowId: String): List<UnaddressedFinding> =
    recorder.fetchUnaddressedLedger(workflowId)

  override fun appendRejectedVerificationFindings(
    workflowId: String,
    passNumber: Int,
    rejected: List<UnaddressedFinding>,
  ) = recorder.appendRejectedVerificationFindings(workflowId, passNumber, rejected)

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
  ): Boolean = recorder.persistFindingVerificationBoundarySelection(workflowId, selections)

  override fun persistFindingVerificationCheckpoint(
    workflowId: String,
    dispositions: List<FeatureTaskRuntimeFindingVerificationDisposition>,
  ): Boolean = recorder.persistFindingVerificationCheckpoint(workflowId, dispositions)

  override fun recordPhaseBriefing(
    workflowId: String,
    briefing: FeatureTaskRuntimePhaseLaunchBriefing,
    sharedEvidenceMeasurement: FeatureTaskRuntimeSharedEvidenceMeasurement?,
    attempt: Int,
  ): RequiredPhaseWrite = recorder.recordPhaseBriefing(workflowId, briefing, sharedEvidenceMeasurement, attempt)

  override fun recordProjectionRejection(
    workflowId: String,
    consumerPhaseId: String,
    error: InvalidFeatureTaskRuntimeHandoffProjectionError,
    repositoryCheckpointFingerprint: String?,
  ): Boolean = recorder.recordProjectionRejection(workflowId, consumerPhaseId, error, repositoryCheckpointFingerprint)

  override fun recordProjectionRejection(rejection: FeatureTaskRuntimeProjectionRejection): Boolean =
    recorder.recordProjectionRejection(rejection)

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
  ) = recorder.persistValidationGateProgress(workflowId, progress)

  override fun loadBuildGateProgress(workflowId: String): FeatureTaskRuntimeValidationGateProgress? =
    recorder.loadBuildGateProgress(workflowId)

  override fun persistBuildGateProgress(
    workflowId: String,
    progress: FeatureTaskRuntimeValidationGateProgress,
  ) = recorder.persistBuildGateProgress(workflowId, progress)

  override fun appendLedgerEntry(request: FeatureTaskRuntimePhaseLedgerRequest): Boolean =
    recorder.appendLedgerEntry(request)

  override fun loadPhaseLedger(workflowId: String): List<FeatureTaskRuntimePhaseLedgerEntry>? =
    recorder.loadPhaseLedger(workflowId)

  override fun appendQuarantineEntry(
    workflowId: String,
    entry: FeatureTaskRuntimeQuarantineEntry,
  ): Boolean = recorder.appendQuarantineEntry(workflowId, entry)

  override fun loadQuarantinedRecords(workflowId: String): List<FeatureTaskRuntimeQuarantineEntry>? =
    recorder.loadQuarantinedRecords(workflowId)

  override fun loadResolvedBranch(workflowId: String): FeatureTaskRuntimeResolvedBranch? =
    recorder.loadResolvedBranch(workflowId)

  override fun loadGoalStartResolvedBranch(parentWorkflowId: String): FeatureTaskRuntimeResolvedBranch? =
    recorder.loadGoalStartResolvedBranch(parentWorkflowId)

  override fun appendCheckpointIdentity(args: AppendCheckpointIdentityArgs): Boolean =
    recorder.appendCheckpointIdentity(args)

  override fun loadCheckpointIdentities(workflowId: String): List<FeatureTaskRuntimeCheckpointIdentity>? =
    recorder.loadCheckpointIdentities(workflowId)

  override fun recordWorkflowOwnedPaths(
    workflowId: String,
    ownedPaths: List<String>,
  ): Boolean = recorder.recordWorkflowOwnedPaths(workflowId, ownedPaths)

  override fun loadDecomposeTerminal(workflowId: String): FeatureTaskRuntimeDecomposeTerminal? =
    decomposeTerminalRecorder.loadDecomposeTerminal(workflowId)

  override fun recordDecomposeTerminal(
    workflowId: String,
    terminal: FeatureTaskRuntimeDecomposeTerminal,
    planStepId: String,
  ): Boolean = decomposeTerminalRecorder.recordDecomposeTerminal(workflowId, terminal, planStepId)
}

internal class DurablePhaseRunGoal(
  private val recorder: FeatureTaskRuntimeGoalContinuationRecorder,
) : PhaseRunGoal {
  override fun recordGoalContinuationState(request: GoalContinuationStateRecordRequest): Boolean =
    recorder.recordGoalContinuationState(request)

  override fun reserveGoalReviewPass(workflowId: String): GoalSubtaskReviewPassReservation =
    recorder.reserveGoalReviewPass(workflowId)

  override fun updateReviewState(
    workflowId: String,
    transform: (GoalSubtaskReviewState) -> GoalSubtaskReviewState,
  ): GoalSubtaskReviewState? = recorder.updateReviewState(workflowId, transform)

  override fun completeGoalReviewPass(request: GoalReviewPassCompletionRequest): GoalSubtaskReviewState? =
    recorder.completeGoalReviewPass(request)

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
    )

  override fun reviewState(workflowId: String): GoalSubtaskReviewState? = recorder.reviewState(workflowId)

  override fun lastGoalReviewResult(workflowId: String): String? = recorder.lastGoalReviewResult(workflowId)

  override fun appendRemediationRollbackDegradationEvidence(
    workflowId: String,
    signal: RemediationDegradationSignal,
  ) {
    recorder.appendRemediationRollbackDegradationEvidence(workflowId, signal)
  }
}

internal class DurablePhaseRunSettlements(
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
  ): Boolean = service.clear(workflowId, phaseId, attempt)
}

internal class DurablePhaseRunCheckpoints(
  private val gitOperations: WorkflowGitOperations,
) : PhaseRunCheckpoints {
  override fun commitSubtask(request: SubtaskCommitPreservationRequest): WorkflowGitOperationResult =
    gitOperations.writeSubtaskCommitPreservingHistory(request)
}
