package skillbill.application.review.parallel.runner

import skillbill.application.review.snapshot.RecordedWorkerResponse
import skillbill.application.review.snapshot.ReviewHarnessConfig
import skillbill.application.review.snapshot.ReviewRecorder
import skillbill.application.review.snapshot.diffForPaths
import skillbill.application.review.snapshot.harnessRequest
import skillbill.application.review.snapshot.reviewHarness
import skillbill.application.review.snapshot.reviewed
import skillbill.application.review.snapshot.sparseReviewPack
import skillbill.application.runner
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ParallelCodeReviewStandaloneEntryTest {
  @Test
  fun `single parent lane keeps parent prose without a findings register`() {
    val pack =
      sparseReviewPack(
        slug = "kotlin",
        requiredArea = "architecture",
        pathAreas = mapOf("testing" to listOf("src/test/")),
      )
    val recorder = ReviewRecorder()
    val prose = "Null is unchecked in Main.\nverdict: changes_requested"
    val result =
      reviewHarness(
        ReviewHarnessConfig(
          manifests = listOf(pack),
          diff = diffForPaths("src/Main.kt"),
          response = { request ->
            when (request.skillRunRequest.issueKey) {
              "code-review" -> RecordedWorkerResponse(stdout = prose)
              else -> RecordedWorkerResponse()
            }
          },
        ),
        recorder,
      ).reviewed(harnessRequest(reviewRunId = "standalone-single-lane"))

    assertEquals(
      1,
      recorder.parentLaunches.count { it.skillRunRequest.issueKey == "code-review" },
    )
    assertTrue(result.mergeResult.findings.isEmpty())
    assertEquals(prose, result.mergeResult.formattedOutput)
    result.accountingSummary?.lanes?.let { lanes ->
      assertTrue(lanes.none { it.lane == "parallel-agent-2" })
    }
  }
}
