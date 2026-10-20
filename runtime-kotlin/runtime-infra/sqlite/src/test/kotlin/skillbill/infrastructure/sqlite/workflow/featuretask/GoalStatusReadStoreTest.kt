package skillbill.infrastructure.sqlite.workflow.featuretask

import skillbill.infrastructure.sqlite.tempDbConnection
import skillbill.workflow.engine.model.DurableWorkflowArtifactFamily
import kotlin.test.Test
import kotlin.test.assertEquals

class GoalStatusReadStoreTest {
  private val historyKey = DurableWorkflowArtifactFamily.GOAL_PROGRESS_RUN_HISTORY.label()

  @Test
  fun `goal status snapshot carries the progress run history the stall check reads`() {
    val (_, connection) = tempDbConnection("goal-status-read")
    connection.use {
      it.createStatement().use { statement ->
        statement.executeUpdate(
          """
          INSERT INTO feature_task_workflows (
            workflow_id, mode, contract_version, workflow_status, steps_json, artifacts_json, started_at, state_entered_at
          ) VALUES (
            'wftr-goal', 'runtime', '0.1', 'running', '[]',
            '{"$historyKey":[{"step_id":"qa"},{"step_id":"qa_fix"}],"unrelated":1}',
            '2026-10-20T07:05:00Z', '2026-10-20T07:05:00Z'
          )
          """.trimIndent(),
        )
      }

      val snapshot = GoalStatusReadStore(it).snapshot("wftr-goal")

      val history = snapshot?.artifacts?.get(historyKey) as? List<*>
      assertEquals(2, history?.size)
    }
  }
}
