package skillbill.engine.featuretask.runloop.state

import skillbill.engine.featuretask.review.core.FeatureTaskRuntimeOutputVerification
import skillbill.engine.featuretask.review.core.FeatureTaskRuntimeStepVerdictRule
import skillbill.engine.featuretask.runloop.core.ReconstructFixLoopBudgetBasesArgs
import skillbill.engine.featuretask.runloop.observability.paused
import skillbill.engine.featuretask.runner.BRANCH_SETUP_AGENT_ID
import skillbill.engine.featuretask.runner.invalidateLegacyPlanWithoutPreplan
import skillbill.engine.goalrunner.status.completed
import skillbill.error.shellcontent.InvalidFeatureTaskRuntimePhaseOutputSchemaError
import skillbill.ports.taskruntime.FeatureTaskRuntimePhaseOutputValidator
import skillbill.workflow.model.WorkflowStepStatus
import skillbill.workflow.model.validation.FeatureTaskRuntimeVerdict
import skillbill.workflow.model.workflowStepStatus
import skillbill.workflow.taskruntime.artifact.toWorkflowArtifactMap
import skillbill.workflow.taskruntime.model.core.FeatureTaskRuntimeWorkflowArtifactMap
import skillbill.workflow.taskruntime.model.handoff.task.FeatureTaskRuntimePhaseOutput
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimePhaseLedgerAction
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimePhaseLedgerEntry
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimePhaseRecord
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimeTransitionDeclaration
import skillbill.workflow.taskruntime.model.phase.requireAcceptedOutput
import skillbill.workflow.taskruntime.model.review.FeatureTaskRuntimeReviewFinding
import skillbill.workflow.taskruntime.phase.task.FeatureTaskRuntimePhaseWorkflowDefinition

