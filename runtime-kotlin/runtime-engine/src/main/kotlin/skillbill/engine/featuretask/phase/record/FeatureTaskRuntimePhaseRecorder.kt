package skillbill.engine.featuretask.phase.record

import me.tatarka.inject.annotations.Inject
import skillbill.application.diagnostics.model.RejectedOutputDiagnosticRequest
import skillbill.engine.featuretask.lifecycle.core.FeatureTaskRuntimeRejectedOutputRecorder
import skillbill.engine.featuretask.model.execution.AdmittedFeatureTaskRuntimeExecution
import skillbill.engine.featuretask.model.phase.AppendCheckpointIdentityArgs
import skillbill.engine.featuretask.model.phase.FeatureTaskRuntimePhaseLaunchBriefing
import skillbill.engine.featuretask.model.phase.FeatureTaskRuntimePhaseLedgerRequest
import skillbill.engine.featuretask.model.phase.FeatureTaskRuntimeProducerOutputRead
import skillbill.engine.featuretask.model.phase.FeatureTaskRuntimeProjectionRejection
import skillbill.engine.featuretask.model.phase.GoalReviewPhaseCompletionRequest
import skillbill.engine.featuretask.model.phase.ProducerOutputQueryArgs
import skillbill.engine.featuretask.model.review.FeatureTaskRuntimeRejectedOutputWrite
import skillbill.engine.featuretask.persist.FeatureTaskRuntimeWorkflowPersistence
import skillbill.engine.featuretask.persist.RuntimeOwnedPersistenceBoundary
import skillbill.engine.featuretask.phase.briefing.FeatureTaskRuntimePhaseBriefingRecorder
import skillbill.engine.featuretask.phase.core.FeatureTaskPhaseSettlementService
import skillbill.engine.featuretask.review.core.FeatureTaskRuntimeFindingVerificationPort
import skillbill.engine.featuretask.review.core.FeatureTaskRuntimeReviewCheckpointRecorder
import skillbill.engine.featuretask.review.core.FeatureTaskRuntimeReviewGenerationPort
import skillbill.engine.featuretask.review.goal.FeatureTaskRuntimeGoalReviewCompletionRecorder
import skillbill.engine.featuretask.slot.state.RequiredPhaseWrite
import skillbill.error.featuretask.InvalidFeatureTaskRuntimeHandoffProjectionContext
import skillbill.ports.db.DatabaseSessionFactory
import skillbill.ports.diagnostics.ProducerOutputEvidenceValidator
import skillbill.ports.diagnostics.RejectedOutputDiagnosticMetadataValidator
import skillbill.ports.diagnostics.RuntimeDiagnostics
import skillbill.ports.diagnostics.model.ProducerOutputEvidence
import skillbill.ports.taskruntime.FeatureTaskRuntimeWireArtifactValidator
import skillbill.ports.taskruntime.model.ValidatedFeatureTaskRuntimeExecutionPlan
import skillbill.ports.workflow.WorkflowSnapshotValidator
import skillbill.workflow.taskruntime.model.audit.FeatureTaskRuntimeDiagnosticSignal
import skillbill.workflow.taskruntime.model.audit.FeatureTaskRuntimeNoChangePause
import skillbill.workflow.taskruntime.model.audit.FeatureTaskRuntimeQuarantineEntry
import skillbill.workflow.taskruntime.model.core.FeatureTaskRuntimeResolvedBranch
import skillbill.workflow.taskruntime.model.handoff.PhaseHandoffProjectionDeclaration
import skillbill.workflow.taskruntime.model.handoff.task.FeatureTaskRuntimeSharedEvidenceMeasurement
import skillbill.workflow.taskruntime.model.persistence.FeatureTaskRuntimeCheckpointIdentity
import skillbill.workflow.taskruntime.model.persistence.FeatureTaskRuntimeGoalContinuationArtifact
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimeDeliveredProjectionRecord
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimePhaseLedgerEntry
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimePhaseRecord
import skillbill.workflow.taskruntime.model.qa.QaRepairState
import skillbill.workflow.taskruntime.model.validation.FeatureTaskRuntimeValidationGateProgress
import skillbill.engine.diagnostics.block.BlockIncidentCapture
import java.time.Clock

