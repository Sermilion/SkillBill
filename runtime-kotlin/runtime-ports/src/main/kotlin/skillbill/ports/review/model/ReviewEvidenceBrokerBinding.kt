package skillbill.ports.review.model

import skillbill.ports.review.evidence.ReviewStoredHunkBodyExtractor
import skillbill.ports.taskruntime.FeatureTaskRuntimeSharedEvidenceLocatorReadPort
import skillbill.review.context.model.accounting.ReviewContextBudgetPolicy
import skillbill.review.context.model.commit.ReviewAssignment
import skillbill.review.context.model.hunk.ReviewChangedHunk
import skillbill.review.context.model.hunk.ReviewExpansionRecord
import java.nio.file.Path

data class ReviewEvidenceBrokerBinding(
  val repoRoot: Path,
  val assignment: ReviewAssignment,
  val laneRubricId: String,
  val budget: ReviewContextBudgetPolicy,
  val namedDependencies: Set<String> = emptySet(),
  val trustedExpansionLedger: List<ReviewExpansionRecord> = emptyList(),
  val projectedHunks: List<ReviewChangedHunk> = emptyList(),
  val locatorReader: FeatureTaskRuntimeSharedEvidenceLocatorReadPort? = null,
  val bodyExtractor: ReviewStoredHunkBodyExtractor = ReviewStoredHunkBodyExtractor.HUNK_CONTENT,
  val sources: List<ReviewEvidenceSource> = listOf(ReviewEvidenceSource(assignment, laneRubricId, namedDependencies)),
  val visibleHunkIds: Set<String> = assignment.assignedHunks.toSet(),
  val visibleTargetPaths: Set<String> =
    (
      assignment.assignedPaths +
        assignment.dependencyAllowlist.normalized +
        assignment.evidenceTargets.map { it.path }
    ).toSet(),
) {
  init {
    require(
      sources.isNotEmpty() && sources.map { it.assignment.digest to it.rubricId }.distinct().size == sources.size,
    ) {
      "A broker must retain unique source assignments and rubric identities."
    }
    require(
      sources.flatMap { it.assignment.assignedPaths }.toSet() == assignment.assignedPaths.toSet() &&
        sources.flatMap { it.assignment.assignedHunks }.toSet() == assignment.assignedHunks.toSet(),
    ) {
      "Source assignments must account for the final broker evidence surface."
    }
    require(
      sources.groupBy {
        it.assignment.digest
      }.values.all { group -> group.map { it.coordinates }.distinct().size == 1 },
    ) {
      "Rubrics sharing an assignment must retain the same evidence coordinates."
    }
    require(
      sources.all {
        it.assignment.reviewId == assignment.reviewId && it.assignment.packetDigest == assignment.packetDigest
      },
    ) {
      "Source assignments must belong to the same review packet."
    }
    require(laneRubricId.isNotBlank()) { "A bound lane must name the single rubric it owns." }
    require(projectedHunks.map { it.hunkId }.toSet() == assignment.assignedHunks.toSet()) {
      "The evidence broker must receive exactly the assignment-owned projected hunks."
    }
    require(visibleHunkIds.all { it in assignment.assignedHunks }) {
      "The evidence broker visibility scope cannot escape the assignment-owned hunks."
    }
    require(
      visibleTargetPaths.all {
        it in assignment.assignedPaths ||
          it in assignment.dependencyAllowlist.normalized ||
          it in assignment.evidenceTargets.map { target -> target.path }
      },
    ) {
      "The evidence broker visibility scope cannot escape the assignment-owned targets."
    }
  }
}
