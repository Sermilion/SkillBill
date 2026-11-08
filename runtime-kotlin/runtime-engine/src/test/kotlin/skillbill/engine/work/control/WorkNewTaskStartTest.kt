package skillbill.engine.work.control

import skillbill.application.FakeDatabaseSessionFactory
import skillbill.application.InMemoryWorkflowStates
import skillbill.application.TestDecompositionManifestStore
import skillbill.application.TestRepositoryEnclosingRoot
import skillbill.application.testProjectStore
import skillbill.contracts.issuekey.TRACKER_STYLE_ISSUE_KEY_PATTERN
import skillbill.engine.featuretask.model.continuation.FeatureTaskContinuationCandidate
import skillbill.engine.featuretask.model.execution.QaNamedDevice
import skillbill.engine.featuretask.model.execution.QaNamedDeviceKind
import skillbill.engine.goal.GoalNewWorkAdmission
import skillbill.engine.goal.GoalNewWorkAdmissionPort
import skillbill.engine.goal.GoalPreflightPort
import skillbill.engine.goal.GoalPreflightRequest
import skillbill.engine.goal.GoalPreflightResult
import skillbill.engine.goal.GoalPreflightVerdict
import skillbill.engine.operation.core.NoOperationCardLifecycle
import skillbill.engine.work.control.model.NewTaskIdentityPolicy
import skillbill.engine.work.control.model.NewTaskIntakeRequest
import skillbill.engine.work.control.model.NewTaskPreparation
import skillbill.engine.work.control.model.NewTaskRunKind
import skillbill.engine.work.control.model.NewTaskStartResult
import skillbill.engine.work.control.model.OperationStartRequest
import skillbill.engine.work.control.model.StandaloneCheckout
import skillbill.engine.work.control.model.StandalonePhaseRequest
import skillbill.model.EnvironmentContext
import skillbill.ports.diagnostics.NoopRuntimeDiagnostics
import skillbill.ports.operation.EmptyOperationCardRepository
import skillbill.ports.work.WorkRunLauncher
import skillbill.ports.work.model.WorkRunExit
import skillbill.ports.work.model.WorkRunLaunchKind
import skillbill.ports.work.model.WorkRunLaunchRequest
import skillbill.ports.work.model.WorkRunLaunchResult
import skillbill.ports.work.model.WorkRunStopRequest
import skillbill.ports.work.model.WorkRunStopResult
import skillbill.ports.workflow.branchworktree.RecordingBranchWorktreeResources
import skillbill.ports.workflow.branchworktree.model.BranchWorktreeCreation
import skillbill.ports.workflow.branchworktree.model.BranchWorktreeHolder
import skillbill.ports.workflow.branchworktree.model.BranchWorktreeHolderKind
import skillbill.ports.workflow.branchworktree.model.BranchWorktreeObservation
import skillbill.ports.workflow.decomposition.UnavailableDecompositionManifestStore
import skillbill.ports.workflow.goalworktree.GoalWorktreeResources
import skillbill.ports.workflow.goalworktree.InPlaceGoalWorktreeResources
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

private fun WorkNewTaskStart.startOperation(
  repoRoot: Path,
  operationId: String,
  target: String?,
  agentId: String,
  checkout: StandaloneCheckout = StandaloneCheckout(),
): NewTaskStartResult =
  startOperation(
    repoRoot,
    OperationStartRequest(operationId, target, agentId, checkout = checkout),
  )

class WorkNewTaskStartTest {
  private val repoRoot: Path = Files.createTempDirectory("work-new-task-start")
  private val launcher = RecordingLauncher()
  private val database = FakeDatabaseSessionFactory(InMemoryWorkflowStates())
  private val preflight =
    object : GoalPreflightPort {
      override fun preflight(request: GoalPreflightRequest) =
        GoalPreflightResult(GoalPreflightVerdict.NEW_WORK, "LOCAL-0")
    }
  private val siblingRoot: Path = Files.createTempDirectory("work-new-task-start-sibling")
  private var branchHolder: Path? = null
  private val goalWorktrees =
    object : GoalWorktreeResources by InPlaceGoalWorktreeResources {
      override fun checkoutHolding(
        repoRoot: Path,
        branch: String,
      ): Path? = branchHolder
    }
  private val branchWorktrees =
    RecordingBranchWorktreeResources(
      observation = BranchWorktreeObservation.CallerOwned(repoRoot, repoRoot, "main", "abc123"),
    )
  private val subject =
    WorkNewTaskStart(
      database,
      GoalNewWorkAdmissionPort { _, _ -> GoalNewWorkAdmission.NoIssueKey },
      NewTaskIssueKeyAllocator(testProjectStore, UnavailableDecompositionManifestStore, NoopRuntimeDiagnostics),
      preflight,
      launcher,
      EnvironmentContext(),
      TestRepositoryEnclosingRoot,
      NoopRuntimeDiagnostics,
      goalWorktrees,
      branchWorktrees,
      OperationCardLaunches(EmptyOperationCardRepository, NoOperationCardLifecycle) { emptyMap() },
    )

