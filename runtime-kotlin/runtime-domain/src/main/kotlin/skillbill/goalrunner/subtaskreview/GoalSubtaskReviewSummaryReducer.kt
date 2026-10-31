package skillbill.goalrunner.subtaskreview

import skillbill.contracts.JsonCodec
import skillbill.contracts.SharedPayloadKeys
import skillbill.goalrunner.model.ReviewFindingOutcomeRecord
import skillbill.goalrunner.model.UnaddressedFinding
import skillbill.goalrunner.model.normalizedUnaddressedFindingCategory
import skillbill.goalrunner.model.normalizedUnaddressedFindingSeverity
import skillbill.goalrunner.subtaskreview.model.GoalSubtaskReviewOutputOutcome
import skillbill.goalrunner.subtaskreview.model.RejectedVerificationFindingsResult
import skillbill.goalrunner.subtaskreview.model.StructuredGoalReviewFinding
import skillbill.goalrunner.subtaskreview.model.UnaddressedFindingLedgerScope
import skillbill.review.model.ReviewFindingVerdict
import skillbill.review.parsing.ReviewFindingActionability
import skillbill.workflow.model.goalreview.GOAL_SUBTASK_REVIEW_PASS_VERDICTS
import skillbill.workflow.model.goalreview.GoalSubtaskBlockerDisposition
import skillbill.workflow.model.goalreview.GoalSubtaskCommitFocusedAccounting
import skillbill.workflow.model.goalreview.GoalSubtaskReviewCompactFinding
import skillbill.workflow.model.goalreview.blocksAdvance
import skillbill.workflow.model.goalreview.withStableFindingRefs
import skillbill.workflow.model.validation.FeatureTaskRuntimeVerdict

object GoalSubtaskReviewSummaryReducer {
  internal const val REJECTED_VERIFICATION_REASON_MAX_UTF8_BYTES: Int =
    GoalSubtaskReviewVerificationRejection.REJECTED_VERIFICATION_REASON_MAX_UTF8_BYTES

  fun fromOutput(
    output: Any,
    recordedVerdicts: List<ReviewFindingVerdict> = emptyList(),
  ): List<GoalSubtaskReviewCompactFinding> {
    return GoalSubtaskReviewStructuredFindingsParse.structuredFindings(output, recordedVerdicts)
      .filter { finding ->
        ReviewFindingActionability.isActionable(finding.claimVerdict, finding.scopeDisposition)
      }
      .map { finding ->
        GoalSubtaskReviewCompactFinding(
          severity = finding.severity,
          label = finding.compactLabel,
          text = GoalSubtaskReviewSummarySanitize.sanitize(finding.message),
          findingId = finding.findingId,
        )
      }
      .groupBy { finding ->
        finding.findingId?.trim()?.lowercase()?.takeIf(String::isNotBlank)
          ?: finding.label.lowercase()
      }
      .values
      .map { sameKeyFindings ->
        sameKeyFindings.minByOrNull(GoalSubtaskReviewSummarySanitize::severityRank)
          ?: error("A grouped compact review summary must contain at least one finding.")
      }
      .let(::withStableFindingRefs)
  }

  fun reviewRunId(output: Any): String? = GoalSubtaskReviewStructuredFindingsParse.reviewRunIdOf(output)

  fun unaddressedFindings(
    output: Any,
    scope: UnaddressedFindingLedgerScope,
    recordedVerdicts: List<ReviewFindingVerdict> = emptyList(),
  ): List<UnaddressedFinding> {
    val reviewRunId = GoalSubtaskReviewStructuredFindingsParse.reviewRunIdOf(output)
    return GoalSubtaskReviewStructuredFindingsParse.structuredFindings(output, recordedVerdicts)
      .mapIndexed { index, finding ->
        UnaddressedFinding(
          issueKey = scope.issueKey,
          subtaskId = scope.subtaskId,
          workflowId = scope.workflowId,
          reviewPassNumber = scope.reviewPassNumber,
          findingOrdinal = index + 1,
          severity = normalizedUnaddressedFindingSeverity(finding.severity),
          issueCategory = normalizedUnaddressedFindingCategory(finding.issueCategory),
          location = finding.location,
          summary = finding.message,
          reviewRunId = reviewRunId,
          findingId = finding.findingId,
          claimVerdict = finding.claimVerdict,
          scopeDisposition = finding.scopeDisposition,
          citations = finding.citations,
          severityAdjustment = finding.severityAdjustment,
        )
      }
  }

