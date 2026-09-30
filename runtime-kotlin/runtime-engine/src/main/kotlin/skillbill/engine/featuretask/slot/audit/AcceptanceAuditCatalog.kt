package skillbill.engine.featuretask.slot.audit

import skillbill.workflow.taskruntime.model.audit.acceptanceCriterionIdentity

internal sealed interface AcceptanceAuditCatalog {
  data class Known(val aliases: Map<String, String>) : AcceptanceAuditCatalog {
    fun resolve(label: String): String? = aliases[normalize(label)]
  }

  data class Unusable(val reason: String) : AcceptanceAuditCatalog

  companion object {
    const val DIAGNOSTIC_LABEL_LIMIT = 80

    fun create(criteria: List<String>): AcceptanceAuditCatalog {
      if (criteria.isEmpty()) return Unusable("The accepted criterion catalog is empty.")
      return createKnown(criteria)
    }

    private fun createKnown(criteria: List<String>): AcceptanceAuditCatalog {
      val aliases = linkedMapOf<String, String>()
      val canonical = mutableSetOf<String>()
      criteria.forEachIndexed { index, criterion ->
        val identity =
          acceptanceCriterionIdentity(criterion, index + 1)
            ?: return Unusable("Invalid acceptance criterion identity at ordinal ${index + 1}.")
        if (!canonical.add(identity.canonicalRef)) {
          return Unusable("Duplicate accepted criterion ${identity.canonicalRef}.")
        }
        listOfNotNull(identity.canonicalRef, identity.originalLabel).forEach { label ->
          val prior = aliases.put(normalize(label), identity.canonicalRef)
          if (prior != null && prior != identity.canonicalRef) {
            return Unusable("Conflicting accepted criterion alias ${label.take(DIAGNOSTIC_LABEL_LIMIT)}.")
          }
        }
      }
      return Known(aliases.toMap())
    }

    private fun normalize(label: String): String = label.uppercase().replace(Regex("""AC-?0*(\d+)"""), "AC-$1")
  }
}
