package skillbill.engine.featuretask.slot.codereview

import skillbill.application.review.model.ParallelCodeReviewResult
import skillbill.contracts.JsonCodec
import skillbill.contracts.SharedPayloadKeys
import skillbill.contracts.review.ReviewFindingPayloadKeys
import skillbill.contracts.workflow.featuretask.FEATURE_TASK_RUNTIME_CONTRACT_VERSION
import skillbill.engine.featuretask.runner.STATUS_COMPLETED
import skillbill.goalrunner.subtaskreview.FeatureTaskRuntimeVerificationSignalKeys
import skillbill.goalrunner.subtaskreview.GoalSubtaskReviewSummaryReducer
import skillbill.review.context.model.launch.CodeReviewExecutionMode
import skillbill.review.context.model.launch.ReviewIntegrationTerminalOutcome
import skillbill.review.model.ParallelReviewMergedFinding
import skillbill.review.model.ReviewFindingCitation
import skillbill.review.model.ReviewFindingCitationDiagnosticKeys
import skillbill.review.model.ReviewFindingCitationDiagnosticWithFinding
import skillbill.workflow.model.goalreview.GoalSubtaskBlockerDisposition
import skillbill.workflow.model.goalreview.GoalSubtaskCommitFocusedAccounting
import skillbill.workflow.model.validation.FeatureTaskRuntimeVerdict
import java.time.Clock
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

internal data class InlineReviewCycle(
  val passNumber: Int,
  val resolvedTier: CodeReviewExecutionMode,
  val repositoryFingerprint: String,
  val blockerDispositions: List<GoalSubtaskBlockerDisposition> = emptyList(),
)

object InlineReviewEnvelope {
  private const val REVIEW_RUN_ID_SUFFIX_LENGTH = 4
  private const val SUMMARY_MAX_CHARS = 2_000

  internal fun assemble(
    result: ParallelCodeReviewResult,
    reviewRunId: String,
    cycle: InlineReviewCycle,
  ): String {
    val prose = result.output.trim().ifBlank { "Review completed." }
    val findings = result.mergeResult.findings.map(::findingPayload)
    val produced =
      linkedMapOf<String, Any?>(
        FeatureTaskRuntimeVerificationSignalKeys.REVIEW_FINDINGS to emptyList<Any?>(),
        FeatureTaskRuntimeVerificationSignalKeys.REVIEW_RUN_ID to reviewRunId,
        FeatureTaskRuntimeVerificationSignalKeys.REPOSITORY_CHECKPOINT to
          mapOf(
            FeatureTaskRuntimeVerificationSignalKeys.REPOSITORY_CHECKPOINT_FINGERPRINT to cycle.repositoryFingerprint,
          ),
      )
    commitFocusedAccounting(result, cycle.resolvedTier)?.let { accounting ->
      produced["commit_focused_accounting"] = accounting.toPersistenceWire()
    }
    val envelope =
      linkedMapOf<String, Any?>(
        SharedPayloadKeys.CONTRACT_VERSION to FEATURE_TASK_RUNTIME_CONTRACT_VERSION,
        SharedPayloadKeys.PHASE_ID to "review",
        SharedPayloadKeys.STATUS to STATUS_COMPLETED,
        SharedPayloadKeys.SUMMARY to prose.take(SUMMARY_MAX_CHARS),
        SharedPayloadKeys.PRODUCED_OUTPUTS to produced,
        FeatureTaskRuntimeVerificationSignalKeys.VERDICT to extractReviewVerdict(prose).wireValue,
      )
    val outcome = GoalSubtaskReviewSummaryReducer.outcomeFor(envelope)
    produced[FeatureTaskRuntimeVerificationSignalKeys.REVIEW_FINDINGS] = findings
    mergeCitationDiagnostics(produced, result.citationDiagnostics)
    envelope[FeatureTaskRuntimeVerificationSignalKeys.VERDICT] = outcome.verdict.wireValue
    return JsonCodec.mapToJsonString(envelope)
  }

  fun extractReviewVerdict(prose: String): FeatureTaskRuntimeVerdict {
    val line =
      prose.lineSequence()
        .map { it.trim() }
        .lastOrNull { it.startsWith("verdict:", ignoreCase = true) }
        ?: prose.lineSequence().map { it.trim() }.lastOrNull {
          it.equals("approved", ignoreCase = true) ||
            it.equals("changes_requested", ignoreCase = true) ||
            it.equals("needs_fix", ignoreCase = true)
        }
    val token =
      when {
        line == null -> return FeatureTaskRuntimeVerdict.APPROVED
        line.startsWith("verdict:", ignoreCase = true) ->
          line.substringAfter(':').trim().lowercase()
        else -> line.lowercase()
      }
    return when (token) {
      "changes_requested", "needs_fix" -> FeatureTaskRuntimeVerdict.CHANGES_REQUESTED
      else -> FeatureTaskRuntimeVerdict.APPROVED
    }
  }

