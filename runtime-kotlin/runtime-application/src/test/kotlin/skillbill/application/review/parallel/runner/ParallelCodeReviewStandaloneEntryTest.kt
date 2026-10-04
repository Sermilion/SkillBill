package skillbill.application.review.parallel.runner

import skillbill.application.review.model.ParallelCodeReviewReportContract
import skillbill.application.review.snapshot.HARNESS_HEAD_REVISION
import skillbill.application.review.snapshot.RecordedCommit
import skillbill.application.review.snapshot.RecordedWorkerResponse
import skillbill.application.review.snapshot.ReviewHarnessConfig
import skillbill.application.review.snapshot.ReviewRecorder
import skillbill.application.review.snapshot.diffForPaths
import skillbill.application.review.snapshot.harnessRequest
import skillbill.application.review.snapshot.reviewHarness
import skillbill.application.review.snapshot.reviewed
import skillbill.application.review.snapshot.sparseReviewPack
import skillbill.application.runner
import skillbill.error.core.CursorReviewStreamFailureCode
import skillbill.error.core.SkillBillRuntimeException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ParallelCodeReviewStandaloneEntryTest {
  @Test
  fun `standalone integration propagates Cursor provider failure instead of returning an incomplete review`() {
    val failure = SkillBillRuntimeException(CursorReviewStreamFailureCode.PROVIDER_FAILURE, "provider unavailable")
    val firstDiff = diffForPaths("src/First.kt")
    val headDiff = diffForPaths("src/Main.kt")
    val runner =
      reviewHarness(
        ReviewHarnessConfig(
          manifests =
            listOf(sparseReviewPack(slug = "kotlin", requiredArea = "architecture", pathAreas = emptyMap())),
          diff = diffForPaths("src/First.kt", "src/Main.kt"),
          commits =
            listOf(
              RecordedCommit("c0", "first change", firstDiff),
              RecordedCommit(HARNESS_HEAD_REVISION, "second change", headDiff),
            ),
          response = { request ->
            if (request.skillRunRequest.issueKey == "code-review-integration") throw failure
            RecordedWorkerResponse()
          },
        ),
        ReviewRecorder(),
      )

    val thrown =
      assertFailsWith<SkillBillRuntimeException> {
        runner.reviewed(harnessRequest().copy(reportContract = ParallelCodeReviewReportContract.STANDALONE_REPORT_ONLY))
      }
    assertSame(failure, thrown)
  }

  @Test
  fun `standalone verification propagates Cursor termination instead of returning an incomplete review`() {
    val failure = SkillBillRuntimeException(CursorReviewStreamFailureCode.TERMINATION, "provider terminated")
    val runner =
      reviewHarness(
        ReviewHarnessConfig(
          manifests =
            listOf(sparseReviewPack(slug = "kotlin", requiredArea = "architecture", pathAreas = emptyMap())),
          diff = diffForPaths("src/Main.kt"),
          response = { request ->
            when (request.skillRunRequest.issueKey) {
              "code-review" -> RecordedWorkerResponse(stdout = "Null is unchecked in Main.\nverdict: changes_requested")
              "code-review-verification" -> throw failure
              else -> RecordedWorkerResponse()
            }
          },
        ),
        ReviewRecorder(),
      )

    val thrown =
      assertFailsWith<SkillBillRuntimeException> {
        runner.reviewed(harnessRequest().copy(reportContract = ParallelCodeReviewReportContract.STANDALONE_REPORT_ONLY))
      }
    assertSame(failure, thrown)
  }

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
