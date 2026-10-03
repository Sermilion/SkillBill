package skillbill.engine.featuretask.runloop.state

import skillbill.engine.featuretask.runloop.core.ReconstructFixLoopBudgetBasesArgs
import skillbill.engine.featuretask.slot.state.PhaseResumeRules
import skillbill.engine.featuretask.slot.state.isRetiredAuditGapLoop
import skillbill.workflow.model.WorkflowStepStatus
import skillbill.workflow.model.validation.FeatureTaskRuntimeVerdict
import skillbill.workflow.model.workflowStepStatus
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimeBackwardEdge
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimePhaseLedgerAction
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimePhaseLedgerEntry
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimePhaseRecord
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimeTransitionDeclaration

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
  ): List<FeatureTaskRuntimePhaseLedgerEntry> {
    val normalizedRecords = normalizeInitialRecordsForStatelessAudit(rawRecords, rules)
    return ledger.filterNot { entry ->
      isRetiredAuditGapLoop(entry.loopId) ||
        (
          entry.action == FeatureTaskRuntimePhaseLedgerAction.BLOCKED &&
            rules(entry.phaseId).dropsBlockedLedgerEntry(rawRecords[entry.phaseId], normalizedRecords[entry.phaseId])
        )
    }
  }

  internal fun reconstructInFlightReentries(
    transitions: FeatureTaskRuntimeTransitionDeclaration,
    initialLedger: List<FeatureTaskRuntimePhaseLedgerEntry>,
    initialRecords: Map<String, FeatureTaskRuntimePhaseRecord>,
    rules: (String) -> PhaseResumeRules,
  ): Map<String, InFlightReentry> =
    buildMap {
      val ledger = normalizeLedgerForStatelessAudit(initialLedger, initialRecords, rules)
      transitions.backwardEdges.forEach { edge ->
        val latestEdge =
          ledger
            .filter { ledger ->
              ledger.action == FeatureTaskRuntimePhaseLedgerAction.LOOP_EDGE && ledger.loopId == edge.loopId
            }
            .maxByOrNull { it.sequenceNumber }
            ?: return@forEach
        val completedAfterEdge =
          ledger
            .asSequence()
            .filter { it.sequenceNumber > latestEdge.sequenceNumber }
            .filter { it.action == FeatureTaskRuntimePhaseLedgerAction.COMPLETE }
            .map { it.phaseId }
            .filter { phaseId -> initialRecords[phaseId]?.status?.workflowStepStatus() == WorkflowStepStatus.COMPLETED }
            .toMutableSet()
        initialRecords.values
          .filter { record ->
            record.status.workflowStepStatus() == WorkflowStepStatus.COMPLETED &&
              record.loopId == edge.loopId &&
              record.edgeIteration == latestEdge.edgeIteration
          }
          .mapTo(completedAfterEdge) { it.phaseId }
        val span = reopenedSpan(transitions, edge)
        if (span.any { phaseId -> phaseId !in completedAfterEdge }) {
          put(
            edge.loopId,
            InFlightReentry(
              destinationPhaseId = edge.destinationPhaseId,
              edgeIteration = requireNotNull(latestEdge.edgeIteration),
              drivingVerdict = edge.triggeringVerdict,
              span = span,
              completedAfterEdge = completedAfterEdge,
              edgeSequenceNumber = latestEdge.sequenceNumber,
            ),
          )
        }
      }
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
  ) {
    transitions.forwardPhaseIds.forEachIndexed { index, stepId ->
      if (!rules(stepId).invalidatesLaterStepsWhileIncomplete || stepId in completedPhases) return@forEachIndexed
      transitions.forwardPhaseIds
        .drop(index + 1)
        .filter(completedPhases::contains)
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
        .filter(completedPhases::contains)
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
