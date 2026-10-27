package skillbill.workflow.taskruntime.model.audit.interaction

internal sealed interface AcceptanceCriterionRecheckParse {
  data class Parsed(
    val entries: List<AcceptanceCriterionRecheckEntry>,
    val ignoredRefs: List<String>,
  ) : AcceptanceCriterionRecheckParse

  data class Rejected(val reason: String) : AcceptanceCriterionRecheckParse
}

internal object AcceptanceCriterionRecheckParser {
  fun parse(
    prose: String,
    refs: List<String>,
  ): AcceptanceCriterionRecheckParse {
    val source = prose.lines()
    val explicit = source.mapNotNull { LINE.find(it.trim()) }.map(::recheckLine)
    val natural =
      source.mapNotNull(::naturalLine).filter { line ->
        explicit.none { it.ref == line.ref && it.verdict == line.verdict }
      }
    val allLines = explicit + natural
    val ignored = allLines.map { it.ref }.filter { it !in refs }.distinct()
    val lines = allLines.filter { it.ref in refs }
    val duplicated = lines.groupingBy { it.ref }.eachCount().entries.firstOrNull { it.value > 1 }
    val missing = refs.firstOrNull { ref -> lines.none { it.ref == ref } }
    return when {
      duplicated != null -> rejected("Recheck ${duplicated.key} appears more than once.")
      missing != null -> rejected("Recheck $missing has no line.")
      else -> entriesOf(refs.map { ref -> lines.single { it.ref == ref } }, ignored)
    }
  }

  private fun entriesOf(
    lines: List<RecheckLine>,
    ignoredRefs: List<String>,
  ): AcceptanceCriterionRecheckParse {
    val entries =
      lines.map { line ->
        val citations =
          citationsOf(line.rest) ?: return rejected("Recheck ${line.ref} needs `evidence <path:line list>`.")
        AcceptanceCriterionRecheckEntry(line.ref, line.verdict, citations)
      }
    return AcceptanceCriterionRecheckParse.Parsed(entries, ignoredRefs)
  }

  private fun citationsOf(rest: String): String? {
    val listed = EVIDENCE.matchEntire(rest)?.groupValues?.get(1) ?: return null
    val parts = listed.split(',').map { it.trim().trimEnd('*', '`', '_', '.') }
    var path: String? = null
    val expanded =
      parts.map { part ->
        when {
          CITATION.matches(part) -> part.also { path = it.substringBeforeLast(':') }
          LINE_RANGE.matches(part) && path != null -> "$path:$part"
          else -> return null
        }
      }
    return expanded.takeIf { it.isNotEmpty() }?.joinToString(", ")
  }

  private fun rejected(reason: String) = AcceptanceCriterionRecheckParse.Rejected(reason)

  private fun recheckLine(match: MatchResult): RecheckLine {
    val verdict =
      AcceptanceCriterionRecheckVerdict.entries.first {
        it.wireValue.equals(match.groupValues[VERDICT_GROUP], ignoreCase = true)
      }
    val rest = match.groupValues[REST_GROUP].trim().trimEnd('*', '`', '_', ' ')
    return RecheckLine(match.groupValues[REF_GROUP].uppercase(), verdict, rest)
  }

  private fun naturalLine(text: String): RecheckLine? {
    val match = NATURAL_LINE.find(text.trim()) ?: return null
    val verdict =
      AcceptanceCriterionRecheckVerdict.entries.first {
        it.wireValue.equals(match.groupValues[VERDICT_GROUP], ignoreCase = true)
      }
    val citations = CITATION.findAll(match.groupValues[REST_GROUP]).map { it.value }.toList()
    return RecheckLine(match.groupValues[REF_GROUP].uppercase(), verdict, "evidence ${citations.joinToString(", ")}")
  }

  private data class RecheckLine(
    val ref: String,
    val verdict: AcceptanceCriterionRecheckVerdict,
    val rest: String,
  )

  private const val REF_GROUP = 1
  private const val VERDICT_GROUP = 2
  private const val REST_GROUP = 3
  private val LINE =
    Regex(
      """^\W*Recheck\s+(AC-\d{3})\s*:\s*\W*(holds|regressed)\b\W*(.*)$""",
      RegexOption.IGNORE_CASE,
    )
  private val EVIDENCE = Regex("""^evidence\s+(.+)$""", RegexOption.IGNORE_CASE)
  private val NATURAL_LINE =
    Regex("""^\W*(?:Recheck\s+)?(AC-\d{3})\s+(?:still\s+)?(holds|regressed)\b[.:]?\s*(.*)$""", RegexOption.IGNORE_CASE)
  private val CITATION = Regex("""[\w./\\-]+:\d+(?:-\d+)?""")
  private val LINE_RANGE = Regex("""\d+(?:-\d+)?""")
}
