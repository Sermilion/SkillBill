package skillbill.engine.work.control

import skillbill.application.testDecompositionManifestValidator
import skillbill.engine.diagnostics.block.recovery.BlockIncidentRecoveryActions
import skillbill.engine.diagnostics.block.recovery.BlockIncidentRecoveryService
import skillbill.engine.featuretask.lifecycle.remediation.FeatureTaskRuntimeStandaloneIncidentRecovery
import skillbill.engine.goalrunner.execution.core.GoalRunnerStatusTestPorts
import skillbill.engine.goalrunner.execution.core.testGoalRunnerStatusService
import skillbill.engine.goalrunner.manifest.GoalRunnerManifestStore
import skillbill.engine.goalrunner.persist.WorkflowGoalRunnerBlockWrites
import skillbill.engine.work.EmptyOutcomeStore
import skillbill.engine.work.StubGoalManifestStore
import skillbill.engine.work.control.model.WorkControlAction
import skillbill.engine.work.control.model.WorkControlRejection
import skillbill.engine.work.control.model.WorkControlResult
import skillbill.engine.work.control.model.WorkControlTarget
import skillbill.engine.work.fixtureCheckedOutBranch
import skillbill.engine.work.goalManifestState
import skillbill.engine.work.ideStatusClock
import skillbill.engine.work.ideStatusObservedAt
import skillbill.engine.work.ideStatusService
import skillbill.engine.work.testGoalRepositoryIdentity
import skillbill.goalrunner.model.GOAL_PAUSE_REASON_OPERATOR_STOP
import skillbill.goalrunner.model.GoalRunnerControlState
import skillbill.goalrunner.model.GoalRunnerExecutionLease
import skillbill.infrastructure.contracts.workflow.featuretask.FeatureTaskRuntimeExecutionPlanSchemaValidator
import skillbill.infrastructure.sqlite.establishTemporarySchemaReadiness
import skillbill.infrastructure.sqlite.sqliteSessionFactoryForTests
import skillbill.ports.diagnostics.NoopRuntimeDiagnostics
import skillbill.ports.featuretask.model.FeatureTaskRuntimeWorkerOwnership
import skillbill.ports.idestatus.model.IdeStatusWorkflowRegistration
import skillbill.ports.idestatus.model.WorkBoardRequest
import skillbill.ports.idestatus.model.WorkDetailRequest
import skillbill.ports.process.IdentifierGeneratorPort
import skillbill.ports.system.CheckedOutBranchSource
import skillbill.ports.taskruntime.FeatureTaskRuntimeWorkerSupervisor
import skillbill.ports.taskruntime.NoopFeatureTaskRuntimeWorkerSupervisor
import skillbill.ports.taskruntime.model.FeatureTaskRuntimeProcessInspection
import skillbill.ports.work.WorkRunLauncher
import skillbill.ports.work.model.WorkRunLaunchRequest
import skillbill.ports.work.model.WorkRunLaunchResult
import skillbill.ports.work.model.WorkRunStopRequest
import skillbill.ports.work.model.WorkRunStopResult
import java.nio.file.Path
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class WorkControlServiceTest {
  @Test
  fun `stop accepts progress since rendering and terminates only the selected runner`() {
    GoalControlsFixture().use { fixture ->
      val rendered = fixture.target()
      fixture.advanceProgress()
      val current = fixture.target()
      assertNotEquals(rendered.updatedAt, current.updatedAt)
      assertNotEquals(rendered.statusRevision, current.statusRevision)
      assertNotEquals(rendered.observation, current.observation)

      assertEquals(WorkControlResult.ExitConfirmed, fixture.service.request(rendered, WorkControlAction.STOP))

      assertEquals(GOAL_PAUSE_REASON_OPERATOR_STOP, fixture.control().pauseReason)
      assertTrue(fixture.control().paused)
      assertEquals(listOf(rendered.leaseOwnerToken), fixture.terminated.map { it.ownerToken })
    }
  }

  @Test
  fun `stop accepts a target built from the detail projection of a leased runner`() {
    GoalControlsFixture().use { fixture ->
      val rendered = fixture.detailTarget()

      assertEquals(WorkControlResult.ExitConfirmed, fixture.service.request(rendered, WorkControlAction.STOP))

      assertEquals(listOf(rendered.leaseOwnerToken), fixture.terminated.map { it.ownerToken })
    }
  }

  @Test
  fun `pause accepts progress since rendering without terminating the runner`() {
    GoalControlsFixture().use { fixture ->
      val rendered = fixture.target()
      fixture.advanceProgress()

      assertEquals(WorkControlResult.IntentRecorded, fixture.service.request(rendered, WorkControlAction.PAUSE))

      assertTrue(fixture.control().pauseRequested)
      assertFalse(fixture.control().paused)
      assertTrue(fixture.terminated.isEmpty())
    }
  }

  @Test
  fun `controls refuse changed goal execution database or owner identities before recording intent`() {
    GoalControlsFixture().use { fixture ->
      val target = fixture.target()
      val changed =
        listOf(
          target.copy(repositoryIdentity = "another-repository"),
          target.copy(repositoryRoot = fixture.root.resolve("another-checkout")),
          target.copy(issueKey = "SKILL-999"),
          target.copy(executionId = "another-execution"),
          target.copy(statusStoreId = "another-status-store"),
          target.copy(runSequence = "999"),
          target.copy(leaseOwnerToken = "another-owner"),
          target.copy(leaseGeneration = 999),
          target.copy(observation = assertNotNull(target.observation).copy(storeId = "another-store")),
          target.copy(
            databaseFileObservation = assertNotNull(target.databaseFileObservation).copy(fileKey = "replaced"),
          ),
        )
      for (action in listOf(WorkControlAction.STOP, WorkControlAction.PAUSE)) {
        for (stale in changed) {
          assertEquals(
            WorkControlResult.Rejected(WorkControlRejection.CHANGED_TARGET),
            fixture.service.request(stale, action),
            "$action must refuse $stale",
          )
        }
        assertEquals(
          WorkControlResult.Rejected(WorkControlRejection.CHANGED_TARGET),
          fixture.service.request(target, action) { false },
        )
      }
      assertFalse(fixture.control().pauseRequested)
      assertTrue(fixture.terminated.isEmpty())
    }
  }
}

