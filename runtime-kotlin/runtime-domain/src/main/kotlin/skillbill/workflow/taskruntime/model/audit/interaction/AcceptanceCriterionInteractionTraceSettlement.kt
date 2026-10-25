package skillbill.workflow.taskruntime.model.audit.interaction

import skillbill.workflow.taskruntime.model.audit.AcceptanceCriterionInteractionTrace
import skillbill.workflow.taskruntime.model.audit.AcceptanceCriterionProseAudit
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
)

fun AcceptanceCriterionRepairState.requireInteractionTrace(
  targets: List<AcceptanceCriterionInteractionTarget>,
  buildable: (String) -> Boolean = { true },
): AcceptanceCriterionRepairState {
  if (stage != AcceptanceCriterionRepairStage.ACCEPTED) return this
  val trace = interactionTrace
  val traced = trace.tracedSpecDigest == specDigest
  val pending = if (traced) retraceRefs(buildable) else targets.map { it.scenarioRef }
  if (pending.isEmpty()) {
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
  val owners = settlement.entries.mapNotNull { it.ownerRef }.distinct()
  val pending = interactionTrace.pendingScenarioRefs
  val refs = settlement.entries.map { it.scenarioRef }
  val matches = refs.size == pending.size && refs.toSet() == pending.toSet()
  val digests = settlement.outputDigest.isDigest() && settlement.treeDigest.isDigest()
  val valid = digests && settlement.checkpointId.isNotBlank()
  if (!matches || !valid || !ownersCanReopen(owners)) return null
  val audits = owners.map { owner -> interactionAudit(settlement, owner) }
  val capacity =
    proseAudits.size + audits.size <= AcceptanceCriterionRepairState.MAX_REPAIR_OUTCOMES &&
      sourceTransitions.size + audits.size <= AcceptanceCriterionRepairState.MAX_REPAIR_SOURCE_TRANSITIONS
  if (!capacity) return null
  return applyInteractionTrace(settlement, audits)
}

private fun AcceptanceCriterionRepairState.replayOfInteractionTrace(
  settlement: AcceptanceCriterionInteractionTraceSettlement,
): AcceptanceCriterionRepairState? {
  val owners = settlement.entries.mapNotNull { it.ownerRef }.distinct()
  val recorded = owners.all { owner -> proseAudits.any { it.id == traceAuditId(settlement.outputId, owner) } }
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

private fun AcceptanceCriterionRepairState.ownersCanReopen(owners: List<String>): Boolean =
  owners.all { owner ->
    criteria.firstOrNull { it.canonicalRef == owner }?.sourceDisposition ==
      AcceptanceCriterionSourceDisposition.SOURCE_CLOSED
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
    prose =
      settlement.entries.filter { it.ownerRef == owner }.joinToString("\n") {
        brokenLine(it.scenarioRef, owner, it.traceRef)
      },
    treeDigest = settlement.treeDigest,
    checkpointId = settlement.checkpointId,
  )

private fun AcceptanceCriterionRepairState.applyInteractionTrace(
  settlement: AcceptanceCriterionInteractionTraceSettlement,
  audits: List<AcceptanceCriterionProseAudit>,
): AcceptanceCriterionRepairState? {
  val auditByOwner = audits.associateBy { it.criterionRef }
  val reopened = mutableListOf<AcceptanceCriterionRepairSourceTransition>()
  val nextCriteria =
    criteria.map { criterion ->
      val audit = auditByOwner[criterion.canonicalRef]
      if (audit == null || criterion.sourceDisposition != AcceptanceCriterionSourceDisposition.SOURCE_CLOSED) {
        criterion
      } else {
        reopened +=
          AcceptanceCriterionRepairSourceTransition(
            criterion.canonicalRef, audit.revision, criterion.sourceDisposition,
            AcceptanceCriterionSourceDisposition.SOURCE_UNRESOLVED,
            audit.id, audit.digest, audit.prose, audit.treeDigest, audit.checkpointId,
          )
        criterion.afterProseAudit(audit, AcceptanceCriterionSourceDisposition.SOURCE_UNRESOLVED)
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
    proseAudits = proseAudits + audits,
    sourceTransitions = sourceTransitions + reopened,
    interactionTrace = interactionTrace.after(settlement.entries, specDigest),
    revision = revision + 1,
  )
}

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
    tracedSpecDigest = specDigest,
  )
}
