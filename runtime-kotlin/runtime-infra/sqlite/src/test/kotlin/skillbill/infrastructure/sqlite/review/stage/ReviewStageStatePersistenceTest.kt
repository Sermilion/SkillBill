package skillbill.infrastructure.sqlite.review.stage

import skillbill.error.shellcontent.InvalidReviewContextSchemaError
import skillbill.infrastructure.sqlite.SQLiteReviewRunCompletenessRepository
import skillbill.infrastructure.sqlite.core.schema.DatabaseRuntime
import skillbill.infrastructure.sqlite.ensureDatabase
import skillbill.infrastructure.sqlite.tempDbConnection
import skillbill.review.model.ParallelReviewMergedFinding
import skillbill.review.model.ParallelReviewSeverity
import skillbill.review.model.ReviewClaimVerdict
import skillbill.review.model.ReviewFindingCitation
import skillbill.review.model.ReviewFindingVerdict
import skillbill.review.model.ReviewPassClaimSnapshot
import skillbill.review.model.ReviewScopeDisposition
import skillbill.review.model.ReviewSeverityAdjustment
import skillbill.review.model.ReviewSeverityAdjustmentDirection
import skillbill.review.model.ReviewSpecProjectionReference
import skillbill.review.model.ReviewStage
import skillbill.review.model.ReviewStageBoundary
import skillbill.review.model.ReviewStageReached
import java.time.Clock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ReviewStageStatePersistenceTest {
  @Test
  fun `malformed SQLite review tokens and citations retain the review typed error`() {
    val (_, connection) = tempDbConnection("review-stage-malformed-row")
    connection.use {
      val repository = SQLiteReviewRunCompletenessRepository(it, Clock.systemUTC())
      repository.recordStageBoundary(
        "rvw-malformed",
        ReviewStageBoundary(ReviewStage.REVIEW, ReviewStageReached.REACHED, "2026-08-14T08:00:00Z"),
      )
      repository.recordFindingVerdicts(
        "rvw-malformed",
        listOf(
          ReviewFindingVerdict(
            stage = ReviewStage.VERIFICATION,
            findingRef = "F-001",
            claimVerdict = ReviewClaimVerdict.CONFIRMED,
            citations = listOf(ReviewFindingCitation("src/Main.kt", 12)),
            recordedAt = "2026-08-14T08:00:00Z",
          ),
        ),
      )
      it.createStatement().use { statement ->
        statement.execute("PRAGMA ignore_check_constraints = ON")
        statement.executeUpdate(
          "UPDATE review_run_stage_boundaries SET stage = 'unknown' WHERE review_run_id = 'rvw-malformed'",
        )
        statement.executeUpdate(
          "UPDATE review_run_finding_verdicts SET citations = 'src/Main.kt\tnot-a-line' " +
            "WHERE review_run_id = 'rvw-malformed'",
        )
      }
      assertFailsWith<InvalidReviewContextSchemaError> {
        repository.fetchStageBoundaries("rvw-malformed")
      }
      assertFailsWith<InvalidReviewContextSchemaError> {
        repository.fetchFindingVerdicts("rvw-malformed")
      }
    }
  }

  @Test
  fun `verdicts stage boundary and spec projection survive close and reopen`() {
    val (dbPath, first) = tempDbConnection("review-stage-durability")
    val fixtures = durableStageFixtures()
    first.use { connection ->
      val repository = SQLiteReviewRunCompletenessRepository(connection, Clock.systemUTC())
      repository.recordFindingVerdicts(RUN_ID, listOf(fixtures.verification, fixtures.adjudication))
      repository.recordFindingVerdicts(RUN_ID, listOf(fixtures.verification, fixtures.adjudication))
      repository.recordStageBoundary(RUN_ID, fixtures.boundary)
      repository.recordSpecProjectionReference(RUN_ID, fixtures.spec)
      repository.recordReviewPassClaims(RUN_ID, fixtures.claims)
      repository.recordReviewPassClaims(RUN_ID, emptyList())
      assertEquals(2, repository.fetchFindingVerdicts(RUN_ID).size)
    }

    DatabaseRuntime.ensureDatabase(dbPath).use { connection ->
      val repository = SQLiteReviewRunCompletenessRepository(connection, Clock.systemUTC())
      assertEquals(
        listOf(fixtures.adjudication, fixtures.verification),
        repository.fetchFindingVerdicts(RUN_ID).sortedBy { it.stage.wireValue },
      )
      assertEquals(listOf(fixtures.boundary), repository.fetchStageBoundaries(RUN_ID))
      assertEquals(fixtures.spec, repository.fetchSpecProjectionReference(RUN_ID))
      assertEquals(ReviewPassClaimSnapshot(fixtures.claims), repository.fetchReviewPassClaims(RUN_ID))
    }
  }

  private fun durableStageFixtures() =
    DurableStageFixtures(
      verification =
        ReviewFindingVerdict(
          stage = ReviewStage.VERIFICATION,
          findingRef = "F-001",
          claimVerdict = ReviewClaimVerdict.CONFIRMED,
          citations = listOf(ReviewFindingCitation("src/Main.kt", 12)),
          recordedAt = "2026-08-14T08:00:00Z",
        ),
      adjudication =
        ReviewFindingVerdict(
          stage = ReviewStage.ADJUDICATION,
          findingRef = "F-001",
          claimVerdict = ReviewClaimVerdict.REFUTED,
          scopeDisposition = ReviewScopeDisposition.IN_SCOPE,
          citations = listOf(ReviewFindingCitation("src/Main.kt", 12)),
          severityAdjustment =
            ReviewSeverityAdjustment(
              ReviewSeverityAdjustmentDirection.LOWER,
              "listed non-goal",
            ),
          recordedAt = "2026-08-14T08:05:00Z",
        ),
      boundary =
        ReviewStageBoundary(
          stage = ReviewStage.VERIFICATION,
          reached = ReviewStageReached.REACHED,
          recordedAt = "2026-08-14T08:01:00Z",
        ),
      spec = ReviewSpecProjectionReference(absenceReason = "spec_context none"),
      claims =
        listOf(
          ParallelReviewMergedFinding(
            fNumber = "F-001",
            agentIds = listOf("codex"),
            severity = ParallelReviewSeverity.MAJOR,
            confidence = "High",
            location = "src/Main.kt:12",
            description = "null is unchecked",
            repositoryPath = "src/Main.kt",
            line = 12,
          ),
        ),
    )

  private data class DurableStageFixtures(
    val verification: ReviewFindingVerdict,
    val adjudication: ReviewFindingVerdict,
    val boundary: ReviewStageBoundary,
    val spec: ReviewSpecProjectionReference,
    val claims: List<ParallelReviewMergedFinding>,
  )

  private companion object {
    const val RUN_ID = "rvw-stage-durable"
  }
}

