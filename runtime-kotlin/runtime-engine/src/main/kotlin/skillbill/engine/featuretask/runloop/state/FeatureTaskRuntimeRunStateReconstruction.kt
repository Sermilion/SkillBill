package skillbill.engine.featuretask.runloop.state

import skillbill.engine.featuretask.runloop.core.ReconstructFixLoopBudgetBasesArgs
import skillbill.engine.featuretask.slot.state.PhaseResumeRules
import skillbill.engine.featuretask.slot.state.isRetiredAuditGapLoop
import skillbill.error.featuretask.qa.invalidQaRepairStateSchema
import skillbill.workflow.model.WorkflowStepStatus
import skillbill.workflow.model.validation.FeatureTaskRuntimeVerdict
import skillbill.workflow.model.workflowStepStatus
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimeBackwardEdge
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimePhaseLedgerAction
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimePhaseLedgerEntry
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimePhaseRecord
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimeTransitionDeclaration
import skillbill.workflow.taskruntime.model.qa.QaRepairAttempt
import skillbill.workflow.taskruntime.model.qa.QaRepairAttemptOutcome
import skillbill.workflow.taskruntime.model.qa.QaRepairState

data class FeatureTaskRuntimeStatelessAuditInputs(
  val records: Map<String, FeatureTaskRuntimePhaseRecord>,
  val ledger: List<FeatureTaskRuntimePhaseLedgerEntry>,
)

object FeatureTaskRuntimeRunStateReconstruction {
  internal fun normalizeForStatelessAudit(
    rawRecords: Map<String, FeatureTaskRuntimePhaseRecord>,
    rawLedger: List<FeatureTaskRuntimePhaseLedgerEntry>,
    rules: (String) -> PhaseResumeRules,
  ): FeatureTaskRuntimeStatelessAuditInputs =
    FeatureTaskRuntimeStatelessAuditInputs(
      records = normalizeInitialRecordsForStatelessAudit(rawRecords, rules),
      ledger = normalizeLedgerForStatelessAudit(rawLedger, rawRecords, rules),
    )

  internal fun normalizeLedgerForStatelessAudit(
    ledger: List<FeatureTaskRuntimePhaseLedgerEntry>,
    rawRecords: Map<String, FeatureTaskRuntimePhaseRecord>,
    rules: (String) -> PhaseResumeRules,
  ): List<FeatureTaskRuntimePhaseLedgerEntry> = ledger.filter(statelessAuditLedgerEntryFilter(rawRecords, rules))

  internal fun statelessAuditLedgerEntryFilter(
    rawRecords: Map<String, FeatureTaskRuntimePhaseRecord>,
    rules: (String) -> PhaseResumeRules,
  ): (FeatureTaskRuntimePhaseLedgerEntry) -> Boolean {
    val normalizedRecords = normalizeInitialRecordsForStatelessAudit(rawRecords, rules)
    val droppedBlockedPhases = mutableMapOf<String, Boolean>()
    return { entry ->
      !isRetiredAuditGapLoop(entry.loopId) &&
        (
          entry.action != FeatureTaskRuntimePhaseLedgerAction.BLOCKED ||
            !droppedBlockedPhases.getOrPut(entry.phaseId) {
              rules(entry.phaseId).dropsBlockedLedgerEntry(rawRecords[entry.phaseId], normalizedRecords[entry.phaseId])
            }
        )
    }
  }

  internal fun reconstructInFlightReentries(
    transitions: FeatureTaskRuntimeTransitionDeclaration,
    initialLedger: List<FeatureTaskRuntimePhaseLedgerEntry>,
    initialRecords: Map<String, FeatureTaskRuntimePhaseRecord>,
    rules: (String) -> PhaseResumeRules,
    qaRepair: QaRepairEvidence = QaRepairEvidence.NONE,
  ): Map<String, InFlightReentry> =
    buildMap {
      val evidence =
        ReentryEvidence(
          ledger = normalizeLedgerForStatelessAudit(initialLedger, initialRecords, rules),
          records = initialRecords,
          rules = rules,
          qaRepair = qaRepair,
        )
      transitions.backwardEdges.forEach { edge ->
        reconstructEdgeReentry(transitions, edge, evidence)?.let { put(edge.loopId, it) }
      }
    }

