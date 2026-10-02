package skillbill.infrastructure.workflow.review.broker

import skillbill.ports.review.evidence.ReviewStoredHunkBodyExtractor
import skillbill.ports.taskruntime.FeatureTaskRuntimeSharedEvidenceLocatorReadPort
import skillbill.review.context.model.accounting.ReviewContextBudgetPolicy
import skillbill.review.context.model.accounting.ReviewLaneIdentity
import skillbill.review.context.model.commit.ReviewAssignment
import skillbill.review.context.model.execution.ReviewOperationPolicy
import skillbill.review.context.model.hunk.ReviewChangedHunk
import skillbill.review.context.model.hunk.ReviewExpansionRecord
import java.nio.file.Path

internal data class FileSystemReviewEvidenceBrokerReadStateInit(
  val root: Path,
  val assignment: ReviewAssignment,
  val budget: ReviewContextBudgetPolicy,
  val identity: ReviewLaneIdentity,
  val policy: ReviewOperationPolicy,
  val authorizedExpansionLedger: List<ReviewExpansionRecord>,
  val projectedHunks: List<ReviewChangedHunk>,
  val locatorReader: FeatureTaskRuntimeSharedEvidenceLocatorReadPort?,
  val bodyExtractor: ReviewStoredHunkBodyExtractor,
  val completeFileCheckpoint: Map<String, String?>,
  val hunkCommitById: Map<String, String>,
)
