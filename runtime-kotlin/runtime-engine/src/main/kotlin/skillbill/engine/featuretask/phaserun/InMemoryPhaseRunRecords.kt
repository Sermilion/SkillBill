package skillbill.engine.featuretask.phaserun

import skillbill.application.diagnostics.model.RejectedOutputDiagnosticRequest
import skillbill.engine.featuretask.model.execution.ValidationGateCommandFamily
import skillbill.engine.featuretask.model.phase.AppendCheckpointIdentityArgs
import skillbill.engine.featuretask.model.phase.FeatureTaskRuntimePhaseLedgerRequest
import skillbill.engine.featuretask.model.phase.FeatureTaskRuntimePhaseStateRequest
import skillbill.engine.featuretask.model.phase.FeatureTaskRuntimeProducerOutputRead
import skillbill.engine.featuretask.model.phase.GoalReviewPhaseCompletionRequest
import skillbill.engine.featuretask.model.phase.PlanBundleFeasibilityStateWrite
import skillbill.engine.featuretask.model.phase.PlanBundleFeasibilityStateWriteResult
import skillbill.engine.featuretask.model.phase.ProducerOutputQueryArgs
import skillbill.engine.featuretask.model.review.FeatureTaskRuntimeRejectedOutputWrite
import skillbill.engine.featuretask.phase.planning.planBundleFeasibilityWriteResult
import skillbill.engine.featuretask.phase.record.FeatureTaskRuntimeNoChangePausePort
import skillbill.engine.featuretask.phase.record.featureTaskRuntimePhaseRecordFor
import skillbill.engine.featuretask.phaserun.monitor.StandaloneMonitorLedger
import skillbill.engine.featuretask.phaserun.monitor.StandaloneMonitorRunRecords
import skillbill.engine.featuretask.runloop.state.REVIEW_INVALIDATION_AGENT_ID
import skillbill.engine.featuretask.runner.STATUS_RUNNING
import skillbill.engine.featuretask.slot.state.FeatureTaskRuntimeImplementationTurnWrite
import skillbill.engine.featuretask.slot.state.PhaseImplementationProgressRecords
import skillbill.engine.featuretask.slot.state.PhaseLaunchRecords
import skillbill.engine.featuretask.slot.state.PhasePlanRecords
import skillbill.engine.featuretask.slot.state.PhaseRunRecords
import skillbill.engine.featuretask.slot.state.RequiredPhaseWrite
import skillbill.engine.featuretask.slot.state.RequiredPhaseWriteKind
import skillbill.error.core.SkillBillRuntimeException
import skillbill.error.shellcontent.WorkflowFailureCode
import skillbill.goalrunner.model.UnaddressedFinding
import skillbill.goalrunner.model.quality.GoalQualityAcceptedEnvelope
import skillbill.goalrunner.model.quality.GoalQualityAcceptedOutput
import skillbill.goalrunner.model.quality.GoalQualityAcceptedSourceObservation
import skillbill.goalrunner.model.quality.GoalQualityPhaseProgress
import skillbill.goalrunner.model.quality.GoalQualityQaSourceWitness
import skillbill.ports.diagnostics.model.ProducerOutputEvidence
import skillbill.review.model.ReviewFindingVerdict
import skillbill.workflow.model.WorkflowStepStatus
import skillbill.workflow.taskruntime.model.audit.FeatureTaskRuntimeNoChangePause
import skillbill.workflow.taskruntime.model.audit.FeatureTaskRuntimeQuarantineEntry
import skillbill.workflow.taskruntime.model.core.FeatureTaskRuntimeDecomposeTerminal
import skillbill.workflow.taskruntime.model.core.FeatureTaskRuntimePlanBundle
import skillbill.workflow.taskruntime.model.core.FeatureTaskRuntimePlanFanOutHistory
import skillbill.workflow.taskruntime.model.core.FeatureTaskRuntimeResolvedBranch
import skillbill.workflow.taskruntime.model.core.FeatureTaskRuntimeWorkflowArtifactMap
import skillbill.workflow.taskruntime.model.feature.FeatureTaskRuntimeVerificationBoundaryHeadingProvenance
import skillbill.workflow.taskruntime.model.persistence.FeatureTaskRuntimeCheckpointIdentity
import skillbill.workflow.taskruntime.model.persistence.FeatureTaskRuntimeImplementationAttempt
import skillbill.workflow.taskruntime.model.persistence.FeatureTaskRuntimeImplementationAttemptStatus
import skillbill.workflow.taskruntime.model.persistence.FeatureTaskRuntimeImplementationProgress
import skillbill.workflow.taskruntime.model.persistence.featureTaskRuntimeAppendImplementationAttempt
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimePhaseLedgerAction
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimePhaseLedgerEntry
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimePhaseRecord
import skillbill.workflow.taskruntime.model.planning.FeatureTaskRuntimePlanBundleFeasibilityState
import skillbill.workflow.taskruntime.model.planning.PlanBundleFeasibilityIdentity
import skillbill.workflow.taskruntime.model.qa.QaRepairState
import skillbill.workflow.taskruntime.model.repair.FeatureTaskRuntimeOperatorBlockRetry
import skillbill.workflow.taskruntime.model.validation.FeatureTaskRuntimeFindingVerificationDisposition
import skillbill.workflow.taskruntime.model.validation.FeatureTaskRuntimeValidationGateProgress
import skillbill.workflow.taskruntime.phase.task.FeatureTaskRuntimePhaseWorkflowDefinition
import skillbill.workflow.taskruntime.validation.FeatureTaskRuntimeProviderLimitPause
import java.security.MessageDigest
import java.time.Clock

