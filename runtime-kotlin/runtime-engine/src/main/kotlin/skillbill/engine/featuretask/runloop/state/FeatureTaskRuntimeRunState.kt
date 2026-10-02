package skillbill.engine.featuretask.runloop.state

import skillbill.engine.featuretask.review.core.FeatureTaskRuntimeOutputVerification
import skillbill.engine.featuretask.review.core.FeatureTaskRuntimeStepVerdictRule
import skillbill.engine.featuretask.runloop.core.FeatureTaskRuntimeRunLoopSession
import skillbill.engine.featuretask.runloop.core.ReconstructFixLoopBudgetBasesArgs
import skillbill.engine.featuretask.runloop.observability.paused
import skillbill.engine.featuretask.runner.BRANCH_SETUP_AGENT_ID
import skillbill.engine.featuretask.slot.state.PhaseBlockResume
import skillbill.engine.featuretask.slot.state.PhaseResumeRules
import skillbill.engine.featuretask.validation.RuntimeGateRecordIntegrity
import skillbill.error.shellcontent.InvalidFeatureTaskRuntimePhaseOutputSchemaError
import skillbill.workflow.model.WorkflowStepStatus
import skillbill.workflow.model.validation.FeatureTaskRuntimeVerdict
import skillbill.workflow.model.workflowStepStatus
import skillbill.workflow.taskruntime.artifact.envelopeWireMap
import skillbill.workflow.taskruntime.artifact.toWorkflowArtifactMap
import skillbill.workflow.taskruntime.model.core.FeatureTaskRuntimeWorkflowArtifactMap
import skillbill.workflow.taskruntime.model.handoff.assembly.FeatureTaskRuntimePhaseOutput
import skillbill.workflow.taskruntime.model.handoff.task.NormalizedFeatureTaskRuntimePhaseOutput
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimePhaseLedgerAction
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimePhaseLedgerEntry
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimePhaseRecord
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimeTransitionDeclaration
import skillbill.workflow.taskruntime.model.review.FeatureTaskRuntimeReviewFinding
import skillbill.workflow.taskruntime.phase.task.FeatureTaskRuntimePhaseWorkflowDefinition

