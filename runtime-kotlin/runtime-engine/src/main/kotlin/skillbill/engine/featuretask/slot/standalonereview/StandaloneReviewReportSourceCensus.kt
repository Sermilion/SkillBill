package skillbill.engine.featuretask.slot.standalonereview

import skillbill.review.model.ParallelReviewFindingNormalization
import skillbill.review.model.ParallelReviewParseResult
import skillbill.review.model.ParallelReviewRawFinding
import skillbill.review.parallel.ParallelReviewFindingParser

internal data class StandaloneReviewSourceCandidate(
  val text: String,
  val source: String,
  val finding: ParallelReviewRawFinding?,
  val producerId: String? = null,
  val linePosition: Int? = null,
)

internal data class StandaloneReviewSourceCensus(
  val candidates: List<StandaloneReviewSourceCandidate>,
  val coverage: List<String>,
  val warnings: List<String>,
  val reviewEvidence: List<StandaloneReviewReviewEvidence>,
  val rejections: List<String>,
  val hasIncompleteReviewWarning: Boolean,
  val normalizations: List<ParallelReviewFindingNormalization> = emptyList(),
)

internal data class StandaloneReviewReviewEvidence(
  val text: String,
  val source: String,
  val alreadyIncomplete: Boolean,
)

internal object StandaloneReviewReportSourceCensusBuilder {
  fun hasExplicitCleanDeclaration(output: String): Boolean =
    output.lineSequence().any { line ->
      line.trim().equals("NO_FINDINGS", ignoreCase = true) ||
        CLEAN_PROSE_PATTERN.matches(line.trim())
    }

  fun build(
    output: String,
    source: String,
    strictContext: Boolean,
    producerId: String? = null,
  ): StandaloneReviewSourceCensus {
    val parsed = ParallelReviewFindingParser.parse(output)
    return Scanner(output.lines(), source, strictContext, producerId, parsed).scan()
  }