class FeatureTaskRuntimePhaseRecorder private constructor(
  private val phaseEvidenceDelegate: FeatureTaskRuntimePhaseEvidenceApiDelegate,
  val phaseQuery: FeatureTaskRuntimePhaseQuery,
  private val workflowPersistence: FeatureTaskRuntimeWorkflowPersistence,
  private val rejectedOutput: FeatureTaskRuntimeRejectedOutputRecorder,
  phaseState: FeatureTaskRuntimePhaseStateRecorder,
  reviewCheckpoint: FeatureTaskRuntimeReviewCheckpointRecorder,
  private val goalReviewCompletion: FeatureTaskRuntimeGoalReviewCompletionRecorder,
  private val briefingRecorder: FeatureTaskRuntimePhaseBriefingRecorder,
  private val gateProgress: FeatureTaskRuntimeGateProgressRecorder,
) : FeatureTaskRuntimePhaseEvidenceApi by phaseEvidenceDelegate,
  FeatureTaskRuntimePhaseOwnedEvidenceApi by phaseEvidenceDelegate,
  FeatureTaskRuntimePhaseStatePort by phaseState,
  FeatureTaskRuntimePhaseStateQueries by phaseState,
  FeatureTaskRuntimeImplementationProgressPort by phaseState,
  FeatureTaskRuntimeReviewGenerationPort by reviewCheckpoint,
  FeatureTaskRuntimeFindingVerificationPort by reviewCheckpoint,
  FeatureTaskRuntimeReadinessEvidencePort by gateProgress,
  FeatureTaskRuntimeNoChangePausePort by gateProgress {
  @Inject
  constructor(
    database: DatabaseSessionFactory,
    workflowSnapshotValidator: WorkflowSnapshotValidator,
    wireArtifactValidator: FeatureTaskRuntimeWireArtifactValidator,
    rejectedOutputDiagnosticMetadataValidator: RejectedOutputDiagnosticMetadataValidator,
    producerOutputEvidenceValidator: ProducerOutputEvidenceValidator,
    diagnostics: RuntimeDiagnostics,
    phaseSettlementService: FeatureTaskPhaseSettlementService,
    clock: Clock,
    blockIncidentCapture: BlockIncidentCapture,
  ) : this(
    phaseRecorderParts(
      FeatureTaskRuntimePhaseRecorderInjected(
        database,
        workflowSnapshotValidator,
        wireArtifactValidator,
        rejectedOutputDiagnosticMetadataValidator,
        producerOutputEvidenceValidator,
        diagnostics,
        phaseSettlementService,
        clock,
      ),
      blockIncidentCapture,
    ),
  )

  private constructor(parts: FeatureTaskRuntimePhaseRecorderParts) : this(
    parts.phaseEvidenceDelegate,
    parts.phaseQuery,
    parts.workflowPersistence,
    parts.rejectedOutput,
    parts.phaseState,
    parts.reviewCheckpoint,
    parts.goalReviewCompletion,
    parts.briefingRecorder,
    parts.gateProgress,
  )

  fun loadQaRepairState(workflowId: String): QaRepairState? = workflowPersistence.readQaRepairState(workflowId)

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

  internal fun recordOwnedRejectedOutput(
    request: RejectedOutputDiagnosticRequest,
    producerGeneration: Int,
    admitted: AdmittedFeatureTaskRuntimeExecution,
  ): FeatureTaskRuntimeRejectedOutputWrite =
    rejectedOutput.recordOwnedRejectedOutput(request, producerGeneration, admitted)

  fun retainProducerOutput(evidence: ProducerOutputEvidence) = rejectedOutput.retainProducerOutput(evidence)

  fun retainOwnedProducerOutput(
    evidence: ProducerOutputEvidence,
    admitted: AdmittedFeatureTaskRuntimeExecution,
  ) = rejectedOutput.retainOwnedProducerOutput(evidence, admitted)

  fun producerOutput(args: ProducerOutputQueryArgs): FeatureTaskRuntimeProducerOutputRead =
    rejectedOutput.producerOutput(args)

  fun loadDiagnosticSignals(workflowId: String): List<FeatureTaskRuntimeDiagnosticSignal> =
    rejectedOutput.loadDiagnosticSignals(workflowId)

  fun loadPhaseRecords(workflowId: String): Map<String, FeatureTaskRuntimePhaseRecord>? =
    phaseQuery.loadPhaseRecords(workflowId)

  fun loadPhaseLedger(workflowId: String): List<FeatureTaskRuntimePhaseLedgerEntry>? =
    phaseQuery.loadPhaseLedger(workflowId)

  fun completeGoalReviewPhase(completion: GoalReviewPhaseCompletionRequest): Boolean =
    goalReviewCompletion.completeGoalReviewPhase(completion)

  fun completeOwnedGoalReviewPhase(
    completion: GoalReviewPhaseCompletionRequest,
    admitted: AdmittedFeatureTaskRuntimeExecution,
  ): Boolean = goalReviewCompletion.completeOwnedGoalReviewPhase(completion, admitted)

  fun recordOwnedPhaseBriefing(
    workflowId: String,
    briefing: FeatureTaskRuntimePhaseLaunchBriefing,
    sharedEvidenceMeasurement: FeatureTaskRuntimeSharedEvidenceMeasurement?,
    attempt: Int,
    admitted: AdmittedFeatureTaskRuntimeExecution,
  ): RequiredPhaseWrite =
    briefingRecorder.recordOwnedPhaseBriefing(
      workflowId,
      briefing,
      sharedEvidenceMeasurement,
      attempt,
      admitted,
    )

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
    context: InvalidFeatureTaskRuntimeHandoffProjectionContext,
    repositoryCheckpointFingerprint: String?,
  ): Boolean =
    briefingRecorder.recordProjectionRejection(
      context,
      repositoryCheckpointFingerprint,
    )

  fun recordOwnedProjectionRejection(
    context: InvalidFeatureTaskRuntimeHandoffProjectionContext,
    repositoryCheckpointFingerprint: String?,
    admitted: AdmittedFeatureTaskRuntimeExecution,
  ): Boolean =
    briefingRecorder.recordOwnedProjectionRejection(
      context,
      repositoryCheckpointFingerprint,
      admitted,
    )

  fun recordProjectionRejection(rejection: FeatureTaskRuntimeProjectionRejection): Boolean =
    briefingRecorder.recordProjectionRejection(rejection)

  fun recordOwnedProjectionRejection(
    rejection: FeatureTaskRuntimeProjectionRejection,
    admitted: AdmittedFeatureTaskRuntimeExecution,
  ): Boolean = briefingRecorder.recordOwnedProjectionRejection(rejection, admitted)

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

  fun persistOwnedValidationGateProgress(
    workflowId: String,
    progress: FeatureTaskRuntimeValidationGateProgress,
    admitted: AdmittedFeatureTaskRuntimeExecution,
  ): Boolean = gateProgress.persistOwnedValidationGateProgress(workflowId, progress, admitted)

  fun persistOwnedNoChangePause(
    workflowId: String,
    pause: FeatureTaskRuntimeNoChangePause,
    admitted: AdmittedFeatureTaskRuntimeExecution,
  ): Boolean = gateProgress.persistOwnedNoChangePause(workflowId, pause, admitted)

  fun loadBuildGateProgress(workflowId: String): FeatureTaskRuntimeValidationGateProgress? =
    gateProgress.loadBuildGateProgress(workflowId)

  fun loadGoalContinuation(workflowId: String): FeatureTaskRuntimeGoalContinuationArtifact? =
    gateProgress.loadGoalContinuation(workflowId)

  fun persistBuildGateProgress(
    workflowId: String,
    progress: FeatureTaskRuntimeValidationGateProgress,
  ) = gateProgress.persistBuildGateProgress(workflowId, progress)

  fun persistOwnedBuildGateProgress(
    workflowId: String,
    progress: FeatureTaskRuntimeValidationGateProgress,
    admitted: AdmittedFeatureTaskRuntimeExecution,
  ): Boolean = gateProgress.persistOwnedBuildGateProgress(workflowId, progress, admitted)
}

