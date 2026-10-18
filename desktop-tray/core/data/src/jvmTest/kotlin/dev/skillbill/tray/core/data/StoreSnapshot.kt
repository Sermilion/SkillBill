package dev.skillbill.tray.core.data

import java.sql.Connection

/**
 * Everything durable the desktop could damage by writing: the schema, its version, the applied migrations, and the
 * workflow, lease and control rows. Two equal snapshots mean nothing was migrated, repaired or written between them.
 */
fun durableStoreSnapshot(connection: Connection): List<List<String?>> = listOf(
  "PRAGMA user_version",
  "SELECT type, name, sql FROM sqlite_master ORDER BY name",
  "SELECT * FROM schema_migrations ORDER BY version",
  "SELECT * FROM goal_issue_progress ORDER BY parent_workflow_id",
  "SELECT * FROM goal_runner_controls ORDER BY parent_workflow_id",
  "SELECT * FROM standalone_phase_status ORDER BY invocation_id",
  "SELECT * FROM feature_verify_workflows ORDER BY workflow_id",
).flatMap { query ->
  connection.createStatement().use { statement ->
    statement.executeQuery(query).use { rows ->
      buildList {
        while (rows.next()) add((1..rows.metaData.columnCount).map(rows::getString))
      }
    }
  }
}
