package skillbill.engine.featuretask.slot.audit

internal sealed interface AcceptanceAuditProgressOutcome {
  data object Advance : AcceptanceAuditProgressOutcome

  data object RestartBaseline : AcceptanceAuditProgressOutcome

  data object MissingBaselineLimitReached : AcceptanceAuditProgressOutcome

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
  val missingBaselineRounds: Int = 0,
)

internal object AcceptanceAuditProgress {
  const val MAX_NON_SHRINKING_ROUNDS: Int = 2
  const val MAX_MISSING_BASELINE_EVENTS: Int = 2
  const val MISSING_BASELINE_LIMIT_REASON: String =
    "Audit comparison baseline is missing after repair for the second time; automatic restart limit reached."

  fun declaresComplete(
    criteria: List<String>,
    text: String?,
  ): Boolean {
    val catalog = AcceptanceAuditCatalog.create(criteria) as? AcceptanceAuditCatalog.Known ?: return false
    return AcceptanceAuditRemainingCriteriaParser.parse(text.orEmpty(), catalog) is
      AcceptanceAuditRemainingCriteria.Complete
  }

  fun reopeningReason(
    criteria: List<String>,
    text: String,
    priorText: String?,
  ): String? {
    val catalog = AcceptanceAuditCatalog.create(criteria) as? AcceptanceAuditCatalog.Known ?: return null
    val current =
      AcceptanceAuditRemainingCriteriaParser.parse(text, catalog) as? AcceptanceAuditRemainingCriteria.Known
        ?: return null
    val prior = priorText?.let { AcceptanceAuditRemainingCriteriaParser.parse(it, catalog) } ?: return null
    val previouslyOpen =
      when (prior) {
        is AcceptanceAuditRemainingCriteria.Known -> prior.identities
        AcceptanceAuditRemainingCriteria.Complete -> emptySet()
        is AcceptanceAuditRemainingCriteria.Unusable -> current.identities
      }
    val reopened = current.identities - previouslyOpen
    return reopened.takeIf { it.isNotEmpty() }?.let {
      "Previously satisfied criteria stay closed: ${it.sorted().joinToString(", ")}. " +
        "Report only unresolved criteria from the last accepted audit."
    }
  }

  fun rejectionReason(
    criteria: List<String>,
    text: String,
    priorText: String?,
    repaired: Boolean,
    operatorReopened: Boolean,
  ): String? {
    val result =
      outcome(
        AcceptanceAuditProgressInput(
          criteria = criteria,
          text = text,
          priorText = priorText,
          repaired = repaired,
          operatorReopened = operatorReopened,
          nonShrinkingRounds = MAX_NON_SHRINKING_ROUNDS,
        ),
      )
    return when (result) {
      AcceptanceAuditProgressOutcome.Advance,
      AcceptanceAuditProgressOutcome.NonShrinking,
      AcceptanceAuditProgressOutcome.RestartBaseline,
      -> null
      AcceptanceAuditProgressOutcome.MissingBaselineLimitReached -> MISSING_BASELINE_LIMIT_REASON
      is AcceptanceAuditProgressOutcome.Rejected -> result.reason
    }
  }

  fun outcome(input: AcceptanceAuditProgressInput): AcceptanceAuditProgressOutcome {
    reopeningReason(input.criteria, input.text, input.priorText)?.let {
      return AcceptanceAuditProgressOutcome.Rejected(it)
    }
    val catalog = AcceptanceAuditCatalog.create(input.criteria)
    if (catalog is AcceptanceAuditCatalog.Unusable) return AcceptanceAuditProgressOutcome.Rejected(catalog.reason)
    catalog as AcceptanceAuditCatalog.Known
    return when (val current = AcceptanceAuditRemainingCriteriaParser.parse(input.text, catalog)) {
      is AcceptanceAuditRemainingCriteria.Unusable -> AcceptanceAuditProgressOutcome.Rejected(current.reason)
      AcceptanceAuditRemainingCriteria.Complete -> AcceptanceAuditProgressOutcome.Advance
      is AcceptanceAuditRemainingCriteria.Known -> comparisonOutcome(catalog, current, input)
    }
  }

  private fun comparisonOutcome(
    catalog: AcceptanceAuditCatalog.Known,
    current: AcceptanceAuditRemainingCriteria.Known,
    input: AcceptanceAuditProgressInput,
  ): AcceptanceAuditProgressOutcome {
    val priorText = input.priorText
    if (priorText == null) {
      return when {
        !input.repaired -> AcceptanceAuditProgressOutcome.Advance
        input.missingBaselineRounds < MAX_MISSING_BASELINE_EVENTS - 1 ->
          AcceptanceAuditProgressOutcome.RestartBaseline
        else -> AcceptanceAuditProgressOutcome.MissingBaselineLimitReached
      }
    }
    if (input.operatorReopened) return AcceptanceAuditProgressOutcome.Advance
    return when (val prior = AcceptanceAuditRemainingCriteriaParser.parse(priorText, catalog)) {
      is AcceptanceAuditRemainingCriteria.Unusable ->
        AcceptanceAuditProgressOutcome.Rejected("Audit comparison baseline is unusable: ${prior.reason}")
      AcceptanceAuditRemainingCriteria.Complete -> AcceptanceAuditProgressOutcome.Advance
      is AcceptanceAuditRemainingCriteria.Known -> shrinkOutcome(current, prior, input)
    }
  }

  private fun shrinkOutcome(
    current: AcceptanceAuditRemainingCriteria.Known,
    prior: AcceptanceAuditRemainingCriteria.Known,
    input: AcceptanceAuditProgressInput,
  ): AcceptanceAuditProgressOutcome =
    when {
      current.identities.size < prior.identities.size -> AcceptanceAuditProgressOutcome.Advance
      input.nonShrinkingRounds < MAX_NON_SHRINKING_ROUNDS -> AcceptanceAuditProgressOutcome.NonShrinking
      else -> AcceptanceAuditProgressOutcome.Rejected(capReachedReason(current, prior))
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
