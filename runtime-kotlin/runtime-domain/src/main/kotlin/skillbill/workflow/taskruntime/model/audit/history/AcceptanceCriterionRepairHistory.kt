package skillbill.workflow.taskruntime.model.audit.history

import skillbill.workflow.taskruntime.model.audit.AcceptanceCriterionAuditAttempt
import skillbill.workflow.taskruntime.model.audit.AcceptanceCriterionCheckedMutation
import skillbill.workflow.taskruntime.model.audit.AcceptanceCriterionCompletedMutation
import skillbill.workflow.taskruntime.model.audit.AcceptanceCriterionProgressClassification
import skillbill.workflow.taskruntime.model.audit.AcceptanceCriterionProseAudit
import skillbill.workflow.taskruntime.model.audit.AcceptanceCriterionRepairOutcome
import skillbill.workflow.taskruntime.model.audit.AcceptanceCriterionRepairSourceTransition
import skillbill.workflow.taskruntime.model.audit.AcceptanceCriterionRepairState
import skillbill.workflow.taskruntime.model.audit.AcceptanceCriterionSourceDisposition
import skillbill.workflow.taskruntime.model.audit.UNKNOWN_AUDIT_SUFFIX
import skillbill.workflow.taskruntime.model.audit.openCriteriaNamedIn
import skillbill.workflow.taskruntime.model.audit.terminal

data class AcceptanceCriterionRepairHistory(
  val attempts: List<AcceptanceCriterionAuditAttempt>,
  val closures: List<AcceptanceCriterionRepairClosure>,
)

data class AcceptanceCriterionRepairClosure(val criterionRef: String, val repairAttemptId: String)

fun AcceptanceCriterionRepairState.auditHistory(): AcceptanceCriterionRepairHistory =
  RepairHistoryProjection(
    this,
  ).project()

private class RepairHistoryProjection(private val state: AcceptanceCriterionRepairState) {
  private val applicable =
    state.criteria.filter { it.sourceDisposition != AcceptanceCriterionSourceDisposition.NOT_APPLICABLE }
      .map { it.canonicalRef }.toSet()
  private val discovery =
    state.proseAudits.firstOrNull()?.takeIf {
      it.criterionRef == null && !it.id.endsWith(UNKNOWN_AUDIT_SUFFIX)
    }
  private val discoveredOpen =
    discovery?.let {
      if (it.satisfied) emptySet() else openCriteriaNamedIn(it.prose)?.intersect(applicable)
    }
  private val open = (discoveredOpen ?: applicable).toMutableSet()
  private val attributed = mutableMapOf<String, String>()
  private val mutations = state.completedMutations.filter { it.terminal }
  private val checks = state.independentChecks.associateBy { it.mutation.repairAttemptId }
  private val decisions = state.historyDecisions(discovery?.revision ?: Long.MIN_VALUE)
  private var cursor = 0

  fun project(): AcceptanceCriterionRepairHistory {
    val attempts =
      mutations.mapIndexed { index, mutation ->
        applyThrough(boundary(index, mutation))
        val satisfied =
          checks[mutation.repairAttemptId]?.comparison?.classification ==
            AcceptanceCriterionProgressClassification.SATISFIED
        val settled = state.outcomes.any { it.reservationId == mutation.repairAttemptId && it.successful }
        AcceptanceCriterionAuditAttempt(mutation.repairAttemptId, index + 1, satisfied && settled, open.size)
      }
    applyThrough(state.revision)
    return AcceptanceCriterionRepairHistory(
      attempts,
      attributed.map { (ref, id) ->
        AcceptanceCriterionRepairClosure(ref, id)
      },
    )
  }

  private fun boundary(
    index: Int,
    mutation: AcceptanceCriterionCompletedMutation,
  ): Long {
    if (index == mutations.lastIndex) return state.revision
    val nextCapture = mutations.getOrNull(index + 1)?.let(::captureRevision)
    return nextCapture?.minus(1) ?: captureRevision(mutation) ?: (discovery?.revision ?: Long.MIN_VALUE)
  }

  private fun captureRevision(mutation: AcceptanceCriterionCompletedMutation): Long? =
    checks[mutation.repairAttemptId]?.comparison?.verificationRevision
      ?: state.reservation?.takeIf { it.reservationId == mutation.repairAttemptId }?.stageRevision
      ?: state.outcomes.singleOrNull { it.reservationId == mutation.repairAttemptId }?.verifiedRevision

