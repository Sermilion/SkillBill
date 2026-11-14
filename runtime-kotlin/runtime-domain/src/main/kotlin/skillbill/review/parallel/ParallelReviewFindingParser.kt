package skillbill.review.parallel

import skillbill.review.model.ParallelReviewFindingNormalization
import skillbill.review.model.ParallelReviewFindingNormalizationField
import skillbill.review.model.ParallelReviewFindingRejection
import skillbill.review.model.ParallelReviewFindingRejectionReason
import skillbill.review.model.ParallelReviewParseResult
import skillbill.review.model.ParallelReviewRawFinding
import skillbill.review.model.ParallelReviewSeverity
import skillbill.review.model.ReviewFindingCitationDiagnosticWithFinding
import skillbill.review.model.repositoryRelativePathViolation

object ParallelReviewFindingParser {
  const val PARALLEL_REVIEW_FINDING_ID_MIN: Int = 0

  const val PARALLEL_REVIEW_FINDING_ID_PAD_CHAR: Char = '0'

  const val PARALLEL_REVIEW_FINDING_ID_PAD_WIDTH: Int = 3

  const val PARALLEL_REVIEW_FINDING_ID_MAX: Int = 999

  const val PARALLEL_REVIEW_MIN_SOURCE_LINE: Int = 1

  val parallelFindingPattern: Regex =
    Regex(
      "^\\s*(?:-\\s+)?\\[(?<findingId>F-\\d{${PARALLEL_REVIEW_FINDING_ID_PAD_WIDTH}})]\\s+" +
        "(?<severity>[^|\\r\\n]+?)\\s+\\|\\s+" +
        "(?<confidenceLevel>[^|\\r\\n]+?)\\s+\\|\\s+" +
        "(?:specialist=(?<specialistSkillName>[a-z0-9-]+)(?:\\[[^\\]]*\\])?\\s+\\|\\s+)?" +
        "(?:commits=(?<commits>[^|\\r\\n]+?)\\s+\\|\\s+)?" +
        "(?:path=(?:(?<pathQuoted>\"(?:\\\\.|[^\"\\\\])*\")|(?<pathBare>[^|\\r\\n]+?))\\s+\\|\\s+" +
        "line=(?<line>\\d+)" +
        "|(?<legacyPath>[^|\\r\\n]+?):(?<legacyLine>\\d+))\\s+\\|\\s+" +
        "(?<description>.+)$",
      RegexOption.MULTILINE,
    )

  const val UNASSIGNED_REPOSITORY_PATH = "unassigned"

  val findingCandidatePattern: Regex = Regex("\\[F-\\d+]")

  private val findingNumberPattern: Regex = Regex("\\[F-(\\d+)]")

  private val nearMissFindingIdLine: Regex =
    Regex(
      """^(\s*(?:-\s+)?)(?:\|\s*)?(?:\*{1,2}|_{1,2})?\[F-(\d+)](?:\*{1,2}|_{1,2})?(?:\s*\|)?\s+(.*)$""",
    )

  private const val FINDING_BODY_WITHOUT_ID_PREFIX_GROUP: Int = 1

  private const val FINDING_ID_TRAILING_BODY_GROUP: Int = 3

  private val findingBodyWithoutId: Regex =
    Regex(
      """^(\s*(?:-\s+)?)(?!(?:\|\s*)?(?:\*{1,2}|_{1,2})?\[F-)""" +
        """(?:Major|Minor|Blocker|Nit|Critical)\s+\|\s+(?:High|Medium|Low)\s+\|\s+\S.*$""",
    )

  fun countRegisterCandidates(text: String): Int =
    text.lineSequence().count { findingCandidatePattern.containsMatchIn(it) }

  fun parse(text: String): ParallelReviewParseResult {
    val normalizedText = normalizeRegisterText(text)
    val lines = normalizedText.lines()
    val admitted = mutableListOf<ParallelReviewRawFinding>()
    val admittedLinePositions = mutableListOf<Int>()
    val rejections = mutableListOf<ParallelReviewFindingRejection>()
    val citationDiagnostics = mutableListOf<ReviewFindingCitationDiagnosticWithFinding>()
    val normalizations = mutableListOf<ParallelReviewFindingNormalization>()
    val matchedPositions = mutableSetOf<Int>()
    parallelFindingPattern.findAll(normalizedText).forEach { match ->
      val position = linePositionOfFindingToken(normalizedText, match)
      matchedPositions += position
      val outcome =
        parseMatch(match, position, lines.getOrElse(position - 1) { match.value }.trim())
      outcome.finding?.let {
        admitted += it
        admittedLinePositions += position
      }
      citationDiagnostics += outcome.citationDiagnostics
      normalizations += outcome.normalizations
      outcome.reason?.let { reason ->
        rejections +=
          ParallelReviewFindingRejection(
            lineText = lines.getOrElse(position - 1) { match.value }.trim(),
            linePosition = position,
            reason = reason,
          )
      }
    }
    var candidateCount = 0
    lines.forEachIndexed { index, line ->
      if (!findingCandidatePattern.containsMatchIn(line)) return@forEachIndexed
      candidateCount++
      if (index + 1 in matchedPositions) return@forEachIndexed
      rejections +=
        ParallelReviewFindingRejection(
          lineText = line.trim(),
          linePosition = index + 1,
          reason = ParallelReviewFindingRejectionReason.UNMATCHED_CANDIDATE_LINE,
        )
    }
    return ParallelReviewParseResult(
      findings = admitted,
      rejections = rejections.sortedBy(ParallelReviewFindingRejection::linePosition),
      candidateCount = candidateCount,
      citationDiagnostics = citationDiagnostics,
      admittedLinePositions = admittedLinePositions,
      normalizations = normalizations.sortedBy(ParallelReviewFindingNormalization::linePosition),
    )
  }

