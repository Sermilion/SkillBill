package skillbill.engine.featuretask.phase.record

import me.tatarka.inject.annotations.Inject
import skillbill.application.diagnostics.model.RejectedOutputDiagnosticRequest
import skillbill.engine.featuretask.lifecycle.core.FeatureTaskRuntimeRejectedOutputRecorder
import skillbill.engine.featuretask.model.execution.AdmittedFeatureTaskRuntimeExecution
import skillbill.engine.featuretask.model.phase.AppendCheckpointIdentityArgs
import skillbill.engine.featuretask.model.phase.FeatureTaskRuntimePhaseLaunchBriefing
import skillbill.engine.featuretask.model.phase.FeatureTaskRuntimePhaseLedgerRequest
import skillbill.engine.featuretask.model.phase.FeatureTaskRuntimePhaseStateRequest
import skillbill.engine.featuretask.model.phase.FeatureTaskRuntimeProducerOutputRead
import skillbill.engine.featuretask.model.phase.FeatureTaskRuntimeProjectionRejection
import skillbill.engine.featuretask.model.phase.GoalReviewPhaseCompletionRequest
import skillbill.engine.featuretask.model.phase.ProducerOutputQueryArgs
import skillbill.engine.featuretask.model.review.FeatureTaskRuntimeRejectedOutputWrite
import skillbill.engine.featuretask.persist.FeatureTaskRuntimeWorkflowPersistence
import skillbill.engine.featuretask.persist.RuntimeOwnedPersistenceBoundary
import skillbill.engine.featuretask.phase.briefing.FeatureTaskRuntimePhaseBriefingRecorder
import skillbill.engine.featuretask.review.core.FeatureTaskRuntimeReviewCheckpointRecorder
import skillbill.engine.featuretask.review.goal.FeatureTaskRuntimeGoalReviewCompletionRecorder
import skillbill.engine.featuretask.slot.state.RequiredPhaseWrite
import skillbill.error.shellcontent.InvalidFeatureTaskRuntimeHandoffProjectionError
import skillbill.goalrunner.model.UnaddressedFinding
import skillbill.ports.db.DatabaseSessionFactory
import skillbill.ports.diagnostics.ProducerOutputEvidenceValidator
import skillbill.ports.diagnostics.RejectedOutputDiagnosticMetadataValidator
import skillbill.ports.diagnostics.RuntimeDiagnostics
import skillbill.ports.diagnostics.model.ProducerOutputEvidence
import skillbill.ports.taskruntime.FeatureTaskRuntimeWireArtifactValidator
import skillbill.ports.taskruntime.model.ValidatedFeatureTaskRuntimeExecutionPlan
import skillbill.ports.workflow.WorkflowSnapshotValidator
import skillbill.review.model.ReviewFindingVerdict
import skillbill.workflow.model.FeatureTaskWorkflowMode
import skillbill.workflow.taskruntime.model.audit.FeatureTaskRuntimeDiagnosticSignal
import skillbill.workflow.taskruntime.model.audit.FeatureTaskRuntimeQuarantineEntry
import skillbill.workflow.taskruntime.model.core.FeatureTaskRuntimeResolvedBranch
import skillbill.workflow.taskruntime.model.feature.FeatureTaskRuntimeVerificationBoundaryHeadingProvenance
import skillbill.workflow.taskruntime.model.handoff.PhaseHandoffProjectionDeclaration
import skillbill.workflow.taskruntime.model.handoff.task.FeatureTaskRuntimeSharedEvidenceMeasurement
import skillbill.workflow.taskruntime.model.persistence.FeatureTaskRuntimeCheckpointIdentity
import skillbill.workflow.taskruntime.model.persistence.FeatureTaskRuntimeGoalContinuationArtifact
import skillbill.workflow.taskruntime.model.persistence.FeatureTaskRuntimeImplementationAttempt
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimeDeliveredProjectionRecord
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimePhaseLedgerEntry
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimePhaseRecord
import skillbill.workflow.taskruntime.model.repair.FeatureTaskRuntimeOperatorBlockRetry
import skillbill.workflow.taskruntime.model.validation.FeatureTaskRuntimeFindingVerificationDisposition
import skillbill.workflow.taskruntime.model.validation.FeatureTaskRuntimeValidationGateProgress
import java.time.Clock

