package skillbill.infrastructure.sqlite.review.stage

import skillbill.contracts.JsonCodec
import skillbill.contracts.learning.LearningEntryDto
import skillbill.error.core.SkillBillRuntimeException
import skillbill.error.learning.InvalidLearningSourceReason
import skillbill.infrastructure.sqlite.SAMPLE_REVIEW
import skillbill.infrastructure.sqlite.SQLiteLearningStore
import skillbill.infrastructure.sqlite.review.accounting.persistImportedReview
import skillbill.infrastructure.sqlite.reviewSessionId
import skillbill.infrastructure.sqlite.tempDbConnection
import skillbill.infrastructure.sqlite.testLearningAppliedSessionWire
import skillbill.infrastructure.sqlite.testLearningEntryDto
import skillbill.learnings.LearningsRuntime
import skillbill.learnings.model.CreateLearningRequest
import skillbill.learnings.model.LearningScope
import skillbill.learnings.model.LearningSourceValidation
import skillbill.learnings.model.RejectedLearningSourceOutcome
import skillbill.review.model.FeedbackRequest
import skillbill.review.model.FeedbackTelemetryOptions
import skillbill.review.model.ImportedReview
import skillbill.review.model.NumberedFinding
import skillbill.review.model.ReviewFinishedTelemetry
import skillbill.review.parsing.ReviewParser
import skillbill.review.parsing.TriageDecisionParser
import java.sql.Connection
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class TriageAndLearningsRuntimeTest {
  @Test
  fun `parseTriageDecisions expands structured selections and normalizes actions`() {
    val decisions =
      TriageDecisionParser.parseTriageDecisions(
        rawDecisions = listOf("fix=[1] reject=[2]"),
        numberedFindings =
          listOf(
            NumberedFinding(1, "F-001", "Major", "High", "README.md:12", "one"),
            NumberedFinding(2, "F-002", "Minor", "Medium", "install.sh:88", "two"),
          ),
      )

    assertEquals(2, decisions.size)
    assertEquals("fix_applied", decisions[0].outcomeType)
    assertEquals("fix_rejected", decisions[1].outcomeType)
  }

  @Test
  fun `recordFeedback and learnings resolve preserve rejected-source and scope ordering`() {
    val (_, connection) = tempDbConnection("triage-learnings")
    connection.use {
      val review = importSampleReview(connection)
      val telemetryPayload = rejectFinding(connection, review.reviewRunId, "F-002", "Keep the current prompt wording.")
      assertEquals("rvs-20260402-001", telemetryPayload?.reviewSessionId)

      val globalId = addLearning(connection, review.reviewRunId, LearningScope.GLOBAL, "", "Prefer explicit wording")
      val repoId = addLearning(connection, review.reviewRunId, LearningScope.REPO, "skill-bill", "Repo phrasing")
      val skillId =
        addLearning(
          connection,
          review.reviewRunId,
          LearningScope.SKILL,
          "bill-kotlin-code-review",
          "Review skill phrasing",
        )

      val (_, _, resolved) =
        SQLiteLearningStore.resolveLearnings(
          connection = connection,
          repoScopeKey = "skill-bill",
          skillName = "bill-kotlin-code-review",
        )

      assertEquals(listOf(skillId, repoId, globalId), resolved.map { it.id })
      assertTrue(resolved.first().rationale.contains("current prompt wording"))

      val payloadEntries = resolved.map(::testLearningEntryDto)
      saveCachedLearnings(connection, review.reviewSessionId, payloadEntries)

      val cached = SQLiteLearningStore.fetchSessionLearnings(connection, review.reviewSessionId)
      assertNotNull(cached)
      assertEquals(3, (cached["applied_learning_count"] as Number).toInt())
    }
  }

  @Test
  fun `learnings resolve excludes disabled learnings and learnings scoped to another repo or skill`() {
    val (_, connection) = tempDbConnection("learnings-exclusion")
    connection.use {
      val review = importSampleReview(connection)
      rejectFinding(connection, review.reviewRunId, "F-002", "Keep the current prompt wording.")
      val repoId = addLearning(connection, review.reviewRunId, LearningScope.REPO, "acme/repo", "Repo phrasing")
      val disabledId = addLearning(connection, review.reviewRunId, LearningScope.REPO, "acme/repo", "Disabled")
      SQLiteLearningStore.setLearningStatus(connection, disabledId, "disabled")
      addLearning(connection, review.reviewRunId, LearningScope.REPO, "other/repo", "Foreign repo")
      addLearning(connection, review.reviewRunId, LearningScope.SKILL, "bill-swift-code-review", "Foreign skill")

      val (_, _, resolved) =
        SQLiteLearningStore.resolveLearnings(
          connection = connection,
          repoScopeKey = "acme/repo",
          skillName = "bill-kotlin-code-review",
        )

      assertEquals(listOf(repoId), resolved.map { it.id })
    }
  }
}

