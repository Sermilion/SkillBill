package skillbill.infrastructure.sqlite.core.migration.area

import skillbill.contracts.persistence.DesktopReadSchema
import skillbill.contracts.persistence.WorkBoardReadModelKeys
import skillbill.infrastructure.sqlite.idestatus.WorkBoardInvalidationKeys
import java.sql.Connection

internal object WorkBoardInvalidationsMigration {
  fun apply(connection: Connection) {
    connection.createStatement().use { statement ->
      statement.execute(
        "CREATE TABLE IF NOT EXISTS ${WorkBoardInvalidationKeys.TABLE} (" +
          "revision INTEGER PRIMARY KEY AUTOINCREMENT, work_id TEXT NOT NULL UNIQUE)",
      )
      statement.execute(
        """
        CREATE TRIGGER IF NOT EXISTS work_board_invalidate_source
        AFTER INSERT ON ${DesktopReadSchema.CHANGE_ITEMS_TABLE}
        WHEN NEW.topic IN ('${DesktopReadSchema.GOALS_TOPIC}', '${DesktopReadSchema.PHASES_TOPIC}',
          '${DesktopReadSchema.ACTIVITY_TOPIC}')
        BEGIN
          INSERT INTO ${WorkBoardInvalidationKeys.TABLE} (work_id) VALUES (NEW.work_id)
          ON CONFLICT(work_id) DO UPDATE SET revision = excluded.revision;
        END
        """.trimIndent(),
      )
      statement.execute(
        """
        INSERT OR IGNORE INTO ${WorkBoardInvalidationKeys.TABLE} (work_id)
        SELECT work_id FROM ${WorkBoardReadModelKeys.ITEMS_TABLE}
        UNION SELECT workflow_id FROM feature_task_workflows
        UNION SELECT workflow_id FROM feature_verify_workflows
        UNION SELECT parent_workflow_id FROM goal_issue_progress
        UNION SELECT invocation_id FROM standalone_phase_status
        UNION SELECT workflow_id FROM ide_status_workflow_execution WHERE workflow_id IS NOT NULL
        """.trimIndent(),
      )
      statement.executeUpdate("UPDATE ${WorkBoardReadModelKeys.STATE_TABLE} SET complete = 0")
    }
  }
}
