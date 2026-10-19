package skillbill.application.telemetry.lifecycle

import skillbill.application.telemetry.model.FeatureVerifyFinishedRequest
import skillbill.application.telemetry.model.PrDescriptionGeneratedRequest
import skillbill.application.telemetry.model.QualityCheckFinishedRequest
import skillbill.application.telemetry.service.prDescriptionWasEditedByUser
import skillbill.contracts.JsonPayloadContract
import skillbill.contracts.telemetry.LifecycleErrorContract
import skillbill.contracts.telemetry.LifecycleOkContract
import skillbill.contracts.telemetry.LifecycleOrchestratedFinishedContract
import skillbill.contracts.telemetry.LifecycleOrchestratedStartedSkippedContract
import skillbill.contracts.telemetry.LifecycleSkippedContract
import skillbill.contracts.telemetry.LifecycleTelemetryPayloadKeys

internal fun lifecycleOkPayload(sessionId: String): JsonPayloadContract = LifecycleOkContract(sessionId)

internal fun lifecycleSkippedPayload(sessionId: String): JsonPayloadContract = LifecycleSkippedContract(sessionId)

internal fun lifecycleErrorPayload(
  sessionId: String,
  error: String,
): JsonPayloadContract = LifecycleErrorContract(sessionId, error)

internal fun orchestratedStartedSkippedPayload(): JsonPayloadContract = LifecycleOrchestratedStartedSkippedContract()

internal fun QualityCheckFinishedRequest.orchestratedPayload(level: String): JsonPayloadContract =
  LifecycleOrchestratedFinishedContract(qualityCheckPayloadContract(level))

internal fun FeatureVerifyFinishedRequest.orchestratedPayload(level: String): JsonPayloadContract =
  LifecycleOrchestratedFinishedContract(featureVerifyPayloadContract(level))

internal fun PrDescriptionGeneratedRequest.orchestratedPayload(level: String): JsonPayloadContract =
  LifecycleOrchestratedFinishedContract(prDescriptionPayloadContract(level))

private fun QualityCheckFinishedRequest.qualityCheckPayloadContract(level: String): JsonPayloadContract =
  QualityCheckFinishedTelemetryPayload(
    routedSkill = routedSkill,
    detectedStack = detectedStack,
    fallback = fallback,
    fallbackReason = fallbackReason,
    scopeType = scopeType,
    initialFailureCount = initialFailureCount,
    finalFailureCount = finalFailureCount,
    iterations = iterations,
    result = result,
    durationSeconds = durationSeconds.toLong(),
    level = level,
    failingCheckNames = failingCheckNames,
    unsupportedReason = unsupportedReason,
  )

private fun FeatureVerifyFinishedRequest.featureVerifyPayloadContract(level: String): JsonPayloadContract =
  FeatureVerifyFinishedTelemetryPayload(
    acceptanceCriteriaCount = acceptanceCriteriaCount,
    rolloutRelevant = rolloutRelevant,
    featureFlagAuditPerformed = featureFlagAuditPerformed,
    reviewIterations = reviewIterations,
    auditResult = auditResult,
    completionStatus = completionStatus,
    historyRelevance = historyRelevance,
    historyHelpfulness = historyHelpfulness,
    durationSeconds = durationSeconds.toLong(),
    level = level,
    specSummary = specSummary,
    gapsFound = gapsFound,
    qaOutcome = qaOutcome,
    qaReason = qaReason,
  )

private fun PrDescriptionGeneratedRequest.prDescriptionPayloadContract(level: String): JsonPayloadContract =
  PrDescriptionGeneratedTelemetryPayload(
    commitCount = commitCount,
    filesChangedCount = filesChangedCount,
    wasEditedByUser = wasEditedByUser || prDescriptionWasEditedByUser(generatedDescription, finalPrBody),
    prCreated = prCreated,
    level = level,
    prTitle = prTitle,
  )