class FeatureTaskRuntimePhaseRecorder
  @Inject
  constructor(
    database: DatabaseSessionFactory,
    workflowSnapshotValidator: WorkflowSnapshotValidator,
    wireArtifactValidator: FeatureTaskRuntimeWireArtifactValidator,
    rejectedOutputDiagnosticMetadataValidator: RejectedOutputDiagnosticMetadataValidator,
    producerOutputEvidenceValidator: ProducerOutputEvidenceValidator,
    diagnostics: RuntimeDiagnostics,
    clock: Clock,
  ) : FeatureTaskRuntimePhaseEvidenceApi by FeatureTaskRuntimePhaseEvidenceApiDelegate(
      database,
      workflowSnapshotValidator,
      wireArtifactValidator,
      clock,
    ),
    FeatureTaskRuntimeReadinessEvidencePort by FeatureTaskRuntimeGateProgressRecorder(
      database,
      FeatureTaskRuntimeWorkflowPersistence(database, workflowSnapshotValidator),
    ) {
    val phaseQuery = FeatureTaskRuntimePhaseQuery(database)
    private val workflowPersistence = FeatureTaskRuntimeWorkflowPersistence(database, workflowSnapshotValidator)
    private val runtimeOwnedPersistence = RuntimeOwnedPersistenceBoundary(database, diagnostics)
    private val rejectedOutput =
      FeatureTaskRuntimeRejectedOutputRecorder(
        database,
        workflowPersistence,
        rejectedOutputDiagnosticMetadataValidator,
        producerOutputEvidenceValidator,
        clock,
      )
    private val phaseState =
      FeatureTaskRuntimePhaseStateRecorder(
        database,
        workflowPersistence,
        runtimeOwnedPersistence,
        wireArtifactValidator,
        clock,
      )
    private val reviewCheckpoint =
      FeatureTaskRuntimeReviewCheckpointRecorder(
        database,
        workflowPersistence,
        runtimeOwnedPersistence,
      )
    private val goalReviewCompletion =
      FeatureTaskRuntimeGoalReviewCompletionRecorder(
        database,
        workflowPersistence,
        clock,
      )
    private val briefingRecorder =
      FeatureTaskRuntimePhaseBriefingRecorder(
        database,
        workflowPersistence,
        wireArtifactValidator,
      )
    private val gateProgress = FeatureTaskRuntimeGateProgressRecorder(database, workflowPersistence)

    fun existingWorkflowMode(workflowId: String): FeatureTaskWorkflowMode? = phaseQuery.existingWorkflowMode(workflowId)

    fun ensureWorkflowOpen(
      workflowId: String,
      sessionId: String,
      issueKey: String? = null,
      executionPlan: ValidatedFeatureTaskRuntimeExecutionPlan? = null,
    ): Boolean = workflowPersistence.ensureWorkflowOpen(workflowId, sessionId, issueKey, executionPlan)

    internal fun recordRejectedOutput(
      request: RejectedOutputDiagnosticRequest,
      producerGeneration: Int = 0,
    ): FeatureTaskRuntimeRejectedOutputWrite = rejectedOutput.recordRejectedOutput(request, producerGeneration)

    fun retainProducerOutput(evidence: ProducerOutputEvidence) = rejectedOutput.retainProducerOutput(evidence)

    fun producerOutput(args: ProducerOutputQueryArgs): FeatureTaskRuntimeProducerOutputRead =
      rejectedOutput.producerOutput(args)

    fun loadDiagnosticSignals(workflowId: String): List<FeatureTaskRuntimeDiagnosticSignal> =
      rejectedOutput.loadDiagnosticSignals(workflowId)

    fun recordPhaseState(request: FeatureTaskRuntimePhaseStateRequest): Boolean = phaseState.recordPhaseState(request)

    fun recordRequiredPhaseStart(request: FeatureTaskRuntimePhaseStateRequest): RequiredPhaseWrite =
      phaseState.recordRequiredPhaseStart(request)

    fun recordCompletedPhase(request: FeatureTaskRuntimePhaseStateRequest): Boolean =
      phaseState.recordCompletedPhase(request)

    fun recordIncompleteImplementationAttempt(request: FeatureTaskRuntimePhaseStateRequest): Boolean =
      phaseState.recordIncompleteImplementationAttempt(request)

    fun loadImplementationAttempts(workflowId: String): List<FeatureTaskRuntimeImplementationAttempt>? =
      phaseState.loadImplementationAttempts(workflowId)

    fun clearBackwardEdgeContext(
      workflowId: String,
      phaseIds: Collection<String>,
    ): Boolean = phaseState.clearBackwardEdgeContext(workflowId, phaseIds)

    fun loadPhaseRecords(workflowId: String): Map<String, FeatureTaskRuntimePhaseRecord>? =
      phaseQuery.loadPhaseRecords(workflowId)

    fun loadOperatorBlockRetry(workflowId: String): FeatureTaskRuntimeOperatorBlockRetry? =
      phaseState.loadOperatorBlockRetry(workflowId)

    fun loadPhaseLedger(workflowId: String): List<FeatureTaskRuntimePhaseLedgerEntry>? =
      phaseQuery.loadPhaseLedger(workflowId)

    fun completeGoalReviewPhase(completion: GoalReviewPhaseCompletionRequest): Boolean =
      goalReviewCompletion.completeGoalReviewPhase(completion)

    fun persistReviewGenerationInvalidation(
      workflowId: String,
      reviewStepId: String,
    ): Int? = reviewCheckpoint.persistReviewGenerationInvalidation(workflowId, reviewStepId)

    fun reconcileReviewGeneration(workflowId: String): Int = reviewCheckpoint.reconcileReviewGeneration(workflowId)

    fun invalidateQuarantinedProducerRecord(
      workflowId: String,
      producerPhaseId: String,
      loopId: String,
      edgeIteration: Int,
      admitted: AdmittedFeatureTaskRuntimeExecution? = null,
    ): Boolean =
      reviewCheckpoint.invalidateQuarantinedProducerRecord(
        workflowId,
        producerPhaseId,
        loopId,
        edgeIteration,
        admitted,
      )

    fun recordedFindingVerdicts(output: Map<String, Any?>): List<ReviewFindingVerdict> =
      reviewCheckpoint.recordedFindingVerdicts(output)

    fun fetchUnaddressedLedger(workflowId: String): List<UnaddressedFinding> =
      reviewCheckpoint.fetchUnaddressedLedger(workflowId)

    fun appendRejectedVerificationFindings(
      workflowId: String,
      passNumber: Int,
      rejected: List<UnaddressedFinding>,
    ) = reviewCheckpoint.appendRejectedVerificationFindings(workflowId, passNumber, rejected)

    fun loadFindingVerificationCheckpoint(workflowId: String): List<FeatureTaskRuntimeFindingVerificationDisposition>? =
      reviewCheckpoint.loadFindingVerificationCheckpoint(workflowId)

    fun loadFindingVerificationBoundarySelection(
      workflowId: String,
    ): Map<String, List<FeatureTaskRuntimeVerificationBoundaryHeadingProvenance>>? =
      reviewCheckpoint.loadFindingVerificationBoundarySelection(workflowId)

    fun persistFindingVerificationBoundarySelection(
      workflowId: String,
      selections: Map<String, List<FeatureTaskRuntimeVerificationBoundaryHeadingProvenance>>,
    ): Boolean = reviewCheckpoint.persistFindingVerificationBoundarySelection(workflowId, selections)

    fun loadFindingVerificationDispositions(
      workflowId: String,
    ): List<FeatureTaskRuntimeFindingVerificationDisposition>? =
      reviewCheckpoint.loadFindingVerificationDispositions(workflowId)

    fun persistFindingVerificationCheckpoint(
      workflowId: String,
      dispositions: List<FeatureTaskRuntimeFindingVerificationDisposition>,
    ): Boolean = reviewCheckpoint.persistFindingVerificationCheckpoint(workflowId, dispositions)

    fun clearFindingVerificationCheckpoint(workflowId: String): Boolean =
      reviewCheckpoint.clearFindingVerificationCheckpoint(workflowId)

    fun recordPhaseBriefing(
      workflowId: String,
      briefing: FeatureTaskRuntimePhaseLaunchBriefing,
      sharedEvidenceMeasurement: FeatureTaskRuntimeSharedEvidenceMeasurement? = null,
      attempt: Int = 1,
    ): RequiredPhaseWrite =
      briefingRecorder.recordPhaseBriefing(
        workflowId,
        briefing,
        sharedEvidenceMeasurement,
        attempt,
      )

    fun recordProjectionRejection(
      workflowId: String,
      consumerPhaseId: String,
      error: InvalidFeatureTaskRuntimeHandoffProjectionError,
      repositoryCheckpointFingerprint: String?,
    ): Boolean =
      briefingRecorder.recordProjectionRejection(
        workflowId,
        consumerPhaseId,
        error,
        repositoryCheckpointFingerprint,
      )

    fun recordProjectionRejection(rejection: FeatureTaskRuntimeProjectionRejection): Boolean =
      briefingRecorder.recordProjectionRejection(rejection)

    fun validateHandoffDeclarations(declarations: List<PhaseHandoffProjectionDeclaration>) =
      briefingRecorder.validateHandoffDeclarations(declarations)

    fun loadPhaseBriefings(workflowId: String): Map<String, FeatureTaskRuntimePhaseLaunchBriefing>? =
      briefingRecorder.loadPhaseBriefings(workflowId)

    fun loadDeliveredProjections(workflowId: String): Map<String, FeatureTaskRuntimeDeliveredProjectionRecord>? =
      briefingRecorder.loadDeliveredProjections(workflowId)

    fun loadValidationGateProgress(workflowId: String): FeatureTaskRuntimeValidationGateProgress? =
      gateProgress.loadValidationGateProgress(workflowId)

    fun persistValidationGateProgress(
      workflowId: String,
      progress: FeatureTaskRuntimeValidationGateProgress,
    ) = gateProgress.persistValidationGateProgress(workflowId, progress)

    fun loadBuildGateProgress(workflowId: String): FeatureTaskRuntimeValidationGateProgress? =
      gateProgress.loadBuildGateProgress(workflowId)

    fun loadGoalContinuation(workflowId: String): FeatureTaskRuntimeGoalContinuationArtifact? =
      gateProgress.loadGoalContinuation(workflowId)

    fun persistBuildGateProgress(
      workflowId: String,
      progress: FeatureTaskRuntimeValidationGateProgress,
    ) = gateProgress.persistBuildGateProgress(workflowId, progress)
  }