  internal fun completedReturnSourcePhaseIds(
    transitions: FeatureTaskRuntimeTransitionDeclaration,
    inFlightReentries: Map<String, InFlightReentry>,
    evidence: ReentryEvidence,
  ): Set<String> =
    inFlightReentries.mapNotNull { (loopId, reentry) ->
      if (!reentry.completionDerived) return@mapNotNull null
      val edge =
        transitions.backwardEdges.singleOrNull { candidate ->
          candidate.loopId == loopId && candidate.destinationPhaseId == reentry.destinationPhaseId
        } ?: return@mapNotNull null
      val phaseId = edge.fromPhaseId
      if (
        evidence.rules(phaseId).completedStepReturnLoopId != loopId ||
        phaseId !in reentry.resumeCoveredPhaseIds ||
        evidence.records[phaseId]?.status?.workflowStepStatus() != WorkflowStepStatus.COMPLETED
      ) {
        return@mapNotNull null
      }
      evidence.completedQaRepairBoundary(phaseId)?.let { phaseId }
    }.toSet()

  private fun reconstructEdgeReentry(
    transitions: FeatureTaskRuntimeTransitionDeclaration,
    edge: FeatureTaskRuntimeBackwardEdge,
    evidence: ReentryEvidence,
  ): InFlightReentry? {
    val completedReturn = evidence.rules(edge.fromPhaseId).completedStepReturnLoopId == edge.loopId
    val latestEdge =
      evidence.ledger
        .filter { it.action == FeatureTaskRuntimePhaseLedgerAction.LOOP_EDGE && it.loopId == edge.loopId }
        .maxByOrNull { it.sequenceNumber }
    val repairBoundary =
      if (completedReturn) evidence.completedQaRepairBoundary(edge.fromPhaseId) else null
    val boundaryAfterLatestEdge =
      repairBoundary?.takeIf { boundary -> latestEdge == null || boundary.sequenceNumber > latestEdge.sequenceNumber }
    return if (boundaryAfterLatestEdge != null) {
      repairBoundaryReentry(transitions, edge, evidence, boundaryAfterLatestEdge)
    } else {
      latestEdge?.let { loopEdgeReentry(transitions, edge, evidence, it, completedReturn) }
    }
  }

  private fun repairBoundaryReentry(
    transitions: FeatureTaskRuntimeTransitionDeclaration,
    edge: FeatureTaskRuntimeBackwardEdge,
    evidence: ReentryEvidence,
    repairBoundary: CompletedQaRepairBoundary,
  ): InFlightReentry? {
    val span = reopenedSpan(transitions, edge).filterNot { it == edge.fromPhaseId }
    val completedAfterBoundary =
      completedReturnPhaseIdsAfter(evidence.ledger, evidence.records, span, repairBoundary.sequenceNumber)
    if (span.all(completedAfterBoundary::contains)) return null
    return InFlightReentry(
      destinationPhaseId = edge.destinationPhaseId,
      edgeIteration = repairBoundary.attemptNumber,
      drivingVerdict = edge.triggeringVerdict,
      span = span,
      completedAfterEdge = completedAfterBoundary,
      edgeSequenceNumber = repairBoundary.sequenceNumber,
      completionDerived = true,
      resumeCoveredPhaseIds = (span + edge.fromPhaseId).toSet(),
    )
  }

