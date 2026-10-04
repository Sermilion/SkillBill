package skillbill.engine.featuretask.slot.standalonereview

import skillbill.application.review.model.ParallelCodeReviewResult
import skillbill.ports.review.model.ReviewIntegrationPassOutcome
import skillbill.review.context.model.accounting.ReviewIntegrationTerminalOutcome
import skillbill.review.model.ParallelReviewMergedFinding
import skillbill.review.model.ParallelReviewParseResult
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
    val verdictLines = rawOutput.lineSequence().map(String::trim).filter { it.startsWith("verdict:") }.toList()
    val canonicalVerdictLines =
      setOf(
        "verdict: ${FeatureTaskRuntimeVerdict.APPROVED.wireValue}",
        "verdict: ${FeatureTaskRuntimeVerdict.CHANGES_REQUESTED.wireValue}",
      )
    val token = verdictLines.singleOrNull()?.takeIf { it in canonicalVerdictLines }?.substringAfter(": ")
    val reasons =
      buildList {
        addAll(reportShapeRejections(rawOutput, parsed, result, truncated))
        if (token == null) add("report verdict is missing or noncanonical")
        addAll(emptyRegisterRejections(rawOutput, parsed, token))
        if (requireDelegatedCoverage) addAll(delegatedRejections(result))
        val severe =
          parsed.findings.map { it.severity }
            .any { it == ParallelReviewSeverity.BLOCKER || it == ParallelReviewSeverity.MAJOR }
        if (severe && token != FeatureTaskRuntimeVerdict.CHANGES_REQUESTED.wireValue) {
          add("Blocker or Major findings require changes_requested")
        }
      }
    val mergedSevere =
      result.mergeResult.findings.any {
        it.severity == ParallelReviewSeverity.BLOCKER || it.severity == ParallelReviewSeverity.MAJOR
      }
    val finalVerdict =
      if (requireDelegatedCoverage && mergedSevere) {
        FeatureTaskRuntimeVerdict.CHANGES_REQUESTED.wireValue
      } else {
        token
      }
    return StandaloneReviewReport(
      rawOutput = rawOutput,
      registerOutput =
        buildString {
          val mergedRegister = ParallelReviewMerger.formattedOutput(result.mergeResult.findings)
          append(if (mergedRegister.isBlank()) "NO_FINDINGS" else mergedRegister)
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

  private fun reportShapeRejections(
    rawOutput: String,
    parsed: ParallelReviewParseResult,
    result: ParallelCodeReviewResult,
    truncated: Boolean,
  ): List<String> =
    buildList {
      if (rawOutput.isBlank()) add("report output is blank")
      if (truncated) add("report output was truncated")
      if (parsed.rejections.isNotEmpty()) add("report contains rejected finding candidates")
      if (hasMalformedFindingIdentifier(rawOutput)) add("report contains a malformed finding identifier")
      if (result.rejectedCandidateCount > 0) add("review contains rejected finding candidates")
      if (rawOutput.lineSequence().count { it.trim().startsWith("verdict:") } != 1) {
        add("report must contain exactly one verdict line")
      }
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

  private fun emptyRegisterRejections(
    rawOutput: String,
    parsed: ParallelReviewParseResult,
    token: String?,
  ): List<String> =
    buildList {
      val noFindingsCount = rawOutput.lineSequence().count { it.trim() == "NO_FINDINGS" }
      if (noFindingsCount > 1) add("report repeats NO_FINDINGS")
      if (noFindingsCount > 0 && parsed.findings.isNotEmpty()) add("NO_FINDINGS conflicts with finding entries")
      if (parsed.findings.isEmpty() && noFindingsCount != 1) add("empty report must declare NO_FINDINGS exactly once")
      if (parsed.findings.isEmpty() && noFindingsCount == 1 && token != FeatureTaskRuntimeVerdict.APPROVED.wireValue) {
        add("an empty report must use verdict: approved")
      }
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

private const val MAX_REPORT_DIAGNOSTICS = 20
private const val MAX_DIAGNOSTIC_CHARS = 200
