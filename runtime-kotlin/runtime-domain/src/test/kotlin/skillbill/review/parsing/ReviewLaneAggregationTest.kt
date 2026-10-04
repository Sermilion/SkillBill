package skillbill.review.parsing

import skillbill.error.core.SkillBillRuntimeException
import skillbill.error.shellcontent.ReviewContextFailureCode
import skillbill.review.model.ReviewLaneAggregationInput
import skillbill.review.model.ReviewLaneReviewDisposition
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ReviewLaneAggregationTest {
  private val sequence = "a".repeat(64)

  private fun result(
    lane: String,
    disposition: ReviewLaneReviewDisposition = ReviewLaneReviewDisposition.COMPLETE,
    unreviewedUnits: List<String> = emptyList(),
    commitSequenceDigest: String = sequence,
  ) = ReviewLaneAggregationInput(lane, commitSequenceDigest, disposition, unreviewedUnits)

  @Test fun `a complete lane set aggregates to clean coverage`() {
    val report =
      ReviewLaneAggregation.requireCompleteLaneResults(
        expectedLanes = listOf("security", "testing"),
        results = listOf(result("security"), result("testing")),
        commitSequenceDigest = sequence,
      )

    assertTrue(report.isCleanCoverage)
    assertEquals(listOf("security", "testing"), report.cleanLanes)
    assertTrue("Coverage: clean" in report.render())
  }

  @Test fun `a missing lane result fails loudly instead of reading as no findings`() {
    val failure =
      assertFailsWith<SkillBillRuntimeException> {
        ReviewLaneAggregation.requireCompleteLaneResults(
          expectedLanes = listOf("security", "testing"),
          results = listOf(result("security")),
          commitSequenceDigest = sequence,
        )
      }.also { assertEquals(ReviewContextFailureCode.REVIEW_AGGREGATION_INTEGRITY, it.code) }

    assertTrue("produced no result" in failure.message.orEmpty())
    assertTrue(failure.message.orEmpty().endsWith("(testing)"))
  }

  @Test fun `a doubled lane result fails loudly`() {
    val failure =
      assertFailsWith<SkillBillRuntimeException> {
        ReviewLaneAggregation.requireCompleteLaneResults(
          expectedLanes = listOf("security"),
          results = listOf(result("security"), result("security")),
          commitSequenceDigest = sequence,
        )
      }.also { assertEquals(ReviewContextFailureCode.REVIEW_AGGREGATION_INTEGRITY, it.code) }

    assertTrue("more than one result" in failure.message.orEmpty())
  }

  @Test fun `a result minted against a different commit sequence is never merged`() {
    val failure =
      assertFailsWith<SkillBillRuntimeException> {
        ReviewLaneAggregation.requireCompleteLaneResults(
          expectedLanes = listOf("security"),
          results = listOf(result("security", commitSequenceDigest = "b".repeat(64))),
          commitSequenceDigest = sequence,
        )
      }.also { assertEquals(ReviewContextFailureCode.REVIEW_AGGREGATION_INTEGRITY, it.code) }

    assertTrue("different commit sequence" in failure.message.orEmpty())
  }

  @Test fun `a result naming an unselected lane fails loudly`() {
    val failure =
      assertFailsWith<SkillBillRuntimeException> {
        ReviewLaneAggregation.requireCompleteLaneResults(
          expectedLanes = listOf("security"),
          results = listOf(result("security"), result("performance")),
          commitSequenceDigest = sequence,
        )
      }.also { assertEquals(ReviewContextFailureCode.REVIEW_AGGREGATION_INTEGRITY, it.code) }

    assertTrue("never selected" in failure.message.orEmpty())
  }

  @Test fun `a completed integration pass never reads as closing an incomplete lane gap`() {
    val report =
      ReviewLaneAggregation.requireCompleteLaneResults(
        expectedLanes = listOf("security", "testing"),
        results =
          listOf(
            result("security"),
            result("testing", ReviewLaneReviewDisposition.INCOMPLETE, listOf("c2@src/B.kt")),
          ),
        commitSequenceDigest = sequence,
      ).copy(integrationCompleted = true)

    val rendered = report.render()

    assertFalse(report.isCleanCoverage)
    assertTrue("Coverage: NOT clean" in rendered, rendered)
    assertTrue("c2@src/B.kt" in rendered, rendered)
    assertTrue("does not close this coverage gap" in rendered, rendered)
  }

  @Test fun `a review with no applicable integration pass says so explicitly`() {
    val rendered =
      ReviewLaneAggregation.requireCompleteLaneResults(
        expectedLanes = listOf("security"),
        results = listOf(result("security")),
        commitSequenceDigest = sequence,
      ).copy(
        integrationNotApplicableReason =
          "the review scope resolved to a synthetic unit, so there is no commit sequence to integrate over",
      ).render()

    assertTrue("not applicable" in rendered, rendered)
    assertTrue("No integration pass was run." in rendered, rendered)
  }
}
