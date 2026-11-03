package skillbill.engine.goalrunner

import skillbill.engine.featuretask.phaserun.PhaseRunRequest
import skillbill.engine.featuretask.phaserun.PhaseRunResult
import skillbill.engine.goalrunner.execution.core.GoalRunnerPullRequestWriter
import skillbill.engine.goalrunner.execution.core.goalRunnerDeps
import skillbill.engine.goalrunner.execution.core.testGoalRunner
import skillbill.engine.goalrunner.model.GoalRunnerRunRequest
import skillbill.engine.goalrunner.qa.GoalQaGateResult
import skillbill.engine.goalrunner.qa.GoalRunnerQaGate
import skillbill.goalrunner.model.GoalRunnerRunReport
import skillbill.ports.goalrunner.runner.PullRequestIdentityLookup
import skillbill.ports.goalrunner.runner.model.PullRequestIdentity
import skillbill.workflow.taskruntime.model.skeleton.SkeletonDefinition
import skillbill.workflow.taskruntime.phase.task.FeatureTaskRuntimePhaseWorkflowDefinition
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class GoalPullRequestDescriptionTest {
  @Test
  fun `the goal pull request is written by the pr description step with the goal facts`() {
    val github = FakeGithub()
    val writer = RecordingWriter { github.opened = true }
    val pr = RecordingPullRequestPort()

    val report = runGoal(github, writer, pr)

    val completed = assertIs<GoalRunnerRunReport.Completed>(report, "$report")
    assertEquals("opened", completed.pullRequestStatus.wireValue)
    assertEquals(PR_URL, completed.pullRequestUrl)
    assertEquals(emptyList(), pr.requests)
    val request = writer.requests.single()
    assertEquals(SkeletonDefinition.GOAL_PR.id, request.definitionId)
    assertEquals(setOf(FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_PR), request.instructions?.stepIds)
    val instructions = request.instructions?.text.orEmpty()
    assertContains(instructions, "[SKILL-56]")
    assertContains(instructions, "feat/SKILL-56-goal")
    assertContains(instructions, "Subtask 1")
    assertContains(instructions, QA_WARNING)
    assertEquals("feat/SKILL-56-goal", writer.branches.single())
  }

  @Test
  fun `the runtime-composed description opens the pull request when the step leaves none`() {
    val github = FakeGithub()
    val writer = RecordingWriter {}
    val pr = RecordingPullRequestPort()

    val report = runGoal(github, writer, pr)

    assertIs<GoalRunnerRunReport.Completed>(report, "$report")
    assertEquals(1, writer.requests.size)
    assertContains(pr.requests.single().body, "- Goal: warning: $QA_WARNING")
  }

  @Test
  fun `the runtime-composed description opens the pull request when the step fails`() {
    val github = FakeGithub()
    val writer = RecordingWriter { error("agent crashed") }
    val pr = RecordingPullRequestPort()

    val report = runGoal(github, writer, pr)

    assertIs<GoalRunnerRunReport.Completed>(report, "$report")
    assertEquals(1, pr.requests.size)
  }

  @Test
  fun `an existing pull request goes to the runtime port without writing a description`() {
    val github = FakeGithub(opened = true)
    val writer = RecordingWriter { error("an existing pull request must not be rewritten") }
    val pr = RecordingPullRequestPort()

    val report = runGoal(github, writer, pr)

    assertIs<GoalRunnerRunReport.Completed>(report, "$report")
    assertTrue(writer.requests.isEmpty())
    assertEquals(1, pr.requests.size)
  }

  private fun runGoal(
    github: FakeGithub,
    writer: RecordingWriter,
    pr: RecordingPullRequestPort,
  ): GoalRunnerRunReport {
    val store = InMemoryGoalManifestStore(manifest = manifest(subtaskCount = 1))
    val outcomes = RecordingOutcomeStore()
    val launcher =
      RecordingSubtaskLauncher {
        store.mutate { current -> current.withWorkflowId(1, "wfl-1") }
        outcomes["wfl-1"] = completeOutcome(1)
        launchFacts()
      }
    val runner =
      testGoalRunner(
        goalRunnerDeps(store, launcher, outcomes, pr).copy(
          qaGate = GoalRunnerQaGate { _, _ -> GoalQaGateResult.Warned(QA_WARNING) },
          pullRequestIdentityLookup = github.lookup,
          pullRequestWriter = writer,
        ),
      )
    return runner.run(
      GoalRunnerRunRequest(
        issueKey = "SKILL-56",
        repoRoot = Path.of("/tmp/skillbill-goal-pr-description"),
        invokedAgentId = "claude",
      ),
    )
  }

  private class FakeGithub(
    var opened: Boolean = false,
  ) {
    val lookup =
      PullRequestIdentityLookup { _, _ ->
        if (opened) PullRequestIdentity.Found(PR_URL, PR_NUMBER) else PullRequestIdentity.Absent
      }
  }

  private class RecordingWriter(
    private val onRun: () -> Unit,
  ) : GoalRunnerPullRequestWriter {
    val requests = mutableListOf<PhaseRunRequest>()
    val branches = mutableListOf<String>()

    override fun run(
      request: PhaseRunRequest,
      parentWorkflowId: String,
      branch: String,
    ): PhaseRunResult {
      requests += request
      branches += branch
      onRun()
      return PhaseRunResult.Completed("goal-pr", listOf("pr"), null, "pull request written")
    }
  }

  private companion object {
    const val PR_URL = "https://github.com/canonical/skill-bill/pull/77"
    const val PR_NUMBER = 77
    const val QA_WARNING = "QA stopped at 'qa': findings remain"
  }
}