  private fun loopEdgeReentry(
    transitions: FeatureTaskRuntimeTransitionDeclaration,
    edge: FeatureTaskRuntimeBackwardEdge,
    evidence: ReentryEvidence,
    latestEdge: FeatureTaskRuntimePhaseLedgerEntry,
    completedReturn: Boolean,
  ): InFlightReentry? {
    val ledger = evidence.ledger
    val records = evidence.records
    val completedAfterEdge =
      if (completedReturn) {
        val returnSpan = reopenedSpan(transitions, edge).filterNot { it == edge.fromPhaseId }
        completedReturnPhaseIdsAfter(ledger, records, returnSpan, latestEdge.sequenceNumber).toMutableSet()
      } else {
        ledger.asSequence()
          .filter { it.sequenceNumber > latestEdge.sequenceNumber }
          .filter { it.action == FeatureTaskRuntimePhaseLedgerAction.COMPLETE }
          .map { it.phaseId }
          .filter { phaseId -> records[phaseId]?.status?.workflowStepStatus() == WorkflowStepStatus.COMPLETED }
          .toMutableSet()
      }
    records.values
      .filter { record ->
        record.status.workflowStepStatus() == WorkflowStepStatus.COMPLETED &&
          record.loopId == edge.loopId && record.edgeIteration == latestEdge.edgeIteration
      }
      .mapTo(completedAfterEdge) { it.phaseId }
    val span =
      reopenedSpan(transitions, edge).let { phases ->
        if (completedReturn) phases.filterNot { it == edge.fromPhaseId } else phases
      }
    if (span.none { phaseId -> phaseId !in completedAfterEdge }) return null
    return InFlightReentry(
      destinationPhaseId = edge.destinationPhaseId,
      edgeIteration = requireNotNull(latestEdge.edgeIteration),
      drivingVerdict = edge.triggeringVerdict,
      span = span,
      completedAfterEdge = completedAfterEdge,
      edgeSequenceNumber = latestEdge.sequenceNumber,
      completionDerived = completedReturn,
      resumeCoveredPhaseIds = if (completedReturn) (span + edge.fromPhaseId).toSet() else emptySet(),
    )
  }

  private fun completedReturnPhaseIdsAfter(
    ledger: List<FeatureTaskRuntimePhaseLedgerEntry>,
    records: Map<String, FeatureTaskRuntimePhaseRecord>,
    span: List<String>,
    sequenceNumber: Int,
  ): Set<String> {
    val completed =
      ledger.asSequence()
        .filter { it.sequenceNumber > sequenceNumber && it.action == FeatureTaskRuntimePhaseLedgerAction.COMPLETE }
        .filter { entry ->
          records[entry.phaseId]?.let { record ->
            record.status.workflowStepStatus() == WorkflowStepStatus.COMPLETED &&
              record.attemptCount == entry.attemptCount
          } == true
        }.map { it.phaseId }.toMutableSet()
    var boundary = sequenceNumber
    var ordered = true
    span.forEach { phaseId ->
      val completion = completedSequenceAfter(ledger, records, phaseId, boundary)
      if (completion == null || !ordered) {
        ordered = false
        completed.remove(phaseId)
      } else {
        boundary = completion
      }
    }
    return completed
  }

  private fun completedSequenceAfter(
    ledger: List<FeatureTaskRuntimePhaseLedgerEntry>,
    records: Map<String, FeatureTaskRuntimePhaseRecord>,
    phaseId: String,
    boundary: Int,
  ): Int? {
    val record =
      records[phaseId]?.takeIf { it.status.workflowStepStatus() == WorkflowStepStatus.COMPLETED } ?: return null
    return ledger.singleOrNull {
      it.phaseId == phaseId && it.action == FeatureTaskRuntimePhaseLedgerAction.COMPLETE &&
        it.attemptCount == record.attemptCount && it.sequenceNumber > boundary
    }?.sequenceNumber
  }

  internal fun reconstructFixLoopBudgetBases(args: ReconstructFixLoopBudgetBasesArgs): MutableMap<String, Int> {
    val transitions = args.transitions
    val edgeIterationByLoop = args.edgeIterationByLoop
    val initialRecords = normalizeInitialRecordsForStatelessAudit(args.initialRecords, args.resumeRules)
    val initialLedger = normalizeLedgerForStatelessAudit(args.initialLedger, args.initialRecords, args.resumeRules)
    val completed = args.completed
    val gateInvalidatedPhases = args.gateInvalidatedPhases
    val nextIteration = args.nextIteration
    val bases = mutableMapOf<String, Int>()
    transitions.backwardEdges.forEach { edge ->
      if ((edgeIterationByLoop[edge.loopId] ?: 0) <= 0) {
        return@forEach
      }
      reopenedSpan(transitions, edge).forEach { phaseId ->
        if (phaseId !in completed) {
          bases[phaseId] = maxOf(nextIteration(phaseId) - 1, 0)
        }
      }
    }
    seedBudgetBasesOutsideLiveSpans(bases, initialRecords, gateInvalidatedPhases, completed, nextIteration)
    seedOperatorRetryBudgetBases(bases, initialLedger, completed)
    return bases
  }

