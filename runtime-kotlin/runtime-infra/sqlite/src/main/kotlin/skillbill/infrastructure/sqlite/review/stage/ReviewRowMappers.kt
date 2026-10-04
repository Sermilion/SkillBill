package skillbill.infrastructure.sqlite.review.stage

import skillbill.contracts.review.ReviewFindingPayloadKeys
import skillbill.contracts.review.ReviewFinishedTelemetryPayloadKeys
import skillbill.contracts.review.ReviewVerificationSignalKeys
import skillbill.contracts.telemetry.LifecycleTelemetryPayloadKeys
import skillbill.review.model.ImportedFinding
import skillbill.review.model.NumberedFinding
import skillbill.review.model.ReviewClaimVerdict
import skillbill.review.model.ReviewExecutionMode
import skillbill.review.model.ReviewFindingCitation
import skillbill.review.model.ReviewScopeDisposition
import skillbill.review.model.ReviewSeverityAdjustment
import skillbill.review.model.ReviewSeverityAdjustmentDirection
import skillbill.review.model.ReviewSummary
import java.sql.ResultSet

internal fun ResultSet.toImportedFinding(): ImportedFinding =
  ImportedFinding(
    findingId = getString(ReviewFindingPayloadKeys.FINDING_ID),
    severity = getString(ReviewFinishedTelemetryPayloadKeys.SEVERITY),
    confidence = getString(ReviewFinishedTelemetryPayloadKeys.CONFIDENCE),
    issueCategory = getString(ReviewFindingPayloadKeys.ISSUE_CATEGORY),
    location = getString(ReviewFinishedTelemetryPayloadKeys.LOCATION),
    description = getString(ReviewFinishedTelemetryPayloadKeys.DESCRIPTION),
    findingText = getString("finding_text"),
    laneSkillName = getString("lane_skill_name"),
  )

internal fun ResultSet.toReviewSummary(): ReviewSummary =
  ReviewSummary(
    reviewRunId = getString(ReviewVerificationSignalKeys.REVIEW_RUN_ID),
    reviewSessionId = getString(ReviewFinishedTelemetryPayloadKeys.REVIEW_SESSION_ID),
    routedSkill = getString(LifecycleTelemetryPayloadKeys.ROUTED_SKILL),
    detectedScope = getString("detected_scope"),
    detectedStack = getString(LifecycleTelemetryPayloadKeys.DETECTED_STACK),
    executionMode = getString(ReviewFinishedTelemetryPayloadKeys.EXECUTION_MODE)?.let(ReviewExecutionMode::fromWire),
    specialistReviewsRaw = getString("specialist_reviews"),
    reviewFinishedAt = getString(ReviewFinishedTelemetryPayloadKeys.REVIEW_FINISHED_AT),
    reviewFinishedEventEmittedAt = getString("review_finished_event_emitted_at"),
    orchestratedRun = getBoolean("orchestrated_run"),
    routedSkillCanonical = getString("routed_skill_canonical") ?: "unresolved",
    detectedStackCanonical = getString("detected_stack_canonical") ?: "unresolved",
    detectedScopeCanonical = getString("detected_scope_canonical") ?: "unresolved",
    detectedScopeDetail = getString("detected_scope_detail"),
  )

internal fun ResultSet.toNumberedFinding(number: Int): NumberedFinding =
  NumberedFinding(
    number = number,
    findingId = getString(ReviewFindingPayloadKeys.FINDING_ID),
    severity = getString(ReviewFinishedTelemetryPayloadKeys.SEVERITY),
    confidence = getString(ReviewFinishedTelemetryPayloadKeys.CONFIDENCE),
    location = getString(ReviewFinishedTelemetryPayloadKeys.LOCATION),
    description = getString(ReviewFinishedTelemetryPayloadKeys.DESCRIPTION),
    claimVerdict =
      getString(ReviewFindingPayloadKeys.CLAIM_VERDICT)
        ?.trim()
        ?.takeIf(String::isNotBlank)
        ?.let(ReviewClaimVerdict::fromWire),
    scopeDisposition =
      getString(ReviewFindingPayloadKeys.SCOPE_DISPOSITION)?.trim()?.takeIf(String::isNotBlank)
        ?.let(ReviewScopeDisposition::fromWire),
    citations = ReviewFindingCitation.decodeList(getString(ReviewFindingPayloadKeys.CITATIONS)),
    severityAdjustment =
      numberedFindingAdjustment(
        getString("severity_adjustment_direction"),
        getString("severity_adjustment_justification"),
      ),
  )

private fun numberedFindingAdjustment(
  direction: String?,
  justification: String?,
): ReviewSeverityAdjustment? {
  val parsedDirection =
    direction?.trim()?.takeIf(String::isNotBlank)?.let(ReviewSeverityAdjustmentDirection::fromWire)
      ?: return null
  val parsedJustification = justification?.trim()?.takeIf(String::isNotBlank) ?: return null
  return ReviewSeverityAdjustment(parsedDirection, parsedJustification)
}
