package skillbill.engine.work.control

import skillbill.application.TestDecompositionManifestStore
import skillbill.application.TestRepositoryEnclosingRoot
import skillbill.application.testProjectStore
import skillbill.engine.goal.GoalNewWorkAdmission
import skillbill.engine.goal.GoalNewWorkAdmissionPort
import skillbill.engine.goalrunner.goalPreflightServiceForTests
import skillbill.engine.operation.core.NoOperationCardLifecycle
import skillbill.engine.work.control.model.NewTaskStartResult
import skillbill.engine.work.control.model.StandalonePhaseRequest
import skillbill.engine.work.ideStatusClock
import skillbill.engine.work.testGoalRepositoryIdentity
import skillbill.infrastructure.sqlite.establishTemporarySchemaReadiness
import skillbill.infrastructure.sqlite.sqliteSessionFactoryForTests
import skillbill.model.EnvironmentContext
import skillbill.ports.diagnostics.NoopRuntimeDiagnostics
import skillbill.ports.operation.EmptyOperationCardRepository
import skillbill.ports.work.WorkRunLauncher
import skillbill.ports.work.model.WorkRunLaunchKind
import skillbill.ports.work.model.WorkRunLaunchRequest
import skillbill.ports.work.model.WorkRunLaunchResult
import skillbill.ports.work.model.WorkRunStopRequest
import skillbill.ports.work.model.WorkRunStopResult
import skillbill.ports.workflow.branchworktree.RecordingBranchWorktreeResources
import skillbill.ports.workflow.goalworktree.InPlaceGoalWorktreeResources
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class WorkNewTaskStartTest {
  @Test
  fun `monitor intake launches phase monitor with the intake before the agent flag`() {
    withRecordingStart("work-new-task-start-monitor") { start, root, launches ->
      val result = start.startStandalone(root, StandalonePhaseRequest("monitor", "branch:main"), "cursor")
      assertIs<NewTaskStartResult.Started>(result)
      val command = launches.single().kind as WorkRunLaunchKind.RuntimeCommand
      assertEquals(listOf("phase", "monitor", "branch:main", "--agent", "cursor"), command.arguments)
    }
  }

  @Test
  fun `model written intake tokens refuse before launch`() {
    withRecordingStart("work-new-task-start-refuse") { start, root, launches ->
      listOf("--recover-stale-owner", "two tokens", "target:main", "mode:inline").forEach { intake ->
        assertIs<NewTaskStartResult.Refused>(
          start.startStandalone(root, StandalonePhaseRequest("monitor", intake), "cursor"),
        )
      }
      assertIs<NewTaskStartResult.Refused>(
        start.startStandalone(root, StandalonePhaseRequest("review", "branch:main"), "cursor"),
      )
      assertEquals(emptyList(), launches)
    }
  }

  @Test
  fun `qa instructions launch as one positional and keep the qa ownership key`() {
    withRecordingStart("work-new-task-start-qa") { start, root, launches ->
      val instructions = "Use staging.\nCompare against https://example.com/design and skip KYC."
      assertIs<NewTaskStartResult.Started>(
        start.startStandalone(root, StandalonePhaseRequest("qa", instructions), "cursor"),
      )
      val launch = launches.single()
      assertEquals(
        listOf("phase", "qa", instructions, "--agent", "cursor"),
        (launch.kind as WorkRunLaunchKind.RuntimeCommand).arguments,
      )
      assertEquals("${testGoalRepositoryIdentity(root)}:qa:standalone", launch.ownershipKey)
    }
  }

  @Test
  fun `qa instructions the cli would read as a phase option refuse before launch`() {
    withRecordingStart("work-new-task-start-qa-refuse") { start, root, launches ->
      listOf("target:main check the login", "mode:inline", "-x staging").forEach { instructions ->
        assertIs<NewTaskStartResult.Refused>(
          start.startStandalone(root, StandalonePhaseRequest("qa", instructions), "cursor"),
        )
      }
      assertEquals(emptyList(), launches)
    }
  }

  @Test
  fun `different monitor intakes use different ownership keys`() {
    withRecordingStart("work-new-task-start-keys") { start, root, launches ->
      val identity = testGoalRepositoryIdentity(root)
      assertIs<NewTaskStartResult.Started>(
        start.startStandalone(root, StandalonePhaseRequest("monitor", "branch:one"), "cursor"),
      )
      assertIs<NewTaskStartResult.Started>(
        start.startStandalone(root, StandalonePhaseRequest("monitor", "branch:two"), "cursor"),
      )
      assertEquals(
        listOf("$identity:monitor:standalone:branch:one", "$identity:monitor:standalone:branch:two"),
        launches.map { it.ownershipKey },
      )
    }
  }

  private fun withRecordingStart(
    name: String,
    block: (WorkNewTaskStart, Path, MutableList<WorkRunLaunchRequest>) -> Unit,
  ) {
    establishTemporarySchemaReadiness(name).use { fixture ->
      val root = fixture.tempDir.toRealPath()
      val database = sqliteSessionFactoryForTests(root, fixture.dbPath.toString(), emptyMap(), ideStatusClock)
      val launches = mutableListOf<WorkRunLaunchRequest>()
      val launcher =
        object : WorkRunLauncher {
          override fun start(request: WorkRunLaunchRequest): WorkRunLaunchResult {
            launches += request
            return WorkRunLaunchResult.Started(root.resolve("monitor.log"))
          }

          override fun stop(request: WorkRunStopRequest) = WorkRunStopResult.NotRunning
        }
      block(
        WorkNewTaskStart(
          database,
          GoalNewWorkAdmissionPort { _, _ -> GoalNewWorkAdmission.NoIssueKey },
          NewTaskIssueKeyAllocator(testProjectStore, TestDecompositionManifestStore, NoopRuntimeDiagnostics),
          goalPreflightServiceForTests(database),
          launcher,
          EnvironmentContext(),
          TestRepositoryEnclosingRoot,
          NoopRuntimeDiagnostics,
          InPlaceGoalWorktreeResources,
          RecordingBranchWorktreeResources(),
          OperationCardLaunches(EmptyOperationCardRepository, NoOperationCardLifecycle) { emptyMap() },
        ),
        root,
        launches,
      )
    }
  }
}
