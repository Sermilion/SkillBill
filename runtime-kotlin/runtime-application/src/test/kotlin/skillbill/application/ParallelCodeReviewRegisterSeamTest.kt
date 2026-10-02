package skillbill.application

import skillbill.application.review.parallel.verification.LaneRegisterParse
import skillbill.application.review.parallel.verification.parseLaneRegisterSeam
import skillbill.application.review.snapshot.reviewed
import skillbill.application.review.snapshot.simulateGovernedEvidenceReads
import skillbill.application.reviewevidence.model.ParallelReviewScope
import skillbill.install.model.SupportedAgent
import skillbill.ports.agentrun.agentRunLaunchFacts
import skillbill.ports.goalrunner.runner.GoalRunnerSubtaskLauncher
import skillbill.review.context.model.execution.CodeReviewExecutionMode
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ParallelCodeReviewRegisterSeamTest {
  @Test
  fun `a zero-exit blocked lane without evidence fails despite an approval verdict`() {
    val blocked =
      """
      This session has no worker-launch capability and no bound evidence broker.
      Per the contract I am not running the review inline as a single prompt, and I am not
      emitting a findings register — zero [F-XXX] lines here means not executed, not clean.
      verdict: approved
      """.trimIndent()
    val launcher =
      GoalRunnerSubtaskLauncher { request ->
        agentRunLaunchFacts(
          agent = SupportedAgent.fromNormalizedId(request.invokedAgentId, label = "agentId"),
          stdout = blocked,
          stderr = "",
        )
      }
    val runner = runner(launcher, diffResolver = RecordingDiffResolver(default = diffFor("A.kt")))

    val result =
      runner.reviewed(
        baseRequest(scope = ParallelReviewScope.STAGED).copy(codeReviewMode = CodeReviewExecutionMode.DELEGATED),
      )

    assertFalse(result.lane1.success)
    assertEquals("Review worker returned without reading assigned evidence.", result.lane1.failureReason)
    assertNull(result.lane1.droppedCandidateDiagnostic)
    assertTrue(result.mergeResult.findings.isEmpty())
  }

  @Test
  fun `a short F-id register line is padded and admitted instead of soft-dropped`() {
    val runner =
      runner(
        stdoutLauncher("[F-1] Major | High | a.kt:3 | x\nverdict: approved"),
        diffResolver = RecordingDiffResolver(default = diffFor("A.kt")),
      )

    val result =
      runner.reviewed(
        baseRequest(scope = ParallelReviewScope.STAGED),
      )

    assertTrue(result.lane1.success)
    assertNull(result.lane1.failureReason)
    assertNull(result.lane1.droppedCandidateDiagnostic)
    assertEquals(1, result.mergeResult.findings.size)
  }

  @Test
  fun `an oversized lane body without register soft-admits without leaking the body into diagnostics`() {
    val body = "prose ".repeat(2_000)
    val runner =
      runner(
        stdoutLauncher("$body\nverdict: approved"),
        diffResolver = RecordingDiffResolver(default = diffFor("A.kt")),
      )

    val result =
      runner.reviewed(
        baseRequest(scope = ParallelReviewScope.STAGED),
      )

    assertTrue(result.lane1.success)
    assertNull(result.lane1.failureReason)
    assertNull(result.lane1.droppedCandidateDiagnostic)
    assertFalse(
      result.lane1.toString().contains(body.trim()),
      "the full lane output must never reach lane status diagnostics",
    )
  }

  @Test
  fun `an admissible register produces findings with no absence verdict`() {
    val runner =
      runner(
        stdoutLauncher("[F-001] Major | High | path=\"A.kt\" | line=1 | admissible\nverdict: approved"),
        diffResolver = RecordingDiffResolver(default = diffFor("A.kt")),
      )

    val result =
      runner.reviewed(
        baseRequest(scope = ParallelReviewScope.STAGED),
      )

    assertTrue(result.lane1.success)
    assertNull(result.lane1.failureReason)
    assertNull(result.lane1.droppedCandidateDiagnostic)
    assertTrue(result.mergeResult.findings.isNotEmpty())
  }

  @Test
  fun `a throwing parse at the register seam returns a failed parse instead of an empty register`() {
    val laneBody = "lane body line ".repeat(200)
    val failed =
      assertIs<LaneRegisterParse.Failed>(
        parseLaneRegisterSeam(laneBody, lane = "lane-1") { error(laneBody) },
      )

    assertEquals("attributeLaneFindings", failed.seam)
    assertEquals("lane-1", failed.lane)
    assertFalse(failed.detail.contains(laneBody), "the seam failure must not carry the full lane output body")
    assertTrue(
      failed.detail.length < laneBody.length,
      "the cause detail must be bounded even when the parser echoes the lane body: ${failed.detail.length}",
    )
  }

  @Test
  fun `a short sibling F-id is padded and admitted alongside a canonical register line`() {
    val runner =
      runner(
        stdoutLauncher(
          "[F-001] Major | High | path=\"A.kt\" | line=1 | admissible\n" +
            "[F-2] Major | High | a.kt:3 | also admissible after pad\n" +
            "verdict: approved",
        ),
        diffResolver = RecordingDiffResolver(default = diffFor("A.kt")),
      )

    val result =
      runner.reviewed(
        baseRequest(scope = ParallelReviewScope.STAGED),
      )

    assertTrue(result.lane1.success)
    assertNull(result.lane1.failureReason)
    assertNull(result.lane1.droppedCandidateDiagnostic)
    assertEquals(2, result.mergeResult.findings.size)
  }

  @Test
  fun `a finding body without an F-token is assigned the next id and admitted`() {
    val runner =
      runner(
        stdoutLauncher("Major | High | a.kt:3 | missing token\nverdict: approved"),
        diffResolver = RecordingDiffResolver(default = diffFor("A.kt")),
      )

    val result =
      runner.reviewed(
        baseRequest(scope = ParallelReviewScope.STAGED),
      )

    assertTrue(result.lane1.success)
    assertNull(result.lane1.failureReason)
    assertNull(result.lane1.droppedCandidateDiagnostic)
    assertEquals(1, result.mergeResult.findings.size)
  }

  @Test
  fun `a register line that remains unparseable after F-id padding is soft-dropped with a diagnostic`() {
    val broken = "[F-1] Major | High | not-a-location | missing line anchor"
    val runner =
      runner(
        stdoutLauncher("$broken\nverdict: approved"),
        diffResolver = RecordingDiffResolver(default = diffFor("A.kt")),
      )

    val result =
      runner.reviewed(
        baseRequest(scope = ParallelReviewScope.STAGED),
      )

    assertTrue(result.lane1.success)
    assertNull(result.lane1.failureReason)
    assertTrue(result.mergeResult.findings.isEmpty())
    val diagnostic = assertNotNull(result.lane1.droppedCandidateDiagnostic)
    assertTrue(diagnostic.contains("unmatched_candidate_line"), "still-broken shape must be named: $diagnostic")
  }

  @Test
  fun `an unmatched candidate remains in authoritative review output`() {
    val nearMiss = "[F-001] Major | architecture | path=\"A.kt\" | line=1 | missing validation"
    val runner =
      runner(
        stdoutLauncher("$nearMiss\nverdict: changes_requested"),
        diffResolver = RecordingDiffResolver(default = diffFor("A.kt")),
      )

    val result =
      runner.reviewed(
        baseRequest(scope = ParallelReviewScope.STAGED),
      )

    assertTrue(result.mergeResult.findings.isEmpty())
    assertContains(result.output, nearMiss)
    assertContains(result.lane1.droppedCandidateDiagnostic.orEmpty(), "unmatched_candidate_line")
  }

  @Test
  fun `a parser fault at the register seam soft-admits instead of failing the lane`() {
    val runner =
      createRunner(
        stdoutLauncher("prose with no register\nverdict: approved"),
        RunnerFixtureConfig(
          diffResolver = RecordingDiffResolver(default = diffFor("A.kt")),
          registerParse = { error("parser exploded") },
        ),
      )

    val result =
      runner.reviewed(
        baseRequest(scope = ParallelReviewScope.STAGED),
      )

    assertTrue(result.lane1.success)
    assertNull(result.lane1.failureReason)
    assertTrue(result.mergeResult.findings.isEmpty())
  }

  private fun stdoutLauncher(stdout: String) =
    GoalRunnerSubtaskLauncher { request ->
      simulateGovernedEvidenceReads(request.skillRunRequest)
      agentRunLaunchFacts(
        agent = SupportedAgent.fromNormalizedId(request.invokedAgentId, label = "agentId"),
        stdout = stdout,
        stderr = "",
      )
    }
}
