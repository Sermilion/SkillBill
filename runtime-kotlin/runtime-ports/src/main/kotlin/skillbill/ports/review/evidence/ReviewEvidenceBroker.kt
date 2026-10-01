package skillbill.ports.review.evidence

import skillbill.ports.review.model.ReviewEvidenceBatchRequest
import skillbill.ports.review.model.ReviewEvidenceBatchResult
import skillbill.ports.review.model.ReviewEvidenceBrokerBinding
import skillbill.ports.review.model.ReviewExpansionAuthorizationRequest
import skillbill.ports.review.model.ReviewLaneAccounting
import skillbill.ports.review.model.ReviewToolCall
import skillbill.ports.review.model.ReviewToolCallResult
import skillbill.review.context.model.accounting.ReviewBudgetOutcome
import skillbill.review.context.model.hunk.ReviewExpansionRecord

interface ReviewEvidenceBroker {
  fun authorizeExpansion(request: ReviewExpansionAuthorizationRequest): ReviewExpansionRecord

  fun readBatch(request: ReviewEvidenceBatchRequest): ReviewEvidenceBatchResult

  fun recordToolCall(call: ReviewToolCall): ReviewToolCallResult

  fun recordModelTurn(): ReviewBudgetOutcome?

  fun validateLaneResult(result: String): ReviewBudgetOutcome?

  fun observeLaneResultChunk(chunk: String): ReviewBudgetOutcome?

  fun hasObservedLaneResult(): Boolean = accounting().resultBytes > 0

  fun accounting(): ReviewLaneAccounting

  fun terminalOutcome(): ReviewBudgetOutcome?
}

fun interface ReviewEvidenceBrokerFactory {
  fun brokerFor(binding: ReviewEvidenceBrokerBinding): ReviewEvidenceBroker
}
