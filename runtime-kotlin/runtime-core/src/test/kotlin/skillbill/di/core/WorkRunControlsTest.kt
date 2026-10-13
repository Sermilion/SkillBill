package skillbill.di.core

import skillbill.engine.work.control.WorkCancellation
import skillbill.engine.work.control.model.WorkControlAction
import skillbill.engine.work.control.model.WorkControlRejection
import skillbill.engine.work.control.model.WorkControlResult
import skillbill.engine.work.control.model.WorkRunTarget
import skillbill.goalrunner.model.GoalRunnerControlState
import skillbill.idestatus.model.IdeStatusLifecycleState
import skillbill.infrastructure.sqlite.ensureTestDatabase
import skillbill.infrastructure.sqlite.establishTemporarySchemaReadiness
import skillbill.model.EnvironmentContext
import skillbill.ports.featuretask.model.FeatureTaskRuntimeWorkerOwnership
import skillbill.ports.idestatus.model.StandalonePhaseStatusRegistration
import skillbill.ports.idestatus.model.StandalonePhaseStatusUpdate
import skillbill.ports.idestatus.model.StandalonePhaseStatusUpdateResult
import skillbill.ports.idestatus.model.WorkBoardRequest
import skillbill.ports.idestatus.model.WorkDetailRequest
import skillbill.ports.taskruntime.FeatureTaskRuntimeWorkerSupervisor
import skillbill.ports.taskruntime.NoopFeatureTaskRuntimeWorkerSupervisor
import skillbill.ports.taskruntime.model.FeatureTaskRuntimeProcessIdentity
import skillbill.ports.taskruntime.model.FeatureTaskRuntimeProcessInspection
import skillbill.ports.work.WorkRunLauncher
import skillbill.ports.work.model.WorkRunLaunchRequest
import skillbill.ports.work.model.WorkRunLaunchResult
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WorkRunControlsTest {
  @Test
  fun `canceling an interrupted phase moves it into history and rejects late owner updates`() {
    establishTemporarySchemaReadiness("canceled-phase").use { fixture ->
      val component = component(fixture.tempDir, fixture.dbPath)
      val registered =
        component.databaseSession.transaction {
          it.standalonePhaseStatuses.register(registration(fixture.tempDir))
        }
      val target = target(component)
      assertEquals(WorkControlResult.Accepted, component.cancelRun(target))
      val canceled = assertNotNull(component.ideStatusService.detail(WorkDetailRequest("interrupted")).item)
      assertEquals(IdeStatusLifecycleState.CANCELED, canceled.snapshot.lifecycleState)
      assertNotNull(canceled.finishedAt)
      assertNull(canceled.liveSection)
      assertFalse(canceled.requiresAttention)
      assertEquals(
        WorkControlResult.Rejected(WorkControlRejection.INELIGIBLE_TARGET),
        component.launchRun(target(component), "codex"),
      )
      val late =
        component.databaseSession.transaction {
          it.standalonePhaseStatuses.update(
            StandalonePhaseStatusUpdate(
              registered.executionId,
              registered.statusRevision,
              registered.leaseOwner,
              registered.leaseGeneration,
              "active",
              "monitor",
              null,
              Instant.now(),
            ),
          )
        }
      assertEquals(StandalonePhaseStatusUpdateResult.TERMINAL_REGRESSION, late)
      val board = component.ideStatusService.projectBoardSelection(listOf("interrupted"), WorkBoardRequest())
      assertEquals(IdeStatusLifecycleState.CANCELED, board.items.single().snapshot.lifecycleState)
    }
  }

  @Test
  fun `run again keeps recorded intake allows another harness and refuses stale targets or a changed branch`() {
    establishTemporarySchemaReadiness("repeat-phase").use { fixture ->
      Files.createDirectory(fixture.tempDir.resolve(".git"))
      Files.writeString(fixture.tempDir.resolve(".git/HEAD"), "ref: refs/heads/main\n")
      val launched = mutableListOf<WorkRunLaunchRequest>()
      val launcher =
        object : WorkRunLauncher {
          override fun start(request: WorkRunLaunchRequest): WorkRunLaunchResult {
            launched += request
            return WorkRunLaunchResult.Started(fixture.tempDir.resolve("run.log"))
          }
        }
      val component = component(fixture.tempDir, fixture.dbPath, launcher)
      val arguments = listOf("phase", "monitor", "https://example.test/pr/42", "--agent", "claude")
      component.databaseSession.transaction {
        it.standalonePhaseStatuses.register(
          registration(fixture.tempDir).copy(launchAgentId = "claude", launchArguments = arguments),
        )
      }
      val target = target(component)
      val options = assertNotNull(component.workControlService.options(target))
      assertTrue(options.canLaunch)
      assertEquals("claude", options.agentId)
      assertEquals("Run again", options.launchLabel)
      assertEquals(WorkControlResult.Accepted, component.launchRun(target, "codex"))
      assertEquals(
        WorkControlResult.Rejected(WorkControlRejection.CHANGED_TARGET),
        component.launchRun(
          target.copy(repositoryRoot = fixture.tempDir.resolve("other-repository")),
          null,
        ),
      )
      assertEquals("codex", launched.single().agentId)
      assertContains(launched.single().prompt, "/skill-bill phase:monitor")
      assertContains(launched.single().prompt, "https://example.test/pr/42")
      assertContains(launched.single().prompt, "Selected agent: codex")
      assertEquals(fixture.dbPath, launched.single().databasePath)
      Files.writeString(fixture.tempDir.resolve(".git/HEAD"), "ref: refs/heads/other\n")
      assertEquals(
        WorkControlResult.Rejected(WorkControlRejection.BRANCH_CHANGED),
        component.launchRun(target, null),
      )
      assertEquals(
        WorkControlResult.Rejected(WorkControlRejection.CHANGED_TARGET),
        component.launchRun(target.copy(statusRevision = "0"), null),
      )
      assertEquals(1, launched.size)
    }
  }

  @Test
  fun `older monitor entries require an explicit agent choice`() {
    establishTemporarySchemaReadiness("legacy-phase").use { fixture ->
      val component = component(fixture.tempDir, fixture.dbPath)
      component.databaseSession.transaction {
        it.standalonePhaseStatuses.register(registration(fixture.tempDir).copy(branchCorrelation = "HEAD"))
      }
      val target = target(component)
      assertNull(component.workControlService.options(target)?.agentId)
      assertEquals(
        WorkControlResult.Rejected(WorkControlRejection.AGENT_REQUIRED),
        component.launchRun(target, null),
      )
    }
  }

  @Test
  fun `canceling a failed historical goal removes unfinished subtasks from the live board`() {
    establishTemporarySchemaReadiness("canceled-goal").use { fixture ->
      ensureTestDatabase(fixture.dbPath).use { connection ->
        connection.prepareStatement(
          """
          INSERT INTO goal_issue_progress (parent_workflow_id, issue_key, first_started_at, status, last_activity_at)
          VALUES ('old-goal', 'SKILL-777', ?, 'failed', ?)
          """.trimIndent(),
        ).use { statement ->
          val time = Instant.now().minusSeconds(60).toString()
          statement.setString(1, time)
          statement.setString(2, time)
          statement.executeUpdate()
        }
      }
      val component = component(fixture.tempDir, fixture.dbPath)
      component.databaseSession.transaction { unit ->
        unit.goalRunnerControls.persistControlState(
          "old-goal",
          GoalRunnerControlState(
            repositoryIdentity = "repo-root-realpath-v1:${fixture.tempDir.toRealPath()}",
          ),
        )
      }
      val item = assertNotNull(component.ideStatusService.detail(WorkDetailRequest("old-goal")).item)
      val target =
        WorkRunTarget(
          "old-goal",
          item.snapshot.repositoryIdentity,
          fixture.tempDir,
          item.snapshot.execution?.executionId,
          item.snapshot.execution?.statusRevision,
          item.snapshot.updatedAt,
          assertNotNull(item.databaseFileObservation),
        )
      assertEquals(WorkControlResult.Accepted, component.cancelRun(target))
      val canceled = assertNotNull(component.ideStatusService.detail(WorkDetailRequest("old-goal")).item)
      assertEquals(IdeStatusLifecycleState.CANCELED, canceled.snapshot.lifecycleState)
      assertNull(canceled.liveSection)
      assertNotNull(canceled.finishedAt)
      assertFalse(canceled.requiresAttention)
    }
  }

  @Test
  fun `cancel terminates only the exact recorded owner and refuses an identity mismatch`() {
    listOf(
      FeatureTaskRuntimeProcessInspection.ExactLive,
      FeatureTaskRuntimeProcessInspection.OwnershipMismatch("PID belongs to another process"),
    ).forEach { initial ->
      establishTemporarySchemaReadiness("owned-phase-cancel").use { fixture ->
        val component = component(fixture.tempDir, fixture.dbPath)
        component.databaseSession.transaction { unit ->
          unit.standalonePhaseStatuses.register(
            registration(fixture.tempDir).copy(
              processOwner = FeatureTaskRuntimeProcessIdentity("fixture-host", "fixture-boot", 999999, "fixture-birth"),
            ),
          )
        }
        var inspection: FeatureTaskRuntimeProcessInspection = initial
        var stopped = 0
        val supervisor =
          object : FeatureTaskRuntimeWorkerSupervisor by NoopFeatureTaskRuntimeWorkerSupervisor {
            override fun inspect(ownership: FeatureTaskRuntimeWorkerOwnership) = inspection

            override fun terminateGracefully(ownership: FeatureTaskRuntimeWorkerOwnership): Boolean {
              assertEquals("owner", ownership.ownerToken)
              assertEquals("fixture-birth", ownership.processBirthToken)
              stopped++
              inspection = FeatureTaskRuntimeProcessInspection.NotRunning
              return true
            }
          }
        val cancellation =
          WorkCancellation(
            component.databaseSession,
            component.ideStatusService,
            component.goalRunnerStatusService,
            supervisor,
            Clock.systemUTC(),
          )
        val result = cancellation.cancel(target(component))
        if (initial == FeatureTaskRuntimeProcessInspection.ExactLive) {
          assertEquals(WorkControlResult.Accepted, result)
          assertEquals(1, stopped)
          assertEquals(
            IdeStatusLifecycleState.CANCELED,
            component.ideStatusService.detail(WorkDetailRequest("interrupted")).item?.snapshot?.lifecycleState,
          )
        } else {
          assertEquals(WorkControlResult.Rejected(WorkControlRejection.OWNERSHIP_MISMATCH), result)
          assertEquals(0, stopped)
          assertEquals(
            IdeStatusLifecycleState.PAUSED,
            component.ideStatusService.detail(WorkDetailRequest("interrupted")).item?.snapshot?.lifecycleState,
          )
        }
      }
    }
  }

  private fun RuntimeComponent.launchRun(
    target: WorkRunTarget,
    agent: String?,
  ) = workControlService.request(target, WorkControlAction.LAUNCH, agent)

  private fun RuntimeComponent.cancelRun(target: WorkRunTarget) =
    workControlService.request(target, WorkControlAction.CANCEL)

  private fun component(
    home: Path,
    database: Path,
    launcher: WorkRunLauncher? = null,
  ): RuntimeComponent =
    RuntimeComponent::class.create(
      RuntimeContext(
        EnvironmentContext(database.toString(), environment = emptyMap(), userHome = home, repositoryRoot = home),
        TransportContext(),
        WorkflowOpsContext(),
        OptionalCallbacks(workRunLauncher = launcher),
      ),
    )

  private fun registration(root: Path): StandalonePhaseStatusRegistration {
    val now = Instant.now().minusSeconds(30)
    return StandalonePhaseStatusRegistration(
      "repo-root-realpath-v1:${root.toRealPath()}", "main", null, null,
      "interrupted", "monitor", "execution-interrupted", "paused", "runner_interrupted", now, "owner", 1, now,
    )
  }

  private fun target(component: RuntimeComponent): WorkRunTarget {
    val item = assertNotNull(component.ideStatusService.detail(WorkDetailRequest("interrupted")).item)
    return WorkRunTarget(
      "interrupted",
      item.snapshot.repositoryIdentity,
      Path.of(item.repositoryRoot),
      item.snapshot.execution?.executionId,
      item.snapshot.execution?.statusRevision,
      item.snapshot.updatedAt,
      assertNotNull(item.databaseFileObservation),
    )
  }
}
