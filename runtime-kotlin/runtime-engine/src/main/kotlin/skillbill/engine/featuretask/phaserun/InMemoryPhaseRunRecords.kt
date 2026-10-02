package skillbill.engine.featuretask.phaserun

import skillbill.application.diagnostics.model.RejectedOutputDiagnosticRequest
import skillbill.engine.featuretask.model.phase.AppendCheckpointIdentityArgs
import skillbill.engine.featuretask.model.phase.FeatureTaskRuntimePhaseLaunchBriefing
import skillbill.engine.featuretask.model.phase.FeatureTaskRuntimePhaseLedgerRequest
import skillbill.engine.featuretask.model.phase.FeatureTaskRuntimePhaseStateRequest
import skillbill.engine.featuretask.model.phase.FeatureTaskRuntimeProducerOutputRead
import skillbill.engine.featuretask.model.phase.FeatureTaskRuntimeProjectionRejection
import skillbill.engine.featuretask.model.phase.GoalReviewPhaseCompletionRequest
import skillbill.engine.featuretask.model.phase.ProducerOutputQueryArgs
import skillbill.engine.featuretask.model.review.FeatureTaskRuntimeRejectedOutputWrite
import skillbill.engine.featuretask.phase.record.featureTaskRuntimePhaseRecordFor
import skillbill.engine.featuretask.runloop.state.REVIEW_INVALIDATION_AGENT_ID
import skillbill.engine.featuretask.slot.state.PhaseRunRecords
import skillbill.engine.featuretask.slot.state.RequiredPhaseWrite
import skillbill.error.shellcontent.InvalidFeatureTaskRuntimeHandoffProjectionError
import skillbill.goalrunner.model.UnaddressedFinding
import skillbill.ports.diagnostics.model.ProducerOutputEvidence
import skillbill.review.model.ReviewFindingVerdict
import skillbill.workflow.model.WorkflowStepStatus
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
import java.time.Clock

