package skillbill.application.review.parallel.runner

import skillbill.application.review.snapshot.RecordedWorkerResponse
import skillbill.application.review.snapshot.ReviewHarnessConfig
import skillbill.application.review.snapshot.ReviewRecorder
import skillbill.application.review.snapshot.diffForPaths
import skillbill.application.review.snapshot.harnessRequest
import skillbill.application.review.snapshot.reviewHarness
import skillbill.application.review.snapshot.reviewPack
import skillbill.application.review.snapshot.reviewed
import skillbill.application.reviewevidence.model.ParallelReviewScope
import skillbill.application.runner
import skillbill.ports.agentrun.model.AgentRunTermination
import skillbill.ports.goalrunner.runner.model.GoalRunnerSubtaskLaunchRequest
import skillbill.review.context.model.execution.CodeReviewExecutionMode
import skillbill.workflow.model.goalreview.toReviewAccountingBoundedJson
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ParallelCodeReviewRegressionTest {
  private val areas = listOf("architecture", "security", "testing")
  private val agentsBody = "AGENTS_BODY_SENTINEL ".repeat(400)
  private val parentBriefing = "PARENT_BRIEFING_SENTINEL ".repeat(400)

  @Test fun `long parent briefing and AGENTS bodies never reach a lane or its accounting`() {
    val recorder = ReviewRecorder()
    val repoRoot = Files.createTempDirectory("review-context-isolation")
    Files.writeString(repoRoot.resolve("AGENTS.md"), agentsBody)
    Files.writeString(repoRoot.resolve("parent-briefing.md"), parentBriefing)
    val runner = reviewHarness(config(), recorder)

    val result = runner.reviewed(harnessRequest(repoRoot = repoRoot))

    recorder.parentPrompts.forEach { prompt ->
      assertFalse(prompt.contains("AGENTS_BODY_SENTINEL"), "A review lane saw a project-guidance body.")
      assertFalse(prompt.contains("PARENT_BRIEFING_SENTINEL"), "A review lane saw the parent briefing body.")
    }
    val summary = assertNotNull(result.accountingSummary)
    val serialized = summary.toReviewAccountingBoundedJson()
    assertFalse(serialized.contains("AGENTS_BODY_SENTINEL"))
    assertFalse(serialized.contains("PARENT_BRIEFING_SENTINEL"))
    assertTrue(summary.aggregateCounters.launchBytes > 0, "The isolated launch projection remains measured.")
  }

  @Test fun `overlapping lane ownership assigns each hunk once`() {
    val recorder = ReviewRecorder()
    val runner =
      reviewHarness(
        config {
          RecordedWorkerResponse(
            stdout = finding("src/Repo.kt", specialist = "bill-kotlin-code-review-architecture"),
          )
        },
        recorder,
      )

    val result = runner.reviewed(harnessRequest())
    val summary = assertNotNull(result.accountingSummary)

    val lanes = summary.lanes.filter { it.children.isEmpty() }
    assertEquals(
      lanes.size,
      lanes.map { it.assignmentDigest }.distinct().size,
      "Every owned lane carries its own assignment digest.",
    )
    assertTrue(result.mergeResult.formattedOutput.isNotBlank())
  }

  @Test fun `a failed parent lane reports its own failure without masking sibling specialist output`() {
    val recorder = ReviewRecorder()
    val runner =
      reviewHarness(
        config { request ->
          if (request.invokedAgentId == "codex") {
            RecordedWorkerResponse(stdout = "", termination = AgentRunTermination.Exited(1))
          } else {
            RecordedWorkerResponse()
          }
        },
        recorder,
      )

    val result = runner.reviewed(harnessRequest())

    assertFalse(result.lane1.success)
    assertNotNull(result.lane1.failureReason)
  }

  @Test fun `each lane accounts its own launch bytes and terminal outcome`() {
    val recorder = ReviewRecorder()
    val runner = reviewHarness(config { RecordedWorkerResponse() }, recorder)

    val summary = assertNotNull(runner.reviewed(harnessRequest()).accountingSummary)

    val lanes = summary.lanes.filter { it.children.isEmpty() }
    assertEquals(1, lanes.size, "No lane is relabeled or duplicated in the accounting tree.")
    assertEquals(lanes.size, lanes.map { it.lane }.distinct().size)
    lanes.forEach { lane ->
      assertTrue(lane.counters.launchBytes > 0)
      assertEquals("completed", lane.terminalOutcome.wireValue)
    }
  }

  @Test fun `non-commit scopes keep their output and report commit-focused sequencing as not applicable`() {
    val branchResult = reviewHarness(config(), ReviewRecorder()).reviewed(harnessRequest())

    listOf(
      ParallelReviewScope.STAGED,
      ParallelReviewScope.UNSTAGED,
      ParallelReviewScope.BRANCH,
    ).forEach { scope ->
      val recorder = ReviewRecorder()

      val result =
        reviewHarness(config(), recorder).reviewed(
          harnessRequest(scope = scope, codeReviewMode = CodeReviewExecutionMode.DELEGATED),
        )

      assertEquals(
        branchResult.mergeResult.formattedOutput,
        result.mergeResult.formattedOutput,
        "Scope $scope must keep its existing merged output.",
      )
      val coverage = assertNotNull(result.coverage)
      assertNotNull(
        coverage.integrationNotApplicableReason,
        "Scope $scope has no commit sequence, so it must say so rather than stay silent.",
      )
      assertTrue(coverage.render().contains("not applicable"))
      assertTrue(
        recorder.parentLaunches.none { it.skillRunRequest.issueKey == "code-review-integration" },
        "A scope with no commit sequence launches no integration pass.",
      )
    }
  }

  private fun config(
    response: (GoalRunnerSubtaskLaunchRequest) -> RecordedWorkerResponse = { RecordedWorkerResponse() },
  ) = ReviewHarnessConfig(
    manifests = listOf(reviewPack("kotlin", areas, routingSignals = listOf("*.kt"))),
    diff = diffForPaths("src/Repo.kt"),
    response = response,
  )
}
