package skillbill.infrastructure.sqlite

import skillbill.infrastructure.sqlite.workflow.featuretask.GoalStatusReadStore
import java.nio.file.Files
import java.sql.Connection
import kotlin.test.Test
import kotlin.test.assertEquals

class GoalContinuationColumnsMigrationTest {
  @Test
  fun `migration v87 backfills goal continuation columns and leaves invalid or missing artifacts null`() {
    val dbPath = Files.createTempDirectory("runtime-kotlin-db-v87-goal-columns").resolve("legacy.db")
    DatabaseRuntime.ensureDatabase(dbPath).use { connection ->
      connection.createStatement().use { statement ->
        statement.executeUpdate("DELETE FROM schema_migrations WHERE name = 'add-goal-continuation-columns'")
        statement.execute("DROP INDEX IF EXISTS idx_feature_task_workflows_parent_workflow_id")
        listOf("parent_workflow_id", "goal_subtask_id", "goal_issue_key").forEach { column ->
          statement.execute("ALTER TABLE feature_task_workflows DROP COLUMN $column")
        }
        statement.execute(
          "CREATE INDEX idx_feature_task_workflows_goal_parent ON feature_task_workflows(" +
            "(CASE WHEN json_valid(artifacts_json) " +
            "THEN json_extract(artifacts_json, '\$.goal_continuation.parent_workflow_id') END))",
        )
        statement.execute("PRAGMA user_version = 86")
      }
      (1..3).forEach { subtask ->
        insertWorkflow(connection, "child-$subtask", goalArtifacts("P", subtask.toString(), "SKILL-1"))
      }
      insertWorkflow(connection, "no-artifact", """{"decomposition_runtime":{"issue_key":"SKILL-1"}}""")
      insertWorkflow(connection, "empty-json", "")
      insertWorkflow(connection, "invalid-json", "{")
      insertWorkflow(connection, "text-subtask", goalArtifacts("P", "\"4\"", "SKILL-1"))
    }

    DatabaseRuntime.ensureDatabase(dbPath).use { connection ->
      assertEquals(
        mapOf(
          "child-1" to Triple("P", 1, "SKILL-1"),
          "child-2" to Triple("P", 2, "SKILL-1"),
          "child-3" to Triple("P", 3, "SKILL-1"),
          "no-artifact" to Triple(null, null, null),
          "empty-json" to Triple(null, null, null),
          "invalid-json" to Triple(null, null, null),
          "text-subtask" to Triple("P", null, "SKILL-1"),
        ),
        goalColumns(connection),
      )
      val children = GoalStatusReadStore(connection).childPage("SKILL-1", 0, 10, setOf(1, 2, 3), "P")
      assertEquals(listOf("child-1", "child-2", "child-3"), children.map { it.workflowId }.sorted())
    }
  }

  private fun goalArtifacts(
    parent: String,
    subtaskJson: String,
    issueKey: String,
  ) = """{"goal_continuation":{"issue_key":"$issueKey","subtask_id":$subtaskJson,"parent_workflow_id":"$parent"}}"""

  private fun insertWorkflow(
    connection: Connection,
    workflowId: String,
    artifactsJson: String,
  ) {
    connection.prepareStatement(
      """
      INSERT INTO feature_task_workflows (
        workflow_id, mode, contract_version, workflow_status, steps_json, artifacts_json, started_at, state_entered_at
      ) VALUES (?, 'runtime', '0.1', 'running', '[]', ?, '2026-10-20T19:20:19Z', '2026-10-20T19:20:19Z')
      """.trimIndent(),
    ).use { statement ->
      statement.setString(1, workflowId)
      statement.setString(2, artifactsJson)
      statement.executeUpdate()
    }
  }

  private fun goalColumns(connection: Connection): Map<String, Triple<String?, Int?, String?>> =
    connection.createStatement().use { statement ->
      statement.executeQuery(
        "SELECT workflow_id, parent_workflow_id, goal_subtask_id, goal_issue_key FROM feature_task_workflows",
      ).use { rows ->
        buildMap {
          while (rows.next()) {
            val subtask = rows.getInt(3).takeUnless { rows.wasNull() }
            put(rows.getString(1), Triple(rows.getString(2), subtask, rows.getString(4)))
          }
        }
      }
    }
}
