package skillbill.workflow.taskruntime.model.audit.interaction

import skillbill.workflow.taskruntime.model.audit.AcceptanceCriterionInteractionTrace
import skillbill.workflow.taskruntime.model.audit.AcceptanceCriterionProseAudit
import skillbill.workflow.taskruntime.model.audit.AcceptanceCriterionRepairCriterion
import skillbill.workflow.taskruntime.model.audit.AcceptanceCriterionRepairMode
import skillbill.workflow.taskruntime.model.audit.AcceptanceCriterionRepairSourceTransition
import skillbill.workflow.taskruntime.model.audit.AcceptanceCriterionRepairStage
import skillbill.workflow.taskruntime.model.audit.AcceptanceCriterionRepairState
import skillbill.workflow.taskruntime.model.audit.AcceptanceCriterionSourceDisposition
import skillbill.workflow.taskruntime.model.audit.afterProseAudit
import skillbill.workflow.taskruntime.model.audit.isDigest

data class AcceptanceCriterionInteractionTraceSettlement(
  val outputId: String,
  val outputDigest: String,
  val treeDigest: String,
  val checkpointId: String,
  val entries: List<AcceptanceCriterionInteractionEntry>,
  val rechecks: List<AcceptanceCriterionRecheckEntry> = emptyList(),
) {
  val reopenedRefs: List<String>
    get() =
      (
        entries.mapNotNull { it.ownerRef } +
          rechecks.filter { it.verdict == AcceptanceCriterionRecheckVerdict.REGRESSED }.map { it.criterionRef }
      ).distinct()
}

private class TraceAudits(
  val reopening: List<AcceptanceCriterionProseAudit>,
  val confirming: List<AcceptanceCriterionProseAudit>,
  val unknown: AcceptanceCriterionProseAudit?,
) {
  val all: List<AcceptanceCriterionProseAudit> get() = reopening + confirming + listOfNotNull(unknown)
}

fun AcceptanceCriterionRepairState.requireInteractionTrace(
  targets: List<AcceptanceCriterionInteractionTarget>,
  buildable: (String) -> Boolean = { true },
): AcceptanceCriterionRepairState {
  if (stage != AcceptanceCriterionRepairStage.ACCEPTED) return this
  val trace = interactionTrace
  val traced = trace.tracedSpecDigest == specDigest
  val pending = if (traced) retraceRefs(buildable) else targets.map { it.scenarioRef }
  if (pending.isEmpty() && trace.pendingRechecks.isEmpty()) {
    if (traced) return this
    return copy(interactionTrace = trace.copy(tracedSpecDigest = specDigest), revision = revision + 1)
  }
  return copy(
    stage = AcceptanceCriterionRepairStage.INTERACTION_TRACE_REQUIRED,
    interactionTrace = trace.copy(pendingScenarioRefs = pending),
    revision = revision + 1,
  )
}

fun AcceptanceCriterionRepairState.recordInteractionTrace(
  settlement: AcceptanceCriterionInteractionTraceSettlement,
): AcceptanceCriterionRepairState? {
  if (stage != AcceptanceCriterionRepairStage.INTERACTION_TRACE_REQUIRED) return replayOfInteractionTrace(settlement)
  val owners = settlement.reopenedRefs
  val digests = settlement.outputDigest.isDigest() && settlement.treeDigest.isDigest()
  val valid = digests && settlement.checkpointId.isNotBlank()
  val closed = (owners + settlement.rechecks.map { it.criterionRef }).all(::isClosed)
  if (!settlementMatchesPending(settlement) || !valid || !closed) return null
  val audits = traceAudits(settlement, owners)
  val capacity =
    proseAudits.size + audits.all.size <= AcceptanceCriterionRepairState.MAX_REPAIR_OUTCOMES &&
      sourceTransitions.size + audits.reopening.size + audits.confirming.size <=
      AcceptanceCriterionRepairState.MAX_REPAIR_SOURCE_TRANSITIONS
  if (!capacity) return null
  return applyInteractionTrace(settlement, audits)
}

private fun AcceptanceCriterionRepairState.settlementMatchesPending(
  settlement: AcceptanceCriterionInteractionTraceSettlement,
): Boolean {
  val pending = interactionTrace.pendingScenarioRefs
  val refs = settlement.entries.map { it.scenarioRef }
  val rechecks = settlement.rechecks.map { it.criterionRef }
  val pendingRechecks = interactionTrace.pendingRechecks
  return refs.size == pending.size && refs.toSet() == pending.toSet() &&
    rechecks.size == pendingRechecks.size && rechecks.toSet() == pendingRechecks.toSet()
}