  private class Scanner(
    private val lines: List<String>,
    private val source: String,
    private val strictContext: Boolean,
    private val producerId: String?,
    private val parsed: ParallelReviewParseResult,
  ) {
    private val structured =
      parsed.admittedLinePositions.zip(parsed.findings).associate { (line, finding) ->
        line - 1 to finding
      }
    private val candidates = mutableListOf<StandaloneReviewSourceCandidate>()
    private val coverage = mutableListOf<String>()
    private val warnings = mutableListOf<String>()
    private val reviewEvidence = mutableListOf<StandaloneReviewReviewEvidence>()
    private val rejections =
      parsed.rejections.map { "line ${it.linePosition}: ${it.reason}" }.toMutableList()
    private val candidatePositions = mutableSetOf<Int>()
    private var hasIncompleteReviewWarning = false

    fun scan(): StandaloneReviewSourceCensus {
      var position = 0
      while (position < lines.size) position = consume(position)
      parsed.rejections.forEach { rejection ->
        if (rejection.linePosition - 1 !in candidatePositions) {
          rejections += "line ${rejection.linePosition}: finding candidate was not admitted"
        }
      }
      return StandaloneReviewSourceCensus(
        candidates,
        coverage,
        warnings,
        reviewEvidence,
        rejections,
        hasIncompleteReviewWarning,
        parsed.normalizations,
      )
    }

    private fun consume(position: Int): Int {
      val trimmed = lines[position].trim()
      return when {
        trimmed.isEmpty() -> position + 1
        isMalformedStructuredStart(trimmed) -> rejectMalformed(position)
        isCoverage(trimmed) -> collectCoverage(position)
        isWarning(trimmed) -> collectWarning(position)
        isStructuredStart(trimmed) -> collectStructured(position)
        isNonExhaustiveCleanStatement(trimmed) -> rejectPartialClean(position)
        isControl(trimmed) || isReviewContext(trimmed) -> skipControl(position)
        startsListItem(trimmed) || isFindingProse(trimmed) -> collectProse(position)
        strictContext -> rejectIntegrationText(position)
        else -> skipProse(position)
      }
    }

    private fun rejectMalformed(position: Int): Int {
      rejections += "line ${position + 1}: malformed finding candidate cannot be admitted"
      return blockEnd(position)
    }

    private fun collectCoverage(position: Int): Int {
      val end = blockEnd(position, rejectAmbiguousContinuation = true, keepCoverageContinuations = true)
      val text = lines.subList(position, end).joinToString("\n").trimEnd()
      coverage += text
      val incomplete = isIncompleteReviewWarning(text)
      if (incomplete) warnings += text
      reviewEvidence += StandaloneReviewReviewEvidence(text, source, incomplete)
      hasIncompleteReviewWarning = hasIncompleteReviewWarning || incomplete
      val line = lines[position].trim()
      if (isFindingProse(line)) {
        rejections += "line ${position + 1}: coverage also contains a finding candidate"
      }
      return end
    }

    private fun collectWarning(position: Int): Int {
      val end = blockEnd(position, rejectAmbiguousContinuation = true)
      val line = lines[position].trim()
      val text = lines.subList(position, end).joinToString("\n").trimEnd()
      warnings += text
      val alreadyIncomplete = isIncompleteReviewWarning(text)
      reviewEvidence += StandaloneReviewReviewEvidence(text, source, alreadyIncomplete)
      hasIncompleteReviewWarning = hasIncompleteReviewWarning || alreadyIncomplete
      if (isFindingProse(line)) {
        rejections += "line ${position + 1}: warning also contains a finding candidate"
      }
      return end
    }

    private fun collectStructured(position: Int): Int {
      val finding = structured[position]
      if (finding == null) {
        rejections += "line ${position + 1}: structured finding was not admitted"
        return blockEnd(position)
      }
      val end = blockEnd(position, rejectAmbiguousContinuation = true)
      addCandidate(position, end, finding)
      return end
    }

    private fun collectProse(position: Int): Int {
      val end = blockEnd(position, rejectAmbiguousContinuation = true, keepFindingMetadata = true)
      addCandidate(position, end, null)
      return end
    }

    private fun addCandidate(
      position: Int,
      end: Int,
      finding: ParallelReviewRawFinding?,
    ) {
      candidates +=
        StandaloneReviewSourceCandidate(
          lines.subList(position, end).joinToString("\n").trimEnd(),
          source,
          finding,
          producerId,
          position + 1,
        )
      for (candidatePosition in position until end) candidatePositions.add(candidatePosition)
    }

    private fun rejectPartialClean(position: Int): Int {
      rejections += "line ${position + 1}: partial clean statement does not establish an empty census"
      return position + 1
    }

    private fun skipControl(position: Int): Int {
      if (isFindingProse(lines[position].trim())) {
        rejections += "line ${position + 1}: control text also contains a finding candidate"
      }
      return position + 1
    }

    private fun rejectIntegrationText(position: Int): Int {
      rejections += "line ${position + 1}: integration report contains unclassified output"
      return blockEnd(position)
    }

    private fun skipProse(position: Int): Int = blockEnd(position)

    private fun blockEnd(
      start: Int,
      rejectAmbiguousContinuation: Boolean = false,
      keepCoverageContinuations: Boolean = false,
      keepFindingMetadata: Boolean = false,
    ): Int {
      var end = start + 1
      val startIndent = indentationOf(lines[start])
      while (end < lines.size) {
        val next = lines[end].trim()
        val coverageContinuation = keepCoverageContinuations && isIncompleteReviewWarning(next)
        if (keepFindingMetadata && FINDING_METADATA_PATTERN.containsMatchIn(next)) {
          end++
          continue
        }
        if (
          next.isEmpty() ||
          isBoundary(next) && !coverageContinuation
        ) {
          return end
        }
        if (
          rejectAmbiguousContinuation && indentationOf(lines[end]) <= startIndent &&
          !coverageContinuation
        ) {
          return end
        }
        end++
      }
      return end
    }
  }
}

private fun indentationOf(line: String): Int = line.takeWhile { it.isWhitespace() }.length

private fun isBoundary(line: String): Boolean =
  isStructuredStart(line) || isMalformedStructuredStart(line) || isCoverage(line) ||
    isWarning(line) || isControl(line) || isReviewContext(line) || startsListItem(line) ||
    isFindingProse(line)

private fun isStructuredStart(line: String): Boolean =
  ParallelReviewFindingParser.findingCandidatePattern.containsMatchIn(line)

private fun isMalformedStructuredStart(line: String): Boolean =
  isStructuredStart(line) && !STRUCTURED_ROW_PATTERN.containsMatchIn(line)

private fun isControl(line: String): Boolean =
  line.equals("NO_FINDINGS", ignoreCase = true) ||
    line.equals("No findings.", ignoreCase = true) ||
    line.matches(Regex("^verdict:\\s*(?:approved|changes_requested)$", RegexOption.IGNORE_CASE)) ||
    line.startsWith("lanes:", ignoreCase = true) ||
    line.matches(
      Regex(
        "^#{1,6}\\s+(?:summary|overview|review|findings|code quality(?: \\(non-blocking\\))?)\\s*$",
        RegexOption.IGNORE_CASE,
      ),
    ) ||
    line.matches(Regex("^(?:Summary|Overview|Review|Findings|Code Quality):$", RegexOption.IGNORE_CASE))

