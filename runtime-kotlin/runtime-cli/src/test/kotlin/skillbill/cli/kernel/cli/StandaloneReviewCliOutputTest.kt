package skillbill.cli.kernel.cli

import skillbill.application.review.model.ParallelCodeReviewResult
import skillbill.application.review.model.ParallelReviewLaneStatus
import skillbill.review.model.ParallelReviewMergeResult
import kotlin.test.Test
import kotlin.test.assertEquals

class StandaloneReviewCliOutputTest {
  @Test
  fun `cli does not restore the reviewer verdict when verified output has none`() {
    val result =
      ParallelCodeReviewResult(
        mergeResult = ParallelReviewMergeResult(emptyList(), "verified register"),
        lane1 = ParallelReviewLaneStatus("reviewer", true),
        rawOutput = "NO_FINDINGS\nverdict: changes_requested",
      )

    assertEquals("verified register", result.standaloneReportText())
  }
}
