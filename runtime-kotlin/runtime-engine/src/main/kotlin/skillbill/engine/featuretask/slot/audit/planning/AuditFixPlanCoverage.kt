package skillbill.engine.featuretask.slot.audit.planning

import skillbill.engine.featuretask.slot.audit.AcceptanceAuditCatalog
import skillbill.engine.featuretask.slot.audit.AcceptanceAuditRemainingCriteria
import skillbill.engine.featuretask.slot.audit.AcceptanceAuditRemainingCriteriaParser

internal object AuditFixPlanCoverage {
  private val itemHeading = Regex("""^###\s+((?:S\d+-)?AC-?\d+)(?:\s.*)?$""", RegexOption.IGNORE_CASE)
  private val requiredLabels = listOf("Gap:", "Production path:", "Changes:", "Closure evidence:")

  fun rejection(
    criteria: List<String>,
    audit: String,
    plan: String,
  ): String? {
    val catalog = AcceptanceAuditCatalog.create(criteria)
    if (catalog is AcceptanceAuditCatalog.Unusable) return catalog.reason
    catalog as AcceptanceAuditCatalog.Known
    val remaining = AcceptanceAuditRemainingCriteriaParser.parse(audit, catalog)
    if (remaining !is AcceptanceAuditRemainingCriteria.Known) {
      return "Audit repair planning requires a usable nonempty remaining-criterion list."
    }
    val lines = plan.lines()
    val headings =
      lines.withIndex().mapNotNull { (index, line) ->
        itemHeading.matchEntire(line.trim())?.let { index to it.groupValues[1] }
      }
    val planned = mutableSetOf<String>()
    headings.forEachIndexed { ordinal, (start, label) ->
      val identity = catalog.resolve(label)
      val end = headings.getOrNull(ordinal + 1)?.first ?: lines.size
      val item = lines.subList(start + 1, end).map(String::trim)
      itemRejection(identity, remaining.identities, label, ordinal, item)?.let { return it }
      planned += requireNotNull(identity)
    }
    val omitted = remaining.identities - planned
    return if (omitted.isNotEmpty()) {
      "Audit repair plan omits remaining production criteria: ${omitted.sorted().joinToString(", ")}."
    } else {
      null
    }
  }

  private fun itemRejection(
    identity: String?,
    remaining: Set<String>,
    label: String,
    ordinal: Int,
    item: List<String>,
  ): String? {
    val missing =
      requiredLabels.firstOrNull { field ->
        item.none { it.startsWith(field) && it.removePrefix(field).isNotBlank() }
      }
    return when {
      identity == null -> "Audit repair plan names unknown criterion '$label'."
      identity !in remaining -> "Audit repair plan includes criterion '$label' absent from the audit."
      missing != null -> "Audit repair plan item ${ordinal + 1} for '$label' requires concrete '$missing' text."
      else -> null
    }
  }
}
