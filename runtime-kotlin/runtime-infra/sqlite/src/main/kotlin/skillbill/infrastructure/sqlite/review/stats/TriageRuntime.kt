package skillbill.infrastructure.sqlite.review.stats

import skillbill.infrastructure.sqlite.core.ops.bindAll
import skillbill.infrastructure.sqlite.review.stage.ReviewRuntime
import skillbill.review.model.FeedbackRequest
import skillbill.review.model.FeedbackTelemetryOptions
import skillbill.review.model.NumberedFinding
import skillbill.review.model.ReviewFinishedTelemetry
import skillbill.review.model.TriageDecision
import skillbill.review.parsing.TriageDecisionParser
import java.sql.Connection

internal object TriageRuntime {
  fun expandBulkDecisions(
    rawDecisions: List<String>,
    numberedFindings: List<NumberedFinding>,
  ): List<String> = TriageDecisionParser.expandBulkDecisions(rawDecisions, numberedFindings)

  fun expandStructuredDecision(rawDecision: String): List<String>? =
    TriageDecisionParser.expandStructuredDecision(rawDecision)

  fun parseTriageDecisions(
    rawDecisions: List<String>,
    numberedFindings: List<NumberedFinding>,
  ): List<TriageDecision> = TriageDecisionParser.parseTriageDecisions(rawDecisions, numberedFindings)

  fun normalizeTriageAction(rawAction: String): String = TriageDecisionParser.normalizeTriageAction(rawAction)

  fun normalizeTriageNote(rawNote: String?): String = TriageDecisionParser.normalizeTriageNote(rawNote)

  fun recordFeedbackWithoutTransaction(
    connection: Connection,
    request: FeedbackRequest,
    telemetryOptions: FeedbackTelemetryOptions = FeedbackTelemetryOptions(),
    runtimeVersion: String,
  ): ReviewFinishedTelemetry? {
    validateFeedbackRequest(connection, request)
    request.findingIds.forEach { findingId ->
      insertFeedbackEvent(connection, request.reviewRunId, findingId, request.eventType, request.note)
    }
    return ReviewStatsRuntime.updateReviewFinishedTelemetryState(
      connection = connection,
      reviewRunId = request.reviewRunId,
      request =
        ReviewFinishedTelemetryUpdateRequest(
          enabled = telemetryOptions.enabled ?: false,
          level = telemetryOptions.level ?: "off",
          routedSkillPlatformSlugs = telemetryOptions.routedSkillPlatformSlugs,
        ),
      runtimeVersion = runtimeVersion,
    )
  }
}

private fun validateFeedbackRequest(
  connection: Connection,
  request: FeedbackRequest,
) {
  require(ReviewRuntime.reviewExists(connection, request.reviewRunId)) {
    "Unknown review run id '${request.reviewRunId}'. Import the review first."
  }
  val missingFindings =
    request.findingIds.filterNot { findingId ->
      ReviewRuntime.findingExists(connection, request.reviewRunId, findingId)
    }.sorted()
  require(missingFindings.isEmpty()) {
    "Unknown finding ids for review run '${request.reviewRunId}': ${missingFindings.joinToString(", ")}"
  }
}

private fun insertFeedbackEvent(
  connection: Connection,
  reviewRunId: String,
  findingId: String,
  eventType: String,
  note: String,
) {
  connection.prepareStatement(
    """
    INSERT INTO feedback_events (review_run_id, finding_id, event_type, note)
    VALUES (?, ?, ?, ?)
    """.trimIndent(),
  ).use { statement ->
    statement.bindAll(reviewRunId, findingId, eventType, note)
    statement.executeUpdate()
  }
}
