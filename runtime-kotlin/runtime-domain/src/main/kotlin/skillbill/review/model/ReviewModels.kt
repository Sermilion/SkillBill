package skillbill.review.model

data class ImportedFinding(
  val findingId: String,
  val severity: String,
  val confidence: String,
  val location: String,
  val description: String,
  val findingText: String,
  val issueCategory: String = ReviewIssueCategory.OTHER.wireValue,
  val laneSkillName: String? = null,
)

data class ReviewRunLane(
  val laneSkillName: String,
  val packSlug: String,
  val area: String,
  val depth: Int,
  val required: Boolean,
  val orderIndex: Int,
  val originLayerChain: List<String>,
  val resolutionState: ReviewLaneResolutionState,
  val reviewDisposition: ReviewLaneReviewDisposition,
  val bundleCompositionDigest: String? = null,
  val segmentAccountingJson: String? = null,
  val unreviewedSegmentIds: List<String> = emptyList(),
  val budgetDimension: String? = null,
)

enum class ReviewLaneResolutionState(val wireValue: String) {
  RESOLVED("resolved"),
  UNRESOLVED("unresolved"),
  ;

  companion object {
    fun fromWire(value: String?): ReviewLaneResolutionState? =
      value?.trim()?.let { candidate -> entries.firstOrNull { it.wireValue == candidate } }
  }
}

data class ReviewLaneEffectivenessRow(
  val routedSkillCanonical: String,
  val packSlug: String,
  val area: String,
  val totalFindings: Int,
  val acceptedFindings: Int,
  val rejectedFindings: Int,
)

data class ImportedReview(
  val reviewRunId: String,
  val reviewSessionId: String,
  val rawText: String,
  val routedSkill: String?,
  val detectedScope: String?,
  val detectedStack: String?,
  val executionMode: ReviewExecutionMode?,
  val specialistReviews: List<String>,
  val findings: List<ImportedFinding>,
  val routedSkillCanonical: String = "unresolved",
  val detectedStackCanonical: String = "unresolved",
  val detectedScopeCanonical: String = "unresolved",
  val detectedScopeDetail: String? = null,
  val planLanes: List<ReviewRunLane> = emptyList(),
)

data class ReviewSummary(
  val reviewRunId: String,
  val reviewSessionId: String?,
  val routedSkill: String?,
  val detectedScope: String?,
  val detectedStack: String?,
  val executionMode: ReviewExecutionMode?,
  val specialistReviewsRaw: String?,
  val reviewFinishedAt: String?,
  val reviewFinishedEventEmittedAt: String?,
  val orchestratedRun: Boolean,
  val routedSkillCanonical: String = "unresolved",
  val detectedStackCanonical: String = "unresolved",
  val detectedScopeCanonical: String = "unresolved",
  val detectedScopeDetail: String? = null,
)

data class NormalizedStackLabel(
  val stack: String,
  val detail: String? = null,
  val fallback: Boolean = false,
  val fallbackReason: String? = null,
)

data class FindingMetadata(
  val findingId: String,
  val severity: String,
  val confidence: String,
)

data class NumberedFinding(
  val number: Int,
  val findingId: String,
  val severity: String,
  val confidence: String,
  val location: String,
  val description: String,
  val claimVerdict: ReviewClaimVerdict? = null,
  val scopeDisposition: ReviewScopeDisposition? = null,
  val citations: List<ReviewFindingCitation> = emptyList(),
  val severityAdjustment: ReviewSeverityAdjustment? = null,
  val laneSkillName: String? = null,
)

enum class ReviewFindingStatus {
  OPEN,
  FIXED,
  ACCEPTED,
  DECLINED,
  FALSE_POSITIVE,
  REJECTED,
  ;

  companion object {
    fun of(
      feedbackEvent: String?,
      loopOutcome: String?,
    ): ReviewFindingStatus =
      when {
        feedbackEvent == "fix_applied" -> FIXED
        feedbackEvent == "fix_rejected" -> DECLINED
        feedbackEvent == "false_positive" -> FALSE_POSITIVE
        loopOutcome == "addressed" -> FIXED
        loopOutcome == "rejected" -> REJECTED
        feedbackEvent == "finding_accepted" -> ACCEPTED
        else -> OPEN
      }
  }
}

data class TriageDecision(
  val number: Int,
  val findingId: String,
  val outcomeType: String,
  val note: String,
)

data class FeedbackRequest(
  val reviewRunId: String,
  val findingIds: List<String>,
  val eventType: String,
  val note: String,
)

data class FeedbackTelemetryOptions(
  val enabled: Boolean? = null,
  val level: String? = null,
  val routedSkillPlatformSlugs: Map<String, String> = emptyMap(),
)

data class FindingOutcomeRow(
  val reviewRunId: String,
  val findingId: String,
  val severity: String,
  val confidence: String,
  val issueCategory: String,
  val location: String,
  val description: String,
  val outcomeType: String,
  val note: String,
)
