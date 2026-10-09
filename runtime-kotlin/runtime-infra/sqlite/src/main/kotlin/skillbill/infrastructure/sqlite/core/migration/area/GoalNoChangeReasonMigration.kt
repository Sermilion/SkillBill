package skillbill.infrastructure.sqlite.core.migration.area

import skillbill.infrastructure.sqlite.core.migration.DatabaseColumnMigrations
import java.sql.Connection

internal object GoalNoChangeReasonMigration {
  fun apply(connection: Connection) {
    TABLES
      .filter { table -> DatabaseColumnMigrations.tableExists(connection, table) }
      .forEach { table -> DatabaseColumnMigrations.ensureColumn(connection, table, COLUMN, "TEXT") }
  }

  private val TABLES = listOf("goal_run_sessions", "goal_issue_progress")
  private const val COLUMN = "no_change_reason"
}
