package skillbill.ports.review.model

import skillbill.review.context.model.accounting.ReviewAccountingTerminalOutcome
import skillbill.review.context.model.accounting.ReviewBudgetOutcome
import skillbill.review.context.model.execution.ForbiddenReviewOperation
import skillbill.review.context.model.execution.ReviewOperationKind
import skillbill.review.context.model.hunk.ReviewEvidenceLimits
import skillbill.review.context.model.hunk.ReviewExpansionRecord
import skillbill.review.context.model.packet.ReviewLaneSegmentAccounting
import skillbill.review.model.ReviewLaneReviewDisposition

enum class ReviewProcessOutcome {
  NOT_STARTED,
  ZERO_EXIT,
  NON_ZERO_EXIT,
  INTERRUPTED,
  TIMED_OUT,
  UNAVAILABLE,
  INVALID_OUTPUT,
  AGGREGATION_FAILURE,
  MISSING_RESULT,
  COORDINATOR_CRASH,
}

data class ReviewEvidenceRequest(
  val lane: String,
  val path: String,
  val reachabilityReason: String? = null,
  val authorizedExpansion: ReviewExpansionRecord? = null,
  val offset: Long? = null,
  val limit: Long? = null,
  val paginationToken: String? = null,
  val selector: String? = null,
) {
  init {
    listOfNotNull(lane, path, reachabilityReason, selector, paginationToken).forEach(ReviewEvidenceLimits::field)
  }
}

data class ReviewExpansionAuthorizationRequest(
  val lane: String,
  val path: String,
  val reachabilityReason: String,
) {
  init {
    listOf(lane, path, reachabilityReason).forEach(ReviewEvidenceLimits::field)
  }
}

data class ReviewEvidenceBatchRequest(val lane: String, val requests: List<ReviewEvidenceRequest>) {
  init {
    require(lane.isNotBlank()) { "Evidence batch lane must not be blank." }
    require(requests.isNotEmpty()) { "Evidence batch must carry at least one request." }
    require(requests.all { it.lane == lane }) { "Every evidence request in a batch belongs to its batch lane." }
  }

  companion object {
    fun of(request: ReviewEvidenceRequest): ReviewEvidenceBatchRequest =
      ReviewEvidenceBatchRequest(request.lane, listOf(request))
  }
}

data class ReviewEvidenceResult(
  val content: String?,
  val bytes: Long,
  val cumulativeBytes: Long,
  val expansionCount: Int,
  val budgetExceeded: ReviewBudgetOutcome? = null,
  val forbidden: ForbiddenReviewOperation? = null,
  val deliveredSelectors: List<String> = emptyList(),
)

data class ReviewEvidenceBatchResult(
  val results: List<ReviewEvidenceResult>,
  val cumulativeBytes: Long,
  val expansions: List<ReviewExpansionRecord>,
  val terminalOutcome: ReviewBudgetOutcome? = null,
  val deliveryReceipt: String? = null,
)

data class ReviewToolCall(
  val lane: String,
  val kind: ReviewOperationKind,
  val target: String,
  val searchScopes: List<String> = emptyList(),
) {
  init {
    require(lane.isNotBlank() && target.isNotBlank()) { "Review tool call must carry a lane and target." }
    require(kind != ReviewOperationKind.SEARCH || searchScopes.isNotEmpty()) {
      "A review search tool call must carry explicit scopes."
    }
  }
}

data class ReviewToolCallResult(
  val forbidden: ForbiddenReviewOperation? = null,
  val budgetExceeded: ReviewBudgetOutcome? = null,
) {
  val admitted: Boolean get() = forbidden == null && budgetExceeded == null
}

data class ReviewRefusedOperationRecord(val category: String, val target: String) {
  init {
    require(category.isNotBlank() && target.isNotBlank()) {
      "A refused review operation record must carry a category and a target."
    }
  }

  override fun toString(): String = "$category=$target"
}

data class ReviewLaneAccounting(
  val lane: String,
  val reviewId: String = "unknown",
  val packetDigest: String = "unknown",
  val assignmentDigest: String = lane,
  val launchBytes: Long = 0,
  val authorizedReadCount: Int = 0,
  val refusedOperationCount: Int = 0,
  val refusals: List<ReviewRefusedOperationRecord> = emptyList(),
  val evidenceBytes: Long = 0,
  val expansions: List<ReviewExpansionRecord> = emptyList(),
  val toolCalls: Int = 0,
  val modelTurns: Int = 0,
  val resultBytes: Long = 0,
  val terminalStatus: String = ReviewAccountingTerminalOutcome.COMPLETED.wireValue,
  val terminalOutcome: ReviewBudgetOutcome? = null,
  val reviewDisposition: ReviewLaneReviewDisposition? = null,
  val bundleCompositionDigest: String? = null,
  val segmentAccounting: List<ReviewLaneSegmentAccounting> = emptyList(),
  val unreviewedSegmentIds: List<String> = emptyList(),
  val budgetDimension: String? = null,
  val unreviewedUnits: List<String> = emptyList(),
  val requiredEvidenceUnits: Int = 0,
  val deliveredEvidenceUnits: Int = 0,
  val remainingEvidence: List<ReviewEvidenceOwner> = emptyList(),
  val evidenceRequests: Int = 0,
) {
  init {
    require(lane.isNotBlank() && reviewId.isNotBlank() && packetDigest.isNotBlank() && assignmentDigest.isNotBlank())
    require(launchBytes >= 0 && evidenceBytes >= 0 && resultBytes >= 0)
    require(toolCalls >= 0 && modelTurns >= 0 && authorizedReadCount >= 0 && refusedOperationCount >= 0)
  }
}
