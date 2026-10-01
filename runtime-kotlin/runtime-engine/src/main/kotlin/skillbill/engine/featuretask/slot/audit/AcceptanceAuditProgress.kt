package skillbill.engine.featuretask.slot.audit

internal object AcceptanceAuditProgress {
  fun rejectionReason(
    criteria: List<String>,
    text: String,
    priorText: String?,
    repaired: Boolean,
    operatorReopened: Boolean,
  ): String? {
    val catalog = AcceptanceAuditCatalog.create(criteria)
    if (catalog is AcceptanceAuditCatalog.Unusable) return catalog.reason
    catalog as AcceptanceAuditCatalog.Known
    val current = AcceptanceAuditRemainingCriteriaParser.parse(text, catalog)
    if (current is AcceptanceAuditRemainingCriteria.Unusable) return current.reason
    current as AcceptanceAuditRemainingCriteria.Known
    return comparisonRejection(catalog, current, priorText, repaired, operatorReopened)
  }

  private fun comparisonRejection(
    catalog: AcceptanceAuditCatalog.Known,
    current: AcceptanceAuditRemainingCriteria.Known,
    priorText: String?,
    repaired: Boolean,
    operatorReopened: Boolean,
  ): String? {
    if (operatorReopened) return null
    if (priorText == null) {
      return if (repaired) {
        "Audit comparison baseline is missing after repair; refusing another automatic repair."
      } else {
        null
      }
    }
    val prior = AcceptanceAuditRemainingCriteriaParser.parse(priorText, catalog)
    if (prior is AcceptanceAuditRemainingCriteria.Unusable) {
      return "Audit comparison baseline is unusable: ${prior.reason}"
    }
    prior as AcceptanceAuditRemainingCriteria.Known
    return if (current.identities.containsAll(prior.identities)) {
      "Audit reported ${current.identities.size} remaining production criteria after repair " +
        "(${current.identities.sorted().joinToString(", ")}) against ${prior.identities.size} before it " +
        "(${prior.identities.sorted().joinToString(", ")}). The repair resolved none of the prior criteria, " +
        "so the run blocks for operator intervention instead of relaunching another repair."
    } else {
      null
    }
  }
}
