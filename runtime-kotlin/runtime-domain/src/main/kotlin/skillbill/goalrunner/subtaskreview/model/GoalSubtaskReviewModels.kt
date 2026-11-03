package skillbill.goalrunner.subtaskreview.model

import skillbill.review.model.ReviewClaimVerdict
import skillbill.review.model.ReviewFindingCitation
import skillbill.review.model.ReviewFindingSettlement
import skillbill.review.model.ReviewScopeDisposition
import skillbill.review.model.ReviewSeverityAdjustment
import skillbill.workflow.model.validation.FeatureTaskRuntimeVerdict

data class StructuredGoalReviewFinding(
  val severity: String,
  val message: String,
  val issueCategory: String,
  val location: String,
  val compactLabel: String,
  val findingId: String? = null,
  val repositoryPath: String? = null,
  val claimVerdict: ReviewClaimVerdict? = null,
  val scopeDisposition: ReviewScopeDisposition? = null,
  val citations: List<ReviewFindingCitation> = emptyList(),
  val severityAdjustment: ReviewSeverityAdjustment? = null,
  val settlement: ReviewFindingSettlement? = null,
)

data class GoalSubtaskReviewOutputOutcome(
  val verdict: FeatureTaskRuntimeVerdict,
  val unresolvedFindingCount: Int,
)

data class UnaddressedFindingLedgerScope(
  val issueKey: String,
  val subtaskId: Int,
  val workflowId: String,
  val reviewPassNumber: Int,
)