private fun AcceptanceCriterionRepairState.replayOfInteractionTrace(
  settlement: AcceptanceCriterionInteractionTraceSettlement,
): AcceptanceCriterionRepairState? {
  val auditIds = proseAudits.map { it.id }.toSet()
  val recorded = settlement.reopenedRefs.all { traceAuditId(settlement.outputId, it) in auditIds }
  val settled =
    interactionTrace.pendingScenarioRefs.isEmpty() &&
      settlement.entries.all {
        it.settledIn(interactionTrace)
      }
  return takeIf { recorded && settled }
}

private fun AcceptanceCriterionInteractionEntry.settledIn(trace: AcceptanceCriterionInteractionTrace): Boolean =
  when (verdict) {
    AcceptanceCriterionInteractionVerdict.HOLDS ->
      scenarioRef in trace.tracedScenarioRefs || scenarioRef in trace.repairedScenarioRefs
    AcceptanceCriterionInteractionVerdict.BROKEN -> scenarioRef in trace.brokenScenarioRefs
    AcceptanceCriterionInteractionVerdict.UNKNOWN -> scenarioRef in trace.unknownScenarioRefs
  }

private fun AcceptanceCriterionRepairState.criterion(ref: String): AcceptanceCriterionRepairCriterion? =
  criteria.firstOrNull { it.canonicalRef == ref }

private fun AcceptanceCriterionRepairState.isClosed(ref: String): Boolean =
  criterion(ref)?.sourceDisposition == AcceptanceCriterionSourceDisposition.SOURCE_CLOSED

private fun AcceptanceCriterionRepairState.traceAudits(
  settlement: AcceptanceCriterionInteractionTraceSettlement,
  owners: List<String>,
): TraceAudits {
  val confirmed =
    settlement.rechecks.filter {
      it.verdict == AcceptanceCriterionRecheckVerdict.HOLDS && it.criterionRef !in owners &&
        it.evidenceRef != criterion(it.criterionRef)?.closureEvidenceRef
    }
  return TraceAudits(
    reopening = owners.map { owner -> interactionAudit(settlement, owner) },
    confirming = confirmed.map { recheck -> confirmingAudit(settlement, recheck) },
    unknown = unknownAudit(settlement),
  )
}

private fun AcceptanceCriterionRepairState.interactionAudit(
  settlement: AcceptanceCriterionInteractionTraceSettlement,
  owner: String,
): AcceptanceCriterionProseAudit =
  AcceptanceCriterionProseAudit(
    id = traceAuditId(settlement.outputId, owner),
    revision = revision + 1,
    criterionRef = owner,
    satisfied = false,
    digest = settlement.outputDigest,
    prose = (brokenLines(settlement, owner) + regressedLines(settlement, owner)).joinToString("\n"),
    treeDigest = settlement.treeDigest,
    checkpointId = settlement.checkpointId,
  )

private fun AcceptanceCriterionRepairState.confirmingAudit(
  settlement: AcceptanceCriterionInteractionTraceSettlement,
  recheck: AcceptanceCriterionRecheckEntry,
): AcceptanceCriterionProseAudit =
  AcceptanceCriterionProseAudit(
    id = recheckAuditId(settlement.outputId, recheck.criterionRef),
    revision = revision + 1,
    criterionRef = recheck.criterionRef,
    satisfied = true,
    digest = settlement.outputDigest,
    prose = recheck.evidenceRef,
    treeDigest = settlement.treeDigest,
    checkpointId = settlement.checkpointId,
  )

private fun AcceptanceCriterionRepairState.unknownAudit(
  settlement: AcceptanceCriterionInteractionTraceSettlement,
): AcceptanceCriterionProseAudit? {
  val unknown = settlement.entries.filter { it.verdict == AcceptanceCriterionInteractionVerdict.UNKNOWN }
  if (unknown.isEmpty()) return null
  return AcceptanceCriterionProseAudit(
    id = unknownAuditId(settlement.outputId),
    revision = revision + 1,
    criterionRef = null,
    satisfied = false,
    digest = settlement.outputDigest,
    prose = unknown.joinToString("\n") { unknownLine(it.scenarioRef, it.traceRef) },
    treeDigest = settlement.treeDigest,
    checkpointId = settlement.checkpointId,
  )
}