private class GoalControlsFixture : AutoCloseable {
  private val schema = establishTemporarySchemaReadiness("goal-controls-progress")
  val root: Path = schema.tempDir.toRealPath()
  private val database = sqliteSessionFactoryForTests(root, schema.dbPath.toString(), emptyMap())
  private val identity = testGoalRepositoryIdentity(root)
  private val lease =
    GoalRunnerExecutionLease(
      1,
      "goal-owner",
      "host",
      "boot",
      123,
      "birth",
      ideStatusObservedAt.minusSeconds(10),
      ideStatusObservedAt.plusSeconds(600),
    )
  val terminated = mutableListOf<FeatureTaskRuntimeWorkerOwnership>()
  private val supervisor =
    object : FeatureTaskRuntimeWorkerSupervisor by NoopFeatureTaskRuntimeWorkerSupervisor {
      override fun inspect(ownership: FeatureTaskRuntimeWorkerOwnership) =
        if (terminated.isEmpty()) {
          FeatureTaskRuntimeProcessInspection.ExactLive
        } else {
          FeatureTaskRuntimeProcessInspection.NotRunning
        }

      override fun terminateGracefully(ownership: FeatureTaskRuntimeWorkerOwnership): Boolean {
        terminated += ownership
        return true
      }
    }
  private val state = goalManifestState(root, identity, "")
  private val store =
    object : GoalRunnerManifestStore by StubGoalManifestStore(state, lease = lease) {
      override fun requestPause(parentWorkflowId: String): GoalRunnerControlState =
        persist(control().copy(pauseRequested = true))

      override fun pauseNow(
        parentWorkflowId: String,
        reason: String,
        pausedAt: String,
        overwriteExistingReason: Boolean,
      ): GoalRunnerControlState =
        persist(
          control().copy(
            pauseRequested = true,
            pauseConsumed = true,
            paused = true,
            pauseReason = reason,
            pausedAt = pausedAt,
          ),
        )
    }
  private val status = ideStatusService(database, manifestStore = store)
  private val goals =
    testGoalRunnerStatusService(
      store,
      EmptyOutcomeStore,
      clock = ideStatusClock,
      database = database,
      ports = GoalRunnerStatusTestPorts(workerSupervisor = supervisor),
    )
  private val launcher =
    object : WorkRunLauncher {
      override fun start(request: WorkRunLaunchRequest) = WorkRunLaunchResult.Refused("Unused")

