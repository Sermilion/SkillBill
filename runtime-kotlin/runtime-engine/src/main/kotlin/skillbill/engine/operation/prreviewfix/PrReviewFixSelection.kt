package skillbill.engine.operation.prreviewfix

import skillbill.error.operation.InvalidOperationSelectionError

/** One selected thread: its ordinal, its GitHub node id, and the matrix option to apply. */
internal data class PrReviewFixSelectedThread(
  val ordinal: String,
  val threadId: String,
  val option: String,
)

/**
 * Resolves `select:` against the stored ordinals. `all-recommended` applies option 1 (the matrix's recommended
 * option) to every actionable thread; `fix-all-unresolved` applies the reviewer's requested change to every actionable
 * thread; `<thread>=<option>,...` names threads by ordinal (`T3`) or node id. A thread that is unknown or was already
 * handled at analysis, a repeated thread, an option that is not a matrix option number, or an empty selection is a
 * usage error; the token stays unconsumed.
 */
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
  if (thread.isEmpty() || option.isEmpty()) {
    throw InvalidOperationSelectionError(select, "'${entry.trim()}' is not <thread>=<option>.")
  }
  if (!OPTION_NUMBER.matches(option)) {
    throw InvalidOperationSelectionError(select, "option '$option' is not a matrix option number (1, 2, ...).")
  }
  val ordinal =
    ordinals.keys.firstOrNull { known -> known.equals(thread, ignoreCase = true) }
      ?: ordinals.entries.firstOrNull { (_, id) -> id == thread }?.key
      ?: throw InvalidOperationSelectionError(
        select,
        "'$thread' is not an actionable thread of this analysis (known: ${ordinals.keys.joinToString(", ")}).",
      )
  return PrReviewFixSelectedThread(ordinal, ordinals.getValue(ordinal), option)
}

internal const val SELECTION_FORMS: String =
  "select:all-recommended, select:fix-all-unresolved, or select:<thread>=<option>,... (thread T1..Tn or node id)"

private const val ALL_RECOMMENDED = "all-recommended"
private const val FIX_ALL_UNRESOLVED = "fix-all-unresolved"
private const val RECOMMENDED_OPTION = "1"
private val OPTION_NUMBER = Regex("""[1-9]\d*""")
internal const val FIX_AS_ASKED_OPTION: String = "fix-as-asked"
