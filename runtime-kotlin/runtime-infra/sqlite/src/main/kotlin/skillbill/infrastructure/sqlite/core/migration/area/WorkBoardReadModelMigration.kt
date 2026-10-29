package skillbill.infrastructure.sqlite.core.migration.area

import skillbill.contracts.persistence.WorkBoardReadModelKeys
import skillbill.infrastructure.sqlite.core.schema.installWorkBoardChangeTriggers
import java.sql.Connection

internal object WorkBoardReadModelMigration {
  fun apply(connection: Connection) {
    connection.createStatement().use { statement ->
      statement.execute(
        """
        CREATE TABLE ${WorkBoardReadModelKeys.ITEMS_TABLE} (
          ${WorkBoardReadModelKeys.WORK_ID} TEXT PRIMARY KEY,
          ${WorkBoardReadModelKeys.DISPLAY_PRIORITY} INTEGER NOT NULL,
          ${WorkBoardReadModelKeys.DISPLAY_GROUP} TEXT,
          ${WorkBoardReadModelKeys.LIVE_SECTION} TEXT,
          ${WorkBoardReadModelKeys.REQUIRES_ATTENTION} INTEGER NOT NULL,
          ${WorkBoardReadModelKeys.FINISHED_AT} TEXT,
          ${WorkBoardReadModelKeys.UPDATED_AT} TEXT NOT NULL,
          ${WorkBoardReadModelKeys.FRESHNESS_DEADLINE} TEXT,
          ${WorkBoardReadModelKeys.MEMBERSHIP_DEADLINE} TEXT,
          ${WorkBoardReadModelKeys.SEARCH_TEXT} TEXT NOT NULL,
          ${WorkBoardReadModelKeys.PAYLOAD} TEXT NOT NULL,
          ${WorkBoardReadModelKeys.PROJECTED_AT} TEXT NOT NULL
        )
        """.trimIndent(),
      )
      statement.execute(
        "CREATE INDEX ${WorkBoardReadModelKeys.DISPLAY_INDEX} ON ${WorkBoardReadModelKeys.ITEMS_TABLE} " +
          "(${WorkBoardReadModelKeys.DISPLAY_PRIORITY}, ${WorkBoardReadModelKeys.UPDATED_AT} DESC, " +
          "${WorkBoardReadModelKeys.WORK_ID})",
      )
      statement.execute(
        "CREATE INDEX ${WorkBoardReadModelKeys.FINISHED_INDEX} ON ${WorkBoardReadModelKeys.ITEMS_TABLE} " +
          "(${WorkBoardReadModelKeys.FINISHED_AT})",
      )
      statement.execute(
        "CREATE TABLE ${WorkBoardReadModelKeys.STATE_TABLE} " +
          "(${WorkBoardReadModelKeys.ID} INTEGER PRIMARY KEY CHECK (${WorkBoardReadModelKeys.ID} = 1), " +
          "${WorkBoardReadModelKeys.COMPLETE} INTEGER NOT NULL)",
      )
      statement.execute(
        "INSERT INTO ${WorkBoardReadModelKeys.STATE_TABLE} (${WorkBoardReadModelKeys.ID}, " +
          "${WorkBoardReadModelKeys.COMPLETE}) VALUES (1, 0)",
      )
    }
  }
}

internal object WorkBoardReadModelDependencyMigration {
  fun apply(connection: Connection) {
    connection.createStatement().use { statement ->
      statement.executeUpdate("UPDATE ${WorkBoardReadModelKeys.STATE_TABLE} SET ${WorkBoardReadModelKeys.COMPLETE} = 0")
      statement.execute(
        "CREATE INDEX IF NOT EXISTS desktop_phase_board_group ON standalone_phase_status " +
          "(repository_identity, phase_id, " +
          "COALESCE(monitor_pr_key, upper(issue_key), workflow_id, branch_correlation), " +
          "length(run_sequence), run_sequence, invocation_id)",
      )
      statement.execute(
        "CREATE INDEX IF NOT EXISTS desktop_goal_issue_identity ON goal_issue_progress " +
          "(upper(trim(issue_key)), parent_workflow_id)",
      )
      statement.execute(
        "CREATE INDEX IF NOT EXISTS desktop_execution_identity_issue ON feature_task_execution_identities " +
          "(normalized_issue_key, route_scope, workflow_id)",
      )
      statement.execute(
        "CREATE INDEX IF NOT EXISTS desktop_verify_issue_identity ON feature_verify_workflows " +
          "(upper(trim(issue_key)), workflow_id)",
      )
      statement.execute(
        "CREATE INDEX IF NOT EXISTS desktop_standalone_workflow_identity ON standalone_phase_status " +
          "(workflow_id, invocation_id)",
      )
      statement.execute(
        "CREATE INDEX IF NOT EXISTS desktop_execution_invocation_identity ON ide_status_workflow_execution " +
          "(invocation_id, workflow_id)",
      )
    }
    installWorkBoardChangeTriggers(connection)
  }
}

internal object WorkBoardReadModelAgentMigration {
  fun apply(connection: Connection) {
    connection.createStatement().use { statement ->
      statement.executeUpdate("UPDATE ${WorkBoardReadModelKeys.STATE_TABLE} SET ${WorkBoardReadModelKeys.COMPLETE} = 0")
    }
  }
}
