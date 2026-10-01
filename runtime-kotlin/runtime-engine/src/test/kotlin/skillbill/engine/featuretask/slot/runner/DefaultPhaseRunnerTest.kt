package skillbill.engine.featuretask.slot.runner

import skillbill.engine.featuretask.slot.PhaseStepFacts
import skillbill.engine.featuretask.slot.PhaseStepInput
import skillbill.engine.featuretask.slot.state.PhaseLaunchState
import skillbill.engine.featuretask.slot.state.PhaseSettledEnvelopeRead
import skillbill.install.model.SupportedAgent
import skillbill.ports.agentrun.agentRunLaunchFacts
import skillbill.ports.goalrunner.runner.GoalRunnerSubtaskLauncher
import skillbill.ports.workflow.gitops.NoopWorkflowGitOperations
import skillbill.workflow.taskruntime.model.skeleton.PhaseStepPolicy
import java.lang.reflect.Proxy
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.fail

class DefaultPhaseRunnerTest {
  @Test
  fun `a step captures its stdout as process output without reading an envelope from it`() {
    val stdout =
      """
      Tried to publish the release notes.
      {"status": "blocked", "value": "Release notes need an approved changelog.", "verdict": "needs_changelog", "failure_disposition": "operator_action"}
      """.trimIndent()
    val runner = DefaultPhaseRunner(launcher(stdout), NoopWorkflowGitOperations)

    val output = runner.run(input("release_notes"), PrepareOnlyPhaseRunState)

    assertEquals(stdout, output.stdout.text)
    assertEquals(PhaseSettledEnvelopeRead.None, output.settledEnvelope)
  }

  private fun launcher(stdout: String) =
    GoalRunnerSubtaskLauncher { agentRunLaunchFacts(SupportedAgent.CLAUDE, stdout = stdout) }

  private fun input(stepName: String) =
    PhaseStepInput(
      stepName = stepName,
      directive = "Publish the release notes.",
      priorValues = emptyMap(),
      operatorInstructions = null,
      facts =
        PhaseStepFacts(
          issueKey = "SKILL-380",
          repoRoot = Path.of("."),
          timeout = null,
          invokedAgentId = "claude",
          configuredAgentOverrideId = null,
          modelOverride = null,
          effortOverride = null,
          compaction = null,
          attempt = null,
          observeLaunch = false,
          briefingText = "",
        ),
      policy = PhaseStepPolicy(false, false, false, false, false),
    )
}

private val PrepareOnlyPhaseRunState: PhaseLaunchState =
  Proxy.newProxyInstance(
    PhaseLaunchState::class.java.classLoader,
    arrayOf(PhaseLaunchState::class.java),
  ) { _, method, args ->
    when (method.name) {
      "prepareLaunch" -> args?.single()
      "toString" -> "PrepareOnlyPhaseRunState"
      "hashCode" -> 0
      "equals" -> false
      else -> fail("an untracked launch reached PhaseLaunchState.${method.name}")
    }
  } as PhaseLaunchState