private fun isCoverage(line: String): Boolean = line.startsWith("coverage:", ignoreCase = true)

private fun isWarning(line: String): Boolean =
  line.startsWith("warning:", ignoreCase = true) || isIncompleteReviewWarning(line)

private fun startsListItem(line: String): Boolean =
  LIST_ITEM_PATTERN.containsMatchIn(line) || line.startsWith("|") && line.contains("[F-")

private fun isFindingProse(line: String): Boolean =
  !CLEAN_PROSE_PATTERN.matches(line) &&
    (
      FINDING_PROSE_PATTERN.containsMatchIn(line) || FILE_LOCATION_PATTERN.containsMatchIn(line) ||
        isContextPrefixedFinding(line)
    )

private fun isContextPrefixedFinding(line: String): Boolean =
  CONTEXT_PREFIX_PATTERN.containsMatchIn(line) && !CLEAN_PROSE_PATTERN.matches(line)

private fun isReviewContext(line: String): Boolean =
  REVIEW_CONTEXT_PATTERN.matches(line) || CLEAN_PROSE_PATTERN.matches(line)

private fun isNonExhaustiveCleanStatement(line: String): Boolean = PARTIAL_CLEAN_PROSE_PATTERN.matches(line)

private val LIST_ITEM_PATTERN = Regex("^\\s*(?:[-*+]\\s+|\\d+[.)]\\s+)\\S")
private val FILE_LOCATION_PATTERN = Regex("(?:[\\w./-]+\\.[A-Za-z0-9]{1,12}:\\d+)")
private val FINDING_PROSE_PATTERN =
  Regex(
    "\\b(?:finding|issue|bug|defect|broken|fails?|failure|incorrect|vulnerability|minor|nit|low severity)\\b|" +
      "\\bseverity\\s*[:=]\\s*(?:blocker|critical|major|high|minor|nit|low)\\b",
    RegexOption.IGNORE_CASE,
  )
private val FINDING_METADATA_PATTERN =
  Regex(
    "^(?:severity\\s*[:=]\\s*(?:blocker|critical|major|high|minor|nit|low)|" +
      "specialist\\s*[:=]\\s*[a-z0-9-]+)\\s*$",
    RegexOption.IGNORE_CASE,
  )

internal fun isIncompleteReviewWarning(text: String): Boolean =
  text.split(Regex("[.!?\\n]")).any { clause ->
    val positiveText = NEGATED_SCOPE_GAP_PATTERN.replace(clause, "")
    INCOMPLETE_REVIEW_PATTERN.containsMatchIn(positiveText) ||
      INCOMPLETE_COVERAGE_PATTERN.containsMatchIn(positiveText) ||
      INCOMPLETE_SCOPE_PATTERN.containsMatchIn(positiveText)
  }

private val INCOMPLETE_REVIEW_PATTERN =
  Regex(
    "\\breview (?:was |is )?incomplete\\b|\\bincomplete review\\b|\\bnot (?:fully )?reviewed\\b|" +
      "\\breview (?:was|is|has|had) not (?:been )?completed\\b|" +
      "\\breview (?:could not|couldn't|cannot|can't|was unable to|was not able to|wasn't able to|did not|didn't) " +
      "(?:be )?complet(?:e|ed)\\b|" +
      "\\b(?:review|inspection) (?:was|is|has been|had been) " +
      "(?:not (?:been )?(?:finished|completed)|never (?:been )?(?:finished|completed)|interrupted)\\b|" +
      "\\b(?:review|inspection) (?:never|did not|didn't) (?:finish(?:ed)?|complete(?:d)?)\\b|" +
      "\\b(?:review|inspection) (?:stopped|ended) (?:too )?early\\b|" +
      "\\b(?:review|inspection) ended before completion\\b|" +
      "\\b(?:could not|couldn't|unable to|was not able to|wasn't able to|did not|didn't) " +
      "(?:inspect|review|read|open|access|load|fetch)\\b|" +
      "\\bpartial(?:ly)?\\s+(?:review(?:ed)?|inspect(?:ed|ion)?|coverage)\\b|" +
      "\\b(?:review|inspection|coverage)\\s+(?:was |is )?partial\\b|" +
      "\\b(?:unreviewed|uninspected|not inspected|not reviewed)\\b|" +
      "\\b(?:review|inspection|coverage|lane|area|file|unit)\\s+(?:was |is )?skipped\\b|" +
      "\\bskipped\\s+(?:review|inspection|coverage|lane|area|file|unit)\\b",
    RegexOption.IGNORE_CASE,
  )
