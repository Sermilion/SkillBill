package skillbill.engine.featuretask.slot.audit

internal sealed interface AcceptanceAuditProgressOutcome {
  data object Advance : AcceptanceAuditProgressOutcome

  data object NonShrinking : AcceptanceAuditProgressOutcome

  data class Rejected(val reason: String) : AcceptanceAuditProgressOutcome
}

internal data class AcceptanceAuditProgressInput(
  val criteria: List<String>,
  val text: String,
  val priorText: String?,
  val repaired: Boolean,
  val operatorReopened: Boolean,
  val nonShrinkingRounds: Int,
)

internal object AcceptanceAuditProgress {
  const val MAX_NON_SHRINKING_ROUNDS: Int = 2

  fun outcome(input: AcceptanceAuditProgressInput): AcceptanceAuditProgressOutcome {
    val catalog = AcceptanceAuditCatalog.create(input.criteria)
    if (catalog is AcceptanceAuditCatalog.Unusable) return AcceptanceAuditProgressOutcome.Rejected(catalog.reason)
    catalog as AcceptanceAuditCatalog.Known
    val current = AcceptanceAuditRemainingCriteriaParser.parse(input.text, catalog)
    if (current is AcceptanceAuditRemainingCriteria.Unusable) {
      return AcceptanceAuditProgressOutcome.Rejected(current.reason)
    }
    current as AcceptanceAuditRemainingCriteria.Known
    return comparisonOutcome(catalog, current, input)
  }

  private fun comparisonOutcome(
    catalog: AcceptanceAuditCatalog.Known,
    current: AcceptanceAuditRemainingCriteria.Known,
    input: AcceptanceAuditProgressInput,
  ): AcceptanceAuditProgressOutcome {
    if (input.operatorReopened) return AcceptanceAuditProgressOutcome.Advance
    val priorText =
      input.priorText
        ?: return if (input.repaired) {
          AcceptanceAuditProgressOutcome.Rejected(
            "Audit comparison baseline is missing after repair; refusing another automatic repair.",
          )
        } else {
          AcceptanceAuditProgressOutcome.Advance
        }
    val prior = AcceptanceAuditRemainingCriteriaParser.parse(priorText, catalog)
    if (prior is AcceptanceAuditRemainingCriteria.Unusable) {
      return AcceptanceAuditProgressOutcome.Rejected("Audit comparison baseline is unusable: ${prior.reason}")
    }
    prior as AcceptanceAuditRemainingCriteria.Known
    return when {
      current.identities.size < prior.identities.size -> AcceptanceAuditProgressOutcome.Advance
      input.nonShrinkingRounds < MAX_NON_SHRINKING_ROUNDS -> AcceptanceAuditProgressOutcome.NonShrinking
      else -> AcceptanceAuditProgressOutcome.Rejected(capReachedReason(current, prior))
    }
  }

  private fun capReachedReason(
    current: AcceptanceAuditRemainingCriteria.Known,
    prior: AcceptanceAuditRemainingCriteria.Known,
  ): String =
    "Audit reported ${current.identities.size} remaining production criteria after repair " +
      "(${current.identities.sorted().joinToString(", ")}) against ${prior.identities.size} before it " +
      "(${prior.identities.sorted().joinToString(", ")}). The remaining list did not shrink after " +
      "$MAX_NON_SHRINKING_ROUNDS earlier non-shrinking repair rounds, so the run blocks for operator " +
      "intervention instead of relaunching another repair."
}
