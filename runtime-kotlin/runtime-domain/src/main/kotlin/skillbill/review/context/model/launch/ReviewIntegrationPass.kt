package skillbill.review.context.model.launch

import skillbill.review.context.model.accounting.ReviewContextBudgetPolicy
import skillbill.review.context.model.execution.ReviewSpecialistSummaryCoverage
import skillbill.review.context.model.hunk.ReviewEvidenceTarget
import skillbill.review.context.model.packet.ReviewContextPacket
import skillbill.review.context.model.packet.ReviewLaneCompletionState
import skillbill.review.model.ReviewLaneReviewDisposition

data class ReviewSpecialistSummary(
  val lane: String,
  val assignmentDigest: String,
  val disposition: ReviewLaneReviewDisposition,
  val assignedPaths: List<String>,
  val commitShas: List<String>,
  val findingCount: Int,
  val unreviewedSegmentIds: List<String> = emptyList(),
  val unreviewedUnits: List<String> = emptyList(),
  val summary: String = "",
) {
  init {
    require(lane.isNotBlank()) { "Specialist summary lane must not be blank." }
    require(assignmentDigest.isNotBlank()) { "Specialist summary must name its assignment digest." }
    require(findingCount >= 0) { "Specialist summary finding count cannot be negative." }
    require(summary.length <= MAX_SUMMARY_LENGTH) {
      "Specialist summary for lane '$lane' exceeds the $MAX_SUMMARY_LENGTH-character bound."
    }
    if (disposition == ReviewLaneReviewDisposition.INCOMPLETE) {
      require(unreviewedSegmentIds.isNotEmpty() && unreviewedUnits.isNotEmpty()) {
        "An incomplete lane summary must name what it left unreviewed."
      }
    }
  }

  val isCleanCoverage: Boolean get() = disposition == ReviewLaneReviewDisposition.COMPLETE

  companion object {
    const val MAX_SUMMARY_LENGTH: Int = 2000

    fun of(
      lane: String,
      assignmentDigest: String,
      completion: ReviewLaneCompletionState,
      coverage: ReviewSpecialistSummaryCoverage,
    ): ReviewSpecialistSummary =
      ReviewSpecialistSummary(
        lane = lane,
        assignmentDigest = assignmentDigest,
        disposition = completion.disposition,
        assignedPaths = coverage.assignedPaths.distinct().sorted(),
        commitShas = coverage.commitShas.distinct(),
        findingCount = coverage.findingCount,
        unreviewedSegmentIds = completion.unreviewedSegmentIds,
        unreviewedUnits = completion.unreviewedUnits,
        summary = coverage.summary.replace("\r\n", "\n").take(MAX_SUMMARY_LENGTH),
      )
  }
}

data class GovernedReviewIntegrationLaunch(
  val packet: ReviewContextPacket,
  val specialistSummaries: List<ReviewSpecialistSummary>,
  val integrationContract: String,
  val brokerId: String,
  val budget: ReviewContextBudgetPolicy,
  val isolation: ReviewConversationIsolation = ReviewConversationIsolation.FRESH,
) {
  init {
    require(integrationContract.isNotBlank()) { "Integration launch requires a non-blank contract." }
    require(brokerId.isNotBlank()) { "Integration launch requires a broker id." }
    require(specialistSummaries.map { it.lane }.distinct().size == specialistSummaries.size) {
      "Integration launch carries more than one summary for the same lane."
    }
    val foreign = specialistSummaries.map { it.lane }.filterNot { it in packet.selectedLanes }
    require(foreign.isEmpty()) {
      "Integration launch carries summaries for lanes outside the packet selection: ${foreign.sorted()}."
    }
    val unknownCommits = specialistSummaries.flatMap { it.commitShas }.filterNot { it in packet.ownedCommitIds }
    require(unknownCommits.isEmpty()) {
      "Integration launch names commits the packet does not own: ${unknownCommits.distinct().sorted()}."
    }
  }

  val commitSequenceDigest: String get() = packet.commitSequenceDigest

  val finalStateEvidenceTargets: List<ReviewEvidenceTarget>
    get() {
      val summarizedPaths = specialistSummaries.flatMap { it.assignedPaths }.toSet()
      return packet.evidenceTargets.filter { it.path in summarizedPaths }.sortedBy { it.targetId }
    }

  val incompleteLanes: List<String>
    get() = specialistSummaries.filterNot { it.isCleanCoverage }.map { it.lane }.sorted()
}
