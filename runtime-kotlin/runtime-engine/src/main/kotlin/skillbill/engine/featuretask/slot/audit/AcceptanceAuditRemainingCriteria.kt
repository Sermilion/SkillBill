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
  private val findingStart = Regex("""^(?:[-*]\s+|\d+[.)]\s+)?[`*]*(?:S\d+-)?AC-?\d+""", RegexOption.IGNORE_CASE)
  private val label = Regex("""(?<![\w-])(?:S\d+-)?AC-?\d+(?!\w)""", RegexOption.IGNORE_CASE)
  private val emptyList = Regex("""\[\s*]""")
  private val listItem = Regex("""^[-*]\s+|^\d+[.)]\s+""")
  private val segmentBreak = Regex("""(?<=[.!?])\s+|\n""")
  private val negatedGap =
    Regex(
      """\b(?:no|zero|without)\s+(?:(?:remaining|open|further|unmet|production|missing)\s+)*""" +
        """(?:gaps?|criteria|criterion|requirements?|behaviou?r|findings?)\b""" +
        """(?:\s+(?:remain(?:s|ing)?|open|missing|unmet))?""" +
        """|\bnothing\s+(?:remains|is\s+missing|is\s+open)\b""",
      RegexOption.IGNORE_CASE,
    )
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
  private val allMet =
    Regex(
      """\b(?:all|every|each)\b[^.]*\b(?:criteria|criterion|requirements?)\b[^.]*""" +
        """\b(?:met|satisfied|implemented|complete|completed|resolved|fulfilled|covered)\b""",
      RegexOption.IGNORE_CASE,
    )
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
    val satisfied = linkedSetOf<String>()
    val lines = value.replace(Regex(""";\s*(?=(?:S\d+-)?AC-?\d+)""", RegexOption.IGNORE_CASE), "\n").lines()
    val findingIndent =
      lines
        .filter { findingStart.containsMatchIn(it.trim()) }
        .minOfOrNull { it.takeWhile(Char::isWhitespace).length } ?: 0
    for (line in lines) {
      val trimmed = line.trim()
      val rejection =
        when {
          isSatisfiedSummary(trimmed) -> collectSatisfied(trimmed, catalog, satisfied)
          findingStart.containsMatchIn(trimmed) -> collectFinding(trimmed, catalog, identities)
          listItem.containsMatchIn(trimmed) && line.takeWhile(Char::isWhitespace).length <= findingIndent ->
            AcceptanceAuditRemainingCriteria.Unusable("A remaining finding has no accepted criterion identity.")
          else -> null
        }
      if (rejection != null) return rejection
    }
    return when {
      identities.any(satisfied::contains) ->
        AcceptanceAuditRemainingCriteria.Unusable("Audit declares a criterion both open and satisfied.")
      identities.isEmpty() -> parseNarrative(value, catalog)
      else -> AcceptanceAuditRemainingCriteria.Known(identities.toSet())
    }
  }

  private fun isSatisfiedSummary(trimmed: String): Boolean =
    !trimmed.startsWith("-") &&
      !trimmed.startsWith("*") &&
      trimmed.contains("no remaining production gap", ignoreCase = true)

  private fun collectSatisfied(
    trimmed: String,
    catalog: AcceptanceAuditCatalog.Known,
    satisfied: MutableSet<String>,
  ): AcceptanceAuditRemainingCriteria? {
    for (reference in label.findAll(trimmed)) {
      satisfied += catalog.resolve(reference.value)
        ?: return AcceptanceAuditRemainingCriteria.Unusable("Unknown criterion in satisfied summary.")
    }
    return null
  }

  private fun collectFinding(
    trimmed: String,
    catalog: AcceptanceAuditCatalog.Known,
    identities: MutableSet<String>,
  ): AcceptanceAuditRemainingCriteria? {
    val parsed = parseReference(trimmed, catalog)
    if (parsed !is AcceptanceAuditRemainingCriteria.Known) return parsed
    identities += parsed.identities
    return null
  }

  private fun parseNarrative(
    value: String,
    catalog: AcceptanceAuditCatalog.Known,
  ): AcceptanceAuditRemainingCriteria {
    val identities = linkedSetOf<String>()
    var completionStated = false
    for (segment in value.split(segmentBreak).filter(String::isNotBlank)) {
      val settled = negatedGap.replace(segment, "satisfied")
      val declaresMet = metCue.containsMatchIn(settled) && !openCue.containsMatchIn(settled)
      if (declaresMet && (allMet.containsMatchIn(settled) || negatedGap.containsMatchIn(segment))) {
        completionStated = true
      }
      for (reference in label.findAll(segment)) {
        val identity =
          catalog.resolve(reference.value)
            ?: return AcceptanceAuditRemainingCriteria.Unusable(
              "Unknown remaining criterion ${reference.value.take(AcceptanceAuditCatalog.DIAGNOSTIC_LABEL_LIMIT)}.",
            )
        if (!declaresMet) identities += identity
      }
    }
    return when {
      identities.isNotEmpty() -> AcceptanceAuditRemainingCriteria.Known(identities.toSet())
      completionStated -> AcceptanceAuditRemainingCriteria.Complete
      else ->
        AcceptanceAuditRemainingCriteria.Unusable(
          "The audit report names no remaining criterion and does not clearly state that none remain.",
        )
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