internal class InMemoryPhaseRunRecords(
  private val clock: Clock,
  private val resolvedBranch: FeatureTaskRuntimeResolvedBranch?,
) : PhaseRunRecords {
  private val phaseRecords = LinkedHashMap<String, FeatureTaskRuntimePhaseRecord>()
  private val producerOutputs = mutableListOf<ProducerOutputEvidence>()
  private val validationGateProgress = mutableMapOf<String, FeatureTaskRuntimeValidationGateProgress>()
  private val buildGateProgress = mutableMapOf<String, FeatureTaskRuntimeValidationGateProgress>()
  private val unaddressedLedger = mutableListOf<UnaddressedFinding>()
  private var verificationCheckpoint: List<FeatureTaskRuntimeFindingVerificationDisposition>? = null
  private var verificationBoundary: Map<String, List<FeatureTaskRuntimeVerificationBoundaryHeadingProvenance>>? = null
  private var reviewGeneration: Int = 0

  override fun recordRejectedOutput(
    request: RejectedOutputDiagnosticRequest,
    producerGeneration: Int,
  ): FeatureTaskRuntimeRejectedOutputWrite {
    retainProducerOutput(
      ProducerOutputEvidence(
        workflowId = request.workflowId,
        phaseId = request.phaseId,
        attempt = request.attempt,
        agentId = request.agentId,
        model = request.model,
        recordedAt = clock.instant(),
        byteSize = request.observedByteSize,
        sha256 = request.observedSha256,
        payload = request.rawResponse.takeUnless { request.truncated },
        generation = producerGeneration,
        repairTurn = request.repairTurn,
      ),
    )
    return FeatureTaskRuntimeRejectedOutputWrite.Written(request.observedSha256)
  }

  override fun retainProducerOutput(evidence: ProducerOutputEvidence) {
    producerOutputs += evidence.detached()
  }

  override fun producerOutput(args: ProducerOutputQueryArgs): FeatureTaskRuntimeProducerOutputRead =
    producerOutputs.lastOrNull { evidence ->
      evidence.phaseId == args.phaseId &&
        evidence.attempt == args.attempt &&
        evidence.agentId == args.agentId &&
        evidence.generation == args.generation
    }?.detached()?.let(FeatureTaskRuntimeProducerOutputRead::Found) ?: FeatureTaskRuntimeProducerOutputRead.Absent

  override fun recordPhaseState(request: FeatureTaskRuntimePhaseStateRequest): Boolean {
    phaseRecords[request.phaseId] =
      featureTaskRuntimePhaseRecordFor(request, phaseRecords[request.phaseId], clock.instant()).detached()
    return true
  }

  override fun recordRequiredPhaseStart(request: FeatureTaskRuntimePhaseStateRequest): RequiredPhaseWrite {
    recordPhaseState(request)
    return RequiredPhaseWrite.Acknowledged
  }

  override fun recordCompletedPhase(request: FeatureTaskRuntimePhaseStateRequest): Boolean = recordPhaseState(request)

  override fun recordIncompleteImplementationAttempt(request: FeatureTaskRuntimePhaseStateRequest): Boolean = true

  override fun loadImplementationAttempts(workflowId: String): List<FeatureTaskRuntimeImplementationAttempt> =
    emptyList()

  override fun loadPhaseRecords(workflowId: String): Map<String, FeatureTaskRuntimePhaseRecord> =
    phaseRecords.mapValues { (_, record) -> record.detached() }.toMap()

  override fun completeGoalReviewPhase(completion: GoalReviewPhaseCompletionRequest): Boolean =
    recordCompletedPhase(completion.phaseState)

  override fun persistReviewGenerationInvalidation(
    workflowId: String,
    reviewStepId: String,
  ): Int? {
    val previousReview = phaseRecords[reviewStepId] ?: return reviewGeneration
    val tombstone =
      FeatureTaskRuntimePhaseRecord(
        phaseId = reviewStepId,
        status = WorkflowStepStatus.RUNNING,
        attemptCount = previousReview.attemptCount,
        startedAt = previousReview.startedAt,
        firstStartedAt = previousReview.firstStartedAt,
        resolvedAgentId = REVIEW_INVALIDATION_AGENT_ID,
      ).detached()
    phaseRecords[reviewStepId] = tombstone
    reviewGeneration += 1
    unaddressedLedger.clear()
    return reviewGeneration
  }

  override fun invalidateQuarantinedProducerRecord(
    workflowId: String,
    producerPhaseId: String,
    loopId: String,
    edgeIteration: Int,
  ): Boolean = phaseRecords.remove(producerPhaseId) != null

  override fun recordedFindingVerdicts(output: Map<String, Any?>): List<ReviewFindingVerdict> = emptyList()

  override fun fetchUnaddressedLedger(workflowId: String): List<UnaddressedFinding> =
    unaddressedLedger.map { finding -> finding.copy(citations = finding.citations.toList()) }

  override fun appendRejectedVerificationFindings(
    workflowId: String,
    passNumber: Int,
    rejected: List<UnaddressedFinding>,
  ) {
    unaddressedLedger += rejected.map { it.copy(citations = it.citations.toList()) }
  }

  override fun loadFindingVerificationCheckpoint(
    workflowId: String,
  ): List<FeatureTaskRuntimeFindingVerificationDisposition>? = verificationCheckpoint?.detachedDispositions()

  override fun loadFindingVerificationBoundarySelection(
    workflowId: String,
  ): Map<String, List<FeatureTaskRuntimeVerificationBoundaryHeadingProvenance>>? =
    verificationBoundary?.detachedBoundary()

  override fun persistFindingVerificationBoundarySelection(
    workflowId: String,
    selections: Map<String, List<FeatureTaskRuntimeVerificationBoundaryHeadingProvenance>>,
  ): Boolean {
    verificationBoundary = selections.detachedBoundary()
    return true
  }

  override fun persistFindingVerificationCheckpoint(
    workflowId: String,
    dispositions: List<FeatureTaskRuntimeFindingVerificationDisposition>,
  ): Boolean {
    verificationCheckpoint = dispositions.detachedDispositions()
    return true
  }

  override fun recordPhaseBriefing(
    workflowId: String,
    briefing: FeatureTaskRuntimePhaseLaunchBriefing,
    sharedEvidenceMeasurement: FeatureTaskRuntimeSharedEvidenceMeasurement?,
    attempt: Int,
  ): RequiredPhaseWrite = RequiredPhaseWrite.Acknowledged

  override fun recordProjectionRejection(
    workflowId: String,
    consumerPhaseId: String,
    error: InvalidFeatureTaskRuntimeHandoffProjectionError,
    repositoryCheckpointFingerprint: String?,
  ): Boolean = true

  override fun recordProjectionRejection(rejection: FeatureTaskRuntimeProjectionRejection): Boolean = true

  override fun validateHandoffDeclarations(declarations: List<PhaseHandoffProjectionDeclaration>) = Unit

  override fun loadDeliveredProjections(workflowId: String): Map<String, FeatureTaskRuntimeDeliveredProjectionRecord> =
    emptyMap()

  override fun loadValidationGateProgress(workflowId: String): FeatureTaskRuntimeValidationGateProgress? =
    validationGateProgress[workflowId]?.detached()

  override fun persistValidationGateProgress(
    workflowId: String,
    progress: FeatureTaskRuntimeValidationGateProgress,
  ) {
    validationGateProgress[workflowId] = progress.detached()
  }

  override fun loadBuildGateProgress(workflowId: String): FeatureTaskRuntimeValidationGateProgress? =
    buildGateProgress[workflowId]?.detached()

  override fun persistBuildGateProgress(
    workflowId: String,
    progress: FeatureTaskRuntimeValidationGateProgress,
  ) {
    buildGateProgress[workflowId] = progress.detached()
  }

  override fun appendLedgerEntry(request: FeatureTaskRuntimePhaseLedgerRequest): Boolean = true

  override fun loadPhaseLedger(workflowId: String): List<FeatureTaskRuntimePhaseLedgerEntry> = emptyList()

  override fun appendQuarantineEntry(
    workflowId: String,
    entry: FeatureTaskRuntimeQuarantineEntry,
  ): Boolean = true

  override fun loadQuarantinedRecords(workflowId: String): List<FeatureTaskRuntimeQuarantineEntry> = emptyList()

  override fun loadResolvedBranch(workflowId: String): FeatureTaskRuntimeResolvedBranch? = resolvedBranch

  override fun loadGoalStartResolvedBranch(parentWorkflowId: String): FeatureTaskRuntimeResolvedBranch? = null

  override fun appendCheckpointIdentity(args: AppendCheckpointIdentityArgs): Boolean = true

  override fun loadCheckpointIdentities(workflowId: String): List<FeatureTaskRuntimeCheckpointIdentity> = emptyList()

  override fun recordWorkflowOwnedPaths(
    workflowId: String,
    ownedPaths: List<String>,
  ): Boolean = true

  override fun loadDecomposeTerminal(workflowId: String): FeatureTaskRuntimeDecomposeTerminal? = null

  override fun recordDecomposeTerminal(
    workflowId: String,
    terminal: FeatureTaskRuntimeDecomposeTerminal,
    planStepId: String,
  ): Boolean = true
}

private fun ProducerOutputEvidence.detached(): ProducerOutputEvidence = copy(payload = payload?.copyOf())

private fun FeatureTaskRuntimePhaseRecord.detached(): FeatureTaskRuntimePhaseRecord =
  copy(
    fileManifestBefore = fileManifestBefore.toList(),
    fileManifestAfter = fileManifestAfter.toList(),
    fileManifestIntroduced = fileManifestIntroduced.toList(),
  )

private fun List<FeatureTaskRuntimeFindingVerificationDisposition>.detachedDispositions() =
  map { disposition -> disposition.copy(selectedBoundaryHeadings = disposition.selectedBoundaryHeadings.toList()) }

private fun Map<String, List<FeatureTaskRuntimeVerificationBoundaryHeadingProvenance>>.detachedBoundary() =
  mapValues { (_, headings) -> headings.toList() }

private fun FeatureTaskRuntimeValidationGateProgress.detached() =
  copy(
    gateRuns = gateRuns.map { run -> run.copy(executedChecks = run.executedChecks.toList()) },
    remainingFindings = remainingFindings.map { it.toMap() },
    completeFindings = completeFindings.map { it.toMap() },
    lastAgentUnfixedCriteria = lastAgentUnfixedCriteria.toList(),
  )