  internal fun unresolvedCount(
    output: Any,
    recordedVerdicts: List<ReviewFindingVerdict> = emptyList(),
  ): Int =
    fromOutput(output, recordedVerdicts)
      .count(GoalSubtaskReviewCompactFinding::blocksAdvance)

  fun outcomeFor(
    output: Any,
    findings: List<GoalSubtaskReviewCompactFinding> = fromOutput(output),
  ): GoalSubtaskReviewOutputOutcome {
    val advanceBlockingCount = findings.count(GoalSubtaskReviewCompactFinding::blocksAdvance)
    val hasOnlyNonBlockingFindings = findings.isNotEmpty() && advanceBlockingCount == 0
    val verdict = reviewPassVerdict(output, findings, advanceBlockingCount, hasOnlyNonBlockingFindings)
    val coverageIncomplete = GoalSubtaskReviewSummaryReducer.evidenceCoverageComplete(output) == false
    return GoalSubtaskReviewOutputOutcome(
      verdict = verdict,
      unresolvedFindingCount =
        when {
          coverageIncomplete -> maxOf(advanceBlockingCount, 1)
          advanceBlockingCount > 0 -> advanceBlockingCount
          hasOnlyNonBlockingFindings ||
            verdict == FeatureTaskRuntimeVerdict.APPROVED ||
            verdict == FeatureTaskRuntimeVerdict.REVIEW_SKIPPED_BY_USER -> 0
          else -> 1
        },
    )
  }

  fun commitFocusedAccounting(output: Any): GoalSubtaskCommitFocusedAccounting? =
    output.asGoalSubtaskReviewPhaseOutputMap()[SharedPayloadKeys.PRODUCED_OUTPUTS]
      ?.let(JsonCodec::anyToStringAnyMap)
      ?.get("commit_focused_accounting")
      ?.let(JsonCodec::anyToStringAnyMap)
      ?.let { GoalSubtaskCommitFocusedAccounting.fromArtifactMap(it, "produced_outputs.commit_focused_accounting") }

  internal fun evidenceCoverageComplete(output: Any): Boolean? =
    output.asGoalSubtaskReviewPhaseOutputMap()[SharedPayloadKeys.PRODUCED_OUTPUTS]
      ?.let(JsonCodec::anyToStringAnyMap)
      ?.get(FeatureTaskRuntimeVerificationSignalKeys.EVIDENCE_COVERAGE_COMPLETE) as? Boolean

  fun rejectedVerificationFindings(
    verifyOutput: Any,
    reviewOutput: Any,
    scope: UnaddressedFindingLedgerScope,
    recordedVerdicts: List<ReviewFindingVerdict> = emptyList(),
  ): RejectedVerificationFindingsResult =
    GoalSubtaskReviewVerificationRejection.rejectedVerificationFindings(
      verifyOutput,
      reviewOutput,
      scope,
      recordedVerdicts,
    )

  fun reviewFindingOutcomes(
    supersededFindings: List<UnaddressedFinding>,
    currentFindings: List<UnaddressedFinding>,
    blockerDispositions: List<GoalSubtaskBlockerDisposition>,
  ): List<ReviewFindingOutcomeRecord> =
    GoalSubtaskReviewOutcomeDispositionReduction.reviewFindingOutcomes(
      supersededFindings,
      currentFindings,
      blockerDispositions,
    )

