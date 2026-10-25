package skillbill.workflow.taskruntime.model.audit.interaction

internal sealed interface AcceptanceCriterionRecheckParse {
  data class Parsed(val entries: List<AcceptanceCriterionRecheckEntry>) : AcceptanceCriterionRecheckParse

  data class Rejected(val reason: String) : AcceptanceCriterionRecheckParse
}

internal object AcceptanceCriterionRecheckParser {
  fun parse(
    prose: String,
    refs: List<String>,
  ): AcceptanceCriterionRecheckParse {
    if (refs.isEmpty()) return AcceptanceCriterionRecheckParse.Parsed(emptyList())
    val lines = prose.lineSequence().mapNotNull { LINE.find(it.trim()) }.map(::recheckLine).toList()
    val unlisted = lines.firstOrNull { it.ref !in refs }
    val duplicated = lines.groupingBy { it.ref }.eachCount().entries.firstOrNull { it.value > 1 }
    val missing = refs.firstOrNull { ref -> lines.none { it.ref == ref } }
    return when {
      unlisted != null -> rejected("Recheck ${unlisted.ref} is not a listed re-check.")
      duplicated != null -> rejected("Recheck ${duplicated.key} appears more than once.")
      missing != null -> rejected("Recheck $missing has no line.")
      else -> entriesOf(refs.map { ref -> lines.single { it.ref == ref } })
    }
  }

  private fun entriesOf(lines: List<RecheckLine>): AcceptanceCriterionRecheckParse {
    val entries =
      lines.map { line ->
        val citations =
          citationsOf(line.rest) ?: return rejected("Recheck ${line.ref} needs `evidence <path:line list>`.")
        AcceptanceCriterionRecheckEntry(line.ref, line.verdict, citations)
      }
    return AcceptanceCriterionRecheckParse.Parsed(entries)
  }

  private fun citationsOf(rest: String): String? {
    val listed = EVIDENCE.matchEntire(rest)?.groupValues?.get(1) ?: return null
    val parts = listed.split(',').map { it.trim().trimEnd('*', '`', '_', '.') }
    return parts.takeIf { list -> list.isNotEmpty() && list.all(CITATION::matches) }?.joinToString(", ")
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
  private val CITATION = Regex("""[\w./\\-]+:\d+(?:-\d+)?""")
}
