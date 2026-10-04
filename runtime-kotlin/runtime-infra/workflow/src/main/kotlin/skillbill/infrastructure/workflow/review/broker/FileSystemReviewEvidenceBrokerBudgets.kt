package skillbill.infrastructure.workflow.review.broker

import skillbill.ports.review.model.ReviewEvidenceResult
import skillbill.review.context.model.accounting.ReviewBudgetEvaluator
import skillbill.review.context.model.accounting.ReviewBudgetKind
import skillbill.review.context.model.hunk.ReviewChangedHunk

internal fun exceededEvidence(
  state: FileSystemReviewEvidenceBrokerReadState,
  kind: ReviewBudgetKind,
  limit: Long,
  observed: Long,
): ReviewEvidenceResult {
  val outcome =
    checkNotNull(ReviewBudgetEvaluator.exceededOrNull(state.identity, kind, limit, observed)) {
      "Budget dimension '$kind' reported an excess of $observed against $limit that does not exceed it."
    }
  state.terminalOutcome = outcome
  return terminalResult(outcome, state.cumulativeBytes, state.expansionLedger.size)
}

internal fun assignedHunkBudgetOutcome(
  state: FileSystemReviewEvidenceBrokerReadState,
  bytes: Long,
  unit: String,
): ReviewEvidenceResult? {
  val observedCumulative = state.cumulativeBytes + bytes
  return if (observedCumulative > state.budget.maxLaneEvidenceBytes) {
    recordLaneEvidenceDenial(state, unit)
    exceededEvidence(state, ReviewBudgetKind.LANE_EVIDENCE_BYTES, state.budget.maxLaneEvidenceBytes, observedCumulative)
  } else {
    null
  }
}

internal fun evidenceBudgetOutcome(
  state: FileSystemReviewEvidenceBrokerReadState,
  bytes: Long,
  unit: String,
): ReviewEvidenceResult? {
  if (bytes > state.budget.maxEvidenceResultBytes) {
    return exceededEvidence(state, ReviewBudgetKind.EVIDENCE_RESULT_BYTES, state.budget.maxEvidenceResultBytes, bytes)
  }
  val observedCumulative = state.cumulativeBytes + bytes
  return if (observedCumulative > state.budget.maxLaneEvidenceBytes) {
    recordLaneEvidenceDenial(state, unit)
    exceededEvidence(state, ReviewBudgetKind.LANE_EVIDENCE_BYTES, state.budget.maxLaneEvidenceBytes, observedCumulative)
  } else {
    null
  }
}

internal fun recordLaneEvidenceDenial(
  state: FileSystemReviewEvidenceBrokerReadState,
  unit: String,
) {
  state.deniedUnits += unit
}

internal fun unitForHunk(
  state: FileSystemReviewEvidenceBrokerReadState,
  hunk: ReviewChangedHunk,
): String = "${commitShaForHunk(state, hunk.hunkId)}@${hunk.path}"

internal fun unitAtPath(
  state: FileSystemReviewEvidenceBrokerReadState,
  path: String,
): String {
  val hunkId = state.projectedHunks.firstOrNull { it.path == path }?.hunkId
  val commit = hunkId?.let { commitShaForHunk(state, it) } ?: state.assignment.headRevision
  return "$commit@$path"
}

internal fun commitShaForHunk(
  state: FileSystemReviewEvidenceBrokerReadState,
  hunkId: String,
): String = state.hunkCommitById[hunkId] ?: state.assignment.headRevision
