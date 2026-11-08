package skillbill.engine.work.control

import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.junit.jupiter.params.provider.ValueSource
import skillbill.application.TestDecompositionManifestStore
import skillbill.application.TestRepositoryEnclosingRoot
import skillbill.application.testProjectStore
import skillbill.engine.featuretask.model.execution.QaNamedDevice
import skillbill.engine.featuretask.model.execution.QaNamedDeviceKind
import skillbill.engine.goal.GoalNewWorkAdmission
import skillbill.engine.goal.GoalNewWorkAdmissionPort
import skillbill.engine.goal.GoalPreflightGateBlock
import skillbill.engine.goal.GoalPreflightResult
import skillbill.engine.goal.GoalPreflightSubtask
import skillbill.engine.goal.GoalPreflightVerdict
import skillbill.engine.goal.GoalRunnerManifestState
import skillbill.engine.goalrunner.execution.core.testGoalRunnerStatusService
import skillbill.engine.goalrunner.execution.core.testWorkflowGoalRunnerManifestStore
import skillbill.engine.goalrunner.goalPreflightServiceForTests
import skillbill.engine.goalrunner.preflight.GoalPreflightService
import skillbill.engine.goalrunner.status.GoalRunnerStatusService
import skillbill.engine.operation.core.NoOperationCardLifecycle
import skillbill.engine.work.EmptyOutcomeStore
import skillbill.engine.work.IdeStatusService
import skillbill.engine.work.control.model.NewTaskPreparation
import skillbill.engine.work.control.model.NewTaskRunKind
import skillbill.engine.work.control.model.NewTaskStartResult
import skillbill.engine.work.control.model.WorkControlRejection
import skillbill.engine.work.control.model.WorkControlResult
import skillbill.engine.work.control.model.WorkRunTarget
import skillbill.engine.work.fixtureCheckedOutBranch
import skillbill.engine.work.goalManifestState
import skillbill.engine.work.goalStartRefusalForTest
import skillbill.engine.work.ideStatusClock
import skillbill.engine.work.ideStatusObservedAt
import skillbill.engine.work.ideStatusService
import skillbill.engine.work.launchForTest
import skillbill.engine.work.optionsForTest
import skillbill.engine.work.runtimeRecord
import skillbill.engine.work.startGoalForTest
import skillbill.engine.work.testGoalRepositoryIdentity
import skillbill.idestatus.model.IdeStatusLifecycleState
import skillbill.infrastructure.sqlite.establishTemporarySchemaReadiness
import skillbill.infrastructure.sqlite.sqliteSessionFactoryForTests
import skillbill.model.EnvironmentContext
import skillbill.ports.db.DatabaseSessionFactory
import skillbill.ports.diagnostics.NoopRuntimeDiagnostics
import skillbill.ports.idestatus.model.WorkDetailRequest
import skillbill.ports.operation.EmptyOperationCardRepository
import skillbill.ports.system.CheckedOutBranchSource
import skillbill.ports.taskruntime.NoopFeatureTaskRuntimeWorkerSupervisor
import skillbill.ports.work.WorkRunLauncher
import skillbill.ports.work.model.WorkRunLaunchKind
import skillbill.ports.work.model.WorkRunLaunchRequest
import skillbill.ports.work.model.WorkRunLaunchResult
import skillbill.ports.work.model.WorkRunStopRequest
import skillbill.ports.work.model.WorkRunStopResult
import skillbill.ports.workflow.branchworktree.RecordingBranchWorktreeResources
import skillbill.ports.workflow.goalworktree.InPlaceGoalWorktreeResources
import skillbill.ports.workflow.model.WorkflowFamily
import skillbill.ports.workflow.toRecord
import skillbill.workflow.decomposition.model.CurrentSubtaskIntent
import skillbill.workflow.decomposition.model.DecompositionDependency
import skillbill.workflow.decomposition.model.DecompositionManifest
import skillbill.workflow.decomposition.model.DecompositionSubtask
import skillbill.workflow.decomposition.model.SpecSource
import skillbill.workflow.model.FeatureTaskWorkflowMode
import skillbill.workflow.model.WorkflowStatus
import java.nio.file.Path
import java.sql.Connection
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WorkRunActionsTest {
  @ParameterizedTest
  @EnumSource(WorkflowStatus::class, names = ["ABANDONED", "COMPLETED", "FAILED"])
  fun `canceling an unfinished goal preserves terminal workflow snapshots`(terminalStatus: WorkflowStatus) {
    establishTemporarySchemaReadiness("cancel-terminal-goal").use { fixture ->
      val root = fixture.tempDir.toRealPath()
      val database = sqliteSessionFactoryForTests(root, fixture.dbPath.toString(), emptyMap(), ideStatusClock)
      val manifests = testWorkflowGoalRunnerManifestStore(database, TestDecompositionManifestStore, ideStatusClock)
      val state = goalManifestState(root, testGoalRepositoryIdentity(root), "w-child")
      seedUnfinishedGoal(database, fixture.connection)
      manifests.save(state)
      manifests.persistControlState("goal-1", state.controlState)
      val terminal =
        database.transaction { unit ->
          listOf("goal-1", "w-child").map { id ->
            val snapshot = assertNotNull(unit.workflowStates.get(WorkflowFamily.TASK_RUNTIME, id))
            snapshot.copy(
              workflowStatus = terminalStatus,
              finishedAt = ideStatusObservedAt.minusSeconds(30),
            ).let {
              unit.workflowStates.save(WorkflowFamily.TASK_RUNTIME, it)
              assertNotNull(unit.workflowStates.get(WorkflowFamily.TASK_RUNTIME, id)).toRecord()
            }
          }
        }
      val status = ideStatusService(database, manifests)
      val goals = testGoalRunnerStatusService(manifests, EmptyOutcomeStore, database = database, clock = ideStatusClock)
      val launcher =
        object : WorkRunLauncher {
          override fun start(request: WorkRunLaunchRequest) = WorkRunLaunchResult.Refused("Unused")

          override fun stop(request: WorkRunStopRequest) = WorkRunStopResult.NotRunning
        }
      val cancellation =
        WorkCancellation(database, status, goals, NoopFeatureTaskRuntimeWorkerSupervisor, ideStatusClock, launcher)
      val item = assertNotNull(status.detail(WorkDetailRequest("goal-1")).item)
      val target =
        WorkRunTarget(
          "goal-1",
          item.snapshot.repositoryIdentity,
          Path.of(item.repositoryRoot),
          item.snapshot.execution?.executionId,
          item.snapshot.execution?.statusRevision,
          item.snapshot.updatedAt,
          assertNotNull(item.databaseFileObservation),
        )

      assertEquals(WorkControlResult.Accepted, cancellation.cancel(target))

      database.read { unit ->
        terminal.forEach { prior ->
          assertEquals(
            prior,
            unit.workflowStates.get(WorkflowFamily.TASK_RUNTIME, prior.workflowId)?.toRecord(),
          )
        }
      }
      val canceled = assertNotNull(status.detail(WorkDetailRequest("goal-1")).item)
      assertEquals(IdeStatusLifecycleState.CANCELED, canceled.snapshot.lifecycleState)
      assertNull(canceled.liveSection)
      assertFalse(canceled.requiresAttention)
      val actions = workRunActions(database, status, goals, launcher)
      val options = database.read { unit -> actions.optionsForTest(canceled, unit) }
      assertFalse(options.canCancel)
      assertTrue(options.canLaunch)
    }
  }

  @Test
  fun `run again reopens canceled parent and child snapshots without losing saved work`() {
    establishTemporarySchemaReadiness("restart-canceled-goal").use { fixture ->
      val root = fixture.tempDir.toRealPath()
      val database = sqliteSessionFactoryForTests(root, fixture.dbPath.toString(), emptyMap(), ideStatusClock)
      val manifests = testWorkflowGoalRunnerManifestStore(database, TestDecompositionManifestStore, ideStatusClock)
      val state = goalManifestState(root, testGoalRepositoryIdentity(root), "w-child")
      seedUnfinishedGoal(database, fixture.connection)
      manifests.save(state)
      manifests.persistControlState("goal-1", state.controlState)
      val status = ideStatusService(database, manifests)
      val goals = testGoalRunnerStatusService(manifests, EmptyOutcomeStore, database = database, clock = ideStatusClock)
      val launches = mutableListOf<WorkRunLaunchRequest>()
      val launcher =
        object : WorkRunLauncher {
          override fun start(request: WorkRunLaunchRequest): WorkRunLaunchResult {
            launches += request
            return WorkRunLaunchResult.Started(root.resolve("goal.log"))
          }

          override fun stop(request: WorkRunStopRequest) = WorkRunStopResult.NotRunning
        }

      fun target(): WorkRunTarget {
        val item = assertNotNull(status.detail(WorkDetailRequest("goal-1")).item)
        return WorkRunTarget(
          "goal-1",
          item.snapshot.repositoryIdentity,
          Path.of(item.repositoryRoot),
          item.snapshot.execution?.executionId,
          item.snapshot.execution?.statusRevision,
          item.snapshot.updatedAt,
          assertNotNull(item.databaseFileObservation),
        )
      }
      val cancel =
        WorkCancellation(
          database,
          status,
          goals,
          NoopFeatureTaskRuntimeWorkerSupervisor,
          ideStatusClock,
          launcher,
        )
      assertEquals(WorkControlResult.Accepted, cancel.cancel(target()))
      val canceled =
        database.read { unit ->
          listOf("goal-1", "w-child").map { assertNotNull(unit.workflowStates.get(WorkflowFamily.TASK_RUNTIME, it)) }
        }
      canceled.forEach { assertEquals(WorkflowStatus.ABANDONED, it.workflowStatus) }
      assertEquals(
        IdeStatusLifecycleState.CANCELED,
        status.detail(WorkDetailRequest("goal-1")).item?.snapshot?.lifecycleState,
      )
      val actions = workRunActions(database, status, goals, launcher)

      assertEquals(
        WorkControlResult.Accepted,
        actions.launchForTest(target(), "codex", QaNamedDevice(QaNamedDeviceKind.SERIAL, "R58M123")),
      )
      assertTrue(
        (launches.single().kind as WorkRunLaunchKind.RuntimeCommand).arguments.containsAll(
          listOf("--qa-device", "serial:R58M123"),
        ),
      )

      database.read { unit ->
        canceled.forEach { prior ->
          val resumed = assertNotNull(unit.workflowStates.get(WorkflowFamily.TASK_RUNTIME, prior.workflowId))
          assertEquals(WorkflowStatus.PAUSED, resumed.workflowStatus)
          assertNull(resumed.finishedAt)
          assertEquals(prior.currentStepId, resumed.currentStepId)
          assertEquals(prior.steps, resumed.steps)
          assertEquals(prior.toRecord().artifactsJson, resumed.toRecord().artifactsJson)
        }
        val control = unit.goalRunnerControls.controlState("goal-1")
        assertFalse(control.paused)
        assertFalse(control.pauseRequested)
      }
      assertEquals(1, launches.size)
      assertFalse(
        status.detail(WorkDetailRequest("goal-1")).item?.snapshot?.lifecycleState == IdeStatusLifecycleState.CANCELED,
      )
    }
  }

  @Test
  fun `start goal launches a fresh issue once with the shared goal arguments and rejects a missing agent first`() {
    establishTemporarySchemaReadiness("start-goal").use { fixture ->
      val root = fixture.tempDir.toRealPath()
      val database = sqliteSessionFactoryForTests(root, fixture.dbPath.toString(), emptyMap(), ideStatusClock)
      val launches = mutableListOf<WorkRunLaunchRequest>()
      val launcher =
        object : WorkRunLauncher {
          override fun start(request: WorkRunLaunchRequest): WorkRunLaunchResult {
            launches += request
            return if (launches.count { it.ownershipKey == request.ownershipKey } == 1) {
              WorkRunLaunchResult.Started(root.resolve("goal.log"))
            } else {
              WorkRunLaunchResult.Refused("A run is already starting or running for this work.")
            }
          }

          override fun stop(request: WorkRunStopRequest) = WorkRunStopResult.NotRunning
        }
      val manifests = testWorkflowGoalRunnerManifestStore(database, TestDecompositionManifestStore, ideStatusClock)
      val actions =
        workRunActions(
          database,
          ideStatusService(database, manifests),
          testGoalRunnerStatusService(manifests, EmptyOutcomeStore, database = database, clock = ideStatusClock),
          launcher,
        )

      assertEquals(
        WorkControlResult.Rejected(WorkControlRejection.AGENT_REQUIRED),
        actions.startGoalForTest(root, "SKILL-901", null),
      )
      assertEquals(
        WorkControlResult.Rejected(WorkControlRejection.AGENT_REQUIRED),
        actions.startGoalForTest(root, "SKILL-901", "not-an-agent"),
      )
      assertTrue(launches.isEmpty())

      assertEquals(WorkControlResult.Accepted, actions.startGoalForTest(root, "SKILL-901", "codex"))
      val request = launches.single()
      assertEquals(
        WorkRunLaunchKind.RuntimeCommand(
          listOf("goal", "SKILL-901", "--agent", "codex", "--repo-root", root.toString(), "--no-live-output"),
        ),
        request.kind,
      )
      assertEquals("${testGoalRepositoryIdentity(root)}:SKILL-901:goal", request.ownershipKey)
      assertEquals(
        WorkControlResult.LaunchFailed("A run is already starting or running for this work."),
        actions.startGoalForTest(root, "SKILL-901", "codex"),
      )
      assertEquals(
        WorkControlResult.Accepted,
        actions.startGoalForTest(
          root,
          "SKILL-902",
          "codex",
          QaNamedDevice(QaNamedDeviceKind.AVD, "Pixel_8_API_35"),
        ),
      )
      assertEquals(
        listOf(
          "goal",
          "SKILL-902",
          "--agent",
          "codex",
          "--qa-device",
          "avd:Pixel_8_API_35",
          "--repo-root",
          root.toString(),
          "--no-live-output",
        ),
        (launches.last().kind as WorkRunLaunchKind.RuntimeCommand).arguments,
      )
    }
  }

  @Test
  fun `start goal never launches when preflight refuses and never reaches preflight without an agent`() {
    establishTemporarySchemaReadiness("start-goal-refused").use { fixture ->
      val root = fixture.tempDir.toRealPath()
      val database = sqliteSessionFactoryForTests(root, fixture.dbPath.toString(), emptyMap(), ideStatusClock)
      val launches = mutableListOf<WorkRunLaunchRequest>()
      val launcher =
        object : WorkRunLauncher {
          override fun start(request: WorkRunLaunchRequest): WorkRunLaunchResult {
            launches += request
            return WorkRunLaunchResult.Started(root.resolve("goal.log"))
          }

          override fun stop(request: WorkRunStopRequest) = WorkRunStopResult.NotRunning
        }
      val manifests = testWorkflowGoalRunnerManifestStore(database, TestDecompositionManifestStore, ideStatusClock)
      val blockedGate =
        DecompositionManifest(
          issueKey = "SKILL-901",
          featureName = "blocked-gate",
          parentSpecPath = ".feature-specs/SKILL-901-goal/spec.md",
          specSource = SpecSource.LOCAL,
          status = "in_progress",
          baseBranch = "main",
          featureBranch = "feat/SKILL-901-goal",
          currentSubtaskIntent = CurrentSubtaskIntent(1, "start"),
          subtasks =
            listOf(
              DecompositionSubtask(
                id = 1,
                name = "first",
                specPath = ".feature-specs/SKILL-901-goal/spec_subtask_1.md",
                dependencies = listOf(DecompositionDependency(99)),
              ),
            ),
        )
      val actions =
        workRunActions(
          database,
          ideStatusService(database, manifests),
          testGoalRunnerStatusService(manifests, EmptyOutcomeStore, database = database, clock = ideStatusClock),
          launcher,
          goalPreflightServiceForTests(database, GoalRunnerManifestState("", fixture.dbPath.toString(), blockedGate)),
        )

      val refused = actions.startGoalForTest(root, "SKILL-901", "codex")

      val failure = assertIs<WorkControlResult.LaunchFailed>(refused)
      assertTrue(failure.reason.startsWith("SKILL-901 is blocked at the goal gate"), failure.reason)
      assertEquals(
        WorkControlResult.Rejected(WorkControlRejection.AGENT_REQUIRED),
        actions.startGoalForTest(root, "SKILL-901", null),
      )
      assertEquals(
        WorkControlResult.Rejected(WorkControlRejection.AGENT_REQUIRED),
        actions.startGoalForTest(root, "SKILL-901", "not-an-agent"),
      )
      assertTrue(launches.isEmpty())
    }
  }

  @Test
  fun `start goal launches reusable abandoned leftover when preflight omits gate block`() {
    establishTemporarySchemaReadiness("start-goal-reusable-leftover").use { fixture ->
      val root = fixture.tempDir.toRealPath()
      val database = sqliteSessionFactoryForTests(root, fixture.dbPath.toString(), emptyMap(), ideStatusClock)
      val launches = mutableListOf<WorkRunLaunchRequest>()
      val manifests = testWorkflowGoalRunnerManifestStore(database, TestDecompositionManifestStore, ideStatusClock)
      val leftover = GoalRunnerManifestState("parent-abandoned", fixture.dbPath.toString(), leftoverBlockedManifest())
      val actions =
        workRunActions(
          database,
          ideStatusService(database, manifests),
          testGoalRunnerStatusService(manifests, EmptyOutcomeStore, database = database, clock = ideStatusClock),
          recordingLauncher(root, launches),
          goalPreflightServiceForTests(database, leftover),
        )

      assertEquals(WorkControlResult.Accepted, actions.startGoalForTest(root, "SKILL-901", "codex"))
      assertEquals(1, launches.size)
    }
  }

  @Test
  fun `start new task goal kind launches reusable abandoned leftover when preflight omits gate block`() {
    establishTemporarySchemaReadiness("start-new-task-reusable-leftover").use { fixture ->
      val root = fixture.tempDir.toRealPath()
      val database = sqliteSessionFactoryForTests(root, fixture.dbPath.toString(), emptyMap(), ideStatusClock)
      val launches = mutableListOf<WorkRunLaunchRequest>()
      val leftover = GoalRunnerManifestState("parent-abandoned", fixture.dbPath.toString(), leftoverBlockedManifest())
      val start =
        WorkNewTaskStart(
          database,
          GoalNewWorkAdmissionPort { _, _ -> GoalNewWorkAdmission.NoIssueKey },
          NewTaskIssueKeyAllocator(testProjectStore, TestDecompositionManifestStore, NoopRuntimeDiagnostics),
          goalPreflightServiceForTests(database, leftover),
          recordingLauncher(root, launches),
          EnvironmentContext(),
          TestRepositoryEnclosingRoot,
          NoopRuntimeDiagnostics,
          InPlaceGoalWorktreeResources,
          RecordingBranchWorktreeResources(),
          OperationCardLaunches(EmptyOperationCardRepository, NoOperationCardLifecycle) { emptyMap() },
        )

      val result =
        start.startNewTask(
          root,
          NewTaskPreparation.Ready("SKILL-901", "SKILL-901", allocated = false),
          NewTaskRunKind.GOAL,
          "codex",
        )

      assertEquals(NewTaskStartResult.Started, result)
      assertEquals(1, launches.size)
    }
  }

  @ParameterizedTest
  @ValueSource(
    strings = [
      GoalPreflightVerdict.ALREADY_RUNNING,
      GoalPreflightVerdict.RESUMABLE,
      GoalPreflightVerdict.GOAL_CONTINUATION,
      GoalPreflightVerdict.TERMINAL_ONLY,
      "unexpected_verdict",
    ],
  )
  fun `start goal refuses every preflight verdict other than new work`(verdict: String) {
    val expected =
      mapOf(
        GoalPreflightVerdict.ALREADY_RUNNING to "SKILL-901 is already running.",
        GoalPreflightVerdict.RESUMABLE to "SKILL-901 already has a run. Use Resume or Run again.",
        GoalPreflightVerdict.GOAL_CONTINUATION to "SKILL-901 already has a run. Use Resume or Run again.",
        GoalPreflightVerdict.TERMINAL_ONLY to "SKILL-901 already finished. Purge its run history to start it again.",
        "unexpected_verdict" to "Skill Bill can't start SKILL-901: preflight returned unexpected_verdict.",
      ).getValue(verdict)

    assertEquals(expected, goalStartRefusalForTest(GoalPreflightResult(verdict, "SKILL-901")))
  }

  @Test
  fun `start goal refuses a new work gate only when no subtask is runnable`() {
    val gate =
      GoalPreflightGateBlock(
        issueKey = "SKILL-901",
        featureName = "spec browser",
        subtasks =
          listOf(
            GoalPreflightSubtask(1, "first", "complete", emptyList()),
            GoalPreflightSubtask(2, "second", "blocked", emptyList()),
          ),
        expectedFirstRunnableSubtask = null,
        childAgent = "codex",
        childAgentOverride = null,
        reviewMode = "inline",
        agentAddons = emptyList(),
      )

    assertEquals(
      "SKILL-901 is blocked at the goal gate: no subtask of spec browser is runnable. " +
        "Subtasks: 1 complete, 2 blocked.",
      goalStartRefusalForTest(GoalPreflightResult(GoalPreflightVerdict.NEW_WORK, "SKILL-901", gateBlock = gate)),
    )
    assertNull(
      goalStartRefusalForTest(
        GoalPreflightResult(
          GoalPreflightVerdict.NEW_WORK,
          "SKILL-901",
          gateBlock = gate.copy(expectedFirstRunnableSubtask = 2),
        ),
      ),
    )
    assertNull(
      goalStartRefusalForTest(GoalPreflightResult(GoalPreflightVerdict.NEW_WORK, "SKILL-901", manifestMissing = true)),
    )
  }

  private fun leftoverBlockedManifest(): DecompositionManifest =
    DecompositionManifest(
      issueKey = "SKILL-901",
      featureName = "blocked-gate",
      parentSpecPath = ".feature-specs/SKILL-901-goal/spec.md",
      specSource = SpecSource.LOCAL,
      status = "in_progress",
      baseBranch = "main",
      featureBranch = "feat/SKILL-901-goal",
      currentSubtaskIntent = CurrentSubtaskIntent(2, "start"),
      subtasks =
        listOf(
          DecompositionSubtask(
            id = 1,
            name = "first",
            specPath = ".feature-specs/SKILL-901-goal/spec_subtask_1.md",
            status = "pending",
          ),
          DecompositionSubtask(
            id = 2,
            name = "second",
            specPath = ".feature-specs/SKILL-901-goal/spec_subtask_2.md",
            dependencies = listOf(DecompositionDependency(1)),
            status = "pending",
          ),
        ),
    )

  private fun recordingLauncher(
    root: Path,
    launches: MutableList<WorkRunLaunchRequest>,
  ) = object : WorkRunLauncher {
    override fun start(request: WorkRunLaunchRequest): WorkRunLaunchResult {
      launches += request
      return WorkRunLaunchResult.Started(root.resolve("goal.log"))
    }

    override fun stop(request: WorkRunStopRequest) = WorkRunStopResult.NotRunning
  }

  private fun workRunActions(
    database: DatabaseSessionFactory,
    status: IdeStatusService,
    goals: GoalRunnerStatusService,
    launcher: WorkRunLauncher,
    preflight: GoalPreflightService = goalPreflightServiceForTests(database),
  ) = WorkRunActions(
    database,
    status,
    goals,
    CheckedOutBranchSource(::fixtureCheckedOutBranch),
    NoopFeatureTaskRuntimeWorkerSupervisor,
    launcher,
    ideStatusClock,
    preflight,
    EnvironmentContext(environment = mapOf("HOME" to TEST_HOME), userHome = Path.of(TEST_HOME)),
    TestRepositoryEnclosingRoot,
  )

  private fun seedUnfinishedGoal(
    database: DatabaseSessionFactory,
    connection: Connection,
  ) {
    database.transaction { unit ->
      listOf("goal-1", "w-child").forEach { id ->
        unit.workflowStates.saveFeatureTaskWorkflow(
          runtimeRecord(id, ideStatusObservedAt.toString()),
          FeatureTaskWorkflowMode.RUNTIME,
        )
      }
    }
    connection.prepareStatement(
      "INSERT INTO goal_issue_progress " +
        "(parent_workflow_id, issue_key, first_started_at, status, last_activity_at) " +
        "VALUES ('goal-1', 'SKILL-148', ?, 'failed', ?)",
    ).use {
      it.setString(1, ideStatusObservedAt.minusSeconds(60).toString())
      it.setString(2, ideStatusObservedAt.minusSeconds(30).toString())
      it.executeUpdate()
    }
  }

  private companion object {
    const val TEST_HOME = "/skill-bill-test-home"
  }
}