      override fun stop(request: WorkRunStopRequest) = WorkRunStopResult.NotRunning
    }
  private val runs =
    WorkRunActions(
      database,
      status,
      goals,
      CheckedOutBranchSource(::fixtureCheckedOutBranch),
      supervisor,
      launcher,
      ideStatusClock,
    )
  private val recovery =
    BlockIncidentRecoveryService(
      database,
      ideStatusClock,
      FeatureTaskRuntimeExecutionPlanSchemaValidator(),
      supervisor,
      NoopRuntimeDiagnostics,
      IdentifierGeneratorPort { UUID.randomUUID().toString() },
      testDecompositionManifestValidator,
      BlockIncidentRecoveryActions(
        WorkflowGoalRunnerBlockWrites(ideStatusClock),
        FeatureTaskRuntimeStandaloneIncidentRecovery(ideStatusClock),
      ),
    )
  val service =
    WorkControlService(
      database,
      status,
      goals,
      WorkCancellation(database, status, goals, supervisor, ideStatusClock, launcher),
      runs,
      WorkControlRecovery(status, recovery, runs),
    )

  init {
    schema.connection.prepareStatement(
      "INSERT INTO goal_issue_progress " +
        "(parent_workflow_id, issue_key, first_started_at, status, last_activity_at, state_entered_at) " +
        "VALUES ('goal-1', 'SKILL-148', ?, 'running', ?, ?)",
    ).use {
      it.setString(1, ideStatusObservedAt.minusSeconds(60).toString())
      it.setString(2, ideStatusObservedAt.minusSeconds(30).toString())
      it.setString(3, ideStatusObservedAt.minusSeconds(30).toString())
      it.executeUpdate()
    }
    persist(GoalRunnerControlState(repositoryIdentity = identity, executionLease = lease))
    database.transaction {
      it.standalonePhaseStatuses.registerWorkflow(
        IdeStatusWorkflowRegistration(
          identity,
          "feat/SKILL-148",
          "SKILL-148",
          "goal-1",
          "invocation",
          "execution",
          "active",
          ideStatusObservedAt.minusSeconds(60),
        ),
      )
    }
  }

  fun target(): WorkControlTarget {
    val board = status.projectBoardSelection(listOf("goal-1"), WorkBoardRequest(observedAt = ideStatusObservedAt))
    val item = board.items.single()
    val snapshot = item.snapshot
    assertTrue(item.controlEligible, snapshot.toString())
    return WorkControlTarget(
      "goal-1", identity, root, "SKILL-148", snapshot.execution?.executionId, snapshot.execution?.statusRevision,
      snapshot.execution?.statusStoreId, snapshot.execution?.runSequence, snapshot.updatedAt,
      lease.ownerToken, lease.generation, board.observation, item.databaseFileObservation,
    )
  }

  fun detailTarget(): WorkControlTarget {
    val detail = status.detail(WorkDetailRequest("goal-1", observedAt = ideStatusObservedAt))
    val item = assertNotNull(detail.item, detail.problem.toString())
    val snapshot = item.snapshot
    val lease = assertNotNull(item.controlLease)
    return WorkControlTarget(
      "goal-1", identity, root, "SKILL-148", snapshot.execution?.executionId, snapshot.execution?.statusRevision,
      snapshot.execution?.statusStoreId, snapshot.execution?.runSequence, snapshot.updatedAt,
      lease.ownerToken, lease.generation, item.observation, item.databaseFileObservation,
    )
  }

  fun advanceProgress() {
    schema.connection.createStatement().use {
      it.executeUpdate(
        "UPDATE goal_issue_progress SET last_activity_at = '$ideStatusObservedAt' " +
          "WHERE parent_workflow_id = 'goal-1'",
      )
      it.executeUpdate(
        "UPDATE ide_status_workflow_execution SET status_revision = '2' WHERE execution_id = 'execution'",
      )
    }
    persist(control().copy(executionLease = lease.copy(heartbeatAt = ideStatusObservedAt)))
  }

  fun control(): GoalRunnerControlState = database.read { it.goalRunnerControls.controlState("goal-1") }

  private fun persist(control: GoalRunnerControlState): GoalRunnerControlState =
    database.transaction {
      it.goalRunnerControls.persistControlState("goal-1", control)
    }

  override fun close() = schema.close()
}
