package skillbill.infrastructure.workflow.review.broker

import skillbill.infrastructure.contracts.sha256Hex
import skillbill.ports.review.model.ReviewEvidenceBatchResult
import skillbill.ports.review.model.ReviewEvidenceResult
import skillbill.ports.review.model.ReviewRefusedOperationRecord
import skillbill.review.context.model.accounting.ReviewBudgetOutcome
import skillbill.review.context.model.hunk.ReviewExpansionRecord
import java.nio.charset.StandardCharsets

private const val EXPANSION_ID_HEX_LENGTH = 24

internal fun stableReviewExpansionId(
  assignmentDigest: String,
  path: String,
  reason: String,
): String {
  val input = "$assignmentDigest\u0000$path\u0000$reason".toByteArray(StandardCharsets.UTF_8)
  val digest = sha256Hex(input)
  return "exp-${digest.take(EXPANSION_ID_HEX_LENGTH)}"
}

internal fun reviewEvidenceBatchResult(
  results: List<ReviewEvidenceResult>,
  outcome: ReviewBudgetOutcome?,
  cumulativeBytes: Long,
  expansionLedger: List<ReviewExpansionRecord>,
  refusalLedger: MutableList<ReviewRefusedOperationRecord>,
): ReviewEvidenceBatchResult {
  results.forEach { result ->
    result.forbidden?.let { refusalLedger += ReviewRefusedOperationRecord(it.category, it.target) }
    result.budgetExceeded?.let { refusalLedger += ReviewRefusedOperationRecord(it.type, it.budgetKind.wireValue) }
  }
  return ReviewEvidenceBatchResult(results, cumulativeBytes, expansionLedger, outcome)
}
