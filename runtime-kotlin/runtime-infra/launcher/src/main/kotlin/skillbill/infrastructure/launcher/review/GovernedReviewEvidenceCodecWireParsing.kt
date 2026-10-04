package skillbill.infrastructure.launcher.review

import skillbill.contracts.JsonCodec
import skillbill.error.core.InvalidGovernedReviewEvidenceRequestError
import skillbill.ports.review.model.ReviewEvidenceRequest
import skillbill.review.context.model.hunk.ReviewEvidenceLimits
import skillbill.review.context.model.hunk.ReviewExpansionRecord

internal object GovernedReviewEvidenceCodecWireParsing {
  fun evidenceRequest(
    lane: String,
    raw: Any?,
    expansionById: (String) -> ReviewExpansionRecord?,
  ): ReviewEvidenceRequest {
    val map = asMap(raw)
    if (map.keys.any { it !in setOf("path", "selector", "expansion_id", "reachability_reason") }) {
      throw InvalidGovernedReviewEvidenceRequestError("review-evidence", "Unknown read selector field.")
    }
    val expansionId = optionalString(map, "expansion_id")
    val authorized =
      expansionId?.let { id ->
        expansionById(id) ?: throw InvalidGovernedReviewEvidenceRequestError(
          "review-evidence",
          "Unknown expansion id for this assignment.",
        )
      }
    return ReviewEvidenceRequest(
      lane = lane,
      selector = optionalString(map, "selector"),
      path = requiredString(map, "path"),
      reachabilityReason = optionalString(map, "reachability_reason") ?: authorized?.reachabilityReason,
      authorizedExpansion = authorized,
    )
  }

  fun requiredString(
    source: Map<String, Any?>,
    key: String,
  ): String {
    val value = source[key] as? String
    if (value.isNullOrBlank()) {
      throw InvalidGovernedReviewEvidenceRequestError(
        "review-evidence",
        "Operation requires string '$key'.",
      )
    }
    ReviewEvidenceLimits.field(value)
    return value
  }

  private fun asMap(raw: Any?): Map<String, Any?> =
    raw?.let(JsonCodec::anyToStringAnyMap)
      ?: throw InvalidGovernedReviewEvidenceRequestError("review-evidence", "Each read selector must be an object.")

  private fun optionalString(
    source: Map<String, Any?>,
    key: String,
  ): String? =
    source[key]?.let { value ->
      (value as? String)?.takeIf(String::isNotBlank)?.also(ReviewEvidenceLimits::field)
        ?: throw InvalidGovernedReviewEvidenceRequestError(
          "review-evidence",
          "Optional selector must be a nonblank string.",
        )
    }
}
