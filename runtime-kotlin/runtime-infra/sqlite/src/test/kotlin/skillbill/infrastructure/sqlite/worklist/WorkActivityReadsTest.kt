package skillbill.infrastructure.sqlite.worklist

import skillbill.infrastructure.sqlite.establishTemporarySchemaReadiness
import kotlin.test.Test
import kotlin.test.assertEquals

class WorkActivityReadsTest {
  @Test
  fun `attempt pages retain orphaned blocks exclude other goals and preserve workflow status`() {
    establishTemporarySchemaReadiness("work-activity").use { fixture ->
      val connection = fixture.connection
      connection.createStatement().use { statement ->
        statement.executeUpdate(
          """
          INSERT INTO feature_task_runtime_sessions (session_id, workflow_id, goal_parent_workflow_id, goal_subtask_id, started_at)
          VALUES ('ftr-orphan', 'orphan', 'goal', 2, '2026-10-31T20:01:00Z'),
                 ('ftr-finished', 'finished', 'goal', 3, '2026-10-31T20:02:00Z'),
                 ('ftr-other', 'other', 'another-goal', 1, '2026-10-31T20:00:00Z')
          """.trimIndent(),
        )
        statement.executeUpdate(
          """
          INSERT INTO goal_subtask_events (issue_key, workflow_id, subtask_id, status, started_at, finished_at, duration_ms, attempt_count, blocked_reason)
          VALUES ('SKILL-455', 'orphan', 2, 'blocked', '2026-10-31T20:01:00Z', '2026-10-31T20:01:10Z', 10000, 1, 'Import conflict'),
                 ('SKILL-455', 'finished', 3, 'blocked', '2026-10-31T20:02:00Z', '2026-10-31T20:03:00Z', 60000, 1, 'Old block')
          """.trimIndent(),
        )
        statement.executeUpdate(
          """
          INSERT INTO feature_task_workflows (workflow_id, mode, contract_version, workflow_status, parent_workflow_id, goal_subtask_id, started_at, finished_at)
          VALUES ('finished', 'runtime', '0.1', 'completed', 'goal', 3, '2026-10-31T20:02:00Z', '2026-10-31T20:04:00Z')
          """.trimIndent(),
        )
      }
      val first = readGoalActivityAttempts(connection, "goal", 0, 1).single()
      val second = readGoalActivityAttempts(connection, "goal", 1, 1).single()
      assertEquals("orphan", first.workflowId)
      assertEquals("Import conflict", first.blockedReason)
      assertEquals(null, first.workflowStatus)
      assertEquals("finished", second.workflowId)
      assertEquals("completed", second.workflowStatus)
      assertEquals("blocked", second.eventStatus)
      assertEquals("2026-10-31T20:04:00Z", second.finishedAt.toString())
      assertEquals(emptyList(), readGoalActivityAttempts(connection, "goal", 2, 1))
    }
  }

  @Test
  fun `stop pages are scoped to the parent across resumed runs`() {
    establishTemporarySchemaReadiness("activity-stops").use { fixture ->
      fixture.connection.createStatement().use { statement ->
        statement.executeUpdate(
          """
          INSERT INTO goal_run_sessions (workflow_id, issue_key, parent_workflow_id, started_at, finished_at, stop_reason)
          VALUES ('a', 'SKILL-455', 'goal', '2026-10-31T20:00:00Z', '2026-10-31T20:01:00Z', 'PULL_REQUEST_FAILED'),
                 ('b', 'SKILL-455', 'goal', '2026-10-31T20:02:00Z', NULL, NULL),
                 ('c', 'SKILL-455', 'other', '2026-10-31T20:03:00Z', NULL, NULL)
          """.trimIndent(),
        )
      }
      val runs = readGoalActivityStops(fixture.connection, "goal", 0, 10)
      assertEquals(listOf("a", "b"), runs.map { it.runId })
      assertEquals("PULL_REQUEST_FAILED", runs.first().reason)
      assertEquals(null, runs.last().finishedAt)
    }
  }
}
