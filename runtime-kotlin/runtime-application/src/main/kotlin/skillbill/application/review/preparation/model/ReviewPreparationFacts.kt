package skillbill.application.review.preparation.model

import skillbill.review.context.model.commit.ReviewCommitCoverageFact
import skillbill.review.context.model.commit.ReviewCommitLaneRoutingMatrix
import skillbill.review.context.model.commit.ReviewCommitUnit
import skillbill.review.context.model.commit.ReviewLaneDecision
import skillbill.review.context.model.hunk.ReviewBuildTestFact
import skillbill.review.context.model.hunk.ReviewChangedHunk
import skillbill.review.context.model.hunk.ReviewLearningsReference
import skillbill.review.context.model.hunk.ReviewRuleReference

data class ReviewScopeFacts(
  val repositoryIdentity: String,
  val baseRevision: String,
  val headRevision: String,
  val status: String,
  val changedHunks: List<ReviewChangedHunk>,
  val commitUnits: List<ReviewCommitUnit>,
  val coverageFact: ReviewCommitCoverageFact,
)

data class ReviewLaneSelection(
  val decisions: List<ReviewLaneDecision>,
  val routingMatrix: ReviewCommitLaneRoutingMatrix,
)

data class ReviewStackRoutingFacts(
  val stack: String?,
  val pack: String?,
  val addOns: List<String>,
  val composedLayers: List<String>,
)

data class ReviewPreparationFacts(
  val scope: ReviewScopeFacts,
  val stackRouting: ReviewStackRoutingFacts,
  val laneSelection: ReviewLaneSelection,
  val matchedRules: List<ReviewRuleReference> = emptyList(),
  val learningsReferences: List<ReviewLearningsReference> = emptyList(),
  val buildTestFacts: List<ReviewBuildTestFact> = emptyList(),
)
