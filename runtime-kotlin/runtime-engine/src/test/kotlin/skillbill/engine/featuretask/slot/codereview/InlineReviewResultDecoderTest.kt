package skillbill.engine.featuretask.slot.codereview

import skillbill.engine.featuretask.slot.PhaseLaunchFailure
import skillbill.engine.featuretask.slot.PhaseLaunchFailureKind
import skillbill.engine.featuretask.slot.reviewStepOutput
import skillbill.ports.agentrun.model.AgentRunTermination
import skillbill.review.model.ParallelReviewSeverity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class InlineReviewResultDecoderTest {
  @Test
  fun `remaining blocker findings parse into the review register`() {
    val result =
      InlineReviewResultDecoder.decode(
        "cursor",
        reviewStepOutput(
          "- [F-001] Blocker | High | src/main/App.kt:42 | remaining defect\nverdict: changes_requested",
        ),
      )

    val finding = result.mergeResult.findings.single()
    assertEquals(ParallelReviewSeverity.BLOCKER, finding.severity)
    assertEquals("src/main/App.kt", finding.repositoryPath)
    assertEquals(42, finding.line)
    assertEquals("remaining defect", finding.description)
    assertTrue(result.lane1.success)
    assertNull(InlineReviewResultDecoder.failedLaneReason(result))
  }

  @Test
  fun `a hung child is a failed lane not an approved empty register`() {
    val result =
      InlineReviewResultDecoder.decode(
        "cursor",
        reviewStepOutput("").copy(termination = AgentRunTermination.TimedOut),
      )

    assertFalse(result.lane1.success)
    assertEquals("agent timed out", result.lane1.failureReason)
    assertEquals(emptyList(), result.mergeResult.findings)
    assertEquals(
      "Feature-task-runtime phase 'review' agent timed out",
      InlineReviewResultDecoder.failedLaneReason(result),
    )
  }

  @Test
  fun `unsupported agent fails the lane with the launcher cause`() {
    val result =
      InlineReviewResultDecoder.decode(
        "cursor",
        reviewStepOutput("").copy(
          launchFailure =
            PhaseLaunchFailure(
              kind = PhaseLaunchFailureKind.UNSUPPORTED_AGENT,
              reason = "Feature-task-runtime agent 'cursor' is unsupported",
              cause = "cursor is not installed",
            ),
        ),
      )

    assertFalse(result.lane1.success)
    assertEquals("cursor is not installed", result.lane1.failureReason)
  }

  @Test
  fun `a non-zero exit is a failed lane even when stdout carries a verdict`() {
    val result =
      InlineReviewResultDecoder.decode(
        "cursor",
        reviewStepOutput("verdict: approved").copy(termination = AgentRunTermination.Exited(3)),
      )

    assertFalse(result.lane1.success)
    assertEquals("agent exited with status 3", result.lane1.failureReason)
  }
}
