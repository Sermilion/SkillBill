package skillbill.engine.work

import skillbill.goalrunner.model.GoalRunnerControlState
import skillbill.idestatus.model.IdeStatusLaunchScope
import skillbill.infrastructure.sqlite.establishTemporarySchemaReadiness
import skillbill.infrastructure.sqlite.sqliteDatabaseSessionFactory
import skillbill.ports.idestatus.model.StandalonePhaseStatusRegistration
import skillbill.ports.idestatus.model.StandalonePhaseStatusUpdate
import skillbill.ports.idestatus.model.StandalonePhaseStatusUpdateResult
import skillbill.ports.idestatus.model.WorkBoardRequest
import skillbill.ports.idestatus.model.WorkDetailRequest
import skillbill.workflow.model.FeatureTaskWorkflowMode
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class WorkBoardRemovedPlanningWorktreeTest {
  @Test
  fun `removed goal worktree does not resurrect its planning row during fallback reads`() {
    boardAfterCleanup(owned = true)
  }

  @Test
  fun `same issue in another worktree does not hide an unrelated planning row`() {
    boardAfterCleanup(owned = false)
  }

  private fun boardAfterCleanup(owned: Boolean) {
    establishTemporarySchemaReadiness("board-removed-plan").use { fixture ->
      val database =
        sqliteDatabaseSessionFactory(fixture.tempDir, fixture.dbPath.toString(), emptyMap(), ideStatusClock)
      val root = fixture.tempDir.toRealPath()
      val identity = testGoalRepositoryIdentity(root)
      val removed = root.resolve("removed-checkout")
      val removedIdentity = testGoalRepositoryIdentity(removed)
      assertFalse(Files.exists(removed))
      database.transaction { unit ->
        unit.workflowStates.saveFeatureTaskWorkflow(
          runtimeRecord("goal-1", ideStatusObservedAt.minusSeconds(60).toString(), "pr").copy(
            artifactsJson = """{"decomposition_runtime":{"issue_key":"SKILL-148","status":"complete","subtasks":[]}}""",
          ),
          FeatureTaskWorkflowMode.RUNTIME,
        )
        unit.goalRunnerControls.persistControlState(
          "goal-1",
          GoalRunnerControlState(
            repositoryIdentity = identity,
            goalCompletedAt = ideStatusObservedAt.minusSeconds(60).toString(),
            executionWorktreePath = if (owned) removed.toString() else root.resolve("other-checkout").toString(),
            executionWorktreeRemovedAt = ideStatusObservedAt.minusSeconds(30).toString(),
          ),
        )
        val plan =
          unit.standalonePhaseStatuses.register(
            StandalonePhaseStatusRegistration(
              removedIdentity, "HEAD", "SKILL-148", "plan-workflow", "plan-invocation", "plan", "plan-execution",
              "active", "preplan", ideStatusObservedAt.minusSeconds(120), "owner", 1,
              ideStatusObservedAt.plusSeconds(30), launchScope = IdeStatusLaunchScope.GOAL,
            ),
          )
        assertEquals(
          StandalonePhaseStatusUpdateResult.ACCEPTED,
          unit.standalonePhaseStatuses.update(
            StandalonePhaseStatusUpdate(
              plan.executionId, plan.statusRevision, plan.leaseOwner, plan.leaseGeneration,
              "terminal", "plan_subtasks", null, ideStatusObservedAt.minusSeconds(90),
              finishedAt = ideStatusObservedAt.minusSeconds(90),
            ),
          ),
        )
      }
      fixture.connection.createStatement().use {
        it.executeUpdate(
          """INSERT INTO goal_issue_progress(parent_workflow_id, issue_key, first_started_at, status, state_entered_at)
            VALUES ('goal-1', 'SKILL-148', '2026-08-06T08:00:00Z', 'completed', '2026-08-06T11:59:00Z')""",
        )
      }
      val service = ideStatusService(database)
      val request = WorkBoardRequest(observedAt = ideStatusObservedAt)
      val indexed = service.board(request)
      assertTrue(database.read { it.workBoardReadModel.isComplete() })
      val expected = if (owned) listOf("goal-1") else listOf("goal-1", "plan-workflow")
      assertEquals(expected, indexed.items.map { it.snapshot.workflowId })
      fixture.connection.createStatement().use {
        it.executeUpdate(
          "UPDATE work_board_read_model_state SET complete = 0",
        )
      }
      val fallback = service.board(request)
      assertEquals(expected, fallback.items.map { it.snapshot.workflowId })
      assertEquals(indexed.items, fallback.items)
      assertNotNull(service.detail(WorkDetailRequest("plan-invocation", observedAt = ideStatusObservedAt)).item)
    }
  }
}