  internal fun envelopeMap(outputText: String): Map<String, Any?> =
    JsonCodec.parseObjectOrNull(outputText)
      ?.let(JsonCodec::jsonElementToValue)
      ?.let(JsonCodec::anyToStringAnyMap)
      .orEmpty()

  fun mintReviewRunId(clock: Clock): String {
    val stamp =
      LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC)
        .format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
    val alphabet = "abcdefghijklmnopqrstuvwxyz0123456789"
    val suffix = CharArray(REVIEW_RUN_ID_SUFFIX_LENGTH) { alphabet.random() }.concatToString()
    return "rvw-$stamp-$suffix"
  }

  private fun findingPayload(finding: ParallelReviewMergedFinding): Map<String, Any?> =
    buildMap {
      put(ReviewFindingPayloadKeys.FINDING_ID, finding.fNumber)
      put("severity", finding.severity.name.lowercase())
      put("message", finding.description)
      put("location", finding.location)
      finding.repositoryPath?.let { put(ReviewFindingPayloadKeys.REPOSITORY_PATH, it) }
      finding.claimVerdict?.let { put(ReviewFindingPayloadKeys.CLAIM_VERDICT, it.wireValue) }
      finding.scopeDisposition?.let { put(ReviewFindingPayloadKeys.SCOPE_DISPOSITION, it.wireValue) }
      if (finding.citations.isNotEmpty()) {
        put(ReviewFindingPayloadKeys.CITATIONS, finding.citations.map(::citationPayload))
      }
    }

  private fun citationPayload(citation: ReviewFindingCitation): Map<String, Any?> =
    mapOf("path" to citation.path, "line" to citation.line)

  private fun mergeCitationDiagnostics(
    produced: MutableMap<String, Any?>,
    diagnostics: List<ReviewFindingCitationDiagnosticWithFinding>,
  ) {
    if (diagnostics.isEmpty()) return
    val merged =
      diagnostics.map(::citationDiagnosticWireMap)
        .distinctBy { entry ->
          listOf(
            entry["finding_ref"],
            entry[ReviewFindingCitationDiagnosticKeys.CITATION_INDEX],
            entry["path"],
            entry[ReviewFindingCitationDiagnosticKeys.RAW_LINE],
            entry["reason"],
          )
        }
    produced[FeatureTaskRuntimeVerificationSignalKeys.CITATION_DIAGNOSTICS] = merged
  }

  private fun commitFocusedAccounting(
    result: ParallelCodeReviewResult,
    resolvedTier: CodeReviewExecutionMode,
  ): GoalSubtaskCommitFocusedAccounting? {
    val summary = result.accountingSummary ?: return null
    val routing =
      summary.commitRouting
        ?.takeIf { resolvedTier == CodeReviewExecutionMode.DELEGATED && it.commitCount >= 1 }
        ?: return null
    val accounting = summary.integration
    val pass = result.integration
    val terminalOutcome =
      accounting?.terminalOutcome
        ?: pass?.terminalOutcome
        ?: ReviewIntegrationTerminalOutcome.SKIPPED_NOT_APPLICABLE
    return GoalSubtaskCommitFocusedAccounting(
      commitSequenceDigest = routing.commitSequenceDigest,
      commitCount = routing.commitCount,
      laneCount = routing.laneCount,
      focusedCommitCount = routing.focusedCommitCount,
      skippedCommitCount = routing.skippedCommitCount,
      integrationTerminalOutcome = terminalOutcome,
      routingDigest = routing.routingDigest,
      focusedPairCount = routing.focusedPairCount,
      skippedPairCount = routing.skippedPairCount,
      incompleteLanes = routing.incompleteLanes,
      parentAnalysisPairs = summary.parentAnalysis?.analyzedPairs,
      parentAnalysisBytes = summary.parentAnalysis?.analyzedBytes,
      integrationSkipReason =
        when (terminalOutcome) {
          ReviewIntegrationTerminalOutcome.SKIPPED_NOT_APPLICABLE ->
            accounting?.skipReason?.takeIf { it.isNotBlank() }
              ?: pass?.skipReason?.takeIf { it.isNotBlank() }
              ?: result.coverage?.integrationNotApplicableReason?.takeIf { it.isNotBlank() }
              ?: "commit-focused accounting was recorded without a settled integration pass"
          else -> accounting?.skipReason ?: pass?.skipReason
        },
      integrationFindingCount = accounting?.findingCount ?: pass?.findings?.size,
    )
  }
}

private fun citationDiagnosticWireMap(diagnostic: ReviewFindingCitationDiagnosticWithFinding): Map<String, Any?> =
  buildMap {
    diagnostic.findingRef?.let { put("finding_ref", it) }
    put(ReviewFindingCitationDiagnosticKeys.CITATION_INDEX, diagnostic.diagnostic.citationIndex)
    put("path", diagnostic.diagnostic.path)
    put(ReviewFindingCitationDiagnosticKeys.RAW_LINE, diagnostic.diagnostic.rawLine)
    put("reason", diagnostic.diagnostic.reason)
  }
