package skillbill.review.model

data class ReviewLaneFindingVerdict(
  val laneId: String,
  val claimVerdict: ReviewClaimVerdict? = null,
  val scopeDisposition: ReviewScopeDisposition? = null,
  val citations: List<ReviewFindingCitation> = emptyList(),
  val severityAdjustment: ReviewSeverityAdjustment? = null,
)

enum class ParallelReviewSeverity(val displayName: String) {
  BLOCKER("Blocker"),
  MAJOR("Major"),
  MINOR("Minor"),
  NIT("Nit"),
}

data class ParallelReviewRawFinding(
  val severity: ParallelReviewSeverity,
  val confidence: String,
  val location: String,
  val description: String,
  val specialistSkillName: String? = null,
  val originLayerChains: List<List<String>> = emptyList(),
  val repositoryPath: String? = null,
  val line: Int? = null,
  val commitShas: List<String> = emptyList(),
  val claimVerdict: ReviewClaimVerdict? = null,
  val scopeDisposition: ReviewScopeDisposition? = null,
  val citations: List<ReviewFindingCitation> = emptyList(),
  val severityAdjustment: ReviewSeverityAdjustment? = null,
  val sourceFindingRef: String? = null,
  val settlement: ReviewFindingSettlement? = null,
)

data class ParallelReviewLaneResult(
  val agentId: String,
  val findings: List<ParallelReviewRawFinding>,
)

data class ParallelReviewMergedFinding(
  val fNumber: String,
  val agentIds: List<String>,
  val severity: ParallelReviewSeverity,
  val confidence: String,
  val location: String,
  val description: String,
  val specialistSkillNames: List<String> = emptyList(),
  val originLayerChains: List<List<String>> = emptyList(),
  val repositoryPath: String? = null,
  val line: Int? = null,
  val commitShas: List<String> = emptyList(),
  val claimVerdict: ReviewClaimVerdict? = null,
  val scopeDisposition: ReviewScopeDisposition? = null,
  val citations: List<ReviewFindingCitation> = emptyList(),
  val severityAdjustment: ReviewSeverityAdjustment? = null,
  val sourceVerdicts: List<ReviewLaneFindingVerdict> = emptyList(),
  val sourceFindingRefs: List<String> = emptyList(),
  val settlement: ReviewFindingSettlement? = null,
) {
  val hasRecordedVerdict: Boolean
    get() =
      claimVerdict != null ||
        scopeDisposition != null ||
        severityAdjustment != null ||
        sourceVerdicts.isNotEmpty()
}

data class ParallelReviewMergeResult(
  val findings: List<ParallelReviewMergedFinding>,
  val formattedOutput: String,
) {
  val output: String
    get() = formattedOutput
}