  internal fun invalidateIncompleteReentrySpans(
    inFlightReentries: Collection<InFlightReentry>,
    completedPhases: MutableSet<String>,
  ) {
    inFlightReentries.forEach { reentry ->
      reentry.span
        .filterNot(reentry.completedAfterEdge::contains)
        .forEach(completedPhases::remove)
    }
  }

  internal fun normalizeInitialRecordsForStatelessAudit(
    initialRecords: Map<String, FeatureTaskRuntimePhaseRecord>,
    rules: (String) -> PhaseResumeRules,
  ): Map<String, FeatureTaskRuntimePhaseRecord> =
    initialRecords.mapValues { (_, record) ->
      val stripped =
        if (isRetiredAuditGapLoop(record.loopId)) {
          record.copy(loopId = null, edgeIteration = null)
        } else {
          record
        }
      rules(record.phaseId).resumedRecord(record, stripped)
    }

  internal fun invalidateLaterStepsOfIncompleteSteps(
    transitions: FeatureTaskRuntimeTransitionDeclaration,
    completedPhases: MutableSet<String>,
    gateInvalidatedPhases: MutableSet<String>,
    rules: (String) -> PhaseResumeRules,
    protectedCompletedReturnSources: Set<String> = emptySet(),
  ) {
    transitions.forwardPhaseIds.forEachIndexed { index, stepId ->
      if (!rules(stepId).invalidatesLaterStepsWhileIncomplete || stepId in completedPhases) return@forEachIndexed
      transitions.forwardPhaseIds
        .drop(index + 1)
        .filter { it in completedPhases && it !in protectedCompletedReturnSources }
        .forEach { phaseId ->
          completedPhases.remove(phaseId)
          gateInvalidatedPhases += phaseId
        }
    }
  }

  fun invalidateUnsatisfiedGateSuccessors(
    transitions: FeatureTaskRuntimeTransitionDeclaration,
    completedPhases: MutableSet<String>,
    gateInvalidatedPhases: MutableSet<String>,
    durableVerdictFor: (String) -> FeatureTaskRuntimeVerdict,
    protectedCompletedReturnSources: Set<String> = emptySet(),
  ) {
    val durableVerdicts = completedPhases.associateWith(durableVerdictFor)
    transitions.entryGates.forEach { gate ->
      if (transitions.entryGateViolation(gate.phaseId, durableVerdicts) == null) {
        return@forEach
      }
      val gatedIndex = transitions.forwardPhaseIds.indexOf(gate.phaseId)
      if (gatedIndex < 0) {
        return@forEach
      }
      transitions.forwardPhaseIds
        .drop(gatedIndex)
        .filter { it in completedPhases && it !in protectedCompletedReturnSources }
        .forEach { phaseId ->
          completedPhases.remove(phaseId)
          gateInvalidatedPhases += phaseId
        }
    }
  }

  private fun seedOperatorRetryBudgetBases(
    bases: MutableMap<String, Int>,
    initialLedger: List<FeatureTaskRuntimePhaseLedgerEntry>,
    completed: Set<String>,
  ) {
    initialLedger
      .filter { it.action == FeatureTaskRuntimePhaseLedgerAction.RETRY }
      .filterNot { it.preserveBudget }
      .groupBy { it.phaseId }
      .forEach { (phaseId, retries) ->
        val latestRetry = retries.maxBy { it.sequenceNumber }
        val settledAfterRetry =
          initialLedger.any { entry ->
            entry.phaseId == phaseId &&
              entry.sequenceNumber > latestRetry.sequenceNumber &&
              entry.action in
              setOf(
                FeatureTaskRuntimePhaseLedgerAction.BLOCKED,
                FeatureTaskRuntimePhaseLedgerAction.COMPLETE,
              )
          }
        if (!settledAfterRetry && phaseId !in completed) {
          bases[phaseId] = latestRetry.attemptCount
        }
      }
  }

