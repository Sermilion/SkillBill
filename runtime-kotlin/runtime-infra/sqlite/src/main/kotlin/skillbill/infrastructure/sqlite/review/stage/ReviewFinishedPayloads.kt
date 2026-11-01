package skillbill.infrastructure.sqlite.review.stage

import skillbill.contracts.JsonCodec
import skillbill.contracts.review.ReviewFinishedTelemetryPayloadKeys
import skillbill.infrastructure.sqlite.core.ops.bindAll
import skillbill.infrastructure.sqlite.review.core.reviewPlatformSlug
import skillbill.infrastructure.sqlite.review.core.summarizeFindingRows
import skillbill.infrastructure.sqlite.telemetry.SqliteReviewTelemetryPayloadKeys
import skillbill.learnings.model.LearningScope
import skillbill.review.attribution.normalizeRoutedSkill
import skillbill.review.attribution.normalizeScopeType
import skillbill.review.attribution.normalizeStackLabel
import skillbill.review.model.FindingOutcomeRow
import skillbill.review.model.ReviewFindingDetail
import skillbill.review.model.ReviewFindingStats
import skillbill.review.model.ReviewFinishedFindingStats
import skillbill.review.model.ReviewFinishedTelemetry
import skillbill.review.model.ReviewLearningEntry
import skillbill.review.model.ReviewLearningsSummary
import skillbill.review.model.ReviewSummary
import java.sql.Connection

internal fun reviewFinishedPayload(
  connection: Connection,
  reviewSummary: ReviewSummary,
  findingRows: List<FindingOutcomeRow>,
  level: String,
  routedSkillPlatformSlugs: Map<String, String> = emptyMap(),
): ReviewFinishedTelemetry {
  val stats = filterReviewFinishedSummary(summarizeFindingRows(findingRows), level)
  val learningsSection = buildLearningsSection(connection, reviewSummary.reviewSessionId.orEmpty(), level)
  val normalizedStack = normalizeStackLabel(reviewSummary.detectedStack, routedSkillPlatformSlugs.values)
  val normalizedRoutedSkill = normalizeRoutedSkill(reviewSummary.routedSkill)
  val normalizedPlatformSlug =
    reviewPlatformSlug(
      reviewSummary.detectedStack,
      normalizedRoutedSkill,
      routedSkillPlatformSlugs,
    )
  val detectedStackDetail = normalizedStack.detail?.takeIf { it != normalizedPlatformSlug }
  val stageMetrics =
    aggregateReviewStageMetrics(
      connection = connection,
      reviewRunId = reviewSummary.reviewRunId,
      runFindingCount = stats.totalFindings,
    )
  return ReviewFinishedTelemetry(
    findingStats = stats,
    reviewRunId = reviewSummary.reviewRunId,
    reviewSessionId = reviewSummary.reviewSessionId.orEmpty(),
    routedSkill = normalizedRoutedSkill,
    reviewSubskills = parseSpecialistReviews(reviewSummary.specialistReviewsRaw),
    reviewScope = normalizeReviewScope(reviewSummary.detectedScope),
    reviewPlatform = normalizedPlatformSlug,
    detectedStack = normalizedPlatformSlug,
    detectedStackDetail = detectedStackDetail,
    fallback = normalizedStack.fallback,
    fallbackReason = normalizedStack.fallbackReason,
    platformSlug = normalizedPlatformSlug,
    scopeType = normalizeScopeType(reviewSummary.detectedScope),
    executionMode = reviewSummary.executionMode,
    reviewFinishedAt = reviewSummary.reviewFinishedAt,
    learnings = learningsSection,
    stageMetrics = stageMetrics,
  )
}

internal fun filterReviewFinishedSummary(
  summary: ReviewFindingStats,
  level: String,
): ReviewFinishedFindingStats =
  ReviewFinishedFindingStats(
    totalFindings = summary.totalFindings,
    acceptedFindings = summary.acceptedFindings,
    rejectedFindings = summary.rejectedFindings,
    unresolvedFindings = summary.unresolvedFindings,
    acceptedRate = summary.acceptedRate,
    rejectedRate = summary.rejectedRate,
    acceptedFindingDetails = reviewFindingDetails(summary.acceptedFindingDetails, level == "full"),
    rejectedFindingDetails = reviewFindingDetails(summary.rejectedFindingDetails, level == "full"),
  )

