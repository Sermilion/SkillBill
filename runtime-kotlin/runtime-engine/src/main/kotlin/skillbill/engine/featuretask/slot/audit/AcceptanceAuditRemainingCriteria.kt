package skillbill.engine.featuretask.slot.audit

import skillbill.contracts.JsonCodec
import skillbill.error.core.MalformedJsonTextError

internal sealed interface AcceptanceAuditRemainingCriteria {
  data class Known(
    val identities: Set<String>,
  ) : AcceptanceAuditRemainingCriteria

  data object Complete : AcceptanceAuditRemainingCriteria

  data class Unusable(
    val reason: String,
  ) : AcceptanceAuditRemainingCriteria
}

internal object AcceptanceAuditRemainingCriteriaParser {
  const val COMPLETION_LINE: String = "No production criteria remain."

  private val findingStart = Regex("""^(?:[-*]\s+|\d+[.)]\s+)?[`*]*(?:S\d+-)?AC-?\d+""", RegexOption.IGNORE_CASE)
  private val label = Regex("""(?<![\w-])(?:S\d+-)?AC-?\d+(?!\w)""", RegexOption.IGNORE_CASE)
  private val emptyList = Regex("""\[\s*]""")
  private val segmentBreak = Regex("""(?<=[.!?])\s+""")
  private val completion = Regex("""\bno production criteria remain[.!]?$""", RegexOption.IGNORE_CASE)
  private val openCue =
    Regex(
      """\b(?:not|missing|remains?|remaining|gaps?|unmet|open|lacks?|absent|incomplete|unimplemented|except|""" +
        """but|however|still|yet|fails?|broken)\b""",
      RegexOption.IGNORE_CASE,
    )
  private val metCue =
    Regex(
      """\b(?:satisfied|met|implemented|resolved|complete|completed|present|done|covered|fulfilled)\b""",
      RegexOption.IGNORE_CASE,
    )
  private val resolved =
    Regex("""^(?:is |was |has been )?(?:already )?(?:satisfied|resolved|closed)\b""", RegexOption.IGNORE_CASE)
  private val testOnlyCriterion = Regex("""\b(?:is|are)\s+test-only\b""", RegexOption.IGNORE_CASE)

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
    return when {
      emptyList.matches(value) -> AcceptanceAuditRemainingCriteria.Complete
      value.startsWith("[") -> parseJson(value, catalog)
      else -> parseProse(value, catalog)
    }
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
      val parsed = parseJsonEntry(entry, catalog)
      if (parsed !is AcceptanceAuditRemainingCriteria.Known) return parsed
      identities += parsed.identities
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
      val parsed = parseReference(reference, catalog)
      if (parsed !is AcceptanceAuditRemainingCriteria.Known) return parsed
      identities += parsed.identities
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
    var completionStated = false
    val lines = value.replace(Regex(""";\s*(?=(?:S\d+-)?AC-?\d+)""", RegexOption.IGNORE_CASE), "\n").lines()
    for (line in lines) {
      val trimmed = line.trim()
      if (findingStart.containsMatchIn(trimmed)) {
        if (declaresSatisfied(trimmed)) continue
        val parsed = parseReference(trimmed, catalog)
        if (parsed !is AcceptanceAuditRemainingCriteria.Known) return parsed
        identities += parsed.identities
      } else if (trimmed.split(segmentBreak).any(completion::containsMatchIn)) {
        completionStated = true
      }
    }
    return when {
      identities.isNotEmpty() && completionStated ->
        AcceptanceAuditRemainingCriteria.Unusable(
          "The audit report states that no production criteria remain but lists open criteria.",
        )
      identities.isNotEmpty() -> AcceptanceAuditRemainingCriteria.Known(identities.toSet())
      completionStated -> AcceptanceAuditRemainingCriteria.Complete
      else ->
        AcceptanceAuditRemainingCriteria.Unusable(
          "The audit report neither lists open criteria by ID nor states \"$COMPLETION_LINE\"",
        )
    }
  }

  private fun declaresSatisfied(trimmed: String): Boolean {
    val statement = trimmed.split(segmentBreak).first()
    return statement.contains("no remaining production gap", ignoreCase = true) ||
      (
        !openCue.containsMatchIn(statement) &&
          (metCue.containsMatchIn(statement) || testOnlyCriterion.containsMatchIn(statement))
      )
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