internal class FeatureTaskRuntimeRunState(
  initialRecords: Map<String, FeatureTaskRuntimePhaseRecord>,
  transitions: FeatureTaskRuntimeTransitionDeclaration,
  private val durableInitialLedger: List<FeatureTaskRuntimePhaseLedgerEntry> = emptyList(),
  initialReviewGeneration: Int = 0,
  private val stepVerdictRule: (String) -> FeatureTaskRuntimeStepVerdictRule? = { null },
  private val resumeRulesFn: (String) -> PhaseResumeRules,
) : FeatureTaskRuntimeProgressSnapshotAccess {
  private var transitionOwnership:
    Pair<FeatureTaskRuntimeRunLoopSession, FeatureTaskRuntimeRunTransitionOwner>? = null

  fun transitionOwnerFor(session: FeatureTaskRuntimeRunLoopSession): FeatureTaskRuntimeRunTransitionOwner {
    val existing = transitionOwnership
    if (existing != null) {
      check(existing.first === session) { "Run progress cannot be paired with a different session." }
      return existing.second
    }
    return FeatureTaskRuntimeRunTransitionOwner(this, session).also { owner ->
      transitionOwnership = session to owner
    }
  }

  override val resumeRules: (String) -> PhaseResumeRules
    get() = resumeRulesFn
  private constructor(source: FeatureTaskRuntimeRunState) : this(
    source.durableInitialRecords,
    source.transitions,
    source.durableInitialLedger,
    source.reviewGeneration,
    source.stepVerdictRule,
    source.resumeRulesFn,
  ) {
    inFlightReentries.replaceFrom(source.inFlightReentries)
    gateInvalidatedPhaseIds.replaceFrom(source.gateInvalidatedPhaseIds)
    phaseTokenUsage.replaceFrom(source.phaseTokenUsage)
    stepVerdictRules.replaceFrom(source.stepVerdictRules)
    parsedOutputsByPayloadStorage.replaceFrom(
      source.parsedOutputsByPayloadStorage.mapValues { (_, value) ->
        requireNotNull(detachedJsonValue(value)).toWorkflowArtifactMap()
      },
    )
    outputBuffer.clear()
    outputBuffer.addAll(source.outputBuffer.map(::detachedOutput))
    completedPhases.replaceFrom(source.completedPhases)
    priorRecords.replaceFrom(source.priorRecords)
    phasesLaunchedThisProcess.replaceFrom(source.phasesLaunchedThisProcess)
    persistedAttemptCounts.replaceFrom(source.persistedAttemptCounts)
    blockedRecords.replaceFrom(source.blockedRecords)
    branchSetupBlockedPhases.replaceFrom(source.branchSetupBlockedPhases)
    edgeIterationByLoop.replaceFrom(source.edgeIterationByLoop)
    liveClaimedLoops.replaceFrom(source.liveClaimedLoops)
    fixLoopBudgetBaseByPhase.replaceFrom(source.fixLoopBudgetBaseByPhase)
    currentReviewPassNumber = source.currentReviewPassNumber
    completedReviewPassNumber = source.completedReviewPassNumber
  }

  val progressSnapshot: FeatureTaskRuntimeProgressSnapshotAccess
    get() =
      detachedProgressObservations(FeatureTaskRuntimeRunState(this))

  private val transitionDeclaration: FeatureTaskRuntimeTransitionDeclaration =
    transitions.copy(
      forwardPhaseIds = transitions.forwardPhaseIds.toList(),
      backwardEdges = transitions.backwardEdges.toList(),
      loopOnlyPhaseIds = transitions.loopOnlyPhaseIds.toSet(),
      entryGates = transitions.entryGates.toList(),
      loopOnlySuccessors = transitions.loopOnlySuccessors.toMap(),
    )

  override val transitions: FeatureTaskRuntimeTransitionDeclaration
    get() =
      transitionDeclaration.copy(
        forwardPhaseIds = transitionDeclaration.forwardPhaseIds.toList(),
        backwardEdges = transitionDeclaration.backwardEdges.toList(),
        loopOnlyPhaseIds = transitionDeclaration.loopOnlyPhaseIds.toSet(),
        entryGates = transitionDeclaration.entryGates.toList(),
        loopOnlySuccessors = transitionDeclaration.loopOnlySuccessors.toMap(),
      )

  private val durableInitialRecords: Map<String, FeatureTaskRuntimePhaseRecord> =
    initialRecords.mapValues { (_, record) -> detachedRecord(record) }.toMap()

  private val statelessAuditInputs: FeatureTaskRuntimeStatelessAuditInputs =
    FeatureTaskRuntimeRunStateReconstruction.normalizeForStatelessAudit(
      durableInitialRecords,
      durableInitialLedger,
      resumeRules,
    )

  private val detachedInitialRecords: Map<String, FeatureTaskRuntimePhaseRecord> =
    statelessAuditInputs.records.mapValues { (_, record) -> detachedRecord(record) }.toMap()

  override val initialRecords: Map<String, FeatureTaskRuntimePhaseRecord>
    get() = detachedInitialRecords.mapValues { (_, record) -> detachedRecord(record) }.toMap()

  private val normalizedInitialLedger: List<FeatureTaskRuntimePhaseLedgerEntry> = statelessAuditInputs.ledger

  internal var reviewGeneration: Int = initialReviewGeneration
    private set

  private val durableReviewInvalidationTombstone: String? =
    durableInitialRecords.values
      .firstOrNull { record -> resumeRules(record.phaseId).tracksReviewPasses }
      ?.takeIf { record -> record.resolvedAgentId == REVIEW_INVALIDATION_AGENT_ID }
      ?.phaseId

  private val hasDurableReviewInvalidationTombstone: Boolean = durableReviewInvalidationTombstone != null

  private val inFlightReentries: MutableMap<String, InFlightReentry> =
    FeatureTaskRuntimeRunStateReconstruction
      .reconstructInFlightReentries(
        transitions,
        durableInitialLedger,
        durableInitialRecords,
        resumeRules,
      ).filterKeys { loopId ->
        !hasDurableReviewInvalidationTombstone ||
          loopId != FeatureTaskRuntimePhaseWorkflowDefinition.REVIEW_FIX_LOOP_ID
      }.toMutableMap()

  private val gateInvalidatedPhaseIds: MutableSet<String> = mutableSetOf()

  private val phaseTokenUsage: MutableMap<String, Pair<Int, Int>> = mutableMapOf()

  private val stepVerdictRules: MutableMap<String, FeatureTaskRuntimeStepVerdictRule?> = mutableMapOf()

  private val parsedOutputsByPayloadStorage: MutableMap<String, FeatureTaskRuntimeWorkflowArtifactMap> = mutableMapOf()

  private val outputBuffer: MutableList<FeatureTaskRuntimePhaseOutput> = mutableListOf()

  private val completedPhases: MutableSet<String> =
    this.initialRecords.values
      .filter { it.status.workflowStepStatus() == WorkflowStepStatus.COMPLETED }
      .map { it.phaseId }
      .toMutableSet()
      .also { completed ->
        completed.removeAll(completed.filter { resumeRules(it).dropsResumedCompletion(completed) }.toSet())
      }.also { completed ->
        val validationState =
          ValidationSettlementState(
            completed,
            this.initialRecords,
            transitions,
            gateInvalidatedPhaseIds,
          )
        invalidateUnsettledResumedCompletions(
          validationState,
          ValidationSettlementValidation(::validatedRecordToOutput, ::durableVerdictFor, resumeRules),
        )
        completed.retainAll(validationState.completed)
        gateInvalidatedPhaseIds.addAll(validationState.gateInvalidatedPhases)
      }.also {
        FeatureTaskRuntimeRunStateReconstruction.invalidateLaterStepsOfIncompleteSteps(
          transitions,
          it,
          gateInvalidatedPhaseIds,
          resumeRules,
        )
      }.also { FeatureTaskRuntimeRunStateReconstruction.invalidateIncompleteReentrySpans(inFlightReentries.values, it) }
      .also {
        FeatureTaskRuntimeRunStateReconstruction.invalidateUnsatisfiedGateSuccessors(
          transitions,
          it,
          gateInvalidatedPhaseIds,
          ::durableVerdictFor,
        )
      }

  init {
    this.initialRecords.values
      .filterNot { it.phaseId in gateInvalidatedPhaseIds }
      .mapNotNull(::validatedRecordToOutput)
      .filterNot {
        !resumeRules(it.phaseId).buffersIncompleteOutput &&
          it.phaseId !in completedPhases
      }.filterNot { it.phaseId in gateInvalidatedPhaseIds }
      .toCollection(outputBuffer)
  }

  override fun validatedRecordToOutput(record: FeatureTaskRuntimePhaseRecord): FeatureTaskRuntimePhaseOutput? {
    if (resumeRules(record.phaseId).withholdsDurableOutput(record)) return null
    return record.outputArtifact?.let { artifact ->
      val normalized =
        try {
          NormalizedFeatureTaskRuntimePhaseOutput.fromEnvelopeText(artifact, record.phaseId).also {
            RuntimeGateRecordIntegrity.requireIntact(it, record.phaseId)
          }
        } catch (error: InvalidFeatureTaskRuntimePhaseOutputSchemaError) {
          if (record.status.workflowStepStatus() == WorkflowStepStatus.COMPLETED) throw error
          return@let null
        }
      FeatureTaskRuntimePhaseOutput(
        phaseId = record.phaseId,
        iteration = record.attemptCount,
        payload = normalized.canonicalJson,
        normalizedOutput = normalized,
        repairEvidence = record.repairEvidence,
      )
    }
  }

  private val priorRecords: MutableSet<String> = this.initialRecords.keys.toMutableSet()
  private val phasesLaunchedThisProcess: MutableSet<String> = mutableSetOf()
  private val initialReviewRecord =
    this.initialRecords.values
      .firstOrNull { record -> resumeRules(record.phaseId).tracksReviewPasses }
      ?.takeIf { record -> record.phaseId !in gateInvalidatedPhaseIds }
  override var currentReviewPassNumber: Int? =
    initialReviewRecord?.reviewPassNumber
      ?: initialReviewRecord?.let { 1 }
    private set
  internal var completedReviewPassNumber: Int? =
    currentReviewPassNumber
      ?.takeIf { initialReviewRecord?.status?.workflowStepStatus() == WorkflowStepStatus.COMPLETED }
    private set

  private val persistedAttemptCounts: MutableMap<String, Int> =
    this.initialRecords.mapValues { (_, record) -> record.attemptCount }.toMutableMap()

  private val blockedRecords: MutableMap<String, String> =
    this.initialRecords
      .filterValues { record ->
        record.status.workflowStepStatus() == WorkflowStepStatus.BLOCKED &&
          record.resolvedAgentId != BRANCH_SETUP_AGENT_ID
      }.mapValues { (_, record) -> record.blockedReason.orEmpty() }
      .toMutableMap()

  private val branchSetupBlockedPhases: MutableSet<String> =
    this.initialRecords
      .filterValues {
        it.status.workflowStepStatus() == WorkflowStepStatus.BLOCKED && it.resolvedAgentId == BRANCH_SETUP_AGENT_ID
      }.keys
      .toMutableSet()

  private val edgeIterationByLoop: MutableMap<String, Int> =
    (
      this.initialRecords.values
        .mapNotNull { record -> record.loopId?.let { loopId -> record.edgeIteration?.let { loopId to it } } } +
        normalizedInitialLedger.mapNotNull { entry ->
          entry
            .takeIf { it.action == FeatureTaskRuntimePhaseLedgerAction.LOOP_EDGE }
            ?.loopId
            ?.let { loopId -> entry.edgeIteration?.let { loopId to it } }
        }
    ).groupBy({ it.first }, { it.second })
      .mapValues { (_, iterations) -> iterations.max() }
      .toMutableMap()
      .apply {
        if (hasDurableReviewInvalidationTombstone) remove(FeatureTaskRuntimePhaseWorkflowDefinition.REVIEW_FIX_LOOP_ID)
      }

  private val liveClaimedLoops: MutableSet<String> = inFlightReentries.keys.toMutableSet()

  private val fixLoopBudgetBaseByPhase: MutableMap<String, Int> =
    FeatureTaskRuntimeRunStateReconstruction.reconstructFixLoopBudgetBases(
      ReconstructFixLoopBudgetBasesArgs(
        transitions = transitions,
        edgeIterationByLoop = edgeIterationByLoop,
        initialRecords = durableInitialRecords,
        initialLedger = durableInitialLedger,
        completed = completedPhases,
        gateInvalidatedPhases = gateInvalidatedPhaseIds,
        nextIteration = ::nextIteration,
        resumeRules = resumeRules,
      ),
    )

  init {
    durableReviewInvalidationTombstone?.let(::resetInvalidatedReviewGeneration)
  }

  override fun phase(phaseId: String): PhaseProgressObservation =
    PhaseProgressObservation(
      completed = phaseId in completedPhases,
      hasPriorRecord = phaseId in priorRecords,
      resumedFromPriorProcess = phaseId in initialRecords && phaseId !in phasesLaunchedThisProcess,
      blockedReason = blockedRecords[phaseId],
      branchSetupBlocked = phaseId in branchSetupBlockedPhases,
      record = detachedInitialRecords[phaseId]?.let(::detachedRecord),
      output = outputBuffer.filter { it.phaseId == phaseId }.maxByOrNull { it.iteration }?.let(::detachedOutput),
      nextIteration = nextIteration(phaseId),
    )

  override fun outputs(requiredPhaseIds: Collection<String>): List<FeatureTaskRuntimePhaseOutput> {
    val inMemory = outputBuffer.map(::detachedOutput)
    if (requiredPhaseIds.isEmpty()) return inMemory
    val inMemoryPhaseIds = inMemory.mapTo(mutableSetOf(), FeatureTaskRuntimePhaseOutput::phaseId)
    val durableOutputs =
      requiredPhaseIds
        .asSequence()
        .filterNot(inMemoryPhaseIds::contains)
        .mapNotNull { phaseId ->
          initialRecords[phaseId]
            ?.takeIf { it.status.workflowStepStatus() == WorkflowStepStatus.COMPLETED }
            ?.let(::validatedRecordToOutput)
        }.toList()
    return inMemory + durableOutputs.map(::detachedOutput)
  }

  internal val phaseTokenView: Map<String, Pair<Int, Int>>
    get() = phaseTokenUsage.toMap()

  internal fun recordPhaseTokenUsage(
    phaseId: String,
    inputTokens: Int,
    outputTokens: Int,
  ) {
    phaseTokenUsage[phaseId] = inputTokens to outputTokens
  }

  override val phasesRequiringDurableGateInvalidation: Set<String>
    get() = gateInvalidatedPhaseIds.toSet()

  internal fun resetInvalidatedReviewGeneration(reviewStepId: String) {
    val loopId = FeatureTaskRuntimePhaseWorkflowDefinition.REVIEW_FIX_LOOP_ID
    val fixStepId = transitions.backwardEdges.firstOrNull { it.loopId == loopId }?.destinationPhaseId
    inFlightReentries.remove(loopId)
    edgeIterationByLoop.remove(loopId)
    liveClaimedLoops.remove(loopId)
    fixStepId?.let(fixLoopBudgetBaseByPhase::remove)
    fixLoopBudgetBaseByPhase.remove(reviewStepId)
    fixStepId?.let(persistedAttemptCounts::remove)
    persistedAttemptCounts.remove(reviewStepId)
    priorRecords.remove(reviewStepId)
    blockedRecords.remove(reviewStepId)
    currentReviewPassNumber = null
    completedReviewPassNumber = null
  }

  internal fun reopenForReentry(phaseId: String) {
    completedPhases.remove(phaseId)
    fixLoopBudgetBaseByPhase[phaseId] = maxOf(nextIteration(phaseId) - 1, 0)
  }

  override fun explicitResumeStart(requestedPhaseId: String): ExplicitResumeStart {
    val requestedStart = ExplicitResumeStart(requestedPhaseId, reopen = true)
    if (!resumeRules(requestedPhaseId).resumesPastCompletion || !(requestedPhaseId in completedPhases)) {
      return requestedStart
    }
    val auditIndex = transitions.forwardPhaseIds.indexOf(requestedPhaseId)
    if (auditIndex < 0) return requestedStart
    val laterPhaseIds = transitions.forwardPhaseIds.drop(auditIndex + 1)
    val furthestLater =
      laterPhaseIds.lastOrNull { phaseId ->
        (phaseId in priorRecords) || (phaseId in completedPhases)
      }
    val resumePhaseId = furthestLater ?: laterPhaseIds.firstOrNull() ?: return requestedStart
    return ExplicitResumeStart(resumePhaseId, reopen = !(resumePhaseId in completedPhases))
  }

  internal fun reopenFromExplicitResume(phaseId: String) {
    val start = transitions.forwardPhaseIds.indexOf(phaseId)
    require(start >= 0) { "Unknown explicit resume phase '$phaseId'." }
    transitions.forwardPhaseIds.drop(start).forEach { phase ->
      completedPhases.remove(phase)
      outputBuffer.removeAll { it.phaseId == phase }
      blockedRecords.remove(phase)
      branchSetupBlockedPhases.remove(phase)
      fixLoopBudgetBaseByPhase[phase] = maxOf(nextIteration(phase) - 1, 0)
    }
    inFlightReentries.clear()
    edgeIterationByLoop.clear()
    liveClaimedLoops.clear()
  }

  internal fun invalidateProducerOutput(phaseId: String) {
    completedPhases.remove(phaseId)
    outputBuffer.removeAll { it.phaseId == phaseId }
    fixLoopBudgetBaseByPhase[phaseId] = maxOf(nextIteration(phaseId) - 1, 0)
  }

  internal fun recordCompleted(output: FeatureTaskRuntimePhaseOutput) {
    outputBuffer += detachedOutput(output)
    completedPhases += output.phaseId
    priorRecords += output.phaseId
    if (resumeRules(output.phaseId).tracksReviewPasses) {
      completedReviewPassNumber = currentReviewPassNumber
    }
  }

  override val completedPhaseIds: List<String>
    get() =
      FeatureTaskRuntimePhaseWorkflowDefinition.definition.stepIds.filter { it in completedPhases }

  override fun fixLoopIterationFor(
    phaseId: String,
    absoluteIteration: Int,
  ): Int = absoluteIteration - (fixLoopBudgetBaseByPhase[phaseId] ?: 0)

  internal fun restartAttemptBudget(phaseId: String) {
    fixLoopBudgetBaseByPhase[phaseId] = maxOf(nextIteration(phaseId) - 1, 0)
  }

  override fun trailingNonOutputAttempts(
    phaseId: String,
    isProcessFailure: (String) -> Boolean,
  ): List<FeatureTaskRuntimeNonOutputAttempt> {
    val base = fixLoopBudgetBaseByPhase[phaseId] ?: 0
    return normalizedInitialLedger
      .filter { entry ->
        entry.phaseId == phaseId &&
          entry.attemptCount > base &&
          entry.action in NON_OUTPUT_LEDGER_ACTIONS
      }.sortedBy(FeatureTaskRuntimePhaseLedgerEntry::sequenceNumber)
      .takeLastWhile { entry ->
        entry.action == FeatureTaskRuntimePhaseLedgerAction.PAUSED ||
          isProcessFailure(entry.blockedReason.orEmpty())
      }.map { entry ->
        FeatureTaskRuntimeNonOutputAttempt(
          paused = entry.action == FeatureTaskRuntimePhaseLedgerAction.PAUSED,
          reason = entry.blockedReason.orEmpty(),
        )
      }
  }

  override fun persistedBlockResume(
    phaseId: String,
    reason: String,
  ): PhaseBlockResume =
    resumeRules(phaseId).persistedBlockResume(reason, recentBlockedReasons(normalizedInitialLedger, phaseId))

  override fun legacyLaunchSeamRejectionConsumedBudget(
    phaseId: String,
    currentReason: String,
  ): Boolean {
    if (!currentReason.startsWith("Phase '$phaseId' exhausted the bounded fix loop") ||
      phaseId !in FeatureTaskRuntimePhaseWorkflowDefinition.REGENERATION_PRODUCER_BY_CONSUMER
    ) {
      return false
    }
    val recentBlocks = recentBlockedReasons(normalizedInitialLedger, phaseId)
    return recentBlocks.firstOrNull() == currentReason &&
      recentBlocks
        .getOrNull(1)
        ?.contains("rejected an upstream bounded planning projection at the launch seam") == true
  }

  internal fun recordPhaseLaunched(phaseId: String) {
    phasesLaunchedThisProcess += phaseId
  }

  internal fun clearPersistedBlock(phaseId: String) {
    blockedRecords.remove(phaseId)
  }

  internal fun clearBranchSetupBlock(phaseId: String) {
    branchSetupBlockedPhases.remove(phaseId)
    persistedAttemptCounts.remove(phaseId)
  }

  override fun loop(loopId: String): LoopProgressObservation =
    LoopProgressObservation(edgeIterationByLoop[loopId] ?: 0, loopId in liveClaimedLoops)

  internal fun recordEdgeIteration(
    loopId: String,
    edgeIteration: Int,
  ) {
    edgeIterationByLoop[loopId] = edgeIteration
    liveClaimedLoops += loopId
  }

  internal fun discardStaleReentry(loopId: String) {
    inFlightReentries.remove(loopId)
    edgeIterationByLoop.remove(loopId)
    liveClaimedLoops.remove(loopId)
  }

  internal val latestInFlightReentry: Pair<String, InFlightReentry>?
    get() = inFlightReentries.maxByOrNull { (_, reentry) -> reentry.edgeSequenceNumber }?.toPair()

  private fun outputFor(phaseId: String): FeatureTaskRuntimePhaseOutput? =
    outputBuffer.filter { it.phaseId == phaseId }.maxByOrNull { it.iteration }?.let(::detachedOutput)

  private fun nextIteration(phaseId: String): Int {
    val latestOutputIteration = outputBuffer.filter { it.phaseId == phaseId }.maxOfOrNull { it.iteration } ?: 0
    val persistedAttempts = persistedAttemptCounts[phaseId] ?: 0
    return maxOf(persistedAttempts, latestOutputIteration) + 1
  }

  internal fun parsedOutput(output: FeatureTaskRuntimePhaseOutput?): FeatureTaskRuntimeWorkflowArtifactMap? {
    val payload = output?.payload ?: return null
    val parsed =
      parsedOutputsByPayloadStorage.getOrPut(payload) {
        val envelope =
          output.normalizedOutput?.envelopeWireMap()
            ?: NormalizedFeatureTaskRuntimePhaseOutput
              .fromEnvelopeText(payload, output.phaseId)
              .envelopeWireMap()
        requireNotNull(detachedJsonValue(envelope)).toWorkflowArtifactMap()
      }
    return requireNotNull(detachedJsonValue(parsed)).toWorkflowArtifactMap()
  }

  internal fun advanceReviewGeneration(next: Int) {
    if (next > reviewGeneration) reviewGeneration = next
  }

  override val reviewEvidenceGeneration: Int get() = reviewGeneration

  internal fun reserveReviewPass(passNumber: Int?) {
    if (passNumber != null) currentReviewPassNumber = passNumber
  }

  private fun stepVerdictRuleFor(phaseId: String): FeatureTaskRuntimeStepVerdictRule? =
    stepVerdictRules.getOrPut(phaseId) { stepVerdictRule(phaseId) }

  override fun verdictFor(phaseId: String): FeatureTaskRuntimeVerdict =
    FeatureTaskRuntimeOutputVerification.verdictFor(
      parsedOutput(outputFor(phaseId)),
      stepVerdictRuleFor(phaseId),
    )

  override val settledVerdictsByPhaseId: Map<String, FeatureTaskRuntimeVerdict>
    get() = completedPhases.associateWith(::verdictFor)

  override fun spanBlockedByEntryGate(span: List<String>): Boolean {
    val settledVerdicts = settledVerdictsByPhaseId
    return span.any { phaseId -> transitions.entryGateViolation(phaseId, settledVerdicts) != null }
  }

  override fun durableVerdictFor(phaseId: String): FeatureTaskRuntimeVerdict {
    val record = initialRecords[phaseId] ?: return verdictFor(phaseId)
    val output = validatedRecordToOutput(record) ?: return verdictFor(phaseId)
    return FeatureTaskRuntimeOutputVerification.verdictFor(parsedOutput(output), stepVerdictRuleFor(phaseId))
  }
}