private data class FeatureTaskRuntimePhaseRecorderInjected(
  val database: DatabaseSessionFactory,
  val workflowSnapshotValidator: WorkflowSnapshotValidator,
  val wireArtifactValidator: FeatureTaskRuntimeWireArtifactValidator,
  val rejectedOutputDiagnosticMetadataValidator: RejectedOutputDiagnosticMetadataValidator,
  val producerOutputEvidenceValidator: ProducerOutputEvidenceValidator,
  val diagnostics: RuntimeDiagnostics,
  val phaseSettlementService: FeatureTaskPhaseSettlementService,
  val clock: Clock,
)

private data class FeatureTaskRuntimePhaseRecorderParts(
  val phaseEvidenceDelegate: FeatureTaskRuntimePhaseEvidenceApiDelegate,
  val phaseQuery: FeatureTaskRuntimePhaseQuery,
  val workflowPersistence: FeatureTaskRuntimeWorkflowPersistence,
  val rejectedOutput: FeatureTaskRuntimeRejectedOutputRecorder,
  val phaseState: FeatureTaskRuntimePhaseStateRecorder,
  val reviewCheckpoint: FeatureTaskRuntimeReviewCheckpointRecorder,
  val goalReviewCompletion: FeatureTaskRuntimeGoalReviewCompletionRecorder,
  val briefingRecorder: FeatureTaskRuntimePhaseBriefingRecorder,
  val gateProgress: FeatureTaskRuntimeGateProgressRecorder,
)

