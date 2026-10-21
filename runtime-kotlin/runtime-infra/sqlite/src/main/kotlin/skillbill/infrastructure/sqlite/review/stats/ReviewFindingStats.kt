package skillbill.infrastructure.sqlite.review.stats

import skillbill.contracts.review.ReviewFindingPayloadKeys
import skillbill.contracts.review.ReviewFinishedTelemetryPayloadKeys
import skillbill.contracts.review.ReviewVerificationSignalKeys
import skillbill.infrastructure.sqlite.core.ops.bindAll
import skillbill.review.model.FindingOutcomeRow
import skillbill.review.model.FindingOutcomeType
import skillbill.review.model.ParallelReviewSeverity
import skillbill.review.model.ReviewFindingDetail
import skillbill.review.model.ReviewFindingStats
import skillbill.review.model.ReviewSummary
import java.sql.Connection

internal val acceptedFindingOutcomeTypes =
  setOf(
    FindingOutcomeType.FindingAccepted,
    FindingOutcomeType.FixApplied,
    FindingOutcomeType.FindingEdited,
  ).map { it.wireValue }.toSet()
internal val rejectedFindingOutcomeTypes =
  setOf(FindingOutcomeType.FixRejected, FindingOutcomeType.FalsePositive).map { it.wireValue }.toSet()
private val findingOutcomeTypes = FindingOutcomeType.entries.map { it.wireValue }

private data class FindingQueryFilter(
  internal val latestFeedbackFilter: String,
  internal val findingsFilter: String,
  internal val parameters: List<String>,
)

private class FindingSummaryAccumulator {
  private val outcomeCounts = findingOutcomeTypes.associateWith { 0 }.toMutableMap()
  private val acceptedSeverityCounts = emptySeverityCounts().toMutableMap()
  private val rejectedSeverityCounts = emptySeverityCounts().toMutableMap()
  private val unresolvedSeverityCounts = emptySeverityCounts().toMutableMap()
  private val acceptedFindingDetails = mutableListOf<ReviewFindingDetail>()
  private val rejectedFindingDetails = mutableListOf<ReviewFindingDetail>()
  private var acceptedFindings = 0
  private var rejectedFindings = 0
  private var unresolvedFindings = 0
  private var rejectedFindingsWithNotes = 0

  fun apply(row: FindingOutcomeRow) {
    incrementOutcomeCount(row.outcomeType)
    when {
      row.outcomeType in acceptedFindingOutcomeTypes -> applyAcceptedFinding(row)
      row.outcomeType in rejectedFindingOutcomeTypes -> applyRejectedFinding(row)
      else -> applyUnresolvedFinding(row)
    }
  }

  fun toStats(): ReviewFindingStats {
    val totalFindings = acceptedFindings + rejectedFindings + unresolvedFindings
    return ReviewFindingStats(
      totalFindings = totalFindings,
      acceptedFindings = acceptedFindings,
      rejectedFindings = rejectedFindings,
      unresolvedFindings = unresolvedFindings,
      acceptedRate = rate(acceptedFindings, totalFindings),
      rejectedRate = rate(rejectedFindings, totalFindings),
      latestOutcomeCounts = outcomeCounts.toMap(),
      acceptedSeverityCounts = acceptedSeverityCounts.toMap(),
      rejectedSeverityCounts = rejectedSeverityCounts.toMap(),
      unresolvedSeverityCounts = unresolvedSeverityCounts.toMap(),
      acceptedFindingDetails = acceptedFindingDetails.toList(),
      rejectedFindingsWithNotes = rejectedFindingsWithNotes,
      rejectedFindingDetails = rejectedFindingDetails.toList(),
    )
  }

  private fun incrementOutcomeCount(outcomeType: String) {
    if (outcomeType in outcomeCounts) {
      outcomeCounts[outcomeType] = outcomeCounts.getValue(outcomeType) + 1
    }
  }

  private fun applyAcceptedFinding(row: FindingOutcomeRow) {
    acceptedFindings += 1
    acceptedSeverityCounts[row.severity] = acceptedSeverityCounts.getValue(row.severity) + 1
    acceptedFindingDetails +=
      ReviewFindingDetail(
        findingId = row.findingId,
        severity = row.severity,
        confidence = row.confidence,
        issueCategory = row.issueCategory,
        location = row.location,
        description = row.description,
        outcomeType = row.outcomeType,
      )
  }

  private fun applyRejectedFinding(row: FindingOutcomeRow) {
    rejectedFindings += 1
    rejectedSeverityCounts[row.severity] = rejectedSeverityCounts.getValue(row.severity) + 1
    if (row.note.isNotEmpty()) {
      rejectedFindingsWithNotes += 1
    }
    rejectedFindingDetails +=
      ReviewFindingDetail(
        findingId = row.findingId,
        severity = row.severity,
        confidence = row.confidence,
        issueCategory = row.issueCategory,
        location = row.location,
        description = row.description,
        outcomeType = row.outcomeType,
        note = row.note,
      )
  }

