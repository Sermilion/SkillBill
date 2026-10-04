package skillbill.ports.review.evidence

import skillbill.ports.review.model.ReviewEvidenceBatchRequest
import skillbill.ports.review.model.ReviewEvidenceBatchResult
import skillbill.ports.review.model.ReviewExpansionAuthorizationRequest
import skillbill.ports.review.model.ReviewLaneAccounting
import skillbill.ports.review.model.ReviewToolCall
import skillbill.ports.review.model.ReviewToolCallResult
import skillbill.review.context.model.accounting.ReviewBudgetOutcome
import skillbill.review.context.model.hunk.ReviewExpansionRecord

abstract class ReviewEvidenceBrokerDefaults : ReviewEvidenceBroker {
  open override fun authorizeExpansion(request: ReviewExpansionAuthorizationRequest): ReviewExpansionRecord =
    error("Review evidence expansion authorization is not configured for this broker.")

  open override fun readBatch(request: ReviewEvidenceBatchRequest): ReviewEvidenceBatchResult =
    error("Review evidence batch reads are not configured for this broker.")

  open override fun recordToolCall(call: ReviewToolCall): ReviewToolCallResult =
    error("Review evidence tool-call recording is not configured for this broker.")

  open override fun recordModelTurn(): ReviewBudgetOutcome? = null

  open override fun validateLaneResult(result: String): ReviewBudgetOutcome? = null

  open override fun observeLaneResultChunk(chunk: String): ReviewBudgetOutcome? = null

  open override fun accounting(): ReviewLaneAccounting =
    error("Review evidence lane accounting is not configured for this broker.")

  open override fun terminalOutcome(): ReviewBudgetOutcome? = null
}
