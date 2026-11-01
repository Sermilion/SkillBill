package skillbill.infrastructure.sqlite.review.stats

import skillbill.goalrunner.model.ReviewFindingOutcome
import skillbill.goalrunner.model.ReviewFindingOutcomeRecord
import skillbill.infrastructure.sqlite.SAMPLE_REVIEW
import skillbill.infrastructure.sqlite.SQLiteUnaddressedFindingsRepository
import skillbill.infrastructure.sqlite.review.accounting.persistImportedReview
import skillbill.infrastructure.sqlite.review.core.queryLatestFindingOutcomes
import skillbill.infrastructure.sqlite.review.core.summarizeFindingRows
import skillbill.infrastructure.sqlite.tempDbConnection
import skillbill.review.model.FeedbackRequest
import skillbill.review.model.FeedbackTelemetryOptions
import skillbill.review.parsing.ReviewParser
import java.sql.Connection
import kotlin.test.Test
import kotlin.test.assertEquals

class LoopRecordedOutcomeStatsTest {
  @Test
  fun `a loop-recorded outcome counts as resolved without any manual triage`() {
    val (_, connection) = tempDbConnection("loop-outcome-stats")
    connection.use {
      val review = ReviewParser.parseReview(SAMPLE_REVIEW.trimIndent())
      persistImportedReview(connection, review, sourcePath = null)
      val untriaged = summarizeFindingRows(queryLatestFindingOutcomes(connection, review.reviewRunId))
      assertEquals(
        0,
        untriaged.acceptedFindings + untriaged.rejectedFindings,
        "Without triage or a loop outcome, nothing is resolved — this is the 13%-coverage baseline.",
      )

      recordLoopOutcome(connection, review.reviewRunId, "F-001", "addressed")
      recordLoopOutcome(connection, review.reviewRunId, "F-002", "rejected")

      val stats = summarizeFindingRows(queryLatestFindingOutcomes(connection, review.reviewRunId))
      assertEquals(1, stats.acceptedFindings, "An addressed loop outcome counts as accepted.")
      assertEquals(1, stats.rejectedFindings, "A rejected loop outcome counts as rejected.")
    }
  }

  @Test
  fun `manual triage still wins over a loop-recorded outcome`() {
    val (_, connection) = tempDbConnection("loop-outcome-stats-precedence")
    connection.use {
      val review = ReviewParser.parseReview(SAMPLE_REVIEW.trimIndent())
      persistImportedReview(connection, review, sourcePath = null)
      recordLoopOutcome(connection, review.reviewRunId, "F-001", "addressed")

      TriageRuntime.recordFeedbackWithoutTransaction(
        connection = connection,
        request =
          FeedbackRequest(
            reviewRunId = review.reviewRunId,
            findingIds = listOf("F-001"),
            eventType = "false_positive",
            note = "Operator overrode the loop.",
          ),
        telemetryOptions = FeedbackTelemetryOptions(enabled = false, level = "off"),
        runtimeVersion = "test-runtime-version",
      )

      val stats = summarizeFindingRows(queryLatestFindingOutcomes(connection, review.reviewRunId))
      assertEquals(0, stats.acceptedFindings, "An explicit operator decision must not be overridden by the loop.")
      assertEquals(1, stats.rejectedFindings)
    }
  }

  @Test
  fun `outcomes written through the production writer attach to the imported run's findings`() {
    val (_, connection) = tempDbConnection("loop-outcome-stats-writer")
    connection.use {
      val review = ReviewParser.parseReview(SAMPLE_REVIEW.trimIndent())
      persistImportedReview(connection, review, sourcePath = null)

      SQLiteUnaddressedFindingsRepository(connection).recordOutcomes(
        listOf(
          ReviewFindingOutcomeRecord(
            workflowId = "wf-1",
            reviewPassNumber = 1,
            findingOrdinal = 1,
            outcome = ReviewFindingOutcome.ADDRESSED,
            reviewRunId = review.reviewRunId,
            findingId = "F-001",
          ),
          ReviewFindingOutcomeRecord(
            workflowId = "wf-1",
            reviewPassNumber = 1,
            findingOrdinal = 2,
            outcome = ReviewFindingOutcome.REJECTED,
            reviewRunId = review.reviewRunId,
            findingId = "F-002",
          ),
        ),
      )

      val stats = summarizeFindingRows(queryLatestFindingOutcomes(connection, review.reviewRunId))
      assertEquals(1, stats.acceptedFindings, "The loop-recorded addressed outcome must attach to its finding.")
      assertEquals(1, stats.rejectedFindings, "The loop-recorded rejected outcome must attach to its finding.")
    }
  }

  @Test
  fun `an unresolved-key loop outcome contributes nothing`() {
    val (_, connection) = tempDbConnection("loop-outcome-stats-unresolved")
    connection.use {
      val review = ReviewParser.parseReview(SAMPLE_REVIEW.trimIndent())
      persistImportedReview(connection, review, sourcePath = null)
      connection.createStatement().use { statement ->
        statement.executeUpdate(
          """
          INSERT INTO review_finding_outcomes
            (workflow_id, review_pass_number, finding_ordinal, key_state, outcome)
          VALUES ('wf-1', 1, 1, 'unresolved', 'addressed')
          """.trimIndent(),
        )
      }

      val stats = summarizeFindingRows(queryLatestFindingOutcomes(connection, review.reviewRunId))
      assertEquals(
        0,
        stats.acceptedFindings + stats.rejectedFindings,
        "An outcome with no review run has nothing to attach to and must not inflate the stats.",
      )
    }
  }

  private fun recordLoopOutcome(
    connection: Connection,
    reviewRunId: String,
    findingId: String,
    outcome: String,
  ) {
    connection.prepareStatement(
      """
      INSERT INTO review_finding_outcomes
        (workflow_id, review_pass_number, finding_ordinal, review_run_id, finding_id, key_state, outcome)
      VALUES ('wf-1', 1, ?, ?, ?, 'resolved', ?)
      """.trimIndent(),
    ).use { statement ->
      statement.setInt(1, findingId.substringAfterLast('-').toInt())
      statement.setString(2, reviewRunId)
      statement.setString(3, findingId)
      statement.setString(4, outcome)
      statement.executeUpdate()
    }
  }
}