private data class QualityCheckFinishedTelemetryPayload(
  val routedSkill: String,
  val detectedStack: String,
  val fallback: Boolean,
  val fallbackReason: String?,
  val scopeType: String,
  val initialFailureCount: Int,
  val finalFailureCount: Int,
  val iterations: Int,
  val result: String,
  val durationSeconds: Long,
  val level: String,
  val failingCheckNames: List<String>,
  val unsupportedReason: String?,
) : JsonPayloadContract {
  override fun toPayload(): Map<String, Any?> =
    linkedMapOf<String, Any?>(
      LifecycleTelemetryPayloadKeys.ROUTED_SKILL to routedSkill,
      LifecycleTelemetryPayloadKeys.DETECTED_STACK to detectedStack,
      LifecycleTelemetryPayloadKeys.FALLBACK to fallback,
      LifecycleTelemetryPayloadKeys.SCOPE_TYPE to scopeType,
      LifecycleTelemetryPayloadKeys.INITIAL_FAILURE_COUNT to initialFailureCount,
      LifecycleTelemetryPayloadKeys.FINAL_FAILURE_COUNT to finalFailureCount,
      LifecycleTelemetryPayloadKeys.ITERATIONS to iterations,
      LifecycleTelemetryPayloadKeys.RESULT to result,
      LifecycleTelemetryPayloadKeys.DURATION_SECONDS to durationSeconds,
      LifecycleTelemetryPayloadKeys.SKILL to "bill-code-check",
    ).apply {
      if (fallback && !fallbackReason.isNullOrBlank()) {
        put(LifecycleTelemetryPayloadKeys.FALLBACK_REASON, fallbackReason)
      }
      if (level == "full") {
        put(LifecycleTelemetryPayloadKeys.FAILING_CHECK_NAMES, failingCheckNames)
        put(LifecycleTelemetryPayloadKeys.UNSUPPORTED_REASON, unsupportedReason)
      }
    }
}

private data class FeatureVerifyFinishedTelemetryPayload(
  val acceptanceCriteriaCount: Int,
  val rolloutRelevant: Boolean,
  val featureFlagAuditPerformed: Boolean,
  val reviewIterations: Int,
  val auditResult: String,
  val completionStatus: String,
  val historyRelevance: String,
  val historyHelpfulness: String,
  val durationSeconds: Long,
  val level: String,
  val specSummary: String,
  val gapsFound: List<String>,
  val qaOutcome: String?,
  val qaReason: String?,
) : JsonPayloadContract {
  override fun toPayload(): Map<String, Any?> =
    linkedMapOf<String, Any?>(
      LifecycleTelemetryPayloadKeys.ACCEPTANCE_CRITERIA_COUNT to acceptanceCriteriaCount,
      LifecycleTelemetryPayloadKeys.ROLLOUT_RELEVANT to rolloutRelevant,
      LifecycleTelemetryPayloadKeys.FEATURE_FLAG_AUDIT_PERFORMED to featureFlagAuditPerformed,
      LifecycleTelemetryPayloadKeys.REVIEW_ITERATIONS to reviewIterations,
      LifecycleTelemetryPayloadKeys.AUDIT_RESULT to auditResult,
      LifecycleTelemetryPayloadKeys.COMPLETION_STATUS to completionStatus,
      LifecycleTelemetryPayloadKeys.HISTORY_RELEVANCE to historyRelevance,
      LifecycleTelemetryPayloadKeys.HISTORY_HELPFULNESS to historyHelpfulness,
      LifecycleTelemetryPayloadKeys.DURATION_SECONDS to durationSeconds,
      LifecycleTelemetryPayloadKeys.SKILL to "bill-feature-verify",
    ).apply {
      qaOutcome?.let { outcome ->
        put(LifecycleTelemetryPayloadKeys.QA_OUTCOME, outcome)
        put(LifecycleTelemetryPayloadKeys.QA_REASON, qaReason)
      }
      if (level == "full") {
        put(LifecycleTelemetryPayloadKeys.SPEC_SUMMARY, specSummary)
        put(LifecycleTelemetryPayloadKeys.GAPS_FOUND, gapsFound)
      }
    }
}

private data class PrDescriptionGeneratedTelemetryPayload(
  val commitCount: Int,
  val filesChangedCount: Int,
  val wasEditedByUser: Boolean,
  val prCreated: Boolean,
  val level: String,
  val prTitle: String,
) : JsonPayloadContract {
  override fun toPayload(): Map<String, Any?> =
    linkedMapOf<String, Any?>(
      LifecycleTelemetryPayloadKeys.COMMIT_COUNT to commitCount,
      LifecycleTelemetryPayloadKeys.FILES_CHANGED_COUNT to filesChangedCount,
      LifecycleTelemetryPayloadKeys.WAS_EDITED_BY_USER to wasEditedByUser,
      LifecycleTelemetryPayloadKeys.PR_CREATED to prCreated,
      LifecycleTelemetryPayloadKeys.SKILL to "bill-pr-description",
    ).apply {
      if (level == "full") {
        put(LifecycleTelemetryPayloadKeys.PR_TITLE, prTitle)
      }
    }
}