class ReviewStageStatePruneTest {
  @Test
  fun `deleting a review run cascades verdicts boundaries and spec projection`() {
    val (_, connection) = tempDbConnection("review-stage-prune")
    connection.use {
      seedPruneRun(SQLiteReviewRunCompletenessRepository(it, Clock.systemUTC()))
      it.createStatement().use { statement ->
        statement.executeUpdate("DELETE FROM review_runs WHERE review_run_id = '$RUN_ID'")
      }
      val repository = SQLiteReviewRunCompletenessRepository(it, Clock.systemUTC())
      assertTrue(repository.fetchFindingVerdicts(RUN_ID).isEmpty())
      assertTrue(repository.fetchStageBoundaries(RUN_ID).isEmpty())
      assertEquals(null, repository.fetchSpecProjectionReference(RUN_ID))
      assertEquals(null, repository.fetchReviewPassClaims(RUN_ID))
      it.createStatement().use { statement ->
        val remaining =
          statement.executeQuery(
            """
            SELECT
              (SELECT COUNT(*) FROM review_run_finding_verdicts) +
              (SELECT COUNT(*) FROM review_run_stage_boundaries) +
              (SELECT COUNT(*) FROM review_run_spec_projections) +
              (SELECT COUNT(*) FROM review_run_pass_claims)
            """.trimIndent(),
          )
        remaining.next()
        assertEquals(0, remaining.getInt(1))
      }
    }
  }

  private fun seedPruneRun(repository: SQLiteReviewRunCompletenessRepository) {
    repository.recordFindingVerdicts(
      RUN_ID,
      listOf(
        ReviewFindingVerdict(
          stage = ReviewStage.VERIFICATION,
          findingRef = "F-001",
          claimVerdict = ReviewClaimVerdict.UNRESOLVED,
          recordedAt = "2026-08-14T08:00:00Z",
        ),
        ReviewFindingVerdict(
          stage = ReviewStage.ADJUDICATION,
          findingRef = "F-001",
          claimVerdict = ReviewClaimVerdict.UNRESOLVED,
          scopeDisposition = ReviewScopeDisposition.IN_SCOPE,
          recordedAt = "2026-08-14T08:02:00Z",
        ),
      ),
    )
    repository.recordStageBoundary(
      RUN_ID,
      ReviewStageBoundary(ReviewStage.REVIEW, ReviewStageReached.REACHED, "2026-08-14T08:00:00Z"),
    )
    repository.recordStageBoundary(
      RUN_ID,
      ReviewStageBoundary(ReviewStage.ADJUDICATION, ReviewStageReached.REACHED, "2026-08-14T08:03:00Z"),
    )
    repository.recordSpecProjectionReference(
      RUN_ID,
      ReviewSpecProjectionReference(absenceReason = "spec_context none"),
    )
    repository.recordReviewPassClaims(
      RUN_ID,
      listOf(
        ParallelReviewMergedFinding(
          fNumber = "F-001",
          agentIds = listOf("codex"),
          severity = ParallelReviewSeverity.NIT,
          confidence = "Low",
          location = "src/Main.kt:1",
          description = "nit",
          repositoryPath = "src/Main.kt",
          line = 1,
        ),
      ),
    )
  }

  private companion object {
    const val RUN_ID = "rvw-stage-prune"
  }
}