  private fun applyThrough(boundary: Long) {
    while (cursor < decisions.size && decisions[cursor].revision <= boundary) {
      val decision = decisions[cursor++]
      if (decision.criterionRef !in applicable) continue
      if (decision.closed) {
        open.remove(decision.criterionRef)
        if (!decision.confirmation) {
          attributed.remove(decision.criterionRef)
          decision.repairAttemptId?.let { attributed[decision.criterionRef] = it }
        }
      } else {
        open.add(decision.criterionRef)
        attributed.remove(decision.criterionRef)
      }
    }
  }
}

private data class RepairHistoryDecision(
  val revision: Long,
  val criterionRef: String,
  val closed: Boolean,
  val confirmation: Boolean = false,
  val repairAttemptId: String? = null,
)

private fun AcceptanceCriterionRepairState.historyDecisions(after: Long): List<RepairHistoryDecision> {
  val transitions = sourceTransitions.filter { it.revision > after }
  val decisions =
    transitions.map { transition ->
      RepairHistoryDecision(
        transition.revision,
        transition.criterionRef,
        transition.toDisposition == AcceptanceCriterionSourceDisposition.SOURCE_CLOSED,
        transition.fromDisposition == AcceptanceCriterionSourceDisposition.SOURCE_CLOSED &&
          transition.toDisposition == AcceptanceCriterionSourceDisposition.SOURCE_CLOSED,
        closingRepair(transition),
      )
    }
  val observations =
    proseAudits.mapNotNull { audit ->
      val ref = audit.criterionRef ?: return@mapNotNull null
      val alreadyRepresented = transitions.any { it.revision == audit.revision && it.criterionRef == ref }
      if (audit.revision <= after || alreadyRepresented) return@mapNotNull null
      RepairHistoryDecision(audit.revision, ref, audit.satisfied, audit.satisfied)
    }
  return (decisions + observations).sortedBy { it.revision }
}

private fun AcceptanceCriterionRepairState.closingRepair(
  transition: AcceptanceCriterionRepairSourceTransition,
): String? {
  if (transition.toDisposition != AcceptanceCriterionSourceDisposition.SOURCE_CLOSED) return null
  val outcome = outcomes.singleOrNull { it.outcomeId == transition.producingId && it.successful }
  val audit = proseAudits.singleOrNull { it.id == transition.producingId && it.satisfied }
  val checks = independentChecks.associateBy { it.mutation.repairAttemptId }
  val prior =
    completedMutations.filter { mutation ->
      mutation.terminal && mutation.criterionRef == transition.criterionRef &&
        checks[mutation.repairAttemptId]?.comparison?.verificationRevision?.let { it < transition.revision } == true
    }
  val eligible = prior.filter { checks[it.repairAttemptId]?.supportsClosure(it, transition) == true }
  return when {
    outcome?.matchesClosure(transition) == true ->
      eligible.singleOrNull {
        it.repairAttemptId ==
          outcome.reservationId
      }?.repairAttemptId
    audit?.matchesClosure(transition) == true ->
      prior.maxByOrNull { checks.getValue(it.repairAttemptId).comparison.verificationRevision }
        ?.takeIf { it in eligible }?.repairAttemptId
    else -> null
  }
}

private fun AcceptanceCriterionCheckedMutation.supportsClosure(
  completed: AcceptanceCriterionCompletedMutation,
  transition: AcceptanceCriterionRepairSourceTransition,
): Boolean =
  mutation == completed &&
    comparison.classification == AcceptanceCriterionProgressClassification.SATISFIED &&
    comparison.currentTreeDigest == transition.treeDigest && comparison.currentCheckpointId == transition.checkpointId

private fun AcceptanceCriterionRepairOutcome.matchesClosure(t: AcceptanceCriterionRepairSourceTransition): Boolean =
  criterionRef == t.criterionRef && digest == t.producingDigest && evidenceRef == t.evidenceRef &&
    treeDigest == t.treeDigest && checkpointId == t.checkpointId && verifiedRevision?.plus(1) == t.revision

private fun AcceptanceCriterionProseAudit.matchesClosure(t: AcceptanceCriterionRepairSourceTransition): Boolean =
  criterionRef == t.criterionRef && revision == t.revision && digest == t.producingDigest &&
    prose == t.evidenceRef && treeDigest == t.treeDigest && checkpointId == t.checkpointId
