package skillbill.workflow.taskruntime.model.audit.interaction

import skillbill.workflow.taskruntime.model.audit.AcceptanceCriterionRepairState
import skillbill.workflow.taskruntime.model.audit.AcceptanceCriterionSourceDisposition

internal fun traceAuditId(
  outputId: String,
  owner: String,
): String = "$outputId:trace:$owner"

internal fun brokenLine(
  ref: String,
  owner: String,
  trace: String,
): String = "Interaction $ref: broken owner $owner trace $trace"

private val BROKEN_LINE = Regex("""^Interaction (\S+): broken owner AC-\d{3} trace """, RegexOption.MULTILINE)

private fun brokenRefsIn(evidence: String): Set<String> =
  BROKEN_LINE.findAll(evidence).map { it.groupValues[1] }.toSet()

internal fun AcceptanceCriterionRepairState.retraceRefs(buildable: (String) -> Boolean): List<String> =
  interactionTrace.brokenScenarioRefs.filter { ref -> buildable(ref) && ownerPassedSinceBreak(ref) }

private fun AcceptanceCriterionRepairState.ownerPassedSinceBreak(ref: String): Boolean {
  val reopening =
    sourceTransitions.filter { transition ->
      transition.toDisposition == AcceptanceCriterionSourceDisposition.SOURCE_UNRESOLVED &&
        transition.producingId.endsWith(traceAuditId("", transition.criterionRef)) &&
        ref in brokenRefsIn(transition.evidenceRef)
    }.maxByOrNull { it.revision } ?: return false
  val closedNow =
    criteria.firstOrNull { it.canonicalRef == reopening.criterionRef }?.sourceDisposition ==
      AcceptanceCriterionSourceDisposition.SOURCE_CLOSED
  return closedNow &&
    sourceTransitions.any {
      it.criterionRef == reopening.criterionRef &&
        it.toDisposition == AcceptanceCriterionSourceDisposition.SOURCE_CLOSED &&
        it.revision > reopening.revision
    }
}