public class InMemoryPhaseRunRecords private constructor(
  public val clock: Clock,
  public val resolvedBranch: FeatureTaskRuntimeResolvedBranch?,
  public val phaseRecords: LinkedHashMap<String, FeatureTaskRuntimePhaseRecord>,
  public override val standaloneMonitorLedger: StandaloneMonitorLedger?,
  private val acceptedGoalRepairOutput: ((String, Int, String) -> Unit)?,
  private val goalQualityProgressSink: ((GoalQualityPhaseProgress, String?) -> Boolean)?,
  private val initialGoalQualityProgress: GoalQualityPhaseProgress?,
  private val goalQualityWorkflowId: String,
  private val phaseRecordSink: ((FeatureTaskRuntimePhaseRecord) -> Boolean)?,
  private val qaSourceWitness: ((GoalQualityPhaseProgress) -> GoalQualityQaSourceWitness?)?,
  private val qualitySourceObservation: ((String, Int) -> GoalQualityAcceptedSourceObservation)?,
) : PhaseRunRecords,
  StandaloneMonitorRunRecords,
  PhaseLaunchRecords by InMemoryPhaseLaunchRecords(),
  PhasePlanRecords by InMemoryPhasePlanRecords(phaseRecords),
  FeatureTaskRuntimeNoChangePausePort by InMemoryNoChangePauses(),
  PhaseImplementationProgressRecords by InMemoryImplementationProgressRecords(clock, phaseRecords) {
  internal constructor(
    clock: Clock,
    resolvedBranch: FeatureTaskRuntimeResolvedBranch?,
    standaloneMonitorLedger: StandaloneMonitorLedger? = null,
    initialQaRepairState: QaRepairState? = null,
    acceptedGoalRepairOutput: ((String, Int, String) -> Unit)? = null,
    goalQualityPhaseProgress: GoalQualityPhaseProgress? = null,
    persistGoalQualityPhaseProgress: ((GoalQualityPhaseProgress, String?) -> Boolean)? = null,
    goalQualityWorkflowId: String = "",
    phaseRecordSink: ((FeatureTaskRuntimePhaseRecord) -> Boolean)? = null,
    qualitySourceObservation: ((String, Int) -> GoalQualityAcceptedSourceObservation)? = null,
    qaSourceWitness: ((GoalQualityPhaseProgress) -> GoalQualityQaSourceWitness?)? = null,
  ) : this(
    clock,
    resolvedBranch,
    LinkedHashMap(goalQualityPhaseProgress?.phaseRecords.orEmpty()),
    standaloneMonitorLedger,
    acceptedGoalRepairOutput,
    persistGoalQualityPhaseProgress,
    goalQualityPhaseProgress,
    goalQualityWorkflowId,
    phaseRecordSink,
    qaSourceWitness,
    qualitySourceObservation,
  ) {
    qaState = goalQualityPhaseProgress?.qaRepairState ?: initialQaRepairState
    goalQualityPhaseProgress?.let { progress ->
      phaseLedger.addAll(progress.phaseLedger)
      progress.acceptedOutputs.forEach { acceptedOutputs[it.phaseId] = it.output }
      progress.buildGateProgress?.let {
        currentBuildGateProgressKey = progress.buildGateProgressKey
        progress.buildGateProgressKey?.let { key -> buildGateProgress[key] = it.detached() }
      }
      progress.validationGateProgress?.let {
        currentValidationGateProgressKey = progress.validationGateProgressKey
        progress.validationGateProgressKey?.let { key -> validationGateProgress[key] = it.detached() }
      }
      verificationCheckpoint = progress.verificationCheckpoint?.detachedDispositions()
      verificationBoundary = progress.verificationBoundary?.detachedBoundary()
    }
  }

  internal val acceptedOutputs: MutableMap<String, String> = mutableMapOf()
  private val producerOutputs = mutableListOf<ProducerOutputEvidence>()
  private val phaseLedger = mutableListOf<FeatureTaskRuntimePhaseLedgerEntry>()
  private val validationGateProgress = mutableMapOf<String, FeatureTaskRuntimeValidationGateProgress>()
  private val buildGateProgress = mutableMapOf<String, FeatureTaskRuntimeValidationGateProgress>()
  private var currentBuildGateProgressKey: String? = null
  private var currentValidationGateProgressKey: String? = null
  private val unaddressedLedger = mutableListOf<UnaddressedFinding>()
  private var verificationCheckpoint: List<FeatureTaskRuntimeFindingVerificationDisposition>? = null
  private var verificationBoundary: Map<String, List<FeatureTaskRuntimeVerificationBoundaryHeadingProvenance>>? = null
  private var reviewGeneration: Int = 0
  private var qaState: QaRepairState? = null
  private var qualityProgressRevision: Long = initialGoalQualityProgress?.revision ?: 0

  private val acceptedOccurrences = initialGoalQualityProgress?.acceptedOutputs?.toMutableList() ?: mutableListOf()
  private val acceptedEnvelopes = initialGoalQualityProgress?.acceptedEnvelopes?.toMutableList() ?: mutableListOf()
  private val acceptedQaSourceWitnesses =
    initialGoalQualityProgress?.acceptedQaSourceWitnesses?.toMutableList() ?: mutableListOf()
  private val acceptedSourceObservations =
    initialGoalQualityProgress?.acceptedSourceObservations?.toMutableList() ?: mutableListOf()

  override fun loadQaRepairState(workflowId: String): QaRepairState? = qaState

  override fun loadOperatorBlockRetry(workflowId: String): FeatureTaskRuntimeOperatorBlockRetry? = null

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
    val proposedRecords = LinkedHashMap(phaseRecords)
    applyPhaseRecord(proposedRecords, request, clock)
    val proposedRecord = proposedRecords.getValue(request.phaseId).detached()
    if (phaseRecordSink?.invoke(proposedRecord) == false) return false
    if (goalQualityProgressSink != null) {
      persistGoalQualityProgress(goalQualityProgress().copy(phaseRecords = proposedRecords.toMap()))
    }
    phaseRecords[request.phaseId] = proposedRecord
    return true
  }

  override fun recordRequiredPhaseStart(request: FeatureTaskRuntimePhaseStateRequest): RequiredPhaseWrite {
    val existing = phaseRecords[request.phaseId]
    val sameAttempt = existing?.attemptCount == request.attemptCount
    val resumablePausedGate =
      existing?.status == WorkflowStepStatus.PAUSED &&
        FeatureTaskRuntimeProviderLimitPause.isProviderLimitPauseReason(existing.blockedReason.orEmpty()) &&
        retainedPausedGate(
          request.phaseId,
          request.attemptCount,
          listOf(
            Triple(currentBuildGateProgressKey, buildGateProgress, ValidationGateCommandFamily.BUILD),
            Triple(currentValidationGateProgressKey, validationGateProgress, ValidationGateCommandFamily.VALIDATION),
          ),
        )
    val retained = existing
    if (goalQualityProgressSink != null && retained != null &&
      recoverableStart(retained, resumablePausedGate, sameAttempt)
    ) {
      val replacement = recoverPhaseStart(request, retained, phaseLedger, goalQualityWorkflowId)
      if (replacement == null) {
        return RequiredPhaseWrite.Rejected(
          RequiredPhaseWriteKind.START,
          request.workflowId,
          request.phaseId,
          request.attemptCount,
        )
      }
      if (replacement != retained) {
        val proposed =
          goalQualityProgress().copy(
            phaseRecords = phaseRecords + (request.phaseId to replacement.detached()),
          )
        persistGoalQualityProgress(proposed)
        phaseRecords[request.phaseId] = replacement.detached()
      }
      return RequiredPhaseWrite.Acknowledged
    }
    val proposedRecords = LinkedHashMap(phaseRecords)
    applyPhaseRecord(proposedRecords, request, clock)
    val proposedRecord = proposedRecords.getValue(request.phaseId).detached()
    if (phaseRecordSink?.invoke(proposedRecord) == false) {
      return RequiredPhaseWrite.Rejected(
        RequiredPhaseWriteKind.START,
        request.workflowId,
        request.phaseId,
        request.attemptCount,
      )
    }
    val proposedLedger =
      phaseLedger +
        phaseLedgerEntry(
          clock,
          nextLedgerSequence(phaseLedger, initialGoalQualityProgress),
          FeatureTaskRuntimePhaseLedgerAction.START,
          request,
        )
    if (goalQualityProgressSink != null) {
      persistGoalQualityProgress(
        goalQualityProgress().copy(phaseRecords = proposedRecords.toMap(), phaseLedger = proposedLedger),
      )
    }
    phaseRecords[request.phaseId] = proposedRecord
    phaseLedger += proposedLedger.last()
    return RequiredPhaseWrite.Acknowledged
  }

  override fun recordCompletedPhase(request: FeatureTaskRuntimePhaseStateRequest): RequiredPhaseWrite {
    val output = (request.normalizedOutput?.output?.value ?: request.outputArtifact)?.takeIf(String::isNotBlank)
    val proposedRecords = LinkedHashMap(phaseRecords)
    applyPhaseRecord(proposedRecords, request, clock)
    val proposedRecord = proposedRecords.getValue(request.phaseId).detached()
    if (phaseRecordSink?.invoke(proposedRecord) == false) {
      return RequiredPhaseWrite.Rejected(
        RequiredPhaseWriteKind.COMPLETION,
        request.workflowId,
        request.phaseId,
        request.attemptCount,
      )
    }
    val proposedLedger =
      phaseLedger +
        phaseLedgerEntry(
          clock,
          nextLedgerSequence(phaseLedger, initialGoalQualityProgress),
          FeatureTaskRuntimePhaseLedgerAction.COMPLETE,
          request,
        )
    val proposedOutputs =
      output?.let {
        acceptedOccurrences + GoalQualityAcceptedOutput(request.phaseId, request.attemptCount, it)
      } ?: acceptedOccurrences.toList()
    val sourceObservation =
      if (goalQualityProgressSink != null) {
        qualitySourceObservation?.invoke(request.phaseId, request.attemptCount)?.copy(
          completionSequenceNumber = proposedLedger.last().sequenceNumber,
          progressRevision = qualityProgressRevision + 1,
          acceptedOutputFingerprint = output?.let(::sha256),
        )
      } else {
        null
      }
    val proposedObservations =
      sourceObservation?.let {
        acceptedSourceObservations + it
      } ?: acceptedSourceObservations.toList()
    val proposed =
      goalQualityProgress().copy(
        phaseRecords = proposedRecords.toMap(),
        phaseLedger = proposedLedger,
        acceptedOutputs = proposedOutputs,
        acceptedSourceObservations = proposedObservations,
        qaRepairState = request.normalizedOutput?.qaRepairState ?: qaState,
      )
    if (goalQualityProgressSink != null) persistGoalQualityProgress(proposed)
    phaseRecords[request.phaseId] = proposedRecord
    phaseLedger += proposedLedger.last()
    if (output != null) {
      acceptedOccurrences += GoalQualityAcceptedOutput(request.phaseId, request.attemptCount, output)
      acceptedOutputs[request.phaseId] = output
    }
    request.normalizedOutput?.qaRepairState?.let { qaState = it }
    sourceObservation?.let(acceptedSourceObservations::add)
    output?.let { acceptedGoalRepairOutput?.invoke(request.phaseId, request.attemptCount, it) }
    return RequiredPhaseWrite.Acknowledged
  }

  override fun recordIncompleteImplementationAttempt(request: FeatureTaskRuntimePhaseStateRequest): Boolean = true

  override fun loadPhaseRecords(workflowId: String): Map<String, FeatureTaskRuntimePhaseRecord> =
    phaseRecords.mapValues { (_, record) -> record.detached() }.toMap()

  public fun identifiedCompletedOutputs(stepIds: List<String>): List<PhaseRunCompletedOutput> =
    if (goalQualityProgressSink != null) {
      acceptedOccurrences.map { PhaseRunCompletedOutput(it.phaseId, it.occurrence, it.output) }
        .filter {
          it.phaseId in stepIds && phaseRecords[it.phaseId]?.let { record ->
            record.status == WorkflowStepStatus.COMPLETED && record.attemptCount == it.occurrence
          } == true
        }
    } else {
      stepIds.mapNotNull { phaseId ->
        phaseRecords[phaseId]?.takeIf { it.status == WorkflowStepStatus.COMPLETED }
          ?.let { acceptedOutputs[phaseId] }?.takeIf(String::isNotBlank)
          ?.let { output -> PhaseRunCompletedOutput(phaseId, phaseRecords.getValue(phaseId).attemptCount, output) }
      }
    }

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
    val detached = selections.detachedBoundary()
    if (goalQualityProgressSink != null) {
      persistGoalQualityProgress(goalQualityProgress().copy(verificationBoundary = detached))
    }
    verificationBoundary = detached
    return true
  }

  override fun persistFindingVerificationCheckpoint(
    workflowId: String,
    dispositions: List<FeatureTaskRuntimeFindingVerificationDisposition>,
  ): Boolean {
    val detached = dispositions.detachedDispositions()
    if (goalQualityProgressSink != null) {
      persistGoalQualityProgress(goalQualityProgress().copy(verificationCheckpoint = detached))
    }
    verificationCheckpoint = detached
    return true
  }

  override fun loadValidationGateProgress(workflowId: String): FeatureTaskRuntimeValidationGateProgress? =
    validationGateProgress[workflowId]?.detached()

  override fun persistValidationGateProgress(
    workflowId: String,
    progress: FeatureTaskRuntimeValidationGateProgress,
  ) {
    val detached = progress.detached()
    if (goalQualityProgressSink != null) {
      persistGoalQualityProgress(
        goalQualityProgress().copy(validationGateProgress = detached, validationGateProgressKey = workflowId),
      )
    }
    validationGateProgress[workflowId] = detached
    currentValidationGateProgressKey = workflowId
  }

  override fun loadBuildGateProgress(workflowId: String): FeatureTaskRuntimeValidationGateProgress? =
    buildGateProgress[workflowId]?.detached()

  override fun persistBuildGateProgress(
    workflowId: String,
    progress: FeatureTaskRuntimeValidationGateProgress,
  ) {
    val detached = progress.detached()
    if (goalQualityProgressSink != null) {
      persistGoalQualityProgress(
        goalQualityProgress().copy(buildGateProgress = detached, buildGateProgressKey = workflowId),
      )
    }
    buildGateProgress[workflowId] = detached
    currentBuildGateProgressKey = workflowId
  }

  override fun appendLedgerEntry(request: FeatureTaskRuntimePhaseLedgerRequest): Boolean {
    val entry =
      FeatureTaskRuntimePhaseLedgerEntry(
        action = request.action,
        sequenceNumber = nextLedgerSequence(phaseLedger, initialGoalQualityProgress),
        timestamp = clock.instant(),
        phaseId = request.phaseId,
        attemptCount = request.attemptCount,
        resolvedAgentId = request.resolvedAgentId,
        loopId = request.loopId,
        edgeIteration = request.edgeIteration,
        blockedReason = request.blockedReason,
      )
    val proposedLedger = phaseLedger + entry
    val proposedObservations = acceptedSourceObservations.toMutableList()
    if (request.action == FeatureTaskRuntimePhaseLedgerAction.BLOCKED && goalQualityProgressSink != null) {
      val record = phaseRecords[request.phaseId]
      if (record?.status == WorkflowStepStatus.BLOCKED && record.attemptCount == request.attemptCount) {
        qualitySourceObservation?.invoke(request.phaseId, request.attemptCount)?.let { observation ->
          proposedObservations +=
            observation.copy(
              completionSequenceNumber = entry.sequenceNumber,
              progressRevision = qualityProgressRevision + 1,
            )
        }
      }
    }
    if (request.action == FeatureTaskRuntimePhaseLedgerAction.COMPLETE) {
      val index =
        proposedObservations.indexOfLast {
          it.phaseId == request.phaseId && it.attempt == request.attemptCount
        }
      if (index >= 0) {
        proposedObservations[index] =
          proposedObservations[index].copy(
            completionSequenceNumber = entry.sequenceNumber,
            progressRevision = qualityProgressRevision + 1,
          )
      }
    }
    if (goalQualityProgressSink != null) {
      persistGoalQualityProgress(
        goalQualityProgress().copy(phaseLedger = proposedLedger, acceptedSourceObservations = proposedObservations),
      )
    }
    phaseLedger += entry
    acceptedSourceObservations.clear()
    acceptedSourceObservations.addAll(proposedObservations)
    return true
  }

  internal fun loadAcceptedEnvelope(
    phaseId: String,
    attempt: Int,
  ): FeatureTaskRuntimeWorkflowArtifactMap? =
    acceptedEnvelopes.lastOrNull {
      it.phaseId == phaseId && it.attempt == attempt && phaseRecords[phaseId]?.attemptCount == attempt
    }?.envelope

  internal fun recordAcceptedEnvelope(
    phaseId: String,
    attempt: Int,
    envelope: FeatureTaskRuntimeWorkflowArtifactMap,
  ): Boolean {
    val existing = acceptedEnvelopes.singleOrNull { it.phaseId == phaseId && it.attempt == attempt }
    if (existing != null) return existing.envelope == envelope
    val proposed = acceptedEnvelopes + GoalQualityAcceptedEnvelope(phaseId, attempt, envelope)
    if (goalQualityProgressSink != null) {
      persistGoalQualityProgress(goalQualityProgress().copy(acceptedEnvelopes = proposed))
    }
    acceptedEnvelopes += proposed.last()
    return true
  }

  internal fun clearAcceptedEnvelope(
    phaseId: String,
    attempt: Int,
  ): Boolean {
    val proposed = acceptedEnvelopes.filterNot { it.phaseId == phaseId && it.attempt == attempt }
    if (proposed.size == acceptedEnvelopes.size) return true
    if (goalQualityProgressSink != null) {
      persistGoalQualityProgress(goalQualityProgress().copy(acceptedEnvelopes = proposed))
    }
    acceptedEnvelopes.clear()
    acceptedEnvelopes.addAll(proposed)
    return true
  }

  internal fun goalQualityProgress(
    terminalBlockedStep: String? = null,
    terminalBlockedReason: String? = null,
    terminalCompleted: Boolean = false,
  ): GoalQualityPhaseProgress =
    GoalQualityPhaseProgress(
      phaseRecords = phaseRecords.mapValues { (_, record) -> record.detached() },
      phaseLedger = phaseLedger.toList(),
      acceptedOutputs = acceptedOccurrences.toList(),
      acceptedEnvelopes = acceptedEnvelopes.toList(),
      acceptedSourceObservations = acceptedSourceObservations.toList(),
      acceptedQaSourceWitnesses = acceptedQaSourceWitnesses.toList(),
      attemptHighWaterMarks =
        initialGoalQualityProgress?.attemptHighWaterMarks.orEmpty() + phaseRecords.mapValues { it.value.attemptCount },
      ledgerSequenceHighWaterMark =
        maxOf(
          initialGoalQualityProgress?.ledgerSequenceHighWaterMark ?: 0,
          phaseLedger.maxOfOrNull { it.sequenceNumber } ?: 0,
        ),
      carriedValidationRepairsUsed = initialGoalQualityProgress?.carriedValidationRepairsUsed ?: 0,
      carriedValidationRepairOutputs = initialGoalQualityProgress?.carriedValidationRepairOutputs.orEmpty(),
      carriedBuildRepairsUsed = initialGoalQualityProgress?.carriedBuildRepairsUsed ?: 0,
      carriedBuildRepairOutputs = initialGoalQualityProgress?.carriedBuildRepairOutputs.orEmpty(),
      qaRepairState = qaState,
      buildGateProgress = currentBuildGateProgressKey?.let(buildGateProgress::get)?.detached(),
      buildGateProgressKey = currentBuildGateProgressKey,
      validationGateProgress = currentValidationGateProgressKey?.let(validationGateProgress::get)?.detached(),
      validationGateProgressKey = currentValidationGateProgressKey,
      verificationCheckpoint = verificationCheckpoint?.detachedDispositions(),
      verificationBoundary = verificationBoundary?.detachedBoundary(),
      terminalBlockedStep = terminalBlockedStep,
      terminalBlockedReason = terminalBlockedReason,
      terminalCompleted = terminalCompleted,
      revision = qualityProgressRevision,
    )

  private fun persistGoalQualityProgress(progress: GoalQualityPhaseProgress = goalQualityProgress()): Boolean {
    val sink = goalQualityProgressSink ?: return true
    val next = progress.copy(revision = qualityProgressRevision + 1)
    val witness = qaSourceWitness?.invoke(next)
    val proposed = next.copy(acceptedQaSourceWitnesses = acceptedQaSourceWitnesses + listOfNotNull(witness))
    if (!sink(proposed, null)) {
      throw SkillBillRuntimeException(
        WorkflowFailureCode.INVALID_GOAL_QUALITY_EXECUTION,
        "Required goal-quality phase progress was not accepted.",
      )
    }
    acceptedQaSourceWitnesses.clear()
    acceptedQaSourceWitnesses.addAll(proposed.acceptedQaSourceWitnesses)
    qualityProgressRevision = proposed.revision
    return true
  }

  override fun loadPhaseLedger(workflowId: String): List<FeatureTaskRuntimePhaseLedgerEntry> = phaseLedger.toList()

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
  private val waveHistory = mutableMapOf<String, FeatureTaskRuntimePlanFanOutHistory>()
  private val feasibilityStates = mutableMapOf<String, FeatureTaskRuntimePlanBundleFeasibilityState>()

  override fun loadPlanBundleFeasibility(workflowId: String): FeatureTaskRuntimePlanBundleFeasibilityState? =
    feasibilityStates[workflowId]?.detached()

  override fun writePlanBundleFeasibility(
    request: PlanBundleFeasibilityStateWrite,
  ): PlanBundleFeasibilityStateWriteResult {
    val phase = phaseRecords[request.phaseId]
    val attempt =
      if (phase?.status == WorkflowStepStatus.RUNNING) phase.attemptCount else (phase?.attemptCount ?: 0) + 1
    if (attempt != request.attempt) return PlanBundleFeasibilityStateWriteResult.Refused("phase attempt changed")
    val result = planBundleFeasibilityWriteResult(feasibilityStates[request.workflowId]?.detached(), request)
    if (result is PlanBundleFeasibilityStateWriteResult.Applied) {
      feasibilityStates[request.workflowId] = result.state.detached()
      return PlanBundleFeasibilityStateWriteResult.Applied(result.state.detached())
    }
    return result
  }

  private fun FeatureTaskRuntimePlanBundleFeasibilityState.detached() =
    copy(
      identity = identity.detached(),
      acceptedLines = acceptedLines.map { it.copy(dependencyHashes = it.dependencyHashes.toList()) },
      conflicts = conflicts.toList(),
      changedSubtaskIds = changedSubtaskIds.toList(),
      requiredRefs = requiredRefs.toList(),
      carriedRefs = carriedRefs.toList(),
      activeLaunch =
        activeLaunch?.let { launch ->
          launch.copy(
            baselineIdentity = launch.baselineIdentity.detached(),
            intendedAfterIdentity = launch.intendedAfterIdentity?.detached(),
          )
        },
      settledLaunches =
        settledLaunches.map {
          it.copy(
            baselineIdentity = it.baselineIdentity.detached(),
            intendedAfterIdentity = it.intendedAfterIdentity?.detached(),
          )
        },
      approval = approval?.let { it.copy(criterionLines = it.criterionLines.toList()) },
    )

  private fun PlanBundleFeasibilityIdentity.detached() =
    copy(
      subtasks =
        subtasks.map { subtask ->
          subtask.copy(criterionRefs = subtask.criterionRefs.toList(), dependencies = subtask.dependencies.toList())
        },
    )

  override fun loadPlanBundle(workflowId: String): FeatureTaskRuntimePlanBundle? = planBundles[workflowId]

  override fun loadPlanFanOutHistory(workflowId: String): FeatureTaskRuntimePlanFanOutHistory? = waveHistory[workflowId]

  override fun recordPlanFanOutWave(
    workflowId: String,
    stepId: String,
    attempt: Int,
    capacity: Int,
    unitIds: List<Int>,
  ): Boolean {
    waveHistory[workflowId] =
      (waveHistory[workflowId] ?: FeatureTaskRuntimePlanFanOutHistory(emptyList()))
        .record(attempt, capacity, unitIds)
    return true
  }

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

  override fun replacePlanBundleWithUnits(
    workflowId: String,
    bundle: FeatureTaskRuntimePlanBundle,
    unitRecords: Map<Int, FeatureTaskRuntimePhaseRecord>,
  ): Boolean {
    waveHistory[workflowId] = FeatureTaskRuntimePlanFanOutHistory(emptyList())
    return recordPlanBundleWithUnits(workflowId, bundle, unitRecords)
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

public fun ProducerOutputEvidence.detached(): ProducerOutputEvidence = copy(payload = payload?.copyOf())

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

public fun FeatureTaskRuntimePhaseRecord.detached(): FeatureTaskRuntimePhaseRecord =
  copy(
    fileManifestBefore = fileManifestBefore.toList(),
    fileManifestAfter = fileManifestAfter.toList(),
    fileManifestIntroduced = fileManifestIntroduced.toList(),
  )

public fun List<FeatureTaskRuntimeFindingVerificationDisposition>.detachedDispositions():
  List<FeatureTaskRuntimeFindingVerificationDisposition> =
  map { disposition -> disposition.copy(selectedBoundaryHeadings = disposition.selectedBoundaryHeadings.toList()) }

public fun Map<String, List<FeatureTaskRuntimeVerificationBoundaryHeadingProvenance>>.detachedBoundary():
  Map<String, List<FeatureTaskRuntimeVerificationBoundaryHeadingProvenance>> =
  mapValues { (_, headings) -> headings.toList() }

public fun FeatureTaskRuntimeValidationGateProgress.detached(): FeatureTaskRuntimeValidationGateProgress =
  copy(
    gateRuns = gateRuns.map { run -> run.copy(executedChecks = run.executedChecks.toList()) },
    remainingFindings = remainingFindings.map { it.toMap() },
    completeFindings = completeFindings.map { it.toMap() },
    lastAgentUnfixedCriteria = lastAgentUnfixedCriteria.toList(),
  )

private fun recoverableStart(
  existing: FeatureTaskRuntimePhaseRecord?,
  resumablePausedGate: Boolean,
  sameAttempt: Boolean,
): Boolean {
  if (existing == null || !sameAttempt) return false
  return existing.status == WorkflowStepStatus.RUNNING || resumablePausedGate
}

private fun retainedPausedGate(
  phaseId: String,
  attempt: Int,
  gates: List<Triple<String?, Map<String, FeatureTaskRuntimeValidationGateProgress>, ValidationGateCommandFamily>>,
): Boolean =
  gates.any { (key, progress, family) ->
    val pausedKey = key != null && key.endsWith(":$phaseId:$attempt:${family.name.lowercase()}")
    pausedKey && progress[key]?.terminalOutcome == "paused"
  }

private fun applyPhaseRecord(
  phaseRecords: MutableMap<String, FeatureTaskRuntimePhaseRecord>,
  request: FeatureTaskRuntimePhaseStateRequest,
  clock: Clock,
) {
  phaseRecords[request.phaseId] =
    featureTaskRuntimePhaseRecordFor(request, phaseRecords[request.phaseId], clock.instant()).detached()
}

private fun phaseLedgerEntry(
  clock: Clock,
  sequenceNumber: Int,
  action: FeatureTaskRuntimePhaseLedgerAction,
  request: FeatureTaskRuntimePhaseStateRequest,
) = FeatureTaskRuntimePhaseLedgerEntry(
  action = action,
  sequenceNumber = sequenceNumber,
  timestamp = clock.instant(),
  phaseId = request.phaseId,
  attemptCount = request.attemptCount,
  resolvedAgentId = request.resolvedAgentId,
)

private fun recoverPhaseStart(
  request: FeatureTaskRuntimePhaseStateRequest,
  existing: FeatureTaskRuntimePhaseRecord,
  ledger: List<FeatureTaskRuntimePhaseLedgerEntry>,
  workflowId: String,
): FeatureTaskRuntimePhaseRecord? {
  val expected = featureTaskRuntimePhaseRecordFor(request, existing, existing.startedAt)
  val providerLimitPause = providerPausedLedger(request, ledger)
  val sameIdentity =
    request.workflowId == workflowId && request.phaseId == existing.phaseId &&
      request.status == STATUS_RUNNING && !request.finished
  val unfinished =
    existing.finishedAt == null && existing.resolvedAgentId == request.resolvedAgentId &&
      unfinishedPhaseStart(request, existing, ledger)
  val resumedExisting =
    if (providerLimitPause) {
      existing.copy(
        status = expected.status,
        blockedReason = expected.blockedReason,
        failureDisposition = expected.failureDisposition,
      )
    } else {
      existing
    }
  val sameRecord =
    expected.copy(
      launchedModel = resumedExisting.launchedModel,
      launchedEffort = resumedExisting.launchedEffort,
    ) ==
      resumedExisting.copy(
        fileManifestBefore = resumedExisting.fileManifestBefore.ifEmpty { expected.fileManifestBefore },
        fileManifestAfter = resumedExisting.fileManifestAfter.ifEmpty { expected.fileManifestAfter },
        fileManifestIntroduced = resumedExisting.fileManifestIntroduced.ifEmpty { expected.fileManifestIntroduced },
      )
  if (!sameIdentity || !unfinished) return null
  if (!sameRecord || !compatibleLaunchMetadata(request, existing, expected)) return null
  return expected.copy(
    launchedModel = if (request.launchOutcomeKnown) expected.launchedModel else existing.launchedModel,
    launchedEffort = if (request.launchOutcomeKnown) expected.launchedEffort else existing.launchedEffort,
  )
}

private fun providerPausedLedger(
  request: FeatureTaskRuntimePhaseStateRequest,
  ledger: List<FeatureTaskRuntimePhaseLedgerEntry>,
): Boolean =
  ledger.any {
    val sameAttempt = it.phaseId == request.phaseId && it.attemptCount == request.attemptCount
    val paused = it.action == FeatureTaskRuntimePhaseLedgerAction.PAUSED
    sameAttempt && paused &&
      FeatureTaskRuntimeProviderLimitPause.isProviderLimitPauseReason(it.blockedReason.orEmpty())
  }

private fun compatibleLaunchMetadata(
  request: FeatureTaskRuntimePhaseStateRequest,
  existing: FeatureTaskRuntimePhaseRecord,
  expected: FeatureTaskRuntimePhaseRecord,
): Boolean {
  val model = existing.launchedModel == null || existing.launchedModel == expected.launchedModel
  val effort = existing.launchedEffort == null || existing.launchedEffort == expected.launchedEffort
  return !request.launchOutcomeKnown || (model && effort)
}

private fun unfinishedPhaseStart(
  request: FeatureTaskRuntimePhaseStateRequest,
  existing: FeatureTaskRuntimePhaseRecord,
  ledger: List<FeatureTaskRuntimePhaseLedgerEntry>,
): Boolean {
  val starts =
    ledger.filter {
      it.phaseId == request.phaseId && it.attemptCount == request.attemptCount &&
        it.action == FeatureTaskRuntimePhaseLedgerAction.START
    }
  val start = starts.lastOrNull() ?: return false
  val sameAgent =
    starts.all { it.resolvedAgentId == existing.resolvedAgentId } &&
      start.resolvedAgentId == request.resolvedAgentId
  val hasTerminal =
    ledger.any { entry ->
      val sameAttempt = entry.phaseId == request.phaseId && entry.attemptCount == request.attemptCount
      val resumableProviderPause =
        entry.action == FeatureTaskRuntimePhaseLedgerAction.PAUSED &&
          FeatureTaskRuntimeProviderLimitPause.isProviderLimitPauseReason(entry.blockedReason.orEmpty())
      val terminal =
        entry.action in
          setOf(
            FeatureTaskRuntimePhaseLedgerAction.BLOCKED,
            FeatureTaskRuntimePhaseLedgerAction.PAUSED,
            FeatureTaskRuntimePhaseLedgerAction.COMPLETE,
          )
      sameAttempt && entry.sequenceNumber > start.sequenceNumber && terminal && !resumableProviderPause
    }
  return sameAgent && !hasTerminal
}

private fun sha256(value: String): String =
  MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray(Charsets.UTF_8))
    .joinToString("") { "%02x".format(it) }

private fun nextLedgerSequence(
  phaseLedger: List<FeatureTaskRuntimePhaseLedgerEntry>,
  initialGoalQualityProgress: GoalQualityPhaseProgress?,
): Int =
  maxOf(
    phaseLedger.maxOfOrNull { it.sequenceNumber } ?: 0,
    initialGoalQualityProgress?.ledgerSequenceHighWaterMark ?: 0,
  ) + 1