  private fun seedBudgetBasesOutsideLiveSpans(
    bases: MutableMap<String, Int>,
    initialRecords: Map<String, FeatureTaskRuntimePhaseRecord>,
    gateInvalidatedPhases: Set<String>,
    completed: Set<String>,
    nextIteration: (String) -> Int,
  ) {
    val staleLoopPhases =
      initialRecords.values
        .filter {
          it.status.workflowStepStatus() != WorkflowStepStatus.COMPLETED &&
            it.loopId != null
        }
        .map { it.phaseId }
    (staleLoopPhases + gateInvalidatedPhases).forEach { phaseId ->
      if (phaseId !in completed && phaseId !in bases) {
        bases[phaseId] = maxOf(nextIteration(phaseId) - 1, 0)
      }
    }
  }

  private fun reopenedSpan(
    transitions: FeatureTaskRuntimeTransitionDeclaration,
    edge: FeatureTaskRuntimeBackwardEdge,
  ): List<String> = transitions.spanBetween(edge.destinationPhaseId, edge.fromPhaseId)
}

internal data class QaRepairEvidence(
  val workflowId: String?,
  val state: QaRepairState?,
) {
  companion object {
    val NONE: QaRepairEvidence = QaRepairEvidence(workflowId = null, state = null)
  }
}

internal data class ReentryEvidence(
  val ledger: List<FeatureTaskRuntimePhaseLedgerEntry>,
  val records: Map<String, FeatureTaskRuntimePhaseRecord>,
  val rules: (String) -> PhaseResumeRules,
  val qaRepair: QaRepairEvidence,
)

internal data class CompletedQaRepairBoundary(
  val sequenceNumber: Int,
  val attemptNumber: Int,
)

internal fun ReentryEvidence.completedQaRepairBoundary(phaseId: String): CompletedQaRepairBoundary? {
  val record = records[phaseId]
  val completedRecord = record?.takeIf { it.status.workflowStepStatus() == WorkflowStepStatus.COMPLETED }
  val completedLedger =
    ledger.filter { it.action == FeatureTaskRuntimePhaseLedgerAction.COMPLETE && it.phaseId == phaseId }
  if (completedRecord == null) {
    requireConsistentIncompleteRepair(record, completedLedger)
    return null
  }
  qaRepair.completedRepairAttempt(phaseId, completedRecord)
  val completion =
    completedLedger.singleOrNull { it.attemptCount == completedRecord.attemptCount }
      ?: invalidQaRepairState("completed qa_fix has no unique matching COMPLETE ledger entry.")
  if (completion.resolvedAgentId != completedRecord.resolvedAgentId) {
    invalidQaRepairState("completed qa_fix agent identity differs between its phase record and ledger.")
  }
  return CompletedQaRepairBoundary(completion.sequenceNumber, completedRecord.attemptCount)
}

private fun requireConsistentIncompleteRepair(
  record: FeatureTaskRuntimePhaseRecord?,
  completedLedger: List<FeatureTaskRuntimePhaseLedgerEntry>,
) {
  if (record == null && completedLedger.isNotEmpty()) {
    invalidQaRepairState("qa_fix has a COMPLETE ledger entry but no phase record.")
  }
  if (record != null && completedLedger.any { it.attemptCount == record.attemptCount }) {
    invalidQaRepairState("qa_fix completion status differs between its phase record and ledger.")
  }
}

private fun QaRepairEvidence.completedRepairAttempt(
  phaseId: String,
  completedRecord: FeatureTaskRuntimePhaseRecord,
): QaRepairAttempt {
  val repairState = state ?: invalidQaRepairState("completed qa_fix has no persisted repair state.")
  val currentWorkflowId = workflowId ?: invalidQaRepairState("completed qa_fix has no workflow identity.")
  val attempt =
    repairState.attempts.lastOrNull()
      ?: invalidQaRepairState("completed qa_fix has no persisted completed repair attempt.")
  val accountingMatches =
    attempt.attemptNumber == repairState.consumedAttempts && completedRecord.attemptCount >= attempt.attemptNumber
  val evidenceMatches =
    attempt.outcome == QaRepairAttemptOutcome.COMPLETED &&
      attempt.repairOutputRef == "$currentWorkflowId:$phaseId:attempt-${attempt.attemptNumber}" &&
      !completedRecord.outputArtifact.isNullOrBlank()
  if (!accountingMatches || !evidenceMatches) {
    invalidQaRepairState("completed qa_fix identity or attempt accounting does not match persisted repair evidence.")
  }
  return attempt
}

private fun invalidQaRepairState(reason: String): Nothing = throw invalidQaRepairStateSchema(reason)
