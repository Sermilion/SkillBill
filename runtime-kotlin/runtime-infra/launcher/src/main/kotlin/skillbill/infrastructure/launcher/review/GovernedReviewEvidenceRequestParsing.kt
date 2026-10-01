package skillbill.infrastructure.launcher.review

import skillbill.error.core.InvalidGovernedReviewEvidenceRequestError

internal const val REVIEW_EVIDENCE_BATCH_SIZE: Int = 32

internal fun evidenceReadItems(arguments: Map<String, Any?>): List<*> {
  val items =
    arguments["requests"] as? List<*>
      ?: throw InvalidGovernedReviewEvidenceRequestError("review-evidence", "Read requests must be an array.")
  if (items.size !in 1..REVIEW_EVIDENCE_BATCH_SIZE) {
    throw InvalidGovernedReviewEvidenceRequestError("review-evidence", "Read batch exceeds its request bounds.")
  }
  return items
}