  private fun applyUnresolvedFinding(row: FindingOutcomeRow) {
    unresolvedFindings += 1
    unresolvedSeverityCounts[row.severity] = unresolvedSeverityCounts.getValue(row.severity) + 1
  }
}

internal fun queryLatestFindingOutcomes(
  connection: Connection,
  reviewRunId: String?,
): List<FindingOutcomeRow> {
  val filter = buildFindingOutcomeFilters(reviewRunId)
  return connection.prepareStatement(latestFindingOutcomesSql(filter)).use { statement ->
    statement.bindAll(filter.parameters)
    statement.executeQuery().use { resultSet ->
      buildList {
        while (resultSet.next()) {
          add(
            FindingOutcomeRow(
              reviewRunId = resultSet.getString(ReviewVerificationSignalKeys.REVIEW_RUN_ID),
              findingId = resultSet.getString(ReviewFindingPayloadKeys.FINDING_ID),
              severity = resultSet.getString(ReviewFinishedTelemetryPayloadKeys.SEVERITY),
              confidence = resultSet.getString(ReviewFinishedTelemetryPayloadKeys.CONFIDENCE),
              issueCategory = resultSet.getString(ReviewFindingPayloadKeys.ISSUE_CATEGORY),
              location = resultSet.getString(ReviewFinishedTelemetryPayloadKeys.LOCATION),
              description = resultSet.getString(ReviewFinishedTelemetryPayloadKeys.DESCRIPTION),
              outcomeType = resultSet.getString(ReviewFinishedTelemetryPayloadKeys.OUTCOME_TYPE).orEmpty(),
              note = resultSet.getString(ReviewFinishedTelemetryPayloadKeys.NOTE).orEmpty(),
            ),
          )
        }
      }
    }
  }
}

internal fun summarizeFindingRows(findingRows: List<FindingOutcomeRow>): ReviewFindingStats {
  val summary = FindingSummaryAccumulator()
  findingRows.forEach(summary::apply)
  return summary.toStats()
}

internal fun shouldSkipReviewFinishedTelemetry(
  findingRows: List<FindingOutcomeRow>,
  reviewSummary: ReviewSummary,
): Boolean {
  val summary = summarizeFindingRows(findingRows)
  val resolvedFindings = summary.acceptedFindings + summary.rejectedFindings
  return summary.totalFindings > 0 &&
    resolvedFindings == 0 &&
    (!reviewSummary.reviewFinishedAt.isNullOrEmpty() || !reviewSummary.reviewFinishedEventEmittedAt.isNullOrEmpty())
}

internal fun emptySeverityCounts(): Map<String, Int> = ParallelReviewSeverity.entries.associate { it.displayName to 0 }

private fun buildFindingOutcomeFilters(reviewRunId: String?): FindingQueryFilter =
  if (reviewRunId == null) {
    FindingQueryFilter(latestFeedbackFilter = "", findingsFilter = "", parameters = emptyList())
  } else {
    FindingQueryFilter(
      latestFeedbackFilter = "WHERE review_run_id = ?",
      findingsFilter = "WHERE f.review_run_id = ?",
      parameters = listOf(reviewRunId, reviewRunId),
    )
  }

private fun latestFindingOutcomesSql(filter: FindingQueryFilter): String =
  """
  WITH latest_feedback AS (
    SELECT review_run_id, finding_id, MAX(id) AS latest_id
    FROM feedback_events
    ${filter.latestFeedbackFilter}
    GROUP BY review_run_id, finding_id
  ),
  latest_loop_outcome AS (
    SELECT review_run_id, finding_id, MAX(rowid) AS latest_rowid
    FROM review_finding_outcomes
    WHERE key_state = 'resolved'
    GROUP BY review_run_id, finding_id
  )
  SELECT
    f.review_run_id,
    f.finding_id,
    f.severity,
    f.confidence,
    f.issue_category,
    f.location,
    f.description,
    COALESCE(
      fe.event_type,
      CASE rfo.outcome
        WHEN 'addressed' THEN '${FindingOutcomeType.FixApplied.wireValue}'
        WHEN 'rejected' THEN '${FindingOutcomeType.FixRejected.wireValue}'
        ELSE ''
      END,
      ''
    ) AS outcome_type,
    COALESCE(fe.note, '') AS note
  FROM findings f
  LEFT JOIN latest_feedback lf
    ON lf.review_run_id = f.review_run_id AND lf.finding_id = f.finding_id
  LEFT JOIN feedback_events fe
    ON fe.id = lf.latest_id
  LEFT JOIN latest_loop_outcome llo
    ON llo.review_run_id = f.review_run_id AND llo.finding_id = f.finding_id
  LEFT JOIN review_finding_outcomes rfo
    ON rfo.rowid = llo.latest_rowid
  ${filter.findingsFilter}
  ORDER BY f.review_run_id, f.finding_id
  """.trimIndent()
