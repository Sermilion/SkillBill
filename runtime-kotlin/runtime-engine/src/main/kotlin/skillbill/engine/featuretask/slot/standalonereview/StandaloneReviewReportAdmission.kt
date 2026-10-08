package skillbill.engine.featuretask.slot.standalonereview

import skillbill.application.review.model.ParallelCodeReviewResult
import skillbill.ports.review.model.ReviewIntegrationPassOutcome
import skillbill.review.context.model.accounting.ReviewIntegrationTerminalOutcome
import skillbill.review.model.ParallelReviewMergedFinding
import skillbill.review.model.ParallelReviewSeverity
import skillbill.review.model.ReviewLaneReviewDisposition
import skillbill.review.parallel.ParallelReviewFindingParser
import skillbill.review.parallel.ParallelReviewMerger
import skillbill.workflow.model.validation.FeatureTaskRuntimeVerdict

internal data class StandaloneReviewReport(
  val rawOutput: String,
  val registerOutput: String,
  val verdict: String?,
  val findings: List<ParallelReviewMergedFinding>,
  val diagnostics: List<String>,
  val rejectionReasons: List<String>,
) {
  val admitted: Boolean get() = rejectionReasons.isEmpty()
}

internal object StandaloneReviewReportAdmission {
  fun admit(
    rawOutput: String,
    result: ParallelCodeReviewResult,
    truncated: Boolean,
    requireDelegatedCoverage: Boolean = false,
  ): StandaloneReviewReport {
    val parsed = ParallelReviewFindingParser.parse(rawOutput)
    val token = declaredVerdict(rawOutput)
    val reasons =
      buildList {
        if (rawOutput.isBlank()) add("report output is blank")
        if (truncated) add("report output was truncated")
        if (token == null) add("report must declare one verdict: approved or verdict: changes_requested")
        if (requireDelegatedCoverage) addAll(delegatedRejections(result))
      }
    val severe =
      (parsed.findings.map { it.severity } + result.mergeResult.findings.map { it.severity })
        .any { it == ParallelReviewSeverity.BLOCKER || it == ParallelReviewSeverity.MAJOR }
    val unparsedCandidates =
      parsed.rejections.isNotEmpty() || hasMalformedFindingIdentifier(rawOutput) || result.rejectedCandidateCount > 0
    val contradictsApproval = severe || unparsedCandidates || proseContradictsApproval(rawOutput)
    val finalVerdict =
      if (token != null && contradictsApproval) {
        FeatureTaskRuntimeVerdict.CHANGES_REQUESTED.wireValue
      } else {
        token
      }
    return StandaloneReviewReport(
      rawOutput = rawOutput,
      registerOutput =
        buildString {
          append(registerBody(rawOutput, result, requireDelegatedCoverage))
          if (finalVerdict != null) {
            appendLine()
            append("verdict: $finalVerdict")
          }
        },
      verdict = finalVerdict,
      findings = result.mergeResult.findings,
      diagnostics =
        (parsed.citationDiagnostics.map { it.toString() } + parsed.rejections.map { it.toString() })
          .take(MAX_REPORT_DIAGNOSTICS)
          .map { it.take(MAX_DIAGNOSTIC_CHARS) },
      rejectionReasons = reasons,
    )
  }

  private fun declaredVerdict(rawOutput: String): String? =
    rawOutput.lineSequence()
      .map { it.trim().lowercase() }
      .filter { it.startsWith(VERDICT_PREFIX) }
      .map { it.removePrefix(VERDICT_PREFIX).trim() }
      .toSet()
      .singleOrNull()
      ?.takeIf { it in CANONICAL_VERDICTS }

  private fun proseContradictsApproval(rawOutput: String): Boolean =
    proseLines(rawOutput).any { line ->
      INCOMPLETE_REVIEW_PATTERN.containsMatchIn(line) || SEVERE_PROSE_FINDING_PATTERN.containsMatchIn(line)
    }

  private fun proseLines(rawOutput: String): List<String> =
    rawOutput.lines()
      .map(String::trim)
      .filter { line ->
        line.isNotEmpty() &&
          line != NO_FINDINGS &&
          !line.lowercase().startsWith(VERDICT_PREFIX) &&
          !ParallelReviewFindingParser.parallelFindingPattern.matches(line)
      }