  internal fun normalizeRegisterText(text: String): String {
    val working = text.lines().map { normalizeRegisterLine(it) }.toMutableList()
    var next = working.maxOfOrNull(::maxFindingNumberIn) ?: 0
    working.indices.forEach { index ->
      val line = working[index]
      if (!findingCandidatePattern.containsMatchIn(line)) {
        findingBodyWithoutId.matchEntire(line)?.let { body ->
          next = (next + 1).coerceAtMost(PARALLEL_REVIEW_FINDING_ID_MAX)
          val id =
            "F-" +
              next.toString().padStart(
                PARALLEL_REVIEW_FINDING_ID_PAD_WIDTH,
                PARALLEL_REVIEW_FINDING_ID_PAD_CHAR,
              )
          working[index] = "${body.groupValues[FINDING_BODY_WITHOUT_ID_PREFIX_GROUP]}[$id] " +
            line.drop(body.groupValues[FINDING_BODY_WITHOUT_ID_PREFIX_GROUP].length)
        }
      }
    }
    return working.joinToString("\n")
  }

  internal fun normalizeRegisterLine(line: String): String {
    val match = nearMissFindingIdLine.matchEntire(line) ?: return line
    val number = match.groupValues[2].toIntOrNull() ?: return line
    val paddedId =
      "F-" +
        number.coerceIn(PARALLEL_REVIEW_FINDING_ID_MIN, PARALLEL_REVIEW_FINDING_ID_MAX)
          .toString()
          .padStart(PARALLEL_REVIEW_FINDING_ID_PAD_WIDTH, PARALLEL_REVIEW_FINDING_ID_PAD_CHAR)
    return "${match.groupValues[1]}[$paddedId] ${match.groupValues[FINDING_ID_TRAILING_BODY_GROUP]}"
  }

  private fun maxFindingNumberIn(line: String): Int =
    findingNumberPattern.findAll(line).mapNotNull { it.groupValues[1].toIntOrNull() }.maxOrNull() ?: 0

  private fun linePositionOfFindingToken(
    text: String,
    match: MatchResult,
  ): Int {
    val tokenAt = match.value.indexOf('[').takeIf { it >= 0 } ?: 0
    return text.take(match.range.first + tokenAt).count { it == '\n' } + 1
  }

  private data class MatchOutcome(
    val finding: ParallelReviewRawFinding? = null,
    val reason: ParallelReviewFindingRejectionReason? = null,
    val citationDiagnostics: List<ReviewFindingCitationDiagnosticWithFinding> = emptyList(),
    val normalizations: List<ParallelReviewFindingNormalization> = emptyList(),
  )

  private data class ResolvedPath(
    val path: String,
    val reason: ParallelReviewFindingRejectionReason? = null,
  )

