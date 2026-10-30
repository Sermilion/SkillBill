package skillbill.infrastructure.sqlite.review.stats

import skillbill.infrastructure.sqlite.review.core.average
import skillbill.infrastructure.sqlite.review.core.rate
import skillbill.review.model.ReviewDeliveryGrainStats
import skillbill.review.model.ReviewHealthStats
import java.sql.Connection

internal val reviewHealthSeverities = listOf("Blocker", "Major", "Minor")
internal val reviewHealthConfidences = listOf("High", "Medium", "Low")
internal val reviewHealthScopes = listOf("branch_diff", "unstaged_changes", "working_tree", "unknown")

internal data class ReviewHealthPayload(
  internal val source: String,
  internal val payload: Map<String, Any?>,
  internal val deliveryIdentity: String? = null,
  internal val deliveryAttempts: Int? = null,
)

internal fun buildReviewHealthStats(
  connection: Connection,
  reviewRunId: String?,
): ReviewHealthStats {
  val parsedPayloads = loadStandaloneReviewPayloads(connection) + loadEmbeddedReviewPayloads(connection)
  val scopedPayloads =
    if (reviewRunId == null) {
      parsedPayloads
    } else {
      parsedPayloads.filter { it.payload.stringHealthValue("review_run_id") == reviewRunId }
    }
  val malformedRecords = scopedPayloads.count { it.payload.isEmpty() }
  val includedPayloads =
    parsedPayloads
      .filter { it.payload.isNotEmpty() }
      .filter { reviewRunId == null || it.payload.stringHealthValue("review_run_id") == reviewRunId }
  val findingCounts = includedPayloads.map { it.payload.healthInt("total_findings") }
  val acceptedFindings = includedPayloads.sumOf { it.payload.healthInt("accepted_findings") }
  val rejectedFindings = includedPayloads.sumOf { it.payload.healthInt("rejected_findings") }
  val unresolvedFindings = includedPayloads.sumOf { it.payload.healthInt("unresolved_findings") }
  val totalFindings = acceptedFindings + rejectedFindings + unresolvedFindings
  return ReviewHealthStats(
    totalReviewPayloadRecords = scopedPayloads.size,
    includedReviewPayloadRecords = includedPayloads.size,
    standaloneReviewPayloadRecords = scopedPayloads.count { it.source == "standalone" },
    embeddedReviewPayloadRecords = scopedPayloads.count { it.source == "embedded" },
    malformedReviewPayloadRecords = malformedRecords,
    dataQualityDebtRecords = malformedRecords,
    totalFindings = totalFindings,
    averageFindings = average(findingCounts),
    medianFindings = median(findingCounts),
    p90Findings = p90(findingCounts),
    acceptedFindings = acceptedFindings,
    rejectedFindings = rejectedFindings,
    unresolvedFindings = unresolvedFindings,
    acceptedRate = rate(acceptedFindings, totalFindings),
    rejectedRate = rate(rejectedFindings, totalFindings),
    unresolvedRate = rate(unresolvedFindings, totalFindings),
    severityCounts = aggregateFindingDetailCounts(includedPayloads, "severity", reviewHealthSeverities),
    confidenceCounts = aggregateFindingDetailCounts(includedPayloads, "confidence", reviewHealthConfidences),
    latestOutcomeCounts = aggregateLatestOutcomeCounts(includedPayloads),
    issueCategoryCounts = aggregateFindingDetailCounts(includedPayloads, "issue_category", emptyList()),
    categorySeverityCounts = aggregateCategorySeverityCrossTab(includedPayloads),
    platformCounts = aggregatePayloadValueCounts(includedPayloads, "platform_slug", emptyList(), "unknown"),
    scopeCounts = aggregatePayloadValueCounts(includedPayloads, "scope_type", reviewHealthScopes, "unknown"),
    sourceCounts = countReviewHealthSources(includedPayloads, malformedRecords),
    reviewDeliveryGrain = reviewDeliveryGrain(scopedPayloads, includedPayloads),
  )
}

private fun reviewDeliveryGrain(
  scopedPayloads: List<ReviewHealthPayload>,
  includedPayloads: List<ReviewHealthPayload>,
): ReviewDeliveryGrainStats {
  val identities = scopedPayloads.map { it.deliveryIdentity?.takeIf(String::isNotBlank) }
  val reviewRunIds = includedPayloads.map { it.payload.stringHealthValue("review_run_id").takeIf(String::isNotBlank) }
  return ReviewDeliveryGrainStats(
    queuedDeliveryRows = scopedPayloads.count { it.deliveryAttempts != null },
    deliveryAttempts = scopedPayloads.sumOf { it.deliveryAttempts ?: 0 },
    logicalEvents = identities.filterNotNull().distinct().size,
    rowsWithUnknownDeliveryIdentity =
      scopedPayloads.count { it.deliveryAttempts != null } -
        identities.count { it != null },
    logicalReviews = reviewRunIds.filterNotNull().distinct().size,
    recordsWithUnknownReview = reviewRunIds.count { it == null },
  )
}
