package skillbill.engine.featuretask.lifecycle.remediation

import skillbill.engine.featuretask.lifecycle.continuation.reviewState
import skillbill.workflow.model.goalreview.FeatureTaskRuntimeRepairReceipt
import skillbill.workflow.model.goalreview.FeatureTaskRuntimeRepairReceiptEntry
import skillbill.workflow.model.goalreview.GoalSubtaskReviewCompactFinding
import skillbill.workflow.model.goalreview.GoalSubtaskReviewState
import skillbill.workflow.model.goalreview.attemptedUnresolvedEntries
import skillbill.workflow.model.goalreview.omittedCarriedFindings
import skillbill.workflow.model.goalreview.withStableFindingRefs
import skillbill.workflow.model.goalreview.withoutRefutedFindings

fun featureTaskRuntimeCarriedFindings(
  reviewState: GoalSubtaskReviewState,
  refutedFindingIds: Set<String> = emptySet(),
): List<GoalSubtaskReviewCompactFinding> =
  withoutRefutedFindings(
    withStableFindingRefs(reviewState.passResults.lastOrNull()?.findings.orEmpty()),
    refutedFindingIds,
  )

fun featureTaskRuntimeRepairReceiptOmittedFindings(
  receipt: FeatureTaskRuntimeRepairReceipt,
  reviewState: GoalSubtaskReviewState,
  refutedFindingIds: Set<String> = emptySet(),
): List<GoalSubtaskReviewCompactFinding> =
  receipt.omittedCarriedFindings(featureTaskRuntimeCarriedFindings(reviewState, refutedFindingIds))

fun featureTaskRuntimeCompactFindingRef(finding: GoalSubtaskReviewCompactFinding): String =
  finding.findingId?.takeIf(String::isNotBlank)
    ?: error("Carried finding must carry a stable finding_id before coverage runs.")

fun featureTaskRuntimeOmittedFindingsRetryReason(omitted: List<GoalSubtaskReviewCompactFinding>): String =
  "The repair report left these carried findings unaccounted for: " +
    omitted.joinToString(", ", transform = ::featureTaskRuntimeCompactFindingRef) +
    ". They stay owed. Continue this round: name each owed finding by its ref and say whether it was " +
    "fixed, needed no edit, or is still open after your attempt. A carried finding may never be left " +
    "out of the report."

class FeatureTaskRuntimeUnresolvedFindings(
  val refs: Set<String>,
  val detail: String,
) {
  val retryReason: String get() =
    "You reported these carried findings still open after your " +
      "attempt: $detail. You have one more attempt at each. Close it, or report it unresolved again " +
      "and the run stops for an operator instead of trying a third time. Do not silently drop it from " +
      "the report and do not restate the same attempt as if it were new work."
}

fun featureTaskRuntimeUnresolvedFindings(
  receipt: FeatureTaskRuntimeRepairReceipt,
): FeatureTaskRuntimeUnresolvedFindings? {
  val unresolved = receipt.attemptedUnresolvedEntries().ifEmpty { return null }
  return FeatureTaskRuntimeUnresolvedFindings(
    refs = unresolved.mapTo(linkedSetOf(), ::unresolvedEntryRef),
    detail = unresolved.joinToString("; ", transform = ::unresolvedEntryDetail),
  )
}

fun featureTaskRuntimeRepeatedUnresolvedBlockReason(
  phaseId: String,
  unresolved: Set<String>,
  priorUnresolved: Set<String>,
  detail: String,
): String? {
  require(unresolved.isNotEmpty()) { "unresolved must name at least one finding, was empty." }
  val repeated = unresolved.intersect(priorUnresolved).ifEmpty { return null }
  return "Phase '$phaseId' reported the same review findings unresolved on two consecutive " +
    "attempts: ${repeated.sorted().joinToString(", ")}. It had its retry at each of them and the " +
    "finding still stands, so the run blocks for an operator rather than spending a third session " +
    "on it. Reported: $detail"
}

private fun unresolvedEntryRef(entry: FeatureTaskRuntimeRepairReceiptEntry): String = entry.findingId

private fun unresolvedEntryDetail(entry: FeatureTaskRuntimeRepairReceiptEntry): String =
  "${unresolvedEntryRef(entry)} (${entry.unresolvedReason.orEmpty()})"