  fun blockerDispositions(
    output: Any,
    priorBlockerFindingIds: List<String> = emptyList(),
  ): List<GoalSubtaskBlockerDisposition> =
    GoalSubtaskReviewOutcomeDispositionReduction.blockerDispositions(output, priorBlockerFindingIds)

  fun refutedBlockerSupersedes(
    priorFindings: List<UnaddressedFinding>,
    currentFindings: List<UnaddressedFinding>,
    recordedVerdicts: List<ReviewFindingVerdict> = emptyList(),
  ): List<GoalSubtaskBlockerDisposition> =
    GoalSubtaskReviewOutcomeDispositionReduction.refutedBlockerSupersedes(
      priorFindings,
      currentFindings,
      recordedVerdicts,
    )
}

internal fun GoalSubtaskReviewSummaryReducer.structuredFindings(
  output: Any,
  recordedVerdicts: List<ReviewFindingVerdict> = emptyList(),
): List<StructuredGoalReviewFinding> =
  GoalSubtaskReviewStructuredFindingsParse.structuredFindings(output, recordedVerdicts)

fun GoalSubtaskReviewSummaryReducer.reviewRunIdOf(output: Any): String? =
  GoalSubtaskReviewStructuredFindingsParse.reviewRunIdOf(output)

fun GoalSubtaskReviewSummaryReducer.recordedVerdicts(
  fetchFindingVerdicts: (String) -> List<ReviewFindingVerdict>,
  output: Any,
): List<ReviewFindingVerdict> = GoalSubtaskReviewStructuredFindingsParse.recordedVerdicts(fetchFindingVerdicts, output)

fun GoalSubtaskReviewSummaryReducer.verificationBoundaryFindingPaths(
  finding: StructuredGoalReviewFinding,
): List<String> = GoalSubtaskReviewStructuredFindingsParse.verificationBoundaryFindingPaths(finding)

internal fun GoalSubtaskReviewSummaryReducer.rejectedVerificationReasonTruncationRecord(findingId: String): String =
  GoalSubtaskReviewVerificationRejection.rejectedVerificationReasonTruncationRecord(findingId)

private fun reviewPassVerdict(
  output: Any,
  findings: List<GoalSubtaskReviewCompactFinding>,
  advanceBlockingCount: Int,
  hasOnlyNonBlockingFindings: Boolean,
): FeatureTaskRuntimeVerdict {
  val wire = output.asGoalSubtaskReviewPhaseOutputMap()
  if (GoalSubtaskReviewSummaryReducer.evidenceCoverageComplete(output) == false) {
    return FeatureTaskRuntimeVerdict.CHANGES_REQUESTED
  }
  val declaredVerdict = (wire[SharedPayloadKeys.VERDICT] as? String)?.trim()
  val changesRequested = declaredVerdict in setOf("needs_fix", FeatureTaskRuntimeVerdict.CHANGES_REQUESTED.wireValue)
  val reportedFindingsWereFiltered =
    findings.isEmpty() &&
      GoalSubtaskReviewStructuredFindingsParse.structuredFindings(output).isNotEmpty()
  return when {
    advanceBlockingCount > 0 -> FeatureTaskRuntimeVerdict.CHANGES_REQUESTED
    hasOnlyNonBlockingFindings || reportedFindingsWereFiltered -> FeatureTaskRuntimeVerdict.APPROVED
    changesRequested -> FeatureTaskRuntimeVerdict.CHANGES_REQUESTED
    declaredVerdict?.isNotBlank() == true ->
      FeatureTaskRuntimeVerdict.fromWire(declaredVerdict)
        .takeIf(GOAL_SUBTASK_REVIEW_PASS_VERDICTS::contains)
        ?: FeatureTaskRuntimeVerdict.APPROVED
    else -> FeatureTaskRuntimeVerdict.APPROVED
  }
}
