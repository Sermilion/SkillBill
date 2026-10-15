package skillbill.engine.goalrunner.execution.support

import skillbill.application.TestRepositoryEnclosingRoot
import skillbill.engine.featuretask.phaserun.StandalonePhaseRegistrationFactory
import skillbill.engine.goalrunner.manifest
import skillbill.model.EnvironmentContext
import skillbill.ports.diagnostics.NoopRuntimeDiagnostics
import skillbill.ports.system.CheckedOutBranchSource
import skillbill.ports.taskruntime.NoopFeatureTaskRuntimeWorkerSupervisor
import skillbill.workflow.decomposition.model.CurrentSubtaskIntent
import skillbill.workflow.decomposition.model.DecompositionExecutionModel
import skillbill.workflow.decomposition.model.DecompositionStackBranch
import java.nio.file.Files
import java.time.Clock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class GoalWorkBranchTest {
  @Test
  fun `same-branch goal works on its feature branch, not its base branch`() {
    assertEquals("feat/SKILL-56-goal", manifest(subtaskCount = 1).copy(baseBranch = "feat/SKILL-55").goalWorkBranch())
  }

  @Test
  fun `stacked goal works on the stack branch of its next subtask`() {
    val stacked =
      manifest(subtaskCount = 2).let { goal ->
        goal.copy(
          executionModel = DecompositionExecutionModel.STACKED_BRANCHES,
          currentSubtaskIntent = CurrentSubtaskIntent(subtaskId = 2, action = "start"),
          stackBranches =
            listOf(
              DecompositionStackBranch(1, "feat/SKILL-56-goal-1", "main"),
              DecompositionStackBranch(2, "feat/SKILL-56-goal-2", "feat/SKILL-56-goal-1"),
            ),
          subtasks =
            goal.subtasks.map { subtask ->
              if (subtask.id == 1) {
                subtask.copy(status = "complete", workflowId = "wfl-1", commitSha = "sha-1")
              } else {
                subtask
              }
            },
        )
      }

    assertEquals("feat/SKILL-56-goal-2", stacked.goalWorkBranch())
  }

  @Test
  fun `goal without a feature branch has no work branch`() {
    assertNull(manifest(subtaskCount = 1).copy(featureBranch = " ").goalWorkBranch())
  }

  @Test
  fun `workflow registration records the work branch over the checked-out branch`() {
    val registrations =
      StandalonePhaseRegistrationFactory(
        TestRepositoryEnclosingRoot,
        CheckedOutBranchSource { "feat/SKILL-55" },
        Clock.systemUTC(),
        NoopFeatureTaskRuntimeWorkerSupervisor,
        NoopRuntimeDiagnostics,
        EnvironmentContext(environment = mapOf("PATH" to "/usr/bin")),
      )
    val repoRoot = Files.createTempDirectory("goal-work-branch")

    assertEquals(
      "feat/SKILL-56-goal",
      registrations.workflowRegistration(repoRoot, "SKILL-56", "wfl-goal", "feat/SKILL-56-goal").branchCorrelation,
    )
    assertEquals(
      "feat/SKILL-55",
      registrations.workflowRegistration(repoRoot, "SKILL-56", "wfl-goal").branchCorrelation,
    )
  }
}