internal fun FeatureTaskRuntimeRunState.unresolvedReviewFindings(
  phaseId: String,
): List<FeatureTaskRuntimeReviewFinding> =
  FeatureTaskRuntimeOutputVerification.unresolvedReviewFindings(parsedOutput(phase(phaseId).output))

private fun recentBlockedReasons(
  ledger: List<FeatureTaskRuntimePhaseLedgerEntry>,
  phaseId: String,
): List<String?> =
  ledger
    .filter { entry -> entry.phaseId == phaseId && entry.action == FeatureTaskRuntimePhaseLedgerAction.BLOCKED }
    .sortedByDescending(FeatureTaskRuntimePhaseLedgerEntry::sequenceNumber)
    .take(2)
    .map(FeatureTaskRuntimePhaseLedgerEntry::blockedReason)

data class ExplicitResumeStart(
  val phaseId: String,
  val reopen: Boolean,
)

internal data class FeatureTaskRuntimeNonOutputAttempt(
  val paused: Boolean,
  val reason: String,
)

val NON_OUTPUT_LEDGER_ACTIONS =
  setOf(
    FeatureTaskRuntimePhaseLedgerAction.BLOCKED,
    FeatureTaskRuntimePhaseLedgerAction.PAUSED,
  )

const val REVIEW_INVALIDATION_AGENT_ID: String = "audit-gate-migration"

internal data class InFlightReentry(
  val destinationPhaseId: String,
  val edgeIteration: Int,
  val drivingVerdict: FeatureTaskRuntimeVerdict,
  val span: List<String>,
  val completedAfterEdge: Set<String>,
  val edgeSequenceNumber: Int,
) {
  val resumePhaseId: String
    get() = span.firstOrNull { phaseId -> phaseId !in completedAfterEdge } ?: destinationPhaseId
}

private fun <K, V> MutableMap<K, V>.replaceFrom(source: Map<K, V>) {
  clear()
  putAll(source)
}

private fun <T> MutableSet<T>.replaceFrom(source: Set<T>) {
  clear()
  addAll(source)
}