private fun AcceptanceCriterionRepairState.applyInteractionTrace(
  settlement: AcceptanceCriterionInteractionTraceSettlement,
  audits: TraceAudits,
): AcceptanceCriterionRepairState? {
  val reopeningByRef = audits.reopening.associateBy { it.criterionRef }
  val confirmingByRef = audits.confirming.associateBy { it.criterionRef }
  val transitions = mutableListOf<AcceptanceCriterionRepairSourceTransition>()
  val nextCriteria =
    criteria.map { criterion ->
      val reopening = reopeningByRef[criterion.canonicalRef]
      val confirming = confirmingByRef[criterion.canonicalRef]
      val closed = criterion.sourceDisposition == AcceptanceCriterionSourceDisposition.SOURCE_CLOSED
      when {
        !closed -> criterion
        reopening != null -> {
          transitions += reopening.transition(criterion, AcceptanceCriterionSourceDisposition.SOURCE_UNRESOLVED)
          criterion.afterProseAudit(reopening, AcceptanceCriterionSourceDisposition.SOURCE_UNRESOLVED)
        }
        confirming != null -> {
          transitions += confirming.transition(criterion, AcceptanceCriterionSourceDisposition.SOURCE_CLOSED)
          criterion.copy(
            closureEvidenceRef = confirming.prose,
            closureTreeDigest = confirming.treeDigest,
            closureCheckpointId = confirming.checkpointId,
          )
        }
        else -> criterion
      }
    }
  val queue =
    AcceptanceCriterionRepairState.stableQueue(nextCriteria, dependencies, nextCriteria.associateBy { it.canonicalRef })
      ?: return null
  val blocked = AcceptanceCriterionRepairState.hasExhaustedUnresolvedCriterion(queue, unsuccessfulTotals)
  return copy(
    criteria = nextCriteria,
    unresolvedQueue = queue,
    currentCriterionRef = queue.firstOrNull(),
    mode =
      if (queue.isEmpty()) {
        AcceptanceCriterionRepairMode.ACCEPTED
      } else {
        AcceptanceCriterionRepairMode.CURRENT_CRITERION_TARGETED
      },
    stage =
      when {
        queue.isEmpty() -> AcceptanceCriterionRepairStage.ACCEPTED
        blocked -> AcceptanceCriterionRepairStage.BUDGET_BLOCKED
        else -> AcceptanceCriterionRepairStage.DISCOVERY_ADMITTED
      },
    proseAudits = proseAudits + audits.all,
    sourceTransitions = sourceTransitions + transitions,
    interactionTrace = interactionTrace.after(settlement.entries, specDigest),
    revision = revision + 1,
  )
}

private fun brokenLines(
  settlement: AcceptanceCriterionInteractionTraceSettlement,
  owner: String,
): List<String> =
  settlement.entries.filter { it.ownerRef == owner }.map { brokenLine(it.scenarioRef, owner, it.traceRef) }

private fun regressedLines(
  settlement: AcceptanceCriterionInteractionTraceSettlement,
  owner: String,
): List<String> =
  settlement.rechecks.filter { it.criterionRef == owner && it.verdict == AcceptanceCriterionRecheckVerdict.REGRESSED }
    .map { regressedLine(owner, it.evidenceRef) }

private fun AcceptanceCriterionProseAudit.transition(
  criterion: AcceptanceCriterionRepairCriterion,
  to: AcceptanceCriterionSourceDisposition,
): AcceptanceCriterionRepairSourceTransition =
  AcceptanceCriterionRepairSourceTransition(
    criterion.canonicalRef, revision, criterion.sourceDisposition, to, id, digest, prose, treeDigest, checkpointId,
  )

private fun AcceptanceCriterionInteractionTrace.after(
  entries: List<AcceptanceCriterionInteractionEntry>,
  specDigest: String,
): AcceptanceCriterionInteractionTrace {
  var traced = tracedScenarioRefs
  var broken = brokenScenarioRefs
  var repaired = repairedScenarioRefs
  var unknown = unknownScenarioRefs
  entries.forEach { entry ->
    val ref = entry.scenarioRef
    when (entry.verdict) {
      AcceptanceCriterionInteractionVerdict.HOLDS -> {
        if (ref in broken) repaired = (repaired + ref).distinct()
        traced = (traced + ref).distinct()
        broken = broken - ref
        unknown = unknown - ref
      }
      AcceptanceCriterionInteractionVerdict.BROKEN -> {
        broken = (broken + ref).distinct()
        traced = traced - ref
        repaired = repaired - ref
        unknown = unknown - ref
      }
      AcceptanceCriterionInteractionVerdict.UNKNOWN -> {
        unknown = (unknown + ref).distinct()
        traced = traced - ref
        broken = broken - ref
        repaired = repaired - ref
      }
    }
  }
  return copy(
    pendingScenarioRefs = emptyList(),
    tracedScenarioRefs = traced,
    brokenScenarioRefs = broken,
    repairedScenarioRefs = repaired,
    unknownScenarioRefs = unknown,
    recheckRefs = emptyList(),
    recheckAnyChangeRefs = emptyList(),
    tracedSpecDigest = specDigest,
  )
}