  @Test
  fun `missing identity suggests the next local key without minting`() {
    val root = Files.createTempDirectory("work-new-task-prefix")
    bundle(root.resolve("project-specs"), "NEWS-8-existing", "", "")
    val result =
      newSubject(root, preflight, GoalNewWorkAdmissionPort { _, _ -> GoalNewWorkAdmission.NoIssueKey })
        .prepareNewTask(
          root,
          intake(keyChoice = null).copy(identityPolicy = NewTaskIdentityPolicy.RESOLVED_DESKTOP_IDENTITY),
        )

    assertEquals(NewTaskPreparation.NeedsKey(listOf("NEWS-9")), result)
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
    val withoutReuse =
      assertIs<NewTaskPreparation.Ready>(
        reusable.prepareNewTask(root, intake(keyChoice = "SKILL-500")),
      )
    assertEquals(
      NewTaskStartResult.Refused("SKILL-500 already finished. Purge its run history to start it again."),
      reusable.startNewTask(root, withoutReuse, NewTaskRunKind.GOAL, "claude"),
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

  @Test
  fun `a cancelled key with a retained spec starts new work without requesting reuse`() {
    val root = Files.createTempDirectory("work-new-task-cancelled")
    bundle(root.resolve("project-specs"), "SKILL-500-cancelled", "# SKILL-500 Old task", "")
    for (verdict in listOf(GoalPreflightVerdict.NEW_WORK, GoalPreflightVerdict.TERMINAL_ONLY)) {
      val start = newSubject(root, preflightWith(verdict, WorkflowStatus.ABANDONED), keyAdmission())
      val ready = assertIs<NewTaskPreparation.Ready>(start.prepareNewTask(root, intake(keyChoice = "SKILL-500")))
      assertEquals(NewTaskStartResult.Started, start.startNewTask(root, ready, NewTaskRunKind.GOAL, "claude"))
      assertTrue("Add a small feature" in ready.intake)
    }
    assertEquals(
      "# SKILL-500 Old task",
      Files.readString(root.resolve("project-specs/SKILL-500-cancelled/spec.md")),
    )
  }

  @Test
  fun `cancelled history cannot release a key claimed by a newer running task`() {
    val root = Files.createTempDirectory("work-new-task-cancelled-occupied")
    bundle(root.resolve("project-specs"), "SKILL-500-cancelled", "", "")
    val start =
      newSubject(root, preflightWith(GoalPreflightVerdict.ALREADY_RUNNING, WorkflowStatus.ABANDONED), keyAdmission())
    val ready = assertIs<NewTaskPreparation.Ready>(start.prepareNewTask(root, intake(keyChoice = "SKILL-500")))
    assertIs<NewTaskStartResult.Refused>(start.startNewTask(root, ready, NewTaskRunKind.GOAL, "claude"))
    assertEquals(0, launcher.calls)
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
    goalWorktrees,
    branchWorktrees,
    OperationCardLaunches(EmptyOperationCardRepository, NoOperationCardLifecycle) { emptyMap() },
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
  fun `a branch another checkout holds runs there without being claimed as created`() {
    branchHolder = siblingRoot

    val result =
      subject.startStandalone(
        repoRoot,
        StandalonePhaseRequest("review"),
        "claude",
        StandaloneCheckout(branch = "feat/x"),
      )

    assertEquals(NewTaskStartResult.Started, result)
    assertEquals(siblingRoot, launcher.lastDirectory())
    assertEquals(emptyList(), branchWorktrees.created)
    assertEquals(
      listOf(siblingRoot to BranchWorktreeHolder(BranchWorktreeHolderKind.PHASE, "review")),
      branchWorktrees.held,
    )
    assertTrue(launcher.lastOwnership().startsWith(TestRepositoryEnclosingRoot.primaryRepositoryIdentity(repoRoot)))
    assertTrue(launcher.lastOwnership().endsWith(":branch:feat/x"), launcher.lastOwnership())
  }

  @Test
  fun `a branch no checkout holds runs in the worktree created for it`() {
    branchWorktrees.creation = BranchWorktreeCreation.Created(siblingRoot)
    branchWorktrees.observation =
      BranchWorktreeObservation.RuntimeManaged(siblingRoot, repoRoot, "feat/x", "abc123", holderPresent = true)

    val result = subject.startOperation(repoRoot, "verify", "SKILL-1", "claude", StandaloneCheckout(branch = "feat/x"))

    assertEquals(NewTaskStartResult.Started, result)
    assertEquals(listOf(repoRoot to "feat/x"), branchWorktrees.created)
    assertEquals(siblingRoot, launcher.lastDirectory())
    assertEquals(emptyList(), branchWorktrees.released)
  }

  @Test
  fun `a branch that exists nowhere refuses the start without launching`() {
    branchWorktrees.creation = BranchWorktreeCreation.Missing

    val result =
      subject.startStandalone(
        repoRoot,
        StandalonePhaseRequest("qa"),
        "claude",
        StandaloneCheckout(branch = "feat/gone"),
      )

    val refused = assertIs<NewTaskStartResult.Refused>(result)
    assertTrue("feat/gone" in refused.reason, refused.reason)
    assertEquals(0, launcher.calls)
  }

  @Test
  fun `a refused launch releases the worktree it claimed`() {
    branchWorktrees.creation = BranchWorktreeCreation.Created(siblingRoot)
    launcher.refusal = "A run is already starting or running for this work."

    val result =
      subject.startStandalone(
        repoRoot,
        StandalonePhaseRequest("pr"),
        "claude",
        StandaloneCheckout(branch = "feat/x"),
      )

    assertIs<NewTaskStartResult.Refused>(result)
    assertEquals(
      listOf(siblingRoot to BranchWorktreeHolder(BranchWorktreeHolderKind.PHASE, "pr")),
      branchWorktrees.released,
    )
  }

  @Test
  fun `monitor ignores the branch and a start without one runs in the given checkout`() {
    branchHolder = siblingRoot
    val checkout = Files.createTempDirectory("work-new-task-start-checkout")

    subject.startStandalone(
      repoRoot,
      StandalonePhaseRequest("monitor", "branch:feat/x"),
      "claude",
      StandaloneCheckout(checkout, "feat/x"),
    )
    assertEquals(checkout, launcher.lastDirectory())
    subject.startStandalone(repoRoot, StandalonePhaseRequest("review"), "claude", StandaloneCheckout(checkout))
    assertEquals(checkout, launcher.lastDirectory())

    assertEquals(emptyList(), branchWorktrees.created)
    assertEquals(emptyList(), branchWorktrees.held)
    assertTrue(launcher.lastOwnership().startsWith(TestRepositoryEnclosingRoot.primaryRepositoryIdentity(repoRoot)))
  }

  @Test
  fun `startStandalone monitor passes target as positional`() {
    val result =
      subject.startStandalone(
        repoRoot,
        StandalonePhaseRequest("monitor", "https://github.com/org/repo/pull/1"),
        "claude",
      )
    assertEquals(NewTaskStartResult.Started, result)
    assertEquals(
      listOf("phase", "monitor", "https://github.com/org/repo/pull/1", "--agent", "claude"),
      launcher.lastArgs(),
    )
  }

  @Test
  fun `startStandalone review keeps argv without target positional`() {
    val result = subject.startStandalone(repoRoot, StandalonePhaseRequest("review"), "claude")
    assertEquals(NewTaskStartResult.Started, result)
    assertEquals(listOf("phase", "review", "--agent", "claude"), launcher.lastArgs())
  }

  @Test
  fun `named device is appended to QA and refused for another standalone phase`() {
    val device = QaNamedDevice(QaNamedDeviceKind.SERIAL, "R58M123")
    val result = subject.startStandalone(repoRoot, StandalonePhaseRequest("qa"), "claude", qaDevice = device)

    assertEquals(NewTaskStartResult.Started, result)
    assertEquals(listOf("phase", "qa", "--agent", "claude", "--qa-device", "serial:R58M123"), launcher.lastArgs())
    assertIs<NewTaskStartResult.Refused>(
      subject.startStandalone(repoRoot, StandalonePhaseRequest("review"), "claude", qaDevice = device),
    )
  }

  @Test
  fun `new task adds a named device to goal argv but not plan argv`() {
    val start = newSubject(repoRoot, preflight, keyAdmission())
    val prepared =
      assertIs<NewTaskPreparation.Ready>(start.prepareNewTask(repoRoot, intake(keyChoice = "SKILL-517")))
    val device = QaNamedDevice(QaNamedDeviceKind.AVD, "Pixel_8_API_35")

    assertEquals(
      NewTaskStartResult.Started,
      start.startNewTask(repoRoot, prepared, NewTaskRunKind.GOAL, "claude", device),
    )
    assertTrue(launcher.lastArgs().takeLast(2) == listOf("--qa-device", "avd:Pixel_8_API_35"))
    assertEquals(
      NewTaskStartResult.Started,
      start.startNewTask(repoRoot, prepared, NewTaskRunKind.PLAN, "claude", device),
    )
    assertEquals(listOf("phase", "plan", prepared.intake, "--agent", "claude"), launcher.lastArgs())
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
  fun `proposal adjustments preserve the spec argument and pass changes as separate operator instructions`() {
    val changes = "Exclude deployment and describe confirm: tokens as documentation only"
    val result =
      subject.startOperation(
        repoRoot,
        OperationStartRequest("verify", "spec:/repo/spec.md", "claude", proposalChanges = changes),
      )

    assertEquals(NewTaskStartResult.Started, result)
    assertEquals(
      listOf(
        "operation",
        "verify",
        "spec:/repo/spec.md",
        "Operator requested proposal changes (later instructions take precedence):\n$changes",
        "--agent",
        "claude",
      ),
      launcher.lastArgs(),
    )
  }

  @Test
  fun `startOperation pr-review-fix passes positional only when target is nonblank`() {
    val withPr =
      subject.startOperation(repoRoot, "pr-review-fix", "mode:remote https://github.com/org/repo/pull/3", "claude")
    assertEquals(NewTaskStartResult.Started, withPr)
    assertEquals(
      listOf("operation", "pr-review-fix", "mode:remote", "https://github.com/org/repo/pull/3", "--agent", "claude"),
      launcher.lastArgs(),
    )

    val missing = subject.startOperation(repoRoot, "pr-review-fix", null, "claude")
    assertEquals(NewTaskStartResult.Started, missing)
    assertEquals(listOf("operation", "pr-review-fix", "--agent", "claude"), launcher.lastArgs())
  }

  @Test
  fun `startOperation pr-review-fix splits remote mode from its PR target`() {
    val result = subject.startOperation(repoRoot, "pr-review-fix", "mode:remote #42", "claude")
    assertEquals(NewTaskStartResult.Started, result)
    assertEquals(
      listOf("operation", "pr-review-fix", "mode:remote", "#42", "--agent", "claude"),
      launcher.lastArgs(),
    )
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
    val phase =
      subject.startStandalone(
        repoRoot,
        StandalonePhaseRequest("monitor", " --recover-stale-owner=x"),
        "claude",
      )
    assertIs<NewTaskStartResult.Refused>(phase)
    assertTrue(phase.reason.contains("--recover-stale-owner=x"))
    assertIs<NewTaskStartResult.Refused>(subject.startOperation(repoRoot, "verify", "-h", "claude"))
    assertIs<NewTaskStartResult.Refused>(subject.startOperation(repoRoot, "pr-review-fix", "--help", "claude"))
    assertEquals(0, launcher.calls)
  }

  @Test
  fun `goal and plan startup failures retain the reason and log instead of claiming an untracked start`() {
    val log = Path.of("new-task.log")
    val reason = "Database read failed: schema is incompatible with this runtime."
    val start = newSubject(repoRoot, preflight, keyAdmission())
    for (kind in NewTaskRunKind.entries) {
      val exit = CompletableFuture<WorkRunExit>()
      launcher.exit = log to exit
      val prepared = assertIs<NewTaskPreparation.Ready>(start.prepareNewTask(repoRoot, intake(keyChoice = "SKILL-514")))

      val running = assertIs<NewTaskStartResult.Running>(start.startNewTask(repoRoot, prepared, kind, "claude"))
      exit.complete(WorkRunExit(1, listOf(reason)))

      assertEquals("$reason Full log: $log", running.failure.toCompletableFuture().get())
    }
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
    val running =
      assertIs<NewTaskStartResult.Running>(
        subject.startStandalone(repoRoot, StandalonePhaseRequest("monitor", "url"), "claude"),
      )
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
    var refusal: String? = null
    private var last: WorkRunLaunchRequest? = null

    override fun start(request: WorkRunLaunchRequest): WorkRunLaunchResult {
      calls++
      last = request
      refusal?.let { return WorkRunLaunchResult.Refused(it) }
      return exit?.let { (log, finished) -> WorkRunLaunchResult.Started(log, finished) }
        ?: WorkRunLaunchResult.Started(Path.of("work-new-task-start.log"))
    }

    override fun stop(request: WorkRunStopRequest) = WorkRunStopResult.NotRunning

    fun lastArgs(): List<String> = (last?.kind as WorkRunLaunchKind.RuntimeCommand).arguments

    fun lastDirectory(): Path? = last?.repositoryRoot

    fun lastOwnership(): String = last?.ownershipKey.orEmpty()
  }
}
