package skillbill.engine.featuretask.slot.audit

import skillbill.contracts.JsonCodec
import skillbill.error.core.MalformedJsonTextError

internal sealed interface AcceptanceAuditRemainingCriteria {
  data class Known(
    val identities: Set<String>,
  ) : AcceptanceAuditRemainingCriteria

  data class Unusable(
    val reason: String,
  ) : AcceptanceAuditRemainingCriteria
}

internal object AcceptanceAuditRemainingCriteriaParser {
  private val findingStart = Regex("""^(?:[-*]\s+|\d+[.)]\s+)?[`*]*(?:S\d+-)?AC-?\d+""", RegexOption.IGNORE_CASE)
  private val label = Regex("""(?:S\d+-)?AC-?\d+(?=$|[.,:\s/`*])""", RegexOption.IGNORE_CASE)
  private val resolved =
    Regex("""^(?:is |was |has been )?(?:already )?(?:satisfied|resolved|closed)\b""", RegexOption.IGNORE_CASE)

  fun parse(
    text: String,
    catalog: AcceptanceAuditCatalog.Known,
  ): AcceptanceAuditRemainingCriteria {
    val value =
      text
        .trimIndent()
        .trim()
        .removePrefix("```json")
        .removePrefix("```")
        .removeSuffix("```")
        .trim()
    return if (value.startsWith("[")) parseJson(value, catalog) else parseProse(value, catalog)
  }

  private fun parseJson(
    value: String,
    catalog: AcceptanceAuditCatalog.Known,
  ): AcceptanceAuditRemainingCriteria {
    val entries =
      try {
        JsonCodec.parseJsonArrayStrict(value)
      } catch (_: MalformedJsonTextError) {
        return AcceptanceAuditRemainingCriteria.Unusable("Malformed JSON remaining-criterion list.")
      }
    val identities = linkedSetOf<String>()
    for (entry in entries) {
      when (val parsed = parseJsonEntry(entry, catalog)) {
        is AcceptanceAuditRemainingCriteria.Known -> identities += parsed.identities
        is AcceptanceAuditRemainingCriteria.Unusable -> return parsed
      }
    }
    return knownNonempty(identities)
  }

  private fun parseJsonEntry(
    entry: Any?,
    catalog: AcceptanceAuditCatalog.Known,
  ): AcceptanceAuditRemainingCriteria {
    val references =
      jsonReferences(entry)
        ?: return AcceptanceAuditRemainingCriteria.Unusable(
          "JSON finding must have one unambiguous criterion identity.",
        )
    val identities = linkedSetOf<String>()
    for (reference in references) {
      when (val parsed = parseReference(reference, catalog)) {
        is AcceptanceAuditRemainingCriteria.Known -> identities += parsed.identities
        is AcceptanceAuditRemainingCriteria.Unusable -> return parsed
      }
    }
    return if (identities.size == 1) {
      AcceptanceAuditRemainingCriteria.Known(identities)
    } else {
      AcceptanceAuditRemainingCriteria.Unusable("Conflicting criterion aliases in one JSON finding.")
    }
  }

  private fun jsonReferences(entry: Any?): List<String>? =
    when (entry) {
      is String -> listOf(entry)
      is Map<*, *> -> {
        val fields = AuditFindingPayloadKeys.IDENTITY_FIELDS.filter(entry::containsKey)
        if (fields.isEmpty() || fields.any { entry[it] !is String }) {
          null
        } else {
          fields.map { entry[it] as String }
        }
      }
      else -> null
    }

  private fun parseProse(
    value: String,
    catalog: AcceptanceAuditCatalog.Known,
  ): AcceptanceAuditRemainingCriteria {
    val identities = linkedSetOf<String>()
    val satisfied = linkedSetOf<String>()
    val lines = value.replace(Regex(""";\s*(?=(?:S\d+-)?AC-?\d+)""", RegexOption.IGNORE_CASE), "\n").lines()
    val findingIndent =
      lines
        .filter { findingStart.containsMatchIn(it.trim()) }
        .minOfOrNull { it.takeWhile(Char::isWhitespace).length } ?: 0
    for (line in lines) {
      val trimmed = line.trim()
      val summary =
        !trimmed.startsWith("-") &&
          !trimmed.startsWith("*") &&
          trimmed.contains("no remaining production gap", ignoreCase = true)
      if (summary) {
        label.findAll(trimmed).forEach { reference ->
          val identity =
            catalog.resolve(reference.value)
              ?: return AcceptanceAuditRemainingCriteria.Unusable("Unknown criterion in satisfied summary.")
          satisfied += identity
        }
      } else if (findingStart.containsMatchIn(trimmed)) {
        when (val parsed = parseReference(trimmed, catalog)) {
          is AcceptanceAuditRemainingCriteria.Known -> identities += parsed.identities
          is AcceptanceAuditRemainingCriteria.Unusable -> return parsed
        }
      } else if (Regex("""^[-*]\s+|^\d+[.)]\s+""").containsMatchIn(trimmed) &&
        line.takeWhile(Char::isWhitespace).length <= findingIndent
      ) {
        return AcceptanceAuditRemainingCriteria.Unusable("A remaining finding has no accepted criterion identity.")
      }
    }
    return if (identities.any(satisfied::contains)) {
      AcceptanceAuditRemainingCriteria.Unusable("Audit declares a criterion both open and satisfied.")
    } else {
      knownNonempty(identities)
    }
  }

  private fun parseReference(
    reference: String,
    catalog: AcceptanceAuditCatalog.Known,
  ): AcceptanceAuditRemainingCriteria {
    var rest = reference.trim().replace(Regex("""^(?:[-*]\s+|\d+[.)]\s+)"""), "").trimStart('`', '*')
    val identities = linkedSetOf<String>()
    do {
      val match =
        label.find(rest)?.takeIf { it.range.first == 0 }
          ?: return AcceptanceAuditRemainingCriteria.Unusable("Unidentified remaining criterion.")
      val identity =
        catalog.resolve(match.value)
          ?: return AcceptanceAuditRemainingCriteria.Unusable(
            "Unknown remaining criterion ${match.value.take(AcceptanceAuditCatalog.DIAGNOSTIC_LABEL_LIMIT)}.",
          )
      identities += identity
      rest = rest.substring(match.value.length).trimStart('`', '*', ' ')
      if (!rest.startsWith("/")) break
      rest = rest.removePrefix("/").trimStart('`', '*', ' ')
    } while (true)
    return when {
      identities.size != 1 ->
        AcceptanceAuditRemainingCriteria.Unusable("Conflicting criterion aliases in one remaining finding.")
      resolved.containsMatchIn(rest.trimStart('.', ':', ' ')) ->
        AcceptanceAuditRemainingCriteria.Unusable("A remaining finding declares its criterion resolved.")
      else -> AcceptanceAuditRemainingCriteria.Known(identities.toSet())
    }
  }

  private fun knownNonempty(identities: Set<String>): AcceptanceAuditRemainingCriteria =
    if (identities.isEmpty()) {
      AcceptanceAuditRemainingCriteria.Unusable("Nonempty audit report has no countable remaining criteria.")
    } else {
      AcceptanceAuditRemainingCriteria.Known(identities.toSet())
    }
}

private object AuditFindingPayloadKeys {
  const val CRITERION_ID = "criterion_id"
  const val CRITERION = "criterion"
  const val ACCEPTANCE_CRITERION_REF = "acceptance_criterion_ref"
  val IDENTITY_FIELDS = listOf(CRITERION_ID, CRITERION, ACCEPTANCE_CRITERION_REF)
}