private interface FeatureTaskRuntimePhaseEvidenceApi {
  fun appendLedgerEntry(request: FeatureTaskRuntimePhaseLedgerRequest): Boolean

  fun appendQuarantineEntry(
    workflowId: String,
    entry: FeatureTaskRuntimeQuarantineEntry,
  ): Boolean

  fun loadQuarantinedRecords(workflowId: String): List<FeatureTaskRuntimeQuarantineEntry>?

  fun recordResolvedBranch(
    workflowId: String,
    resolvedBranch: FeatureTaskRuntimeResolvedBranch,
  ): Boolean

  fun loadResolvedBranch(workflowId: String): FeatureTaskRuntimeResolvedBranch?

  fun loadGoalStartResolvedBranch(parentWorkflowId: String): FeatureTaskRuntimeResolvedBranch?

  fun appendCheckpointIdentity(args: AppendCheckpointIdentityArgs): Boolean

  fun loadCheckpointIdentities(workflowId: String): List<FeatureTaskRuntimeCheckpointIdentity>?

  fun recordWorkflowOwnedPaths(
    workflowId: String,
    ownedPaths: List<String>,
  ): Boolean
}

private class FeatureTaskRuntimePhaseEvidenceApiDelegate(
  database: DatabaseSessionFactory,
  workflowSnapshotValidator: WorkflowSnapshotValidator,
  wireArtifactValidator: FeatureTaskRuntimeWireArtifactValidator,
  clock: Clock,
) : FeatureTaskRuntimePhaseEvidenceApi {
  private val evidence =
    FeatureTaskRuntimePhaseEvidenceRecorder(
      database,
      FeatureTaskRuntimeWorkflowPersistence(database, workflowSnapshotValidator),
      wireArtifactValidator,
      clock,
    )

  override fun appendLedgerEntry(request: FeatureTaskRuntimePhaseLedgerRequest): Boolean =
    evidence.appendLedgerEntry(request)

  override fun appendQuarantineEntry(
    workflowId: String,
    entry: FeatureTaskRuntimeQuarantineEntry,
  ): Boolean = evidence.appendQuarantineEntry(workflowId, entry)

  override fun loadQuarantinedRecords(workflowId: String): List<FeatureTaskRuntimeQuarantineEntry>? =
    evidence.loadQuarantinedRecords(workflowId)

  override fun recordResolvedBranch(
    workflowId: String,
    resolvedBranch: FeatureTaskRuntimeResolvedBranch,
  ): Boolean = evidence.recordResolvedBranch(workflowId, resolvedBranch)

  override fun loadResolvedBranch(workflowId: String): FeatureTaskRuntimeResolvedBranch? =
    evidence.loadResolvedBranch(workflowId)

  override fun loadGoalStartResolvedBranch(parentWorkflowId: String): FeatureTaskRuntimeResolvedBranch? =
    evidence.loadGoalStartResolvedBranch(parentWorkflowId)

  override fun appendCheckpointIdentity(args: AppendCheckpointIdentityArgs): Boolean =
    evidence.appendCheckpointIdentity(args)

  override fun loadCheckpointIdentities(workflowId: String): List<FeatureTaskRuntimeCheckpointIdentity>? =
    evidence.loadCheckpointIdentities(workflowId)

  override fun recordWorkflowOwnedPaths(
    workflowId: String,
    ownedPaths: List<String>,
  ): Boolean = evidence.recordWorkflowOwnedPaths(workflowId, ownedPaths)
}
