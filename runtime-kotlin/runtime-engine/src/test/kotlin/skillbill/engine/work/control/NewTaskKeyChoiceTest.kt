package skillbill.engine.work.control

import skillbill.application.FakeDatabaseSessionFactory
import skillbill.application.InMemoryWorkflowStates
import skillbill.application.TestDecompositionManifestStore
import skillbill.application.TestRepositoryEnclosingRoot
import skillbill.engine.goal.GoalNewWorkAdmission
import skillbill.engine.goal.GoalNewWorkAdmissionPort
import skillbill.engine.goal.GoalPreflightPort
import skillbill.engine.goal.GoalPreflightRequest
import skillbill.engine.goal.GoalPreflightResult
import skillbill.engine.goal.GoalPreflightVerdict
import skillbill.engine.operation.core.NoOperationCardLifecycle
import skillbill.engine.work.control.model.NewTaskIntakeRequest
import skillbill.engine.work.control.model.NewTaskPreparation
import skillbill.model.EnvironmentContext
import skillbill.ports.diagnostics.NoopRuntimeDiagnostics
import skillbill.ports.operation.EmptyOperationCardRepository
import skillbill.ports.work.WorkRunLauncher
import skillbill.ports.work.model.WorkRunLaunchRequest
import skillbill.ports.work.model.WorkRunLaunchResult
import skillbill.ports.work.model.WorkRunStopRequest
import skillbill.ports.work.model.WorkRunStopResult
import skillbill.ports.workflow.branchworktree.RecordingBranchWorktreeResources
import skillbill.ports.workflow.goalworktree.InPlaceGoalWorktreeResources
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs

class NewTaskKeyChoiceTest {
  private val repoRoot: Path = Files.createTempDirectory("new-task-key-choice")
  private val subject =
    WorkNewTaskStart(
      FakeDatabaseSessionFactory(InMemoryWorkflowStates()),
      GoalNewWorkAdmissionPort { intake, _ ->
        LEADING_KEY.find(intake.trim())?.let { GoalNewWorkAdmission.Admitted(it.groupValues[1]) }
          ?: GoalNewWorkAdmission.NoIssueKey
      },
      NewTaskIssueKeyAllocator(
        projectStore(repoRoot, repoRoot.resolve("project-specs")),
        TestDecompositionManifestStore,
        NoopRuntimeDiagnostics,
      ),
      object : GoalPreflightPort {
        override fun preflight(request: GoalPreflightRequest) =
          GoalPreflightResult(GoalPreflightVerdict.NEW_WORK, request.issueKey)
      },
      object : WorkRunLauncher {
        override fun start(request: WorkRunLaunchRequest) = WorkRunLaunchResult.Refused("Unused")

        override fun stop(request: WorkRunStopRequest) = WorkRunStopResult.NotRunning
      },
      EnvironmentContext(),
      TestRepositoryEnclosingRoot,
      NoopRuntimeDiagnostics,
      InPlaceGoalWorktreeResources,
      RecordingBranchWorktreeResources(),
      OperationCardLaunches(EmptyOperationCardRepository, NoOperationCardLifecycle) { emptyMap() },
    )

  @Test
  fun `an admitted first-token key is ready without allocation`() {
    val ready =
      assertIs<NewTaskPreparation.Ready>(
        subject.prepareNewTask(
          repoRoot,
          NewTaskIntakeRequest("SKILL-5249 Add follow-up fixes", null, null, null, emptySet()),
        ),
      )

    assertEquals("SKILL-5249", ready.key)
    assertFalse(ready.allocated)
  }

  private companion object {
    val LEADING_KEY = Regex("^([A-Z][A-Z0-9]*-\\d+)(?:\\s|$)")
  }
}