class FeatureTaskRuntimeRunState(
  initialRecords: Map<String, FeatureTaskRuntimePhaseRecord>,
  val transitions: FeatureTaskRuntimeTransitionDeclaration,
  durableInitialLedger: List<FeatureTaskRuntimePhaseLedgerEntry> = emptyList(),
  val outputValidator: FeatureTaskRuntimePhaseOutputValidator,
  initialReviewGeneration: Int = 0,
  private val stepVerdictRule: (String) -> FeatureTaskRuntimeStepVerdictRule? = { null },
) {
  private val durableInitialRecords: Map<String, FeatureTaskRuntimePhaseRecord> = initialRecords

  private val statelessAuditInputs: FeatureTaskRuntimeStatelessAuditInputs =
    FeatureTaskRuntimeRunStateReconstruction.normalizeForStatelessAudit(
      durableInitialRecords,
      durableInitialLedger,
    )

  val initialRecords: Map<String, FeatureTaskRuntimePhaseRecord> = statelessAuditInputs.records

  private val normalizedInitialLedger: List<FeatureTaskRuntimePhaseLedgerEntry> = statelessAuditInputs.ledger

  internal var reviewGeneration: Int = initialReviewGeneration
    private set

  private val hasDurableReviewInvalidationTombstone: Boolean =
    durableInitialRecords[
      FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_REVIEW,
    ]?.resolvedAgentId == REVIEW_INVALIDATION_AGENT_ID
  private val inFlightReentries: MutableMap<String, InFlightReentry> =
    FeatureTaskRuntimeRunStateReconstruction.reconstructInFlightReentries(
      transitions,
      durableInitialLedger,
      durableInitialRecords,
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
      .also(::invalidateLegacyPlanWithoutPreplan)
      .also { completed ->
        val validationState =
          ValidationSettlementState(
            completed,
            this.initialRecords,
            transitions,
            gateInvalidatedPhaseIds,
          )
        invalidateIncompleteValidationSettlement(
          validationState,
          ValidationSettlementValidation(::validatedRecordToOutput, ::durableVerdictFor),
        )
        completed.retainAll(validationState.completed)
        gateInvalidatedPhaseIds.addAll(validationState.gateInvalidatedPhases)
      }
      .also {
        FeatureTaskRuntimeRunStateReconstruction.invalidateLegacyRemovedAuditCompletion(
          this.initialRecords,
          it,
          gateInvalidatedPhaseIds,
        )
      }
      .also {
        FeatureTaskRuntimeRunStateReconstruction.invalidateDownstreamOfIncompleteAudit(
          transitions,
          it,
          gateInvalidatedPhaseIds,
        )
      }
      .also { FeatureTaskRuntimeRunStateReconstruction.invalidateIncompleteReentrySpans(inFlightReentries.values, it) }
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
        it.phaseId == FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_PLAN &&
          it.phaseId !in completedPhases
      }
      .filterNot { it.phaseId in gateInvalidatedPhaseIds }
      .toCollection(outputBuffer)
  }

  fun validatedRecordToOutput(record: FeatureTaskRuntimePhaseRecord): FeatureTaskRuntimePhaseOutput? {
    if (FeatureTaskRuntimeRunStateReconstruction.hasLegacyRemovedAuditVerdict(record)) return null
    return record.outputArtifact?.let { artifact ->
      val accepted =
        try {
          outputValidator.validatePhaseOutput(artifact, record.phaseId).requireAcceptedOutput(record.phaseId)
        } catch (error: InvalidFeatureTaskRuntimePhaseOutputSchemaError) {
          if (record.status.workflowStepStatus() == WorkflowStepStatus.COMPLETED) throw error
          return@let null
        }
      FeatureTaskRuntimePhaseOutput(
        phaseId = record.phaseId,
        iteration = record.attemptCount,
        payload = accepted.normalizedOutput.canonicalJson,
        normalizedOutput = accepted.normalizedOutput,
        repairEvidence = record.repairEvidence ?: accepted.repairEvidence,
      )
    }
  }

  private val priorRecords: MutableSet<String> = this.initialRecords.keys.toMutableSet()
  private val phasesLaunchedThisProcess: MutableSet<String> = mutableSetOf()
  private val initialReviewRecord =
    this.initialRecords[FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_REVIEW]
      ?.takeIf { FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_REVIEW !in gateInvalidatedPhaseIds }
  internal var currentReviewPassNumber: Int? =
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
      }
      .mapValues { (_, record) -> record.blockedReason.orEmpty() }
      .toMutableMap()

  private val branchSetupBlockedPhases: MutableSet<String> =
    this.initialRecords
      .filterValues {
        it.status.workflowStepStatus() == WorkflowStepStatus.BLOCKED && it.resolvedAgentId == BRANCH_SETUP_AGENT_ID
      }
      .keys
      .toMutableSet()

  private val edgeIterationByLoop: MutableMap<String, Int> =
    (
      this.initialRecords.values
        .mapNotNull { record -> record.loopId?.let { loopId -> record.edgeIteration?.let { loopId to it } } } +
        normalizedInitialLedger.mapNotNull { entry ->
          entry.takeIf { it.action == FeatureTaskRuntimePhaseLedgerAction.LOOP_EDGE }
            ?.loopId?.let { loopId -> entry.edgeIteration?.let { loopId to it } }
        }
    )
      .groupBy({ it.first }, { it.second })
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
      ),
    )

  init {
    if (hasDurableReviewInvalidationTombstone) resetInvalidatedReviewGeneration()
  }

  fun outputs(requiredPhaseIds: Collection<String> = emptyList()): List<FeatureTaskRuntimePhaseOutput> {
    val inMemory = outputBuffer.toList()
    if (requiredPhaseIds.isEmpty()) return inMemory
    val inMemoryPhaseIds = inMemory.mapTo(mutableSetOf(), FeatureTaskRuntimePhaseOutput::phaseId)
    val durableOutputs =
      requiredPhaseIds.asSequence()
        .filterNot(inMemoryPhaseIds::contains)
        .mapNotNull { phaseId ->
          initialRecords[phaseId]
            ?.takeIf { it.status.workflowStepStatus() == WorkflowStepStatus.COMPLETED }
            ?.let(::validatedRecordToOutput)
        }
        .toList()
    return inMemory + durableOutputs
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

  fun phasesRequiringDurableGateInvalidation(): Set<String> = gateInvalidatedPhaseIds.toSet()

  fun resetInvalidatedReviewGeneration() {
    val loopId = FeatureTaskRuntimePhaseWorkflowDefinition.REVIEW_FIX_LOOP_ID
    val reviewPhaseId = FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_REVIEW
    val fixPhaseId = FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_IMPLEMENT_FIX
    inFlightReentries.remove(loopId)
    edgeIterationByLoop.remove(loopId)
    liveClaimedLoops.remove(loopId)
    fixLoopBudgetBaseByPhase.remove(fixPhaseId)
    fixLoopBudgetBaseByPhase.remove(reviewPhaseId)
    persistedAttemptCounts.remove(fixPhaseId)
    persistedAttemptCounts.remove(reviewPhaseId)
    priorRecords.remove(reviewPhaseId)
    blockedRecords.remove(reviewPhaseId)
    currentReviewPassNumber = null
    completedReviewPassNumber = null
  }

  fun recordFor(phaseId: String): FeatureTaskRuntimePhaseRecord? = initialRecords[phaseId]

  fun reopenForReentry(phaseId: String) {
    completedPhases.remove(phaseId)
    fixLoopBudgetBaseByPhase[phaseId] = maxOf(nextIteration(phaseId) - 1, 0)
  }

  fun explicitResumeStart(requestedPhaseId: String): ExplicitResumeStart {
    val requestedStart = ExplicitResumeStart(requestedPhaseId, reopen = true)
    val auditPhaseId = FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_AUDIT
    if (requestedPhaseId != auditPhaseId || !isComplete(auditPhaseId)) return requestedStart
    val auditIndex = transitions.forwardPhaseIds.indexOf(auditPhaseId)
    if (auditIndex < 0) return requestedStart
    val laterPhaseIds = transitions.forwardPhaseIds.drop(auditIndex + 1)
    val furthestLater =
      laterPhaseIds.lastOrNull { phaseId ->
        hasPriorRecord(phaseId) || isComplete(phaseId)
      }
    val resumePhaseId = furthestLater ?: laterPhaseIds.firstOrNull() ?: return requestedStart
    return ExplicitResumeStart(resumePhaseId, reopen = !isComplete(resumePhaseId))
  }

  fun reopenFromExplicitResume(phaseId: String) {
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

  fun invalidateProducerOutput(phaseId: String) {
    completedPhases.remove(phaseId)
    outputBuffer.removeAll { it.phaseId == phaseId }
    fixLoopBudgetBaseByPhase[phaseId] = maxOf(nextIteration(phaseId) - 1, 0)
  }

  fun isComplete(phaseId: String): Boolean = phaseId in completedPhases

  fun recordCompleted(output: FeatureTaskRuntimePhaseOutput) {
    outputBuffer += output
    completedPhases += output.phaseId
    priorRecords += output.phaseId
    if (output.phaseId == FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_REVIEW) {
      completedReviewPassNumber = currentReviewPassNumber
    }
  }

  fun completedPhaseIds(): List<String> =
    FeatureTaskRuntimePhaseWorkflowDefinition.definition.stepIds.filter { it in completedPhases }

  fun fixLoopIterationFor(
    phaseId: String,
    absoluteIteration: Int,
  ): Int = absoluteIteration - (fixLoopBudgetBaseByPhase[phaseId] ?: 0)

  fun restartAttemptBudget(phaseId: String) {
    fixLoopBudgetBaseByPhase[phaseId] = maxOf(nextIteration(phaseId) - 1, 0)
  }

  internal fun trailingNonOutputAttempts(
    phaseId: String,
    isProcessFailure: (String) -> Boolean,
  ): List<FeatureTaskRuntimeNonOutputAttempt> {
    val base = fixLoopBudgetBaseByPhase[phaseId] ?: 0
    return normalizedInitialLedger
      .filter { entry ->
        entry.phaseId == phaseId &&
          entry.attemptCount > base &&
          entry.action in NON_OUTPUT_LEDGER_ACTIONS
      }
      .sortedBy(FeatureTaskRuntimePhaseLedgerEntry::sequenceNumber)
      .takeLastWhile { entry ->
        entry.action == FeatureTaskRuntimePhaseLedgerAction.PAUSED ||
          isProcessFailure(entry.blockedReason.orEmpty())
      }
      .map { entry ->
        FeatureTaskRuntimeNonOutputAttempt(
          paused = entry.action == FeatureTaskRuntimePhaseLedgerAction.PAUSED,
          reason = entry.blockedReason.orEmpty(),
        )
      }
  }

  fun legacyReviewPreparationRetryConsumedBudget(
    phaseId: String,
    currentReason: String,
  ): Boolean {
    if (phaseId != FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_REVIEW ||
      !currentReason.startsWith("Phase 'review' exhausted the bounded fix loop")
    ) {
      return false
    }
    val recentBlocks = recentBlockedReasons(normalizedInitialLedger, phaseId)
    return recentBlocks.firstOrNull() == currentReason &&
      recentBlocks.getOrNull(1)
        ?.startsWith(
          "Goal-subtask review state or durable raw evidence is malformed: $LEGACY_SQLITE_BUSY_REASON_MARKER",
        ) == true
  }

  fun legacyLaunchSeamRejectionConsumedBudget(
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
      recentBlocks.getOrNull(1)
        ?.contains("rejected an upstream bounded planning projection at the launch seam") == true
  }

  fun hasPriorRecord(phaseId: String): Boolean = phaseId in priorRecords

  fun resumedFromPriorProcess(phaseId: String): Boolean =
    phaseId in initialRecords && phaseId !in phasesLaunchedThisProcess

  fun recordPhaseLaunched(phaseId: String) {
    phasesLaunchedThisProcess += phaseId
  }

  fun persistedBlockedReason(phaseId: String): String? = blockedRecords[phaseId]

  internal fun clearPersistedBlock(phaseId: String) {
    blockedRecords.remove(phaseId)
  }

  fun hasBranchSetupBlock(phaseId: String): Boolean = phaseId in branchSetupBlockedPhases

  fun clearBranchSetupBlock(phaseId: String) {
    branchSetupBlockedPhases.remove(phaseId)
    persistedAttemptCounts.remove(phaseId)
  }

  fun edgeIterationCount(loopId: String): Int = edgeIterationByLoop[loopId] ?: 0

  fun recordEdgeIteration(
    loopId: String,
    edgeIteration: Int,
  ) {
    edgeIterationByLoop[loopId] = edgeIteration
    liveClaimedLoops += loopId
  }

  fun isLoopLiveClaimed(loopId: String): Boolean = loopId in liveClaimedLoops

  fun discardStaleReentry(loopId: String) {
    inFlightReentries.remove(loopId)
    edgeIterationByLoop.remove(loopId)
    liveClaimedLoops.remove(loopId)
  }

  internal val latestInFlightReentry: Pair<String, InFlightReentry>?
    get() = inFlightReentries.maxByOrNull { (_, reentry) -> reentry.edgeSequenceNumber }?.toPair()

  fun outputFor(phaseId: String): FeatureTaskRuntimePhaseOutput? =
    outputBuffer.filter { it.phaseId == phaseId }.maxByOrNull { it.iteration }

  fun nextIteration(phaseId: String): Int {
    val latestOutputIteration = outputBuffer.filter { it.phaseId == phaseId }.maxOfOrNull { it.iteration } ?: 0
    val persistedAttempts = persistedAttemptCounts[phaseId] ?: 0
    return maxOf(persistedAttempts, latestOutputIteration) + 1
  }

  internal fun parsedOutput(output: FeatureTaskRuntimePhaseOutput?): FeatureTaskRuntimeWorkflowArtifactMap? {
    val payload = output?.payload ?: return null
    return parsedOutputsByPayloadStorage.getOrPut(payload) {
      val envelope =
        output.normalizedOutput?.envelopePayload()
          ?: outputValidator.validatePhaseOutput(payload, sourceLabel = output.phaseId)
            .requireAcceptedOutput(output.phaseId)
            .normalizedOutput
            .envelopePayload()
      envelope.toWorkflowArtifactMap()
    }
  }

  fun advanceReviewGeneration(next: Int) {
    if (next > reviewGeneration) reviewGeneration = next
  }

  fun evidenceGeneration(generationScoped: Boolean): Int = if (generationScoped) reviewGeneration else 0

  fun reserveReviewPass(passNumber: Int?) {
    if (passNumber != null) currentReviewPassNumber = passNumber
  }

  private fun stepVerdictRuleFor(phaseId: String): FeatureTaskRuntimeStepVerdictRule? =
    stepVerdictRules.getOrPut(phaseId) { stepVerdictRule(phaseId) }

  fun verdictFor(phaseId: String): FeatureTaskRuntimeVerdict =
    FeatureTaskRuntimeOutputVerification.verdictFor(
      phaseId,
      parsedOutput(outputFor(phaseId)),
      stepVerdictRuleFor(phaseId),
    )

  val settledVerdictsByPhaseId: Map<String, FeatureTaskRuntimeVerdict>
    get() = completedPhases.associateWith(::verdictFor)

  fun spanBlockedByEntryGate(span: List<String>): Boolean {
    val settledVerdicts = settledVerdictsByPhaseId
    return span.any { phaseId -> transitions.entryGateViolation(phaseId, settledVerdicts) != null }
  }

  fun unresolvedReviewFindings(phaseId: String): List<FeatureTaskRuntimeReviewFinding> =
    FeatureTaskRuntimeOutputVerification.unresolvedReviewFindings(parsedOutput(outputFor(phaseId)))

  fun durableVerdictFor(phaseId: String): FeatureTaskRuntimeVerdict {
    val record = initialRecords[phaseId] ?: return verdictFor(phaseId)
    val output = validatedRecordToOutput(record) ?: return verdictFor(phaseId)
    return FeatureTaskRuntimeOutputVerification.verdictFor(phaseId, parsedOutput(output), stepVerdictRuleFor(phaseId))
  }
}

private fun recentBlockedReasons(
  ledger: List<FeatureTaskRuntimePhaseLedgerEntry>,
  phaseId: String,
): List<String?> =
  ledger
    .filter { entry -> entry.phaseId == phaseId && entry.action == FeatureTaskRuntimePhaseLedgerAction.BLOCKED }
    .sortedByDescending(FeatureTaskRuntimePhaseLedgerEntry::sequenceNumber)
    .take(2)
    .map(FeatureTaskRuntimePhaseLedgerEntry::blockedReason)

data class ExplicitResumeStart(val phaseId: String, val reopen: Boolean)

internal data class FeatureTaskRuntimeNonOutputAttempt(val paused: Boolean, val reason: String)

val NON_OUTPUT_LEDGER_ACTIONS =
  setOf(
    FeatureTaskRuntimePhaseLedgerAction.BLOCKED,
    FeatureTaskRuntimePhaseLedgerAction.PAUSED,
  )

const val REVIEW_INVALIDATION_AGENT_ID: String = "audit-gate-migration"

internal const val LEGACY_SQLITE_BUSY_REASON_MARKER: String = "[SQLITE_BUSY]"

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
