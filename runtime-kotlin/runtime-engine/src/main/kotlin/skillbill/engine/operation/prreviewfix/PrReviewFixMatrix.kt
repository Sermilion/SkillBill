package skillbill.engine.operation.prreviewfix

import skillbill.contracts.operationcard.OperationCardDetails
import skillbill.contracts.operationcard.OperationCardOption
import skillbill.contracts.operationcard.OperationCardThread
import skillbill.engine.operation.core.OperationArguments
import skillbill.engine.operation.core.OperationOutcome
import skillbill.engine.operation.core.OperationRefusal
import skillbill.engine.operation.core.invalidSelection

/** What the runtime could read out of an analysis matrix, never guessed: every actionable thread or nothing. */
internal sealed interface PrReviewFixMatrixExtraction {
  data class Extracted(val threads: List<OperationCardThread>) : PrReviewFixMatrixExtraction

  data class Malformed(val problems: List<String>) : PrReviewFixMatrixExtraction
}

internal object PrReviewFixMatrix {
  fun extract(
    matrix: String,
    ordinals: Map<String, String>,
  ): PrReviewFixMatrixExtraction {
    val blocks = MatrixReader(ordinals).read(matrix)
    val problems = mutableListOf<String>()
    val threads =
      ordinals.map { (ordinal, threadId) ->
        OperationCardThread(ordinal, threadId, validOptions(ordinal, blocks[ordinal].orEmpty(), problems))
      }
    return if (problems.isEmpty()) {
      PrReviewFixMatrixExtraction.Extracted(threads)
    } else {
      PrReviewFixMatrixExtraction.Malformed(problems)
    }
  }

  fun details(
    arguments: OperationArguments,
    mode: String,
    pullRequest: String?,
    threads: List<OperationCardThread>,
  ): OperationCardDetails.PrReviewFix =
    OperationCardDetails.PrReviewFix(
      mode = mode,
      pullRequest = pullRequest,
      push = arguments.push,
      replies = arguments.replies,
      threads = threads,
    )

  fun malformed(
    summary: String,
    problems: List<String>,
  ): OperationOutcome.Failed =
    OperationOutcome.Failed(
      "$summary\nThe analysis did not produce a selectable option matrix (${problems.joinToString("; ")}). " +
        "Nothing was proposed; run pr-review-fix again for a new proposal.",
    )

  fun offeredOptionRefusal(
    token: String,
    select: String,
    matrix: String,
    ordinals: Map<String, String>,
    selected: List<PrReviewFixSelectedThread>,
  ): OperationRefusal? =
    when (val extraction = extract(matrix, ordinals)) {
      is PrReviewFixMatrixExtraction.Malformed ->
        OperationOutcome.Blocked(
          "Proposal '$token' carries no readable option matrix (${extraction.problems.joinToString("; ")}); " +
            "run pr-review-fix again for a new proposal.",
        )
      is PrReviewFixMatrixExtraction.Extracted -> {
        val offered = extraction.threads.associate { it.ordinal to it.options }
        selected
          .firstOrNull { thread -> !isOffered(thread, offered[thread.ordinal].orEmpty()) }
          ?.let { thread ->
            invalidSelection(select, "option '${thread.option}' is not offered for thread ${thread.ordinal}.")
          }
      }
    }

  private fun isOffered(
    thread: PrReviewFixSelectedThread,
    offered: List<OperationCardOption>,
  ): Boolean = thread.option == FIX_AS_ASKED_OPTION || offered.any { it.optionId == thread.option }

  private fun validOptions(
    ordinal: String,
    blocks: List<List<MatrixOption>>,
    problems: MutableList<String>,
  ): List<OperationCardOption> {
    val before = problems.size
    val options = blocks.singleOrNull()
    when {
      blocks.isEmpty() -> problems += "thread $ordinal has no option list"
      options == null -> problems += "thread $ordinal has more than one option list"
      else -> problems += optionProblems(ordinal, options)
    }
    return if (options == null || problems.size > before) {
      emptyList()
    } else {
      options.map { OperationCardOption(it.number, it.label, it.number == RECOMMENDED_OPTION) }
    }
  }

