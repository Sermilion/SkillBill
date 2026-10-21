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
import skillbill.engine.featuretask.phase.record.FeatureTaskRuntimeNoChangePausePort
import skillbill.engine.featuretask.phase.record.featureTaskRuntimePhaseRecordFor
import skillbill.engine.featuretask.phaserun.monitor.StandaloneMonitorLedger
import skillbill.engine.featuretask.phaserun.monitor.StandaloneMonitorRunRecords
import skillbill.engine.featuretask.runloop.state.REVIEW_INVALIDATION_AGENT_ID
import skillbill.engine.featuretask.slot.state.FeatureTaskRuntimeImplementationTurnWrite
import skillbill.engine.featuretask.slot.state.PhaseImplementationProgressRecords
import skillbill.engine.featuretask.slot.state.PhasePlanRecords
import skillbill.engine.featuretask.slot.state.PhaseRunRecords
import skillbill.engine.featuretask.slot.state.RequiredPhaseWrite
import skillbill.engine.featuretask.slot.state.RequiredPhaseWriteKind
import skillbill.error.featuretask.InvalidFeatureTaskRuntimeHandoffProjectionContext
import skillbill.goalrunner.model.UnaddressedFinding
import skillbill.ports.diagnostics.model.ProducerOutputEvidence
import skillbill.review.model.ReviewFindingVerdict
import skillbill.workflow.model.WorkflowStepStatus
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
import skillbill.workflow.taskruntime.model.persistence.FeatureTaskRuntimeImplementationAttemptStatus
import skillbill.workflow.taskruntime.model.persistence.FeatureTaskRuntimeImplementationProgress
import skillbill.workflow.taskruntime.model.persistence.featureTaskRuntimeAppendImplementationAttempt
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimeDeliveredProjectionRecord
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimePhaseLedgerAction
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimePhaseLedgerEntry
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimePhaseRecord
import skillbill.workflow.taskruntime.model.qa.QaRepairState
import skillbill.workflow.taskruntime.model.validation.FeatureTaskRuntimeFindingVerificationDisposition
import skillbill.workflow.taskruntime.model.validation.FeatureTaskRuntimeValidationGateProgress
import skillbill.workflow.taskruntime.phase.task.FeatureTaskRuntimePhaseWorkflowDefinition
import java.time.Clock

internal class InMemoryPhaseRunRecords private constructor(
  private val clock: Clock,
  private val resolvedBranch: FeatureTaskRuntimeResolvedBranch?,
  private val phaseRecords: LinkedHashMap<String, FeatureTaskRuntimePhaseRecord>,
  override val standaloneMonitorLedger: StandaloneMonitorLedger?,
) : PhaseRunRecords,
  StandaloneMonitorRunRecords,
  PhasePlanRecords by InMemoryPhasePlanRecords(phaseRecords),
  FeatureTaskRuntimeNoChangePausePort by InMemoryNoChangePauses(),
  PhaseImplementationProgressRecords by InMemoryImplementationProgressRecords(clock, phaseRecords) {
  constructor(
    clock: Clock,
    resolvedBranch: FeatureTaskRuntimeResolvedBranch?,
    standaloneMonitorLedger: StandaloneMonitorLedger? = null,
    initialQaRepairState: QaRepairState? = null,
  ) : this(clock, resolvedBranch, LinkedHashMap(), standaloneMonitorLedger) {
    qaState = initialQaRepairState
  }

  val acceptedOutputs = mutableMapOf<String, String>()
  private val producerOutputs = mutableListOf<ProducerOutputEvidence>()
  private val phaseLedger = mutableListOf<FeatureTaskRuntimePhaseLedgerEntry>()
  private val validationGateProgress = mutableMapOf<String, FeatureTaskRuntimeValidationGateProgress>()
  private val buildGateProgress = mutableMapOf<String, FeatureTaskRuntimeValidationGateProgress>()
  private val unaddressedLedger = mutableListOf<UnaddressedFinding>()
  private var verificationCheckpoint: List<FeatureTaskRuntimeFindingVerificationDisposition>? = null
  private var verificationBoundary: Map<String, List<FeatureTaskRuntimeVerificationBoundaryHeadingProvenance>>? = null
  private var reviewGeneration: Int = 0
  private var qaState: QaRepairState? = null

  override fun loadQaRepairState(workflowId: String): QaRepairState? = qaState

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
    phaseLedger += phaseLedgerEntry(FeatureTaskRuntimePhaseLedgerAction.START, request)
    return RequiredPhaseWrite.Acknowledged
  }

  override fun recordCompletedPhase(request: FeatureTaskRuntimePhaseStateRequest): RequiredPhaseWrite {
    request.normalizedOutput?.qaRepairState?.let { qaState = it }
    request.normalizedOutput?.output?.value?.let { acceptedOutputs[request.phaseId] = it }
    recordPhaseState(request)
    phaseLedger += phaseLedgerEntry(FeatureTaskRuntimePhaseLedgerAction.COMPLETE, request)
    return RequiredPhaseWrite.Acknowledged
  }

  override fun recordIncompleteImplementationAttempt(request: FeatureTaskRuntimePhaseStateRequest): Boolean = true

  override fun loadPhaseRecords(workflowId: String): Map<String, FeatureTaskRuntimePhaseRecord> =
    phaseRecords.mapValues { (_, record) -> record.detached() }.toMap()

  override fun completeGoalReviewPhase(completion: GoalReviewPhaseCompletionRequest): Boolean =
    recordCompletedPhase(completion.phaseState) == RequiredPhaseWrite.Acknowledged

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
    context: InvalidFeatureTaskRuntimeHandoffProjectionContext,
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

  override fun appendLedgerEntry(request: FeatureTaskRuntimePhaseLedgerRequest): Boolean {
    phaseLedger +=
      FeatureTaskRuntimePhaseLedgerEntry(
        action = request.action,
        sequenceNumber = phaseLedger.size + 1,
        timestamp = clock.instant(),
        phaseId = request.phaseId,
        attemptCount = request.attemptCount,
        resolvedAgentId = request.resolvedAgentId,
        loopId = request.loopId,
        edgeIteration = request.edgeIteration,
        blockedReason = request.blockedReason,
      )
    return true
  }

  override fun loadPhaseLedger(workflowId: String): List<FeatureTaskRuntimePhaseLedgerEntry> = phaseLedger.toList()

  private fun phaseLedgerEntry(
    action: FeatureTaskRuntimePhaseLedgerAction,
    request: FeatureTaskRuntimePhaseStateRequest,
  ) = FeatureTaskRuntimePhaseLedgerEntry(
    action = action,
    sequenceNumber = phaseLedger.size + 1,
    timestamp = clock.instant(),
    phaseId = request.phaseId,
    attemptCount = request.attemptCount,
    resolvedAgentId = request.resolvedAgentId,
  )

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
}

