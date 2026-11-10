package skillbill.workflow.taskruntime.model.audit

import skillbill.workflow.model.repair.FocusedRepairStrategy

object AcceptanceRepairBlockReason {
  const val MAX_CHARS: Int = 8000

  fun build(
    state: AcceptanceCriterionRepairState,
    criterionRef: String,
  ): String {
    val criterion = state.criteria.firstOrNull { it.canonicalRef == criterionRef }
    val canonicalRef = criterion?.canonicalRef ?: criterionRef
    val alias =
      criterion?.originalAlias?.takeIf(String::isNotBlank)
        ?.let { " (${shorten(it, MAX_ALIAS_CHARS)})" }.orEmpty()
    val charged =
      state.outcomes.filter { it.criterionRef == criterionRef && it.charged }
        .takeLast(FocusedRepairStrategy.MAX_UNSUCCESSFUL_OUTCOMES)
    val fixedLines =
      listOf("Criterion $canonicalRef$alias used its three charged repair attempts.") +
        charged.map { outcome ->
          "${shorten(outcome.outcomeId, MAX_OUTCOME_ID_CHARS)} ${outcome.kind.wireValue} " +
            "open gaps ${outcome.openGapsBefore} -> ${outcome.openGapsAfter}"
        } + OPERATOR_RETRY
    val gaps = criterion?.gaps.orEmpty().filter { it.status == AcceptanceCriterionGapStatus.OPEN }
    val fullGapLines = gaps.map(::gapLine)
    val full = (listOf(fixedLines.first()) + fullGapLines + fixedLines.drop(1)).joinToString("\n")
    if (full.length <= MAX_CHARS) return full

    val fixedLength = fixedLines.sumOf(String::length) + fixedLines.size - 1
    val gapSeparatorLength = gaps.size
    val gapLineBudget =
      ((MAX_CHARS - fixedLength - gapSeparatorLength).coerceAtLeast(0) / gaps.size.coerceAtLeast(1))
    val shortened = gaps.map { gap -> shortenedGapLine(gap, gapLineBudget) }
    return (listOf(fixedLines.first()) + shortened + fixedLines.drop(1)).joinToString("\n")
  }

  private fun gapLine(gap: AcceptanceCriterionGap): String = "${gap.id}: ${gap.text} @ ${gap.citation}"

  private fun shortenedGapLine(
    gap: AcceptanceCriterionGap,
    budget: Int,
  ): String {
    val prefix = "${gap.id}: "
    val available = (budget - prefix.length).coerceAtLeast(0)
    val separator = " @ "
    val text = gap.text
    val citation = gap.citation
    val citationReserve = minOf(citation.length, (available - separator.length - ELLIPSIS.length).coerceAtLeast(0))
    val textBudget = (available - separator.length - citationReserve).coerceAtLeast(0)
    val shortenedText = shorten(text, textBudget)
    val citationBudget = (available - shortenedText.length - separator.length).coerceAtLeast(0)
    return prefix + shortenedText + separator + shorten(citation, citationBudget)
  }

  private fun shorten(
    value: String,
    limit: Int,
  ): String =
    if (value.length <= limit) {
      value
    } else if (limit <= ELLIPSIS.length) {
      value.take(limit)
    } else {
      value.take(limit - ELLIPSIS.length) + ELLIPSIS
    }

  private const val OPERATOR_RETRY = "An operator retry resets the charged count for this criterion only."
  private const val ELLIPSIS = "..."
  private const val MAX_ALIAS_CHARS = 96
  private const val MAX_OUTCOME_ID_CHARS = 96
}
