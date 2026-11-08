package skillbill.infrastructure.sqlite

import skillbill.idestatus.model.WorktreeEditSource
import skillbill.idestatus.model.WorktreeEditTick
import skillbill.ports.workflow.model.WorkflowStateRecord
import skillbill.workflow.model.FeatureTaskWorkflowMode.RUNTIME
import skillbill.workflow.model.goalobservability.GoalObservabilityFileDiffStat
import java.nio.file.Files
import java.sql.DriverManager
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WorkBoardInvalidationTest {
  @Test
  fun `a large file tick coalesces to one durable invalidation and survives restart`() {
    val home = Files.createTempDirectory("board-file-invalidation")
    val path = home.resolve("state.db")
    val database = sqliteDatabaseSessionFactory(home, path.toString(), emptyMap())
    database.transaction { unit ->
      unit.worktreeEditJournal.append(
        "changed-workflow",
        WorktreeEditTick(
          Instant.EPOCH,
          "implement",
          WorktreeEditSource.WORKTREE_PROBE,
          (0 until 467).map { GoalObservabilityFileDiffStat("file-$it.kt", 1, 0) },
        ),
      )
    }
    database.release()
    val restarted = sqliteDatabaseSessionFactory(home, path.toString(), emptyMap())
    restarted.read {
      assertEquals("changed-workflow", it.workBoardReadModel.pendingInvalidation()?.workId)
      assertTrue(it.workBoardReadModel.readByWorkIds(setOf("changed-workflow")).isEmpty())
    }
    DriverManager.getConnection("jdbc:sqlite:$path").use { connection ->
      connection.createStatement().use { statement ->
        statement.executeQuery("SELECT count(*) FROM work_board_invalidations").use { rows ->
          assertTrue(rows.next())
          assertEquals(1, rows.getInt(1))
        }
      }
    }
  }

  @Test
  fun `rollback retains neither source change nor invalidation and stale acknowledgment cannot lose a new change`() {
    val home = Files.createTempDirectory("board-invalidation-fence")
    val database = sqliteDatabaseSessionFactory(home, home.resolve("state.db").toString(), emptyMap())
    val workflow =
      WorkflowStateRecord(
        workflowId = "rollback-workflow", sessionId = "session", workflowName = "bill-feature-task",
        contractVersion = "", workflowStatus = "running", currentStepId = "implement", stepsJson = "[]",
        artifactsJson = "{}", mode = RUNTIME, startedAt = null, updatedAt = null, finishedAt = null,
      )
    assertFailsWith<IllegalStateException> {
      database.transaction {
        it.workflowStates.saveFeatureTaskWorkflow(workflow, RUNTIME)
        error("abort source write")
      }
    }
    database.read {
      assertNull(it.workflowStates.getFeatureTaskWorkflow(workflow.workflowId))
      assertNull(it.workBoardReadModel.pendingInvalidation())
    }
    database.transaction { it.workflowStates.saveFeatureTaskWorkflow(workflow, RUNTIME) }
    val first = database.read { assertNotNull(it.workBoardReadModel.pendingInvalidation()) }
    database.transaction { it.workBoardReadModel.acknowledge(first) }
    val acknowledgedRevision = database.read { it.workBoardReadModel.invalidationRevision() }
    database.transaction {
      it.workflowStates.saveFeatureTaskWorkflow(
        workflow.copy(workflowStatus = "completed"),
        RUNTIME,
      )
    }
    database.transaction { it.workBoardReadModel.acknowledge(first) }
    database.read {
      val next = assertNotNull(it.workBoardReadModel.pendingInvalidation())
      assertEquals(first.workId, next.workId)
      assertTrue(next.revision > first.revision)
      assertTrue(it.workBoardReadModel.invalidationRevision() > acknowledgedRevision)
      assertEquals("completed", it.workflowStates.getFeatureTaskWorkflow(workflow.workflowId)?.workflowStatus)
    }
  }
}