private fun phaseRecorderParts(
  injected: FeatureTaskRuntimePhaseRecorderInjected,
  blockIncidentCapture: BlockIncidentCapture,
): FeatureTaskRuntimePhaseRecorderParts {
  val workflowPersistence =
    FeatureTaskRuntimeWorkflowPersistence(
      injected.database,
      injected.workflowSnapshotValidator,
      injected.wireArtifactValidator,
    )
  val runtimeOwnedPersistence = RuntimeOwnedPersistenceBoundary(injected.database, injected.diagnostics)
  return FeatureTaskRuntimePhaseRecorderParts(
    phaseEvidenceDelegate =
      FeatureTaskRuntimePhaseEvidenceApiDelegate(
        injected.database,
        injected.workflowSnapshotValidator,
        injected.wireArtifactValidator,
        injected.clock,
      ),
    phaseQuery = FeatureTaskRuntimePhaseQuery(injected.database),
    workflowPersistence = workflowPersistence,
    rejectedOutput =
      FeatureTaskRuntimeRejectedOutputRecorder(
        injected.database,
        workflowPersistence,
        injected.rejectedOutputDiagnosticMetadataValidator,
        injected.producerOutputEvidenceValidator,
        injected.clock,
      ),
    phaseState =
      FeatureTaskRuntimePhaseStateRecorder(
        injected.database,
        workflowPersistence,
        runtimeOwnedPersistence,
        injected.wireArtifactValidator,
        injected.phaseSettlementService,
        injected.clock,
        blockIncidentCapture,
      ),
    reviewCheckpoint =
      FeatureTaskRuntimeReviewCheckpointRecorder(
        injected.database,
        workflowPersistence,
        runtimeOwnedPersistence,
        injected.clock,
      ),
    goalReviewCompletion =
      FeatureTaskRuntimeGoalReviewCompletionRecorder(
        injected.database,
        workflowPersistence,
        runtimeOwnedPersistence,
        injected.phaseSettlementService,
        injected.clock,
      ),
    briefingRecorder =
      FeatureTaskRuntimePhaseBriefingRecorder(
        injected.database,
        workflowPersistence,
        injected.wireArtifactValidator,
        injected.clock,
      ),
    gateProgress = FeatureTaskRuntimeGateProgressRecorder(injected.database, workflowPersistence, injected.clock),
  )
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

private interface FeatureTaskRuntimePhaseOwnedEvidenceApi {
  fun appendOwnedLedgerEntry(
    request: FeatureTaskRuntimePhaseLedgerRequest,
    admitted: AdmittedFeatureTaskRuntimeExecution,
  ): Boolean

  fun appendOwnedQuarantineEntry(
    workflowId: String,
    entry: FeatureTaskRuntimeQuarantineEntry,
    admitted: AdmittedFeatureTaskRuntimeExecution,
  ): Boolean

  fun recordOwnedWorkflowOwnedPaths(
    workflowId: String,
    ownedPaths: List<String>,
    admitted: AdmittedFeatureTaskRuntimeExecution,
  ): Boolean

  fun appendOwnedCheckpointIdentity(
    args: AppendCheckpointIdentityArgs,
    admitted: AdmittedFeatureTaskRuntimeExecution,
  ): Boolean

  fun recordOwnedResolvedBranch(
    workflowId: String,
    resolvedBranch: FeatureTaskRuntimeResolvedBranch,
    admitted: AdmittedFeatureTaskRuntimeExecution,
  ): Boolean
}

private class FeatureTaskRuntimePhaseEvidenceApiDelegate(
  database: DatabaseSessionFactory,
  workflowSnapshotValidator: WorkflowSnapshotValidator,
  wireArtifactValidator: FeatureTaskRuntimeWireArtifactValidator,
  clock: Clock,
) : FeatureTaskRuntimePhaseEvidenceApi, FeatureTaskRuntimePhaseOwnedEvidenceApi {
  private val evidence =
    FeatureTaskRuntimePhaseEvidenceRecorder(
      database,
      FeatureTaskRuntimeWorkflowPersistence(database, workflowSnapshotValidator, wireArtifactValidator),
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

  override fun appendOwnedLedgerEntry(
    request: FeatureTaskRuntimePhaseLedgerRequest,
    admitted: AdmittedFeatureTaskRuntimeExecution,
  ): Boolean = evidence.appendOwnedLedgerEntry(request, admitted)

  override fun appendOwnedQuarantineEntry(
    workflowId: String,
    entry: FeatureTaskRuntimeQuarantineEntry,
    admitted: AdmittedFeatureTaskRuntimeExecution,
  ): Boolean = evidence.appendOwnedQuarantineEntry(workflowId, entry, admitted)

  override fun recordOwnedWorkflowOwnedPaths(
    workflowId: String,
    ownedPaths: List<String>,
    admitted: AdmittedFeatureTaskRuntimeExecution,
  ): Boolean = evidence.recordOwnedWorkflowOwnedPaths(workflowId, ownedPaths, admitted)

  override fun appendOwnedCheckpointIdentity(
    args: AppendCheckpointIdentityArgs,
    admitted: AdmittedFeatureTaskRuntimeExecution,
  ): Boolean = evidence.appendOwnedCheckpointIdentity(args, admitted)

  override fun recordOwnedResolvedBranch(
    workflowId: String,
    resolvedBranch: FeatureTaskRuntimeResolvedBranch,
    admitted: AdmittedFeatureTaskRuntimeExecution,
  ): Boolean = evidence.recordOwnedResolvedBranch(workflowId, resolvedBranch, admitted)
}
