package skillbill.engine.operation.prreviewfix

import skillbill.engine.operation.core.InvalidOperationSelectionError

internal fun parsePrReviewFixSelection(
  select: String,
  ordinals: Map<String, String>,
): List<PrReviewFixSelectedThread> {
  val trimmed = select.trim()
  return when (trimmed) {
    "" -> throw InvalidOperationSelectionError(select, "it names no thread.")
    ALL_RECOMMENDED -> ordinals.map { (ordinal, id) -> PrReviewFixSelectedThread(ordinal, id, RECOMMENDED_OPTION) }
    FIX_ALL_UNRESOLVED -> ordinals.map { (ordinal, id) -> PrReviewFixSelectedThread(ordinal, id, FIX_AS_ASKED_OPTION) }
    else -> explicitSelection(trimmed, ordinals)
  }
}

private fun explicitSelection(
  select: String,
  ordinals: Map<String, String>,
): List<PrReviewFixSelectedThread> {
  val selected = select.split(',').map { entry -> selectedEntry(select, entry, ordinals) }
  val repeated = selected.groupingBy(PrReviewFixSelectedThread::ordinal).eachCount().filterValues { it > 1 }.keys
  if (repeated.isNotEmpty()) {
    throw InvalidOperationSelectionError(select, "thread ${repeated.first()} is selected more than once.")
  }
  return selected.sortedBy { thread -> ordinals.keys.indexOf(thread.ordinal) }
}

private fun selectedEntry(
  select: String,
  entry: String,
  ordinals: Map<String, String>,
): PrReviewFixSelectedThread {
  val thread = entry.substringBefore('=', missingDelimiterValue = "").trim()
  val option = entry.substringAfter('=', missingDelimiterValue = "").trim()
  entryShapeProblem(entry, thread, option)?.let { reason -> throw InvalidOperationSelectionError(select, reason) }
  val ordinal =
    ordinals.keys.firstOrNull { known -> known.equals(thread, ignoreCase = true) }
      ?: ordinals.entries.firstOrNull { (_, id) -> id == thread }?.key
      ?: throw InvalidOperationSelectionError(
        select,
        "'$thread' is not an actionable thread of this analysis (known: ${ordinals.keys.joinToString(", ")}).",
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
