package skillbill.infrastructure.sqlite.workflow.featuretask

import skillbill.contracts.JsonCodec
import skillbill.infrastructure.sqlite.core.ops.bindAll
import skillbill.infrastructure.sqlite.tempDbConnection
import skillbill.workflow.engine.model.DurableWorkflowArtifactFamily
import kotlin.test.Test
import kotlin.test.assertEquals

class GoalStatusReadStoreTest {
  private val historyKey = DurableWorkflowArtifactFamily.GOAL_PROGRESS_RUN_HISTORY.label()

  @Test
  fun `child pages filter the owning parent before pagination and workflow decoding`() {
    val (_, connection) = tempDbConnection("goal-status-parent-scope")
    connection.use {
      it.prepareStatement(
        """
        INSERT INTO feature_task_workflows (
          workflow_id, mode, contract_version, workflow_status, steps_json, artifacts_json, started_at, state_entered_at,
          parent_workflow_id, goal_subtask_id, goal_issue_key
        ) VALUES (?, 'runtime', ?, 'running', '[]', ?, '2026-10-20T19:20:19Z', '2026-10-20T19:20:19Z', ?, 1, '0AC-68')
        """.trimIndent(),
      ).use { statement ->
        listOf("current-parent", "foreign-parent", null).forEachIndexed { index, parentId ->
          val artifacts =
            mapOf(
              "goal_continuation" to
                mapOf(
                  "issue_key" to "0AC-68",
                  "subtask_id" to 1,
                  "parent_workflow_id" to parentId,
                ),
            )
          statement.bindAll(
            "child-$index",
            if (index == 0) "0.1" else "99.0",
            JsonCodec.valueToJsonString(artifacts),
            parentId,
          )
          statement.executeUpdate()
        }
      }
      val store = GoalStatusReadStore(it)

      val first = store.childPage("0AC-68", 0, 1, setOf(1), "current-parent")
      val next = store.childPage("0AC-68", 1, 1, setOf(1), "current-parent")

      assertEquals(listOf("child-0"), first.map { row -> row.workflowId })
      assertEquals(emptyList(), next)
    }
  }

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
