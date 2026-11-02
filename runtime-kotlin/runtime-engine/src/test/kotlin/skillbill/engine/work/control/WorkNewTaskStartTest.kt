package skillbill.engine.work.control

import skillbill.application.FakeDatabaseSessionFactory
import skillbill.application.InMemoryWorkflowStates
import skillbill.application.TestRepositoryEnclosingRoot
import skillbill.application.testProjectStore
import skillbill.engine.goal.GoalNewWorkAdmission
import skillbill.engine.goal.GoalNewWorkAdmissionPort
import skillbill.engine.goal.GoalPreflightPort
import skillbill.engine.goal.GoalPreflightRequest
import skillbill.engine.goal.GoalPreflightResult
import skillbill.engine.goal.GoalPreflightVerdict
import skillbill.engine.work.control.model.NewTaskStartResult
import skillbill.model.EnvironmentContext
import skillbill.ports.diagnostics.NoopRuntimeDiagnostics
import skillbill.ports.work.WorkRunLauncher
import skillbill.ports.work.model.WorkRunLaunchKind
import skillbill.ports.work.model.WorkRunLaunchRequest
import skillbill.ports.work.model.WorkRunLaunchResult
import skillbill.ports.work.model.WorkRunStopRequest
import skillbill.ports.work.model.WorkRunStopResult
import skillbill.ports.workflow.decomposition.UnavailableDecompositionManifestStore
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class WorkNewTaskStartTest {
  private val repoRoot: Path = Files.createTempDirectory("work-new-task-start")
  private val launcher = RecordingLauncher()
  private val database = FakeDatabaseSessionFactory(InMemoryWorkflowStates())
  private val preflight =
    object : GoalPreflightPort {
      override fun preflight(request: GoalPreflightRequest) =
        GoalPreflightResult(GoalPreflightVerdict.NEW_WORK, "LOCAL-0")
    }
  private val subject =
    WorkNewTaskStart(
      database,
      GoalNewWorkAdmissionPort { _, _ -> GoalNewWorkAdmission.NoIssueKey },
      NewTaskIssueKeyAllocator(testProjectStore, UnavailableDecompositionManifestStore, NoopRuntimeDiagnostics),
      preflight,
      launcher,
      EnvironmentContext(),
      TestRepositoryEnclosingRoot,
    )

  @Test
  fun `startStandalone monitor passes target as positional`() {
    val result = subject.startStandalone(repoRoot, "monitor", "claude", "https://github.com/org/repo/pull/1")
    assertEquals(NewTaskStartResult.Started, result)
    assertEquals(
      listOf("phase", "monitor", "https://github.com/org/repo/pull/1", "--agent", "claude"),
      launcher.lastArgs(),
    )
  }

  @Test
  fun `startStandalone review keeps argv without target positional`() {
    val result = subject.startStandalone(repoRoot, "review", "claude", null)
    assertEquals(NewTaskStartResult.Started, result)
    assertEquals(listOf("phase", "review", "--agent", "claude"), launcher.lastArgs())
  }

  @Test
  fun `startOperation verify passes intake without flags`() {
    val result = subject.startOperation(repoRoot, "verify", "check the branch", "claude")
    assertEquals(NewTaskStartResult.Started, result)
    val args = launcher.lastArgs()
    assertEquals(listOf("operation", "verify", "check the branch", "--agent", "claude"), args)
    assertTrue(args.none { it.startsWith("target:") || it.startsWith("mode:") || it.startsWith("qa:") })
  }

  @Test
  fun `startOperation pr-review-fix passes positional only when target is nonblank`() {
    val withPr = subject.startOperation(repoRoot, "pr-review-fix", "https://github.com/org/repo/pull/3", "claude")
    assertEquals(NewTaskStartResult.Started, withPr)
    assertEquals(
      listOf("operation", "pr-review-fix", "https://github.com/org/repo/pull/3", "--agent", "claude"),
      launcher.lastArgs(),
    )

    val missing = subject.startOperation(repoRoot, "pr-review-fix", null, "claude")
    assertEquals(NewTaskStartResult.Started, missing)
    assertEquals(listOf("operation", "pr-review-fix", "--agent", "claude"), launcher.lastArgs())
  }

  @Test
  fun `startOperation verify refuses blank target without launching`() {
    launcher.calls = 0
    val missing = subject.startOperation(repoRoot, "verify", null, "claude")
    assertIs<NewTaskStartResult.Refused>(missing)
    assertTrue(missing.reason.contains("intake", ignoreCase = true))
    assertEquals(0, launcher.calls)

    val blank = subject.startOperation(repoRoot, "verify", "  ", "claude")
    assertIs<NewTaskStartResult.Refused>(blank)
    assertEquals(0, launcher.calls)
  }

  private class RecordingLauncher : WorkRunLauncher {
    var calls = 0
    private var last: List<String> = emptyList()

    override fun start(request: WorkRunLaunchRequest): WorkRunLaunchResult {
      calls++
      last = (request.kind as WorkRunLaunchKind.RuntimeCommand).arguments
      return WorkRunLaunchResult.Started(Path.of("work-new-task-start.log"))
    }

    override fun stop(request: WorkRunStopRequest) = WorkRunStopResult.NotRunning

    fun lastArgs(): List<String> = last
  }
}