  private fun registerBody(
    rawOutput: String,
    result: ParallelCodeReviewResult,
    requireDelegatedCoverage: Boolean,
  ): String {
    if (!requireDelegatedCoverage && proseLines(rawOutput).isNotEmpty()) {
      return rawOutput.lines()
        .filterNot { it.trim().lowercase().startsWith(VERDICT_PREFIX) }
        .joinToString("\n")
        .trim()
    }
    return ParallelReviewMerger.formattedOutput(result.mergeResult.findings).ifBlank { NO_FINDINGS }
  }

  private fun integrationRejections(outcome: ReviewIntegrationPassOutcome): List<String> =
    buildList {
      val integrationReport = ParallelReviewFindingParser.parse(outcome.rawOutput)
      if (outcome.outputTruncated) add("delegated integration output was truncated")
      if (integrationReport.rejections.isNotEmpty() || hasMalformedFindingIdentifier(outcome.rawOutput)) {
        add("delegated integration report contains rejected finding candidates")
      }
      if (integrationReport.findings.size != outcome.findings.size) {
        add("delegated integration report contains findings outside its commit sequence")
      }
    }

  private fun hasMalformedFindingIdentifier(output: String): Boolean =
    output.lineSequence().any { line ->
      val candidate = line.trimStart().removePrefix("-").trimStart()
      candidate.startsWith("[F-") && !ParallelReviewFindingParser.findingCandidatePattern.containsMatchIn(candidate)
    }

  private fun delegatedRejections(result: ParallelCodeReviewResult): List<String> =
    buildList {
      result.analysisStageFailures.forEach { failure ->
        val finding = failure.findingRef?.let { "finding $it: " }.orEmpty()
        add(
          "delegated ${failure.stage.wireValue} analysis failed (${failure.reason.wireValue}): " +
            "$finding${failure.detail}",
        )
      }
      if (!result.lane1.success) add(result.lane1.failureReason ?: "delegated parent execution failed")
      if (result.lane1.reviewDisposition != ReviewLaneReviewDisposition.COMPLETE) {
        add("delegated parent report disposition is incomplete")
      }
      if (result.coverage?.isCleanCoverage != true) add("delegated review coverage is missing or incomplete")
      val integration = result.integration
      integration?.let { addAll(integrationRejections(it)) }
      when {
        integration == null -> add("delegated integration stage is missing")
        integration.completed -> Unit
        integration.terminalOutcome ==
          ReviewIntegrationTerminalOutcome.SKIPPED_NOT_APPLICABLE &&
          !integration.skipReason.isNullOrBlank() -> Unit
        else -> add(integration.failureReason ?: "delegated integration stage is incomplete")
      }
    }
}

private const val VERDICT_PREFIX = "verdict:"
private const val NO_FINDINGS = "NO_FINDINGS"
private val CANONICAL_VERDICTS =
  setOf(FeatureTaskRuntimeVerdict.APPROVED.wireValue, FeatureTaskRuntimeVerdict.CHANGES_REQUESTED.wireValue)
private val INCOMPLETE_REVIEW_PATTERN =
  Regex(
    "\\breview (?:was |is )?incomplete\\b|\\bincomplete review\\b|\\bnot (?:fully )?reviewed\\b|" +
      "\\b(?:could not|couldn't|unable to|was not able to|wasn't able to) " +
      "(?:inspect|review|read|open|access|load|fetch)\\b",
    RegexOption.IGNORE_CASE,
  )
private val SEVERE_PROSE_FINDING_PATTERN =
  Regex(
    "\\[(?:blocker|critical|major|high)\\b|" +
      "(?<!\\bno )(?<!\\bwithout )\\b(?:blocker|critical|major|high)[- ]severity\\b|" +
      "\\bseverity\\s*[:=]?\\s*(?:blocker|critical|major|high)\\b",
    RegexOption.IGNORE_CASE,
  )
private const val MAX_REPORT_DIAGNOSTICS = 20
private const val MAX_DIAGNOSTIC_CHARS = 200