private class InMemoryPhasePlanRecords(
  private val phaseRecords: MutableMap<String, FeatureTaskRuntimePhaseRecord>,
) : PhasePlanRecords {
  private val planBundles = mutableMapOf<String, FeatureTaskRuntimePlanBundle>()

  override fun loadPlanBundle(workflowId: String): FeatureTaskRuntimePlanBundle? = planBundles[workflowId]

  override fun recordPlanBundle(
    workflowId: String,
    bundle: FeatureTaskRuntimePlanBundle,
  ): Boolean {
    planBundles[workflowId] = bundle
    return true
  }

  override fun recordPlanBundleWithUnits(
    workflowId: String,
    bundle: FeatureTaskRuntimePlanBundle,
    unitRecords: Map<Int, FeatureTaskRuntimePhaseRecord>,
  ): Boolean {
    unitRecords.forEach { (subtaskId, record) ->
      phaseRecords[FeatureTaskRuntimePhaseWorkflowDefinition.planSubtaskUnitRecordId(subtaskId)] = record.detached()
    }
    return recordPlanBundle(workflowId, bundle)
  }

  override fun loadDecomposeTerminal(workflowId: String): FeatureTaskRuntimeDecomposeTerminal? = null

  override fun recordDecomposeTerminal(
    workflowId: String,
    terminal: FeatureTaskRuntimeDecomposeTerminal,
    planStepId: String,
  ): Boolean = true
}

private class InMemoryImplementationProgressRecords(
  private val clock: Clock,
  private val phaseRecords: Map<String, FeatureTaskRuntimePhaseRecord>,
) : PhaseImplementationProgressRecords {
  private var implementationProgress: FeatureTaskRuntimeImplementationProgress? = null
  private val implementationTurns = mutableListOf<FeatureTaskRuntimeImplementationAttempt>()

  override fun loadImplementationAttempts(workflowId: String): List<FeatureTaskRuntimeImplementationAttempt> =
    implementationTurns.toList()

  override fun loadImplementationProgress(workflowId: String): FeatureTaskRuntimeImplementationProgress? =
    implementationProgress

  override fun recordImplementationTurn(write: FeatureTaskRuntimeImplementationTurnWrite): RequiredPhaseWrite {
    val phase = phaseRecords[write.phaseId]
    if (
      implementationProgress != write.expectedProgress ||
      phase?.attemptCount != write.attemptNumber ||
      phase.status == WorkflowStepStatus.COMPLETED
    ) {
      return RequiredPhaseWrite.Rejected(
        RequiredPhaseWriteKind.IMPLEMENTATION_PROGRESS,
        write.workflowId,
        write.phaseId,
        write.attemptNumber,
      )
    }
    val appended =
      featureTaskRuntimeAppendImplementationAttempt(
        implementationTurns.toList(),
        FeatureTaskRuntimeImplementationAttempt(
          sequenceNumber = (implementationTurns.maxOfOrNull { it.sequenceNumber } ?: -1) + 1,
          phaseId = write.phaseId,
          attemptNumber = write.attemptNumber,
          agentId = write.agentId,
          status = FeatureTaskRuntimeImplementationAttemptStatus.COMPLETED,
          recordedAt = clock.instant().toString(),
          value = write.value,
          prompt = write.marker.encode(),
        ),
      )
    implementationTurns.clear()
    implementationTurns += appended
    implementationProgress = write.progress
    return RequiredPhaseWrite.Acknowledged
  }
}

private fun ProducerOutputEvidence.detached(): ProducerOutputEvidence = copy(payload = payload?.copyOf())

private class InMemoryNoChangePauses : FeatureTaskRuntimeNoChangePausePort {
  private val pauses = mutableMapOf<String, FeatureTaskRuntimeNoChangePause>()

  override fun loadNoChangePause(workflowId: String): FeatureTaskRuntimeNoChangePause? = pauses[workflowId]

  override fun persistNoChangePause(
    workflowId: String,
    pause: FeatureTaskRuntimeNoChangePause,
  ) {
    pauses[workflowId] = pause
  }
}

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