  private fun optionProblems(
    ordinal: String,
    options: List<MatrixOption>,
  ): List<String> =
    buildList {
      if (options.isEmpty()) add("thread $ordinal lists no options")
      options
        .filter { it.label.isBlank() }
        .forEach { add("option ${it.number} of thread $ordinal has no description") }
      options
        .groupingBy(MatrixOption::number)
        .eachCount()
        .filterValues { it > 1 }
        .keys
        .forEach { add("thread $ordinal repeats option $it") }
      if (options.isNotEmpty() && options.none { it.number == RECOMMENDED_OPTION }) {
        add("thread $ordinal has no option $RECOMMENDED_OPTION")
      }
      options
        .filter { it.recommended && it.number != RECOMMENDED_OPTION }
        .forEach { add("thread $ordinal marks option ${it.number} recommended; option 1 is the recommended one") }
    }
}

private data class MatrixOption(
  val number: String,
  val label: String,
  val recommended: Boolean,
  val indent: Int,
)

private class MatrixReader(private val ordinals: Map<String, String>) {
  private val blocks = mutableMapOf<String, MutableList<MutableList<MatrixOption>>>()
  private var ordinal: String? = null
  private var block: MutableList<MatrixOption>? = null

  fun read(matrix: String): Map<String, List<List<MatrixOption>>> {
    matrix.lines().forEach(::accept)
    return blocks
  }

  private fun accept(line: String) {
    val heading = THREAD_HEADING.find(line)
    when {
      heading != null -> {
        ordinal = resolve(heading.groupValues[1])
        block = null
      }
      ordinal == null -> Unit
      block == null -> if (OPTIONS_LABEL.matches(line)) openBlock()
      else -> acceptInBlock(line, requireNotNull(block))
    }
  }

  private fun acceptInBlock(
    line: String,
    open: MutableList<MatrixOption>,
  ) {
    val option = OPTION_LINE.matchEntire(line)
    when {
      REPLY_LABEL.matches(line) -> block = null
      OPTIONS_LABEL.matches(line) -> openBlock()
      option != null -> open += option.toOption()
      line.isBlank() -> Unit
      open.isNotEmpty() && indentOf(line) > open.last().indent ->
        open[open.lastIndex] = open.last().let { it.copy(label = "${it.label} ${line.trim()}".trim()) }
      else -> block = null
    }
  }

  private fun openBlock() {
    val opened = mutableListOf<MatrixOption>()
    blocks.getOrPut(requireNotNull(ordinal)) { mutableListOf() }.add(opened)
    block = opened
  }

  private fun resolve(label: String): String? {
    val candidates = listOf(label, label.trimEnd('_'))
    return candidates.firstNotNullOfOrNull { candidate ->
      ordinals.keys.firstOrNull { it.equals(candidate, ignoreCase = true) }
        ?: ordinals.entries.firstOrNull { it.value == candidate }?.key
    }
  }

  private fun MatchResult.toOption(): MatrixOption {
    val (indent, number, rawText) = destructured
    val text = rawText.trim()
    val marker = RECOMMENDED_MARKER.find(text)
    return MatrixOption(
      number = number,
      label = text.removePrefix(marker?.value.orEmpty()).trim(),
      recommended = marker != null,
      indent = indent.length,
    )
  }

  private fun indentOf(line: String): Int = line.length - line.trimStart().length

  private companion object {
    val THREAD_HEADING =
      Regex(
        """^\s*(?:#{1,6}\s+|[-*+]\s+)?[*_`]*\s*Thread\s+([^\s—–:`*]+)[*_`]*\s*(?:[—–:]|\s-(?:\s|$)|$)""",
        RegexOption.IGNORE_CASE,
      )
    val OPTIONS_LABEL = decoratedLabel("Options")
    val REPLY_LABEL = decoratedLabel("Proposed reply")
    val OPTION_LINE = Regex("""^(\s*)(?:[-*+]\s+)?\*{0,2}([1-9]\d*)\s*[.)]\*{0,2}\s+(.*)$""")
    val RECOMMENDED_MARKER = Regex("""^[*_]{0,2}\(recommended\)[*_]{0,2}\s*""", RegexOption.IGNORE_CASE)

    fun decoratedLabel(text: String): Regex =
      Regex("""^\s*(?:#{1,6}\s*|[-*+]\s+)?[*_]{0,2}$text[*_]{0,2}\s*:?[*_]{0,2}\s*$""", RegexOption.IGNORE_CASE)
  }
}
