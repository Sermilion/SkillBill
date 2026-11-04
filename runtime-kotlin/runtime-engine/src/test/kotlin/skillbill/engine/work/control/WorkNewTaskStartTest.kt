package skillbill.engine.work.control

import skillbill.application.FakeDatabaseSessionFactory
import skillbill.application.InMemoryWorkflowStates
import skillbill.application.TestDecompositionManifestStore
import skillbill.application.TestRepositoryEnclosingRoot
import skillbill.application.testProjectStore
import skillbill.contracts.issuekey.TRACKER_STYLE_ISSUE_KEY_PATTERN
import skillbill.engine.featuretask.model.continuation.FeatureTaskContinuationCandidate
import skillbill.engine.goal.GoalNewWorkAdmission
import skillbill.engine.goal.GoalNewWorkAdmissionPort
import skillbill.engine.goal.GoalPreflightPort
import skillbill.engine.goal.GoalPreflightRequest
import skillbill.engine.goal.GoalPreflightResult
import skillbill.engine.goal.GoalPreflightVerdict
import skillbill.engine.work.control.model.NewTaskIntakeRequest
import skillbill.engine.work.control.model.NewTaskPreparation
import skillbill.engine.work.control.model.NewTaskRunKind
import skillbill.engine.work.control.model.NewTaskStartResult
import skillbill.model.EnvironmentContext
import skillbill.ports.diagnostics.NoopRuntimeDiagnostics
import skillbill.ports.work.WorkRunLauncher
import skillbill.ports.work.model.WorkRunExit
import skillbill.ports.work.model.WorkRunLaunchKind
import skillbill.ports.work.model.WorkRunLaunchRequest
import skillbill.ports.work.model.WorkRunLaunchResult
import skillbill.ports.work.model.WorkRunStopRequest
import skillbill.ports.work.model.WorkRunStopResult
import skillbill.workflow.model.FeatureTaskExecutionIdentity
import skillbill.workflow.model.FeatureTaskWorkflowMode
import skillbill.workflow.model.WorkflowStatus
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
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
      NewTaskIssueKeyAllocator(testProjectStore, TestDecompositionManifestStore, NoopRuntimeDiagnostics),
      preflight,
      launcher,
      EnvironmentContext(),
      TestRepositoryEnclosingRoot,
      NoopRuntimeDiagnostics,
    )

  @Test
  fun `missing identity asks for the single inventory prefix without minting`() {
    val root = Files.createTempDirectory("work-new-task-prefix")
    bundle(root.resolve("project-specs"), "NEWS-8-existing", "", "")
    val result =
      newSubject(root, preflight, GoalNewWorkAdmissionPort { _, _ -> GoalNewWorkAdmission.NoIssueKey })
        .prepareNewTask(root, intake(keyChoice = null))

    assertEquals(NewTaskPreparation.NeedsKey(listOf("NEWS")), result)
  }

  @Test
  fun `URL identity binds only when unused`() {
    val root = Files.createTempDirectory("work-new-task-url")
    val url = "https://linear.app/acme/issue/SKILL-501/title?view=all#top"
    val start = newSubject(root, preflight, keyAdmission())
    val ready = assertIs<NewTaskPreparation.Ready>(start.prepareNewTask(root, intake(keyChoice = url)))
    assertEquals("SKILL-501", ready.key)
    assertEquals(false, ready.allocated)

    bundle(root.resolve("project-specs"), "SKILL-501-existing", "", "")
    val reused = start.prepareNewTask(root, intake(keyChoice = url, reuseRequested = true))
    assertEquals(NewTaskPreparation.Refused("SKILL-501 is already used."), reused)
  }

  @Test
  fun `a sentence mention does not become identity`() {
    val root = Files.createTempDirectory("work-new-task-mention")
    bundle(root.resolve("project-specs"), "SKILL-476-existing", "", "")
    val result =
      newSubject(root, preflight, GoalNewWorkAdmissionPort { _, _ -> GoalNewWorkAdmission.NoIssueKey })
        .prepareNewTask(root, intake(requirements = "Please fix SKILL-476 while adding export", keyChoice = null))

    assertIs<NewTaskPreparation.NeedsKey>(result)
  }

  @Test
  fun `typed complete key binds without allocating and prefix alone asks`() {
    val root = Files.createTempDirectory("work-new-task-typed")
    val start = newSubject(root, preflight, keyAdmission())
    assertIs<NewTaskPreparation.NeedsKey>(start.prepareNewTask(root, intake(keyChoice = "NEWS")))
    val ready = assertIs<NewTaskPreparation.Ready>(start.prepareNewTask(root, intake(keyChoice = "skill-501")))
    assertEquals("SKILL-501", ready.key)
    assertEquals(false, ready.allocated)
    assertEquals(
      NewTaskPreparation.Refused("SKILL-501 is already in use. Choose another key."),
      start.prepareNewTask(root, intake(keyChoice = "SKILL-501", occupiedKeys = setOf("skill-501"))),
    )
  }

  @Test
  fun `typed spec reuse skips terminal refusal but never in-flight refusal`() {
    val root = Files.createTempDirectory("work-new-task-reuse")
    bundle(root.resolve("project-specs"), "SKILL-500-existing", "", "")
    val terminalWithoutReuse = newSubject(root, preflightWith(GoalPreflightVerdict.TERMINAL_ONLY), keyAdmission())
    val terminalReady =
      assertIs<NewTaskPreparation.Ready>(
        terminalWithoutReuse.prepareNewTask(root, intake(keyChoice = "SKILL-501")),
      )
    assertEquals(
      NewTaskStartResult.Refused("SKILL-501 already finished. Purge its run history to start it again."),
      terminalWithoutReuse.startNewTask(root, terminalReady, NewTaskRunKind.GOAL, "claude"),
    )
    val reusable = newSubject(root, preflightWith(GoalPreflightVerdict.TERMINAL_ONLY), keyAdmission())
    assertEquals(
      NewTaskPreparation.Refused("SKILL-500 already has a spec."),
      reusable.prepareNewTask(root, intake(keyChoice = "SKILL-500")),
    )
    val ready =
      assertIs<NewTaskPreparation.Ready>(
        reusable.prepareNewTask(root, intake(keyChoice = "SKILL-500", reuseRequested = true)),
      )
    assertEquals(NewTaskStartResult.Started, reusable.startNewTask(root, ready, NewTaskRunKind.GOAL, "claude"))

    val failed =
      newSubject(root, preflightWith(GoalPreflightVerdict.TERMINAL_ONLY, WorkflowStatus.FAILED), keyAdmission())
    assertEquals(
      NewTaskStartResult.Refused("SKILL-500 already finished. Purge its run history to start it again."),
      failed.startNewTask(root, ready, NewTaskRunKind.GOAL, "claude"),
    )

    listOf(
      GoalPreflightVerdict.ALREADY_RUNNING to "SKILL-500 is already running.",
      GoalPreflightVerdict.RESUMABLE to "SKILL-500 already has a run. Use Resume or Run again.",
      GoalPreflightVerdict.GOAL_CONTINUATION to "SKILL-500 already has a run. Use Resume or Run again.",
    ).forEach { (verdict, message) ->
      val running = newSubject(root, preflightWith(verdict), keyAdmission())
      val runningReady =
        assertIs<NewTaskPreparation.Ready>(
          running.prepareNewTask(root, intake(keyChoice = "SKILL-500", reuseRequested = true)),
        )
      assertEquals(
        NewTaskStartResult.Refused(message),
        running.startNewTask(root, runningReady, NewTaskRunKind.GOAL, "claude"),
      )
    }
  }

  private fun newSubject(
    root: Path,
    preflight: GoalPreflightPort,
    admission: GoalNewWorkAdmissionPort,
  ) = WorkNewTaskStart(
    database,
    admission,
    NewTaskIssueKeyAllocator(
      projectStore(root, root.resolve("project-specs")),
      TestDecompositionManifestStore,
      NoopRuntimeDiagnostics,
    ),
    preflight,
    launcher,
    EnvironmentContext(),
    TestRepositoryEnclosingRoot,
    NoopRuntimeDiagnostics,
  )

  private fun keyAdmission() =
    GoalNewWorkAdmissionPort { requirements, _ ->
      val first = requirements.trim().split(Regex("\\s+")).firstOrNull().orEmpty()
      Regex("(?i)$TRACKER_STYLE_ISSUE_KEY_PATTERN").matchEntire(first)?.let {
        GoalNewWorkAdmission.Admitted(first.uppercase())
      } ?: GoalNewWorkAdmission.NoIssueKey
    }

  private fun intake(
    requirements: String = "Add a small feature",
    keyChoice: String?,
    reuseRequested: Boolean = false,
    occupiedKeys: Set<String> = emptySet(),
  ) = NewTaskIntakeRequest(requirements, null, null, keyChoice, occupiedKeys, reuseRequested)

  private fun preflightWith(
    verdict: String,
    status: WorkflowStatus = WorkflowStatus.COMPLETED,
  ) = object : GoalPreflightPort {
    override fun preflight(request: GoalPreflightRequest): GoalPreflightResult {
      val identity =
        FeatureTaskExecutionIdentity("workflow", request.issueKey, "repo", "spec.md", FeatureTaskWorkflowMode.RUNTIME)
      val candidate =
        FeatureTaskContinuationCandidate(
          identity, "workflow", FeatureTaskWorkflowMode.RUNTIME, status.wireValue, "plan", "spec.md", null, null, "",
        )
      return GoalPreflightResult(verdict, request.issueKey, candidates = listOf(candidate))
    }
  }

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

  @Test
  fun `standalone target starting with a dash is refused without launching`() {
    launcher.calls = 0
    val phase = subject.startStandalone(repoRoot, "monitor", "claude", " --recover-stale-owner=x")
    assertIs<NewTaskStartResult.Refused>(phase)
    assertTrue(phase.reason.contains("--recover-stale-owner=x"))
    assertIs<NewTaskStartResult.Refused>(subject.startOperation(repoRoot, "verify", "-h", "claude"))
    assertIs<NewTaskStartResult.Refused>(subject.startOperation(repoRoot, "pr-review-fix", "--help", "claude"))
    assertEquals(0, launcher.calls)
  }

  @Test
  fun `a standalone run that exits blocked reports the blocked line and log`() {
    val log = Path.of("standalone-run.log")
    launcher.exit =
      log to
      CompletableFuture.completedFuture(
        WorkRunExit(
          1,
          listOf(
            "Picked up JAVA_TOOL_OPTIONS: -Dx",
            "Phase 'monitor' blocked at 'monitor': Monitor target refused (CALLER_REPOSITORY_UNAVAILABLE): no origin.",
            "Phase invocation ID: phr-1",
            "",
          ),
        ),
      )
    val running = assertIs<NewTaskStartResult.Running>(subject.startStandalone(repoRoot, "monitor", "claude", "url"))
    val reason = assertNotNull(running.failure.toCompletableFuture().get())
    assertTrue(reason.startsWith("Phase 'monitor' blocked at 'monitor': Monitor target refused"), reason)
    assertTrue(reason.endsWith("Full log: $log"), reason)
  }

  @Test
  fun `an operation that exits blocked or failed reports its reason instead of the invocation id`() {
    listOf(
      "Operation 'verify' blocked: rehydrate-needed: neither spec exists.",
      "Operation 'pr-review-fix' failed: the agent stopped.",
    ).forEach { stop ->
      val log = Path.of("standalone-operation.log")
      val lines = listOf("Picked up JAVA_TOOL_OPTIONS: -Dx", stop, "Operation invocation ID: opr-1", "")
      launcher.exit = log to CompletableFuture.completedFuture(WorkRunExit(1, lines))
      val running = assertIs<NewTaskStartResult.Running>(subject.startOperation(repoRoot, "verify", "QAX-1", "claude"))
      assertEquals("$stop Full log: $log", running.failure.toCompletableFuture().get())
    }
  }

  @Test
  fun `a standalone run that exits cleanly reports no failure`() {
    launcher.exit = Path.of("clean.log") to CompletableFuture.completedFuture(WorkRunExit(0, emptyList()))
    val running = assertIs<NewTaskStartResult.Running>(subject.startOperation(repoRoot, "verify", "SKILL-1", "claude"))
    assertNull(running.failure.toCompletableFuture().get())
  }

  private class RecordingLauncher : WorkRunLauncher {
    var calls = 0
    var exit: Pair<Path, CompletableFuture<WorkRunExit>>? = null
    private var last: List<String> = emptyList()

    override fun start(request: WorkRunLaunchRequest): WorkRunLaunchResult {
      calls++
      last = (request.kind as WorkRunLaunchKind.RuntimeCommand).arguments
      return exit?.let { (log, finished) -> WorkRunLaunchResult.Started(log, finished) }
        ?: WorkRunLaunchResult.Started(Path.of("work-new-task-start.log"))
    }

    override fun stop(request: WorkRunStopRequest) = WorkRunStopResult.NotRunning

    fun lastArgs(): List<String> = last
  }
}
