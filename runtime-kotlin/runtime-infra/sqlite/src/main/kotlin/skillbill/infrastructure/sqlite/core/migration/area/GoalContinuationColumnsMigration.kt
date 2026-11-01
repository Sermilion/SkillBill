package skillbill.infrastructure.sqlite.core.migration.area

import skillbill.infrastructure.sqlite.core.migration.DatabaseColumnMigrations
import java.sql.Connection

internal fun addGoalContinuationColumns(connection: Connection) {
  DatabaseColumnMigrations.ensureColumn(connection, "feature_task_workflows", "parent_workflow_id", "TEXT")
  DatabaseColumnMigrations.ensureColumn(connection, "feature_task_workflows", "goal_subtask_id", "INTEGER")
  DatabaseColumnMigrations.ensureColumn(connection, "feature_task_workflows", "goal_issue_key", "TEXT")
  connection.createStatement().use { statement ->
    statement.execute(
      """
      UPDATE feature_task_workflows
      SET parent_workflow_id = CASE
            WHEN json_type(artifacts_json, '$.goal_continuation.parent_workflow_id') = 'text'
            THEN json_extract(artifacts_json, '$.goal_continuation.parent_workflow_id') END,
          goal_subtask_id = CASE
            WHEN json_type(artifacts_json, '$.goal_continuation.subtask_id') = 'integer'
            THEN json_extract(artifacts_json, '$.goal_continuation.subtask_id') END,
          goal_issue_key = CASE
            WHEN json_type(artifacts_json, '$.goal_continuation.issue_key') = 'text'
            THEN json_extract(artifacts_json, '$.goal_continuation.issue_key') END
      WHERE json_valid(artifacts_json) AND json_type(artifacts_json, '$.goal_continuation') = 'object'
      """.trimIndent(),
    )
    statement.execute("DROP INDEX IF EXISTS idx_feature_task_workflows_goal_parent")
    statement.execute(
      "CREATE INDEX IF NOT EXISTS idx_feature_task_workflows_parent_workflow_id " +
        "ON feature_task_workflows(parent_workflow_id)",
    )
  }
}
