package skillbill.engine.work.control

import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import skillbill.application.TestDecompositionManifestStore
import skillbill.engine.goalrunner.execution.core.testGoalRunnerStatusService
import skillbill.engine.goalrunner.execution.core.testWorkflowGoalRunnerManifestStore
import skillbill.engine.work.EmptyOutcomeStore
import skillbill.engine.work.control.model.WorkControlResult
import skillbill.engine.work.control.model.WorkRunTarget
import skillbill.engine.work.fixtureCheckedOutBranch
import skillbill.engine.work.goalManifestState
import skillbill.engine.work.ideStatusClock
import skillbill.engine.work.ideStatusObservedAt
import skillbill.engine.work.ideStatusService
import skillbill.engine.work.runtimeRecord
import skillbill.engine.work.testGoalRepositoryIdentity
import skillbill.idestatus.model.IdeStatusLifecycleState
import skillbill.infrastructure.sqlite.establishTemporarySchemaReadiness
import skillbill.infrastructure.sqlite.sqliteSessionFactoryForTests
import skillbill.ports.db.DatabaseSessionFactory
import skillbill.ports.idestatus.model.WorkDetailRequest
import skillbill.ports.system.CheckedOutBranchSource
import skillbill.ports.taskruntime.NoopFeatureTaskRuntimeWorkerSupervisor
import skillbill.ports.work.WorkRunLauncher
import skillbill.ports.work.model.WorkRunLaunchRequest
import skillbill.ports.work.model.WorkRunLaunchResult
import skillbill.ports.work.model.WorkRunStopRequest
import skillbill.ports.work.model.WorkRunStopResult
import skillbill.ports.workflow.model.WorkflowFamily
import skillbill.ports.workflow.toRecord
import skillbill.workflow.model.FeatureTaskWorkflowMode
import skillbill.workflow.model.WorkflowStatus
import java.nio.file.Path
import java.sql.Connection
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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
      val actions =
        WorkRunActions(
          database,
          status,
          goals,
          CheckedOutBranchSource(::fixtureCheckedOutBranch),
          NoopFeatureTaskRuntimeWorkerSupervisor,
          launcher,
          ideStatusClock,
        )
      val options = database.read { unit -> actions.options(canceled, unit) }
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
      val actions =
        WorkRunActions(
          database,
          status,
          goals,
          CheckedOutBranchSource(::fixtureCheckedOutBranch),
          NoopFeatureTaskRuntimeWorkerSupervisor,
          launcher,
          ideStatusClock,
        )

      assertEquals(WorkControlResult.Accepted, actions.launch(target(), "codex"))

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
}