class LearningPromotionTest {
  @Test
  fun `promoting a rejected finding records the originating review run and finding`() {
    val (_, connection) = tempDbConnection("learning-promotion")
    connection.use {
      val review = importSampleReview(connection)
      rejectFinding(connection, review.reviewRunId, "F-002", "Keep the current prompt wording.")

      val learningId =
        addLearning(connection, review.reviewRunId, LearningScope.GLOBAL, "", "Prefer explicit wording")

      val record = SQLiteLearningStore.getLearning(connection, learningId)
      assertEquals(review.reviewRunId, record.sourceReviewRunId, "Promotion must carry the source review run.")
      assertEquals("F-002", record.sourceFindingId, "Promotion must carry the source finding.")
    }
  }

  @Test
  fun `promoting an unknown review run and finding pair fails loudly`() {
    val (_, connection) = tempDbConnection("learning-promotion-unknown")
    connection.use {
      val review = importSampleReview(connection)
      rejectFinding(connection, review.reviewRunId, "F-002", "Keep the current prompt wording.")

      val failure =
        assertFailsWith<SkillBillRuntimeException> {
          LearningsRuntime.validateLearningSource(
            sourceReviewRunId = review.reviewRunId,
            sourceFindingId = "F-does-not-exist",
            sourceFindingExists = ReviewRuntime.findingExists(connection, review.reviewRunId, "F-does-not-exist"),
            latestRejectedOutcome = null,
          )
        }
      assertEquals(InvalidLearningSourceReason.UNKNOWN_FINDING, failure.code)
      assertTrue("F-does-not-exist" in failure.message.orEmpty(), "The failure must name the unresolvable finding.")
      assertEquals(
        0,
        learningCount(connection),
        "An unresolvable pair must insert no orphan learning row.",
      )
    }
  }

  @Test
  fun `deleting the source review run leaves the learning with both source columns null`() {
    val (_, connection) = tempDbConnection("learning-promotion-cascade")
    connection.use {
      val review = importSampleReview(connection)
      rejectFinding(connection, review.reviewRunId, "F-002", "Keep the current prompt wording.")
      val learningId =
        addLearning(connection, review.reviewRunId, LearningScope.GLOBAL, "", "Prefer explicit wording")

      connection.createStatement().use { statement ->
        statement.execute("PRAGMA foreign_keys = ON")
        statement.executeUpdate("DELETE FROM review_runs WHERE review_run_id = '${review.reviewRunId}'")
      }

      val record = SQLiteLearningStore.getLearning(connection, learningId)
      assertEquals(null, record.sourceReviewRunId, "ON DELETE SET NULL must clear the run reference.")
      assertEquals(
        null,
        record.sourceFindingId,
        "Both source columns must clear together, or the paired-null CHECK would be violated.",
      )
    }
  }

  private fun learningCount(connection: Connection): Int = SQLiteLearningStore.countLearnings(connection)
}

private fun importSampleReview(connection: Connection): ImportedReview {
  val review = ReviewParser.parseReview(SAMPLE_REVIEW.trimIndent())
  persistImportedReview(connection, review, sourcePath = null)
  return review
}

private fun rejectFinding(
  connection: Connection,
  reviewRunId: String,
  findingId: String,
  note: String,
): ReviewFinishedTelemetry? =
  TriageRuntime.recordFeedbackWithoutTransaction(
    connection = connection,
    request =
      FeedbackRequest(
        reviewRunId = reviewRunId,
        findingIds = listOf(findingId),
        eventType = "fix_rejected",
        note = note,
      ),
    telemetryOptions = FeedbackTelemetryOptions(enabled = false, level = "anonymous"),
    runtimeVersion = "test-runtime-version",
  )

internal fun addLearning(
  connection: Connection,
  reviewRunId: String,
  scope: LearningScope,
  scopeKey: String,
  title: String,
): Int =
  SQLiteLearningStore.addLearning(
    connection = connection,
    request =
      CreateLearningRequest(
        scope = scope,
        scopeKey = scopeKey,
        title = title,
        ruleText = "Rule text for $title.",
        rationale = "",
        sourceReviewRunId = reviewRunId,
        sourceFindingId = "F-002",
      ),
    sourceValidation =
      LearningSourceValidation(
        reviewRunId = reviewRunId,
        findingId = "F-002",
        rejectedOutcome = RejectedLearningSourceOutcome("fix_rejected", "Keep the current prompt wording."),
      ),
  )

private fun saveCachedLearnings(
  connection: Connection,
  reviewSessionId: String,
  payloadEntries: List<LearningEntryDto>,
) {
  SQLiteLearningStore.saveSessionLearnings(
    connection = connection,
    reviewSessionId = reviewSessionId,
    learningsJson = JsonCodec.mapToJsonString(testLearningAppliedSessionWire(null, payloadEntries).toPayload()),
  )
}
