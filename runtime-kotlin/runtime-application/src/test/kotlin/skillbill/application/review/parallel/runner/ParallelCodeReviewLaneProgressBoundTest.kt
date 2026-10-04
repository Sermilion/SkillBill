package skillbill.application.review.parallel.runner

import skillbill.application.review.snapshot.HARNESS_HEAD_REVISION
import skillbill.application.review.snapshot.RecordedCommit
import skillbill.application.review.snapshot.ReviewHarnessConfig
import skillbill.application.review.snapshot.ReviewRecorder
import skillbill.application.review.snapshot.diffForPaths
import skillbill.application.review.snapshot.harnessRequest
import skillbill.application.review.snapshot.reviewHarness
import skillbill.application.review.snapshot.reviewed
import skillbill.application.review.snapshot.sparseReviewPack
import skillbill.install.model.SupportedAgent
import skillbill.ports.agentrun.agentRunLaunchFacts
import skillbill.ports.agentrun.model.AgentRunTermination
import skillbill.ports.review.evidence.GovernedReviewEvidenceEndpointBinder
import skillbill.ports.review.evidence.GovernedReviewEvidenceEndpointHandle
import skillbill.ports.review.evidence.ReviewEvidenceBroker
import skillbill.ports.review.model.GovernedReviewEvidenceEndpointDescriptor
import skillbill.review.context.model.execution.CodeReviewExecutionMode
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

class ParallelCodeReviewLaneProgressBoundTest {
  @Test
  fun `a delegated lane that times out fails lane1 and every lane launch carries the progress bound`() {
    val recorder = ReviewRecorder()
    val binder = CapturingEndpointBinder()
    val probeTokens = mutableListOf<String?>()
    val config =
      ReviewHarnessConfig(
        manifests = listOf(sparseReviewPack("kotlin", "architecture", mapOf("security" to listOf("src/api/")))),
        diff = diffForPaths(PATH),
        commits = listOf(RecordedCommit(HARNESS_HEAD_REVISION, "commit touching $PATH", diffForPaths(PATH))),
        simulateEvidenceReads = false,
        evidenceEndpointBinder = binder,
        parentLaunch = { request ->
          val probe = request.skillRunRequest.progressProbe
          if (request.skillRunRequest.reviewEvidenceEndpoint != null) {
            probeTokens += probe.progressToken()
            binder.onEvidenceRead?.invoke()
            probeTokens += probe.progressToken()
          }
          agentRunLaunchFacts(SupportedAgent.CODEX, termination = AgentRunTermination.TimedOut, stdout = "")
        },
      )

    val result =
      reviewHarness(config, recorder)
        .reviewed(
          harnessRequest(codeReviewMode = CodeReviewExecutionMode.DELEGATED).copy(laneProgressIdleTimeout = BOUND),
        )

    assertFalse(result.lane1.success)
    assertTrue(assertNotNull(result.lane1.failureReason).contains("agent timed out"))
    assertTrue(recorder.parentLaunches.isNotEmpty())
    recorder.parentLaunches.forEach { launch ->
      assertEquals(BOUND, launch.skillRunRequest.progressIdleTimeout)
      assertFalse(launch.skillRunRequest.readOnlyPhase)
    }
    assertEquals(2, probeTokens.size, "the governed lane must launch once")
    assertNotEquals(probeTokens[0], probeTokens[1], "an evidence read must advance the governed probe token")
  }

  private class CapturingEndpointBinder : GovernedReviewEvidenceEndpointBinder {
    var onEvidenceRead: (() -> Unit)? = null
      private set

    override fun bind(
      lane: String,
      broker: ReviewEvidenceBroker,
      onEvidenceRead: (() -> Unit)?,
    ): GovernedReviewEvidenceEndpointHandle {
      this.onEvidenceRead = onEvidenceRead
      val root = Files.createTempDirectory("review-bound-endpoint")
      return object : GovernedReviewEvidenceEndpointHandle {
        override val descriptor =
          GovernedReviewEvidenceEndpointDescriptor(
            lane = lane,
            socketPath = root.resolve("evidence.sock"),
            mcpConfigPath = root.resolve("mcp.json"),
            token = "bound-token",
          )

        override fun close() = Unit
      }
    }
  }

  private companion object {
    const val PATH = "src/api/Auth.kt"
    val BOUND = 7.minutes
  }
}