internal fun buildLearningsSection(
  connection: Connection,
  reviewSessionId: String,
  level: String,
): ReviewLearningsSummary {
  val defaultScopeCounts = LearningScope.emptyScopeCounts()
  val learningsData =
    if (reviewSessionId.isEmpty()) {
      null
    } else {
      fetchSessionLearnings(connection, reviewSessionId)
    }
  val learningsEntries =
    learningsEntries(
      entries =
        (
          learningsData?.get(
            ReviewFinishedTelemetryPayloadKeys.LEARNINGS,
          ) as? List<*>
        )?.filterIsInstance<Map<String, Any?>>() ?: emptyList(),
      includeText = level == "full",
    )
  val scopeCounts =
    defaultScopeCounts + (
      (learningsData?.get(ReviewFinishedTelemetryPayloadKeys.SCOPE_COUNTS) as? Map<*, *>)
        ?.filterKeys { it is String }
        ?.mapKeys { it.key as String }
        ?.mapValues { entry -> (entry.value as? Number)?.toInt() ?: 0 }
        ?: emptyMap()
    )
  return ReviewLearningsSummary(
    appliedCount =
      (
        learningsData?.get(
          SqliteReviewTelemetryPayloadKeys.APPLIED_LEARNING_COUNT,
        ) as? Number
      )?.toInt() ?: 0,
    appliedReferences =
      (learningsData?.get(SqliteReviewTelemetryPayloadKeys.APPLIED_LEARNING_REFERENCES) as? List<*>)
        ?.mapNotNull { it?.toString() }
        ?: emptyList(),
    appliedSummary = learningsData?.get(SqliteReviewTelemetryPayloadKeys.APPLIED_LEARNINGS)?.toString() ?: "none",
    scopeCounts = scopeCounts,
    entries = learningsEntries,
  )
}

internal fun learningsEntries(
  entries: List<Map<String, Any?>>,
  includeText: Boolean,
): List<ReviewLearningEntry> =
  entries.map { entry ->
    if (includeText) {
      ReviewLearningEntry(
        reference = entry[ReviewFinishedTelemetryPayloadKeys.REFERENCE]?.toString(),
        scope = entry[ReviewFinishedTelemetryPayloadKeys.SCOPE]?.toString(),
        title = entry[ReviewFinishedTelemetryPayloadKeys.TITLE]?.toString(),
        ruleText = entry[ReviewFinishedTelemetryPayloadKeys.RULE_TEXT]?.toString(),
      )
    } else {
      ReviewLearningEntry(
        reference = entry[ReviewFinishedTelemetryPayloadKeys.REFERENCE]?.toString(),
        scope = entry[ReviewFinishedTelemetryPayloadKeys.SCOPE]?.toString(),
      )
    }
  }

internal fun reviewFindingDetails(
  details: List<ReviewFindingDetail>,
  includeText: Boolean,
): List<ReviewFindingDetail> =
  details.map { detail ->
    if (includeText) {
      detail
    } else {
      detail.copy(
        location = "",
        description = "",
        note = "",
      )
    }
  }

internal fun parseSpecialistReviews(rawValue: String?): List<String> =
  rawValue.orEmpty().split(",").map(String::trim).filter(String::isNotEmpty)

internal fun normalizeReviewScope(detectedScope: String?): String = detectedScope.orEmpty().substringBefore("(").trim()

internal fun fetchSessionLearnings(
  connection: Connection,
  reviewSessionId: String,
): Map<String, Any?>? {
  val rawJson =
    connection.prepareStatement(
      """
      SELECT learnings_json
      FROM session_learnings
      WHERE review_session_id = ?
      """.trimIndent(),
    ).use { statement ->
      statement.bindAll(reviewSessionId)
      statement.executeQuery().use { resultSet ->
        if (resultSet.next()) {
          resultSet.getString("learnings_json")
        } else {
          null
        }
      }
    }
  return rawJson?.let(::decodeSessionLearnings)
}

private fun decodeSessionLearnings(rawJson: String): Map<String, Any?>? =
  JsonCodec.parseObjectOrNull(rawJson)?.let {
    JsonCodec.anyToStringAnyMap(JsonCodec.jsonElementToValue(it))
  }
