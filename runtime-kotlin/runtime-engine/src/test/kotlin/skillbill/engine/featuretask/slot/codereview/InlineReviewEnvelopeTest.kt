package skillbill.engine.featuretask.slot.codereview

import skillbill.application.review.model.ParallelCodeReviewResult
import skillbill.application.review.model.ParallelReviewLaneStatus
import skillbill.contracts.JsonCodec
import skillbill.review.context.model.launch.CodeReviewExecutionMode
import skillbill.review.model.ParallelReviewMergeResult
import skillbill.review.model.ParallelReviewMergedFinding
import skillbill.review.model.ParallelReviewSeverity
import skillbill.review.model.ReviewClaimVerdict
import skillbill.review.model.ReviewLaneReviewDisposition
import skillbill.review.model.ReviewScopeDisposition
import skillbill.workflow.model.validation.FeatureTaskRuntimeVerdict
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class InlineReviewEnvelopeTest {
  @Test
  fun `extractReviewVerdict reads changes_requested and defaults to approved`() {
    assertEquals(
      FeatureTaskRuntimeVerdict.CHANGES_REQUESTED,
      InlineReviewEnvelope.extractReviewVerdict("notes\nverdict: needs_fix"),
    )
    assertEquals(
      FeatureTaskRuntimeVerdict.CHANGES_REQUESTED,
      InlineReviewEnvelope.extractReviewVerdict("verdict: changes_requested"),
    )
    assertEquals(
      FeatureTaskRuntimeVerdict.APPROVED,
      InlineReviewEnvelope.extractReviewVerdict("clean prose without a verdict line"),
    )
  }

  @Test
  fun `settlement envelope takes findings and review_run_id from the review register`() {
    val result =
      ParallelCodeReviewResult(
        mergeResult =
          ParallelReviewMergeResult(
            findings =
              listOf(
                ParallelReviewMergedFinding(
                  fNumber = "F-001",
                  agentIds = listOf("codex"),
                  severity = ParallelReviewSeverity.MINOR,
                  confidence = "High",
                  location = "Foo.kt:1",
                  description = "naming",
                  scopeDisposition = ReviewScopeDisposition.SPEC_DEVIATION,
                  claimVerdict = ReviewClaimVerdict.CONFIRMED,
                ),
              ),
            formattedOutput = "Naming drift in Foo.\nverdict: changes_requested",
          ),
        lane1 =
          ParallelReviewLaneStatus(
            agentId = "codex",
            success = true,
            reviewDisposition = ReviewLaneReviewDisposition.COMPLETE,
          ),
      )
    val output =
      InlineReviewEnvelope.assemble(
        result = result,
        reviewRunId = "rvw-191-empty-register",
        cycle = InlineReviewCycle(1, CodeReviewExecutionMode.INLINE, "fp-1"),
      )
    val envelope = InlineReviewEnvelope.envelopeMap(output)
    val produced = JsonCodec.anyToStringAnyMap(envelope["produced_outputs"]).orEmpty()

    assertEquals("rvw-191-empty-register", produced["review_run_id"])
    val finding = JsonCodec.anyToStringAnyMap((produced["findings"] as List<*>).single()).orEmpty()
    assertEquals("F-001", finding["finding_id"])
    assertEquals("minor", finding["severity"])
    assertEquals("changes_requested", envelope["verdict"])
    assertTrue((envelope["summary"] as String).contains("Naming drift"))
    assertFalse(produced.containsKey("unmet_criteria"))
  }
}
