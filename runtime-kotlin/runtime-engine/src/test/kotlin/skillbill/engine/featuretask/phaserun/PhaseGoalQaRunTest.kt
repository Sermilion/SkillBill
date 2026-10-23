package skillbill.engine.featuretask.phaserun

import org.junit.jupiter.api.io.TempDir
import skillbill.engine.RecordingWorkflowGitOperations
import skillbill.engine.featuretask.qa.execution.testQaExecutionLifecycle
import skillbill.engine.featuretask.qa.report.FeatureTaskQaOutcomeRecorder
import skillbill.engine.featuretask.slot.testPhaseStrategies
import skillbill.ports.goalrunner.runner.GoalRunnerSubtaskLauncher
import skillbill.ports.idestatus.model.StandaloneQaOutcome
import skillbill.ports.workflow.gitops.model.WorkflowGitOperationResult
import skillbill.workflow.taskruntime.model.skeleton.FeatureTaskRuntimeQaSelection
import skillbill.workflow.taskruntime.model.skeleton.SkeletonDefinition
import java.nio.file.Path
import java.time.Clock
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertIs

class PhaseGoalQaRunTest {
  @TempDir lateinit var home: Path

  @Test
  fun `off selection settles a runtime authored skip without launching an agent`() {
    val clock = Clock.systemUTC()
    val database = phaseRunDatabase(home, clock)
    val git =
      RecordingWorkflowGitOperations().apply {
        headCommitShaValue = "1".repeat(40)
        repositoryFingerprintValue = "2".repeat(64)
      }
    val strategies =
      testPhaseStrategies(
        launcher = GoalRunnerSubtaskLauncher { error("QA disabled by policy must not launch an agent.") },
        gitOperations = git,
        qaExecution = testQaExecutionLifecycle(git),
        qaOutcomes = FeatureTaskQaOutcomeRecorder(database, clock),
      )
    val entry = phaseRunEntry(strategies, git, database, clock)

    val result =
      assertIs<PhaseRunResult.Completed>(
        entry.runForGoal(
          PhaseRunRequest(
            definitionId = SkeletonDefinition.GOAL_QA.id,
            repoRoot = home,
            invokedAgentId = "claude",
            intake = "SKILL-431",
            qaSelection = FeatureTaskRuntimeQaSelection.OFF,
            acceptanceCriteria = listOf("The parent goal acceptance criteria are used."),
          ),
          "goal-parent",
          "feat/existing-runtime-branch",
        ),
      )

    assertEquals(listOf("qa"), result.completedStepIds)
    val outcome = database.read { it.featureTaskQaOutcomes.find("goal-parent") }
    assertEquals(StandaloneQaOutcome.SKIPPED, outcome?.outcome)
    assertEquals("2".repeat(64), outcome?.testedTreeIdentity)
  }

  @Test
  fun `QA repository failure before launch blocks without an invalid zero attempt ledger entry`() {
    val clock = Clock.systemUTC()
    val database = phaseRunDatabase(home, clock)
    val git =
      RecordingWorkflowGitOperations().apply {
        worktreeStatusResult = WorkflowGitOperationResult.Failed(error = "worktree is unreadable")
      }
    val strategies =
      testPhaseStrategies(
        launcher = GoalRunnerSubtaskLauncher { error("A repository failure must not launch an agent.") },
        gitOperations = git,
        qaExecution = testQaExecutionLifecycle(git),
        qaOutcomes = FeatureTaskQaOutcomeRecorder(database, clock),
      )
    val entry = phaseRunEntry(strategies, git, database, clock)

    val result =
      assertIs<PhaseRunResult.Blocked>(
        entry.runForGoal(
          PhaseRunRequest(
            definitionId = SkeletonDefinition.GOAL_QA.id,
            repoRoot = home,
            invokedAgentId = "claude",
            qaSelection = FeatureTaskRuntimeQaSelection.OFF,
            acceptanceCriteria = listOf("The goal stops before publication."),
          ),
          "goal-parent",
          "feat/existing-runtime-branch",
        ),
      )

    assertEquals("qa", result.stepId)
    assertContains(result.reason, "worktree is unreadable")
  }
}