private val INCOMPLETE_COVERAGE_PATTERN =
  Regex(
    "\\bcoverage\\s*:\\s*(?:not clean|partial|incomplete|skipped)\\b|\\bincomplete coverage\\b|" +
      "\\bpartial coverage\\b",
    RegexOption.IGNORE_CASE,
  )
private val INCOMPLETE_SCOPE_PATTERN =
  Regex(
    "\\b(?:excluded|omitted|left out|skipped|missed|not examined|not checked|not covered|" +
      "not inspected|not reviewed|not verified|not accessible|inaccessible|unavailable)\\b" +
      "[^.!?\\n]{0,80}\\b(?:from|for|in|of|during)\\s+(?:the\\s+)?" +
      "(?:review|scope|inspection|verification|assessment|coverage)\\b|" +
      "\\b(?:review|scope|inspection|verification|assessment|coverage)\\b[^.!?\\n]{0,80}\\b" +
      "(?:excluded|omitted|left out|skipped|missed|not examined|not checked|not covered|" +
      "not inspected|not reviewed|not verified|not accessible|inaccessible|unavailable)\\b|" +
      "\\b(?:[\\w./-]+(?:[\\s-]+[\\w./-]+){0,5}\\s+)?(?:was|were|is|are)\\s+" +
      "(?:out of scope|outside the review scope)\\b|" +
      "\\b(?:the\\s+)?(?:[\\w./-]+(?:[\\s-]+[\\w./-]+){0,4}\\s+)?" +
      "(?:module|area|file|path|package|component|check|test|patch|diff|change|section|portion|part)s?\\s+" +
      "(?:was|were|is|are|has been|have been|had been)\\s+(?:excluded|omitted|left out|skipped|missed|" +
      "unavailable|inaccessible|not examined|not checked|not covered|not inspected|not reviewed|not verified)\\b|" +
      "\\b(?:[\\w./-]+(?:[\\s-]+[\\w./-]+){0,5}\\s+)?(?:could not|couldn't|was unable to|" +
      "were unable to|was not able to|were not able to|cannot|can't|did not|didn't)\\s+" +
      "(?:be\\s+)?(?:inspect(?:ed)?|examin(?:ed|e)|check(?:ed)?|review(?:ed)?|verify|verified|" +
      "cover(?:ed)?|assess(?:ed)?|access(?:ed)?)\\b",
    RegexOption.IGNORE_CASE,
  )
private val NEGATED_SCOPE_GAP_PATTERN =
  Regex(
    "\\b(?:no|none of the|not)\\b[^,;]{0,60}\\b(?:excluded|omitted|left out|skipped|missed|" +
      "unavailable|inaccessible|not examined|not checked|not covered|not inspected|not reviewed|not verified)\\b" +
      "[^.!?\\n]{0,60}\\b(?:from|for|in|of|during)\\s+(?:the\\s+)?" +
      "(?:review|scope|inspection|verification|assessment|coverage)\\b",
    RegexOption.IGNORE_CASE,
  )
private val CLEAN_PROSE_PATTERN =
  Regex(
    "(?:No (?:findings|issues|bugs)(?: were (?:found|observed|detected))?\\.?|" +
      "Could not find any issues\\.?|Couldn't find any issues\\.?)",
    RegexOption.IGNORE_CASE,
  )
private val PARTIAL_CLEAN_PROSE_PATTERN =
  Regex(
    "No (?:(?:high|low|minor|nit|blocker|critical|major)[- ]severity|actionable|blocking) " +
      "(?:findings|issues|bugs)\\.?",
    RegexOption.IGNORE_CASE,
  )
private val REVIEW_CONTEXT_PATTERN =
  Regex(
    "^(?:I reviewed|I inspected|I checked|Reviewed|Review completed)(?: the (?:change|patch|code|repository))?\\.?$|" +
      "^Actionable$|^No findings?\\.?$",
    RegexOption.IGNORE_CASE,
  )
private val CONTEXT_PREFIX_PATTERN =
  Regex("^(?:The change is|This change is|Overall,)\\s+.+$", RegexOption.IGNORE_CASE)
private val STRUCTURED_ROW_PATTERN = Regex("^\\s*(?:-\\s+)?\\[F-\\d+]\\s+.+$")
