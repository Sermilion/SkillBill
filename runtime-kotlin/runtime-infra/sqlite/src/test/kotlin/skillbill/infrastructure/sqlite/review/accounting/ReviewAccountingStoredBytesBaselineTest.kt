package skillbill.infrastructure.sqlite.review.accounting

import skillbill.infrastructure.sqlite.ensureTestDatabase
import skillbill.infrastructure.sqlite.reviewAccountingOnConnection
import skillbill.ports.review.model.ReviewAccountingRecord
import skillbill.review.context.ReviewTreeAccounting
import skillbill.review.context.model.accounting.ReviewAccountingCounters
import skillbill.review.context.model.accounting.ReviewAccountingInput
import skillbill.review.context.model.accounting.ReviewCommitRoutingAccounting
import skillbill.review.context.model.accounting.ReviewIntegrationAccounting
import skillbill.review.context.model.accounting.ReviewIntegrationTerminalOutcome
import skillbill.review.context.model.accounting.ReviewParentAnalysisConsumption
import java.nio.file.Files
import java.sql.Connection
import kotlin.test.Test
import kotlin.test.assertEquals

class ReviewAccountingStoredBytesBaselineTest {
  @Test
  fun `a persisted accounting row keeps the checked-in baseline bytes`() {
    val summary =
      ReviewTreeAccounting.summarize(
        "fixture-review",
        "fixture-packet",
        ReviewAccountingInput(
          lane = "parent",
          assignmentDigest = "fixture-assignment",
          counters = ReviewAccountingCounters(1, 2, 3, 4, 5, 6),
        ),
      ).copy(
        commitRouting =
          ReviewCommitRoutingAccounting(
            commitSequenceDigest = "fixture-commits",
            routingDigest = "fixture-routing",
            commitCount = 1,
            laneCount = 1,
            focusedCommitCount = 1,
            skippedCommitCount = 0,
            focusedPairCount = 1,
            skippedPairCount = 0,
          ),
        parentAnalysis =
          ReviewParentAnalysisConsumption(
            analyzedPairs = 1,
            analyzedBytes = 2,
            maxAnalysisPairs = 3,
            maxAnalysisBytes = 4,
          ),
        integration =
          ReviewIntegrationAccounting(
            commitSequenceDigest = "fixture-integration",
            terminalOutcome = ReviewIntegrationTerminalOutcome.COMPLETED,
            summarizedLaneCount = 1,
            findingCount = 0,
            counters = ReviewAccountingCounters(7, 8, 9, 10, 11, 12),
          ),
      )

    val dbPath = Files.createTempDirectory("review-accounting-baseline").resolve("metrics.db")
    ensureTestDatabase(dbPath).use { connection ->
      reviewAccountingOnConnection(connection).upsert(
        ReviewAccountingRecord(summary.reviewId, summary.packetDigest, summary),
      )

      assertEquals(baselineBytes(), storedAccountingJson(connection))
    }
  }

  private fun baselineBytes(): String =
    requireNotNull(
      javaClass.classLoader.getResourceAsStream(BASELINE_RESOURCE),
    ) { "missing baseline fixture $BASELINE_RESOURCE" }.use { stream -> stream.readBytes().toString(Charsets.UTF_8) }

  private fun storedAccountingJson(connection: Connection): String =
    connection.prepareStatement("SELECT bounded_payload_json FROM review_accounting").use { statement ->
      statement.executeQuery().use { rows ->
        buildString { while (rows.next()) append(rows.getString(1)) }
      }
    }

  private companion object {
    const val BASELINE_RESOURCE =
      "skillbill/infrastructure/sqlite/review/accounting/review-accounting-baseline.json"
  }
}
