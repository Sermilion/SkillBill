package skillbill.infrastructure.sqlite.review.stage

import skillbill.infrastructure.sqlite.SAMPLE_REVIEW
import skillbill.infrastructure.sqlite.TABLE_REVIEW
import skillbill.infrastructure.sqlite.review.accounting.persistImportedReview
import skillbill.infrastructure.sqlite.review.accounting.reviewSummaryChanged
import skillbill.infrastructure.sqlite.reviewSessionId
import skillbill.infrastructure.sqlite.tempDbConnection
import skillbill.review.attribution.canonicalPlatformSlugs
import skillbill.review.attribution.withCanonicalAttribution
import skillbill.review.model.ReviewFindingStatus
import skillbill.review.parsing.ReviewParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ReviewRuntimeTest {
  @Test
  fun `parseReview extracts bullet findings and deduplicates specialist reviews`() {
    val review = ReviewParser.parseReview(SAMPLE_REVIEW.trimIndent())

    assertEquals("rvw-20260402-001", review.reviewRunId)
    assertEquals("rvs-20260402-001", review.reviewSessionId)
    assertEquals("bill-kotlin-code-review", review.routedSkill)
    assertEquals("kotlin", review.detectedStack)
    assertEquals(listOf("architecture", "testing"), review.specialistReviews)
    assertEquals(2, review.findings.size)
    assertEquals("F-001", review.findings.first().findingId)
    assertEquals("High", review.findings.first().confidence)
    assertEquals("behavior_correctness", review.findings.first().issueCategory)
  }

  @Test
  fun `saveImportedReview persists table-format findings with normalized severities`() {
    val (_, connection) = tempDbConnection("review-runtime")
    connection.use {
      val review = ReviewParser.parseReview(TABLE_REVIEW.trimIndent())

      persistImportedReview(connection, review, sourcePath = null)

      val summary = ReviewRuntime.fetchReviewSummary(connection, "rvw-20260402-tbl-a")
      val numberedFindings = ReviewRuntime.fetchNumberedFindings(connection, "rvw-20260402-tbl-a")

      assertEquals("rvs-20260402-tbl-a", summary.reviewSessionId)
      assertEquals(2, numberedFindings.size)
      assertEquals("F-001", numberedFindings[0].findingId)
      assertEquals("Major", numberedFindings[0].severity)
      assertEquals("ViewModel.kt:147-152", numberedFindings[0].location)
      assertEquals("behavior_correctness", review.findings[0].issueCategory)
      assertEquals("Minor", numberedFindings[1].severity)
      assertEquals("ux_accessibility", review.findings[1].issueCategory)
      assertNull(summary.reviewFinishedAt)
    }
  }

  @Test
  fun `finding statuses take the latest settling feedback before the goal loop outcome`() {
    val (_, connection) = tempDbConnection("review-finding-status")
    connection.use {
      persistImportedReview(connection, ReviewParser.parseReview(TABLE_REVIEW.trimIndent()), sourcePath = null)
      val run = "rvw-20260402-tbl-a"
      connection.createStatement().use { statement ->
        statement.executeUpdate(
          "INSERT INTO feedback_events (review_run_id, finding_id, event_type) VALUES " +
            "('$run', 'F-001', 'finding_accepted'), ('$run', 'F-001', 'fix_applied'), " +
            "('$run', 'F-002', 'finding_edited')",
        )
        statement.executeUpdate(
          "INSERT INTO review_finding_outcomes (workflow_id, review_pass_number, finding_ordinal, review_run_id, " +
            "finding_id, finding_key, key_state, outcome) " +
            "VALUES ('wf-1', 1, 2, '$run', 'F-002', 'k', 'resolved', 'rejected')",
        )
      }

      assertEquals(
        mapOf("F-001" to ReviewFindingStatus.FIXED, "F-002" to ReviewFindingStatus.REJECTED),
        ReviewRuntime.fetchFindingStatuses(connection, run),
      )
      assertEquals(emptyMap(), ReviewRuntime.fetchFindingStatuses(connection, "rvw-unknown"))
    }
  }

  @Test
  fun `canonical attribution round trips and participates in change detection`() {
    val (_, connection) = tempDbConnection("review-canonical")
    connection.use {
      val review =
        ReviewParser.parseReview(SAMPLE_REVIEW.trimIndent()).withCanonicalAttribution(
          knownPackSkillNames = setOf("bill-kotlin-code-review"),
          knownPlatformSlugs = canonicalPlatformSlugs,
        )

      persistImportedReview(connection, review, sourcePath = null)
      val summary = ReviewRuntime.fetchReviewSummary(connection, review.reviewRunId)

      assertEquals("bill-kotlin-code-review", summary.routedSkillCanonical)
      assertEquals("kotlin", summary.detectedStackCanonical)
      assertEquals(review.routedSkill, summary.routedSkill)

      val findings = ReviewRuntime.fetchImportedFindings(connection, review.reviewRunId)
      assertFalse(reviewSummaryChanged(summary, review, findings))
      assertTrue(reviewSummaryChanged(summary, review.copy(routedSkillCanonical = "unresolved"), findings))
      assertTrue(reviewSummaryChanged(summary, review.copy(detectedScopeDetail = "main..HEAD"), findings))
    }
  }

  @Test
  fun `parseReview assigns fallback classifier category when routing is generic`() {
    val review =
      ReviewParser.parseReview(
        """
        Routed to: bill-code-review
        Review session ID: rvs-20260402-security
        Review run ID: rvw-20260402-security
        Detected review scope: branch diff
        Detected stack: unknown
        Execution mode: inline

        ### 2. Risk Register
        - [F-001] Major | High | Auth.kt:12 | Token is logged with sensitive user data.
        """.trimIndent(),
      )

    assertEquals("security_privacy", review.findings.single().issueCategory)
  }
}
