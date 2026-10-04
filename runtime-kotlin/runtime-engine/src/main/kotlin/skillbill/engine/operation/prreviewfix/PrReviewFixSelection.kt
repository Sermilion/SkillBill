package skillbill.engine.operation.prreviewfix

import skillbill.engine.operation.core.OperationOutcome
import skillbill.engine.operation.core.invalidSelection

internal sealed interface PrReviewFixSelection {
  data class Selected(val threads: List<PrReviewFixSelectedThread>) : PrReviewFixSelection

  data class Invalid(val usage: OperationOutcome.Usage) : PrReviewFixSelection
}

internal fun parsePrReviewFixSelection(
  select: String,
  ordinals: Map<String, String>,
): PrReviewFixSelection {
  val trimmed = select.trim()
  return when (trimmed) {
    "" -> PrReviewFixSelection.Invalid(invalidSelection(select, "it names no thread."))
    ALL_RECOMMENDED ->
      PrReviewFixSelection.Selected(
        ordinals.map { (ordinal, id) -> PrReviewFixSelectedThread(ordinal, id, RECOMMENDED_OPTION) },
      )
    FIX_ALL_UNRESOLVED ->
      PrReviewFixSelection.Selected(
        ordinals.map { (ordinal, id) -> PrReviewFixSelectedThread(ordinal, id, FIX_AS_ASKED_OPTION) },
      )
    else -> explicitSelection(trimmed, ordinals)
  }
}

private fun explicitSelection(
  select: String,
  ordinals: Map<String, String>,
): PrReviewFixSelection {
  val selected =
    select.split(',').map { entry ->
      selectedEntry(select, entry, ordinals) { return PrReviewFixSelection.Invalid(it) }
    }
  val repeated = selected.groupingBy(PrReviewFixSelectedThread::ordinal).eachCount().filterValues { it > 1 }.keys
  if (repeated.isNotEmpty()) {
    val detail = "thread ${repeated.first()} is selected more than once."
    return PrReviewFixSelection.Invalid(invalidSelection(select, detail))
  }
  return PrReviewFixSelection.Selected(selected.sortedBy { thread -> ordinals.keys.indexOf(thread.ordinal) })
}

private inline fun selectedEntry(
  select: String,
  entry: String,
  ordinals: Map<String, String>,
  refuse: (OperationOutcome.Usage) -> Nothing,
): PrReviewFixSelectedThread {
  val thread = entry.substringBefore('=', missingDelimiterValue = "").trim()
  val option = entry.substringAfter('=', missingDelimiterValue = "").trim()
  entryShapeProblem(entry, thread, option)?.let { reason -> refuse(invalidSelection(select, reason)) }
  val ordinal =
    ordinals.keys.firstOrNull { known -> known.equals(thread, ignoreCase = true) }
      ?: ordinals.entries.firstOrNull { (_, id) -> id == thread }?.key
      ?: refuse(
        invalidSelection(
          select,
          "'$thread' is not an actionable thread of this analysis (known: ${ordinals.keys.joinToString(", ")}).",
        ),
      )
  return PrReviewFixSelectedThread(ordinal, ordinals.getValue(ordinal), option)
}

private fun entryShapeProblem(
  entry: String,
  thread: String,
  option: String,
): String? =
  when {
    thread.isEmpty() || option.isEmpty() -> "'${entry.trim()}' is not <thread>=<option>."
    !OPTION_NUMBER.matches(option) -> "option '$option' is not a matrix option number (1, 2, ...)."
    else -> null
  }

internal const val SELECTION_FORMS: String =
  "select:all-recommended, select:fix-all-unresolved, or select:<thread>=<option>,... (thread T1..Tn or node id)"

private const val ALL_RECOMMENDED = "all-recommended"
private const val FIX_ALL_UNRESOLVED = "fix-all-unresolved"
private const val RECOMMENDED_OPTION = "1"
private val OPTION_NUMBER = Regex("""[1-9]\d*""")
internal const val FIX_AS_ASKED_OPTION: String = "fix-as-asked"
