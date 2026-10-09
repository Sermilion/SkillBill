package skillbill.cli.phase

import skillbill.cli.kernel.cli.CliRunState
import skillbill.engine.featuretask.phaserun.PhaseRunResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PhaseResultReportingTest {
  @Test
  fun `green CI report includes the earlier pull request URL in execution order`() {
    val state = CliRunState(stdinText = null)
    val prUrl = "https://github.com/example/repo/pull/7"
    val result =
      PhaseRunResult.Completed(
        invocationId = "phr-reporting",
        completedStepIds = listOf("commit_push", "pr", "monitor"),
        reviewResult = null,
        value = "CI passed",
        completedOutputs = listOf("Commit pushed", "Created pull request $prUrl", "CI passed"),
      )

    writePhaseResult(state, "pr", result)

    val output = requireNotNull(state.result).stdout
    assertEquals(0, state.result?.exitCode)
    assertTrue(output.contains(prUrl), output)
    assertTrue(output.indexOf("Commit pushed") < output.indexOf(prUrl))
    assertTrue(output.indexOf(prUrl) < output.indexOf("CI passed"))
  }

  @Test
  fun `blocked monitor retains the already created pull request in CLI output`() {
    val state = CliRunState(stdinText = null)
    val prUrl = "https://github.com/example/repo/pull/7"
    val result =
      PhaseRunResult.Blocked(
        invocationId = "phr-reporting",
        completedStepIds = listOf("commit_push", "pr"),
        reviewResult = null,
        stepId = "monitor",
        reason = "CI failed after three repair attempts",
        completedOutputs = listOf("Created pull request $prUrl"),
      )

    writePhaseResult(state, "pr", result)

    val output = requireNotNull(state.result).stdout
    assertEquals(1, state.result?.exitCode)
    assertTrue(output.contains(prUrl), output)
    assertTrue(output.contains(result.reason), output)
    assertTrue(output.indexOf(prUrl) < output.indexOf(result.reason))
  }
}
