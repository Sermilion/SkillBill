package skillbill.goalrunner.model

import skillbill.review.model.ReviewClaimVerdict
import skillbill.review.model.ReviewFindingCitation
import skillbill.review.model.ReviewIssueCategory
import skillbill.review.model.ReviewScopeDisposition
import skillbill.review.model.ReviewSeverityAdjustment
import skillbill.workflow.taskruntime.model.review.FeatureTaskRuntimeReviewSeverity

val UNADDRESSED_FINDING_SEVERITIES: Set<String> =
  FeatureTaskRuntimeReviewSeverity.entries.mapTo(linkedSetOf()) { it.wireValue }
val UNADDRESSED_FINDING_CATEGORIES: Set<String> = ReviewIssueCategory.entries.mapTo(linkedSetOf()) { it.wireValue }
internal val UNADDRESSED_FINDING_DEFAULT_CATEGORY: String = ReviewIssueCategory.OTHER.wireValue

internal const val UNADDRESSED_FINDING_DEFAULT_SEVERITY: String = "nit"

const val UNADDRESSED_FINDING_REJECTED_DISPOSITION: String = "rejected"

internal fun normalizedUnaddressedFindingCategory(issueCategory: String): String =
  issueCategory.takeIf { it in UNADDRESSED_FINDING_CATEGORIES } ?: UNADDRESSED_FINDING_DEFAULT_CATEGORY

internal fun normalizedUnaddressedFindingSeverity(severity: String): String =
  severity.takeIf { it in UNADDRESSED_FINDING_SEVERITIES } ?: UNADDRESSED_FINDING_DEFAULT_SEVERITY

enum class ReviewFindingOutcome(val wireValue: String) {
  ADDRESSED("addressed"),

  CARRIED("carried"),

  REJECTED("rejected"),
  ;

  companion object {
    fun fromWireValue(wireValue: String): ReviewFindingOutcome =
      entries.firstOrNull { it.wireValue == wireValue }
        ?: error("Unsupported review finding outcome '$wireValue'.")
  }
}

private val identityWhitespace = Regex("\\s+")

private fun reviewFindingIdentityKey(
  location: String,
  summary: String,
): String = "${normalizedIdentityPart(location)}|${normalizedIdentityPart(summary)}"

private fun normalizedIdentityPart(value: String): String = value.trim().lowercase().replace(identityWhitespace, " ")

data class UnaddressedFinding(
  val issueKey: String,
  val subtaskId: Int,
  val workflowId: String,
  val reviewPassNumber: Int,
  val findingOrdinal: Int,
  val severity: String,
  val issueCategory: String,
  val location: String,
  val summary: String,
  val reviewRunId: String? = null,
  val findingId: String? = null,
  val claimVerdict: ReviewClaimVerdict? = null,
  val scopeDisposition: ReviewScopeDisposition? = null,
  val citations: List<ReviewFindingCitation> = emptyList(),
  val severityAdjustment: ReviewSeverityAdjustment? = null,
  val verificationDisposition: String? = null,
  val verificationReason: String? = null,
) {
  val findingKey: String get() = reviewFindingIdentityKey(location, summary)
}

data class ReviewFindingOutcomeRecord(
  val workflowId: String,
  val reviewPassNumber: Int,
  val findingOrdinal: Int,
  val outcome: ReviewFindingOutcome,
  val reviewRunId: String? = null,
  val findingId: String? = null,
  val findingKey: String? = null,
) {
  val keyState: String = if (reviewRunId != null && findingId != null) "resolved" else "unresolved"
}

fun UnaddressedFinding.toOutcomeRecord(outcome: ReviewFindingOutcome): ReviewFindingOutcomeRecord =
  ReviewFindingOutcomeRecord(
    workflowId = workflowId,
    reviewPassNumber = reviewPassNumber,
    findingOrdinal = findingOrdinal,
    outcome = outcome,
    reviewRunId = reviewRunId,
    findingId = findingId,
    findingKey = findingKey,
  )

data class UnaddressedFindingsLedger(
  val issueKey: String,
  val findings: List<UnaddressedFinding>,
) {
  val severityBreakdown: Map<String, Int> =
    UNADDRESSED_FINDING_SEVERITIES.associateWith { severity ->
      findings.count { it.severity == severity }
    }
}
