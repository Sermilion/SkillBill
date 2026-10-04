package skillbill.infrastructure.launcher.review

import skillbill.ports.review.model.ReviewEvidenceResult
import skillbill.review.context.model.accounting.ReviewBudgetOutcome
import skillbill.review.context.model.execution.ForbiddenReviewOperation
import skillbill.review.context.model.hunk.ReviewExpansionRecord

internal object GovernedReviewEvidenceCodecWirePayloads {
  fun expansionPayload(record: ReviewExpansionRecord): Map<String, Any?> =
    linkedMapOf(
      GovernedReviewEvidencePayloadKeys.EXPANSION_ID to record.expansionId,
      GovernedReviewEvidencePayloadKeys.ASSIGNMENT_DIGEST to record.assignmentDigest,
      GovernedReviewEvidencePayloadKeys.REQUESTED_PATH to record.requestedPath,
      GovernedReviewEvidencePayloadKeys.REACHABILITY_REASON to record.reachabilityReason,
      GovernedReviewEvidencePayloadKeys.AUTHORIZED to record.authorized,
      GovernedReviewEvidencePayloadKeys.SEQUENCE to record.sequence,
    )

  fun resultPayload(result: ReviewEvidenceResult): Map<String, Any?> {
    val refusal = refusal(result)
    if (refusal != null && (result.forbidden != null || result.content == null)) return refusal
    return linkedMapOf(
      GovernedReviewEvidencePayloadKeys.REFUSED to false,
      GovernedReviewEvidencePayloadKeys.TERMINAL_OUTCOME to refusal,
      GovernedReviewEvidencePayloadKeys.CONTENT to result.content,
      GovernedReviewEvidencePayloadKeys.BYTES to result.bytes,
      GovernedReviewEvidencePayloadKeys.CUMULATIVE_BYTES to result.cumulativeBytes,
      GovernedReviewEvidencePayloadKeys.EXPANSION_COUNT to result.expansionCount,
    )
  }

  fun budgetPayload(outcome: ReviewBudgetOutcome): Map<String, Any?> =
    linkedMapOf(
      GovernedReviewEvidencePayloadKeys.REFUSED to true,
      GovernedReviewEvidencePayloadKeys.REFUSAL_KIND to "budget_exceeded",
      GovernedReviewEvidencePayloadKeys.REASON to outcome.type,
      GovernedReviewEvidencePayloadKeys.BUDGET_KIND to outcome.budgetKind,
      GovernedReviewEvidencePayloadKeys.CONFIGURED_LIMIT to outcome.configuredLimit,
      GovernedReviewEvidencePayloadKeys.OBSERVED_VALUE to outcome.observedValue,
    )

  private fun refusal(result: ReviewEvidenceResult): Map<String, Any?>? {
    result.forbidden?.let { return forbiddenPayload(it) }
    result.budgetExceeded?.let { return budgetPayload(it) }
    return null
  }

  private fun forbiddenPayload(forbidden: ForbiddenReviewOperation): Map<String, Any?> =
    linkedMapOf(
      GovernedReviewEvidencePayloadKeys.REFUSED to true,
      GovernedReviewEvidencePayloadKeys.REFUSAL_KIND to "forbidden",
      GovernedReviewEvidencePayloadKeys.REASON to forbidden.reason,
      GovernedReviewEvidencePayloadKeys.CATEGORY to forbidden.category,
      GovernedReviewEvidencePayloadKeys.TARGET to forbidden.target,
    )
}
