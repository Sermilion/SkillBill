package skillbill.infrastructure.sqlite.core.migration

import java.sql.Connection

internal object DatabaseColumnMigrationsConditional {
  fun apply(connection: Connection) {
    ensureGoalSubtaskEventColumns(connection)
    ensureGoalRunSessionColumns(connection)
    ensureGoalIssueProgressColumns(connection)
  }

  private fun ensureGoalSubtaskEventColumns(connection: Connection) {
    if (!DatabaseColumnMigrations.tableExists(connection, "goal_subtask_events")) return
    DatabaseColumnMigrationsEnsure.ensureColumn(connection, "goal_subtask_events", "finalizing_agent_id", "TEXT")
    DatabaseColumnMigrationsEnsure.ensureColumn(
      connection,
      "goal_subtask_events",
      "participating_agent_ids",
      "TEXT NOT NULL DEFAULT '[]'",
    )
    DatabaseColumnMigrationsEnsure.ensureColumn(
      connection,
      "goal_subtask_events",
      "boundary_history_value",
      "TEXT NOT NULL DEFAULT 'none'",
    )
    DatabaseColumnMigrationsEnsure.ensureColumn(
      connection,
      "goal_subtask_events",
      "boundary_history_written",
      "INTEGER NOT NULL DEFAULT 0",
    )
  }

  private fun ensureGoalRunSessionColumns(connection: Connection) {
    if (!DatabaseColumnMigrations.tableExists(connection, "goal_run_sessions")) return
    DatabaseColumnMigrationsEnsure.ensureColumn(
      connection,
      "goal_run_sessions",
      "mode",
      "TEXT NOT NULL DEFAULT 'runtime'",
    )
    DatabaseColumnMigrationsEnsure.ensureColumn(connection, "goal_run_sessions", "stop_reason", "TEXT")
    DatabaseColumnMigrationsEnsure.ensureColumn(connection, "goal_run_sessions", "parent_workflow_id", "TEXT")
  }

  private fun ensureGoalIssueProgressColumns(connection: Connection) {
    if (!DatabaseColumnMigrations.tableExists(connection, "goal_issue_progress")) return
    DatabaseColumnMigrationsEnsure.ensureColumn(
      connection,
      "goal_issue_progress",
      "finished_event_emitted_at",
      "TEXT",
    )
    DatabaseColumnMigrationsEnsure.ensureColumn(connection, "goal_issue_progress", "last_activity_at", "TEXT")
    DatabaseColumnMigrationsEnsure.ensureColumn(connection, "goal_issue_progress", "last_blocked_at", "TEXT")
    DatabaseColumnMigrationsEnsure.ensureColumn(
      connection,
      "goal_issue_progress",
      "latest_segment_workflow_id",
      "TEXT",
    )
    DatabaseColumnMigrationsEnsure.ensureColumn(
      connection,
      "goal_issue_progress",
      "last_blocked_segment_workflow_id",
      "TEXT",
    )
  }
}