  private fun parseMatch(
    match: MatchResult,
    linePosition: Int,
    rawLine: String,
  ): MatchOutcome {
    val severityStr = match.groups["severity"]?.value.orEmpty().trim()
    val severity = normalizeSeverity(severityStr)
    val confidenceStr = match.groups["confidenceLevel"]?.value.orEmpty().trim()
    val confidence = normalizeConfidence(confidenceStr)
    val normalizations =
      buildList {
        if (severity.displayName != severityStr) {
          add(
            ParallelReviewFindingNormalization(
              lineText = rawLine,
              linePosition = linePosition,
              field = ParallelReviewFindingNormalizationField.SEVERITY,
              rawValue = severityStr,
              normalizedValue = severity.displayName,
            ),
          )
        }
        if (confidence != confidenceStr) {
          add(
            ParallelReviewFindingNormalization(
              lineText = rawLine,
              linePosition = linePosition,
              field = ParallelReviewFindingNormalizationField.CONFIDENCE,
              rawValue = confidenceStr,
              normalizedValue = confidence,
            ),
          )
        }
      }
    val resolvedPath = resolvePath(match)
    resolvedPath.reason?.let { return MatchOutcome(reason = it) }
    val path = resolvedPath.path
    val lineText = match.groups["line"]?.value ?: match.groups["legacyLine"]?.value
    val line =
      lineText?.toIntOrNull()?.takeIf { it >= PARALLEL_REVIEW_MIN_SOURCE_LINE }
        ?: return MatchOutcome(reason = ParallelReviewFindingRejectionReason.INVALID_LINE_NUMBER)
    val findingRef = match.groups["findingId"]?.value
    val peeled = peelTrailingStructuredFields(match.groups["description"]?.value.orEmpty().trim())
    return MatchOutcome(
      finding =
        ParallelReviewRawFinding(
          severity = severity,
          confidence = confidence,
          location = "$path:$line",
          description = peeled.description,
          specialistSkillName = match.groups["specialistSkillName"]?.value,
          repositoryPath = path,
          line = line,
          commitShas = parseCommitShas(match.groups["commits"]?.value),
          claimVerdict = peeled.claimVerdict,
          scopeDisposition = peeled.scopeDisposition,
          citations = peeled.citations,
          severityAdjustment = peeled.severityAdjustment,
          sourceFindingRef = findingRef,
          settlement = peeled.settlement,
        ),
      citationDiagnostics =
        peeled.citationDiagnostics.map { diagnostic ->
          diagnostic.withFindingRef(findingRef)
        },
      normalizations = normalizations,
    )
  }

  private fun resolvePath(match: MatchResult): ResolvedPath {
    val quoted = match.groups["pathQuoted"]?.value
    val bare = match.groups["pathBare"]?.value?.trim()
    val decoded =
      when {
        quoted != null ->
          decodeParallelReviewStructuredStringOrNull(quoted) ?: run {
            return ResolvedPath(
              UNASSIGNED_REPOSITORY_PATH,
              ParallelReviewFindingRejectionReason.UNPARSEABLE_STRUCTURED_PATH,
            )
          }
        bare != null -> bare
        else -> match.groups["legacyPath"]?.value?.trim().orEmpty()
      }
    if (decoded.isNotEmpty() && repositoryRelativePathViolation(decoded) == null) return ResolvedPath(decoded)
    return ResolvedPath(UNASSIGNED_REPOSITORY_PATH, ParallelReviewFindingRejectionReason.NO_ADMISSIBLE_LOCATION)
  }

  private fun parseCommitShas(raw: String?): List<String> {
    if (raw.isNullOrBlank()) return emptyList()
    return raw.split(',').map { it.trim() }.filter { it.isNotEmpty() }.distinct()
  }

  private fun normalizeSeverity(raw: String): ParallelReviewSeverity =
    SEVERITY_NORMALIZATIONS[raw.trim().lowercase()] ?: ParallelReviewSeverity.MINOR

  private fun normalizeConfidence(raw: String): String =
    CONFIDENCE_NORMALIZATIONS[raw.trim().lowercase()] ?: CONFIDENCE_FALLBACK

  private const val CONFIDENCE_FALLBACK = "Medium"

  private val SEVERITY_NORMALIZATIONS: Map<String, ParallelReviewSeverity> =
    mapOf(
      "blocker" to ParallelReviewSeverity.BLOCKER,
      "critical" to ParallelReviewSeverity.BLOCKER,
      "severe" to ParallelReviewSeverity.BLOCKER,
      "fatal" to ParallelReviewSeverity.BLOCKER,
      "p0" to ParallelReviewSeverity.BLOCKER,
      "p1" to ParallelReviewSeverity.BLOCKER,
      "major" to ParallelReviewSeverity.MAJOR,
      "high" to ParallelReviewSeverity.MAJOR,
      "significant" to ParallelReviewSeverity.MAJOR,
      "serious" to ParallelReviewSeverity.MAJOR,
      "p2" to ParallelReviewSeverity.MAJOR,
      "minor" to ParallelReviewSeverity.MINOR,
      "medium" to ParallelReviewSeverity.MINOR,
      "moderate" to ParallelReviewSeverity.MINOR,
      "low" to ParallelReviewSeverity.MINOR,
      "warning" to ParallelReviewSeverity.MINOR,
      "info" to ParallelReviewSeverity.MINOR,
      "informational" to ParallelReviewSeverity.MINOR,
      "p3" to ParallelReviewSeverity.MINOR,
      "nit" to ParallelReviewSeverity.NIT,
      "trivial" to ParallelReviewSeverity.NIT,
      "cosmetic" to ParallelReviewSeverity.NIT,
      "suggestion" to ParallelReviewSeverity.NIT,
      "note" to ParallelReviewSeverity.NIT,
    )

  private val CONFIDENCE_NORMALIZATIONS: Map<String, String> =
    mapOf(
      "high" to "High",
      "medium" to "Medium",
      "moderate" to "Medium",
      "med" to "Medium",
      "low" to "Low",
    )
}
