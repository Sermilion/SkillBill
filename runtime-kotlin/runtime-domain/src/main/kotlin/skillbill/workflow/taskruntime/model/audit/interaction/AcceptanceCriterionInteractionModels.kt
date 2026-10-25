package skillbill.workflow.taskruntime.model.audit.interaction

import skillbill.workflow.taskruntime.model.audit.AcceptanceCriterionRepairCriterion
import skillbill.workflow.taskruntime.model.audit.AcceptanceCriterionSourceDisposition

data class AcceptanceCriterionInteractionTarget(
  val scenarioRef: String,
  val allowedOwners: List<String>,
) {
  companion object {
    private val PAIR_REF = Regex("""^(AC-\d{3})\+(AC-\d{3})$""")

    fun pairOf(
      ref: String,
      criteria: List<AcceptanceCriterionRepairCriterion>,
    ): AcceptanceCriterionInteractionTarget? {
      val members = PAIR_REF.matchEntire(ref)?.groupValues?.drop(1) ?: return null
      val known = criteria.associateBy { it.canonicalRef }
      if (members.distinct().size != members.size || members.any { it !in known }) return null
      val owners =
        members.filter {
          known.getValue(it).sourceDisposition == AcceptanceCriterionSourceDisposition.SOURCE_CLOSED
        }
      return AcceptanceCriterionInteractionTarget(ref, owners)
    }
  }
}

data class AcceptanceCriterionSameFilePair(
  val firstRef: String,
  val secondRef: String,
  val sharedPaths: List<String>,
) {
  val pairRef: String get() = "$firstRef+$secondRef"

  fun target(): AcceptanceCriterionInteractionTarget =
    AcceptanceCriterionInteractionTarget(pairRef, listOf(firstRef, secondRef))
}

enum class AcceptanceCriterionInteractionVerdict(val wireValue: String) {
  HOLDS("holds"),
  BROKEN("broken"),
  UNKNOWN("unknown"),
}

enum class AcceptanceCriterionRecheckVerdict(val wireValue: String) {
  HOLDS("holds"),
  REGRESSED("regressed"),
}

data class AcceptanceCriterionInteractionEntry(
  val scenarioRef: String,
  val verdict: AcceptanceCriterionInteractionVerdict,
  val ownerRef: String?,
  val traceRef: String,
)

enum class AcceptanceCriterionAuditResultMode(val wireValue: String) {
  INITIAL_DISCOVERY("initial_discovery"),
  CURRENT_CRITERION_TARGETED("current_criterion_targeted"),
  INTERACTION_TRACE("interaction_trace"),
}
