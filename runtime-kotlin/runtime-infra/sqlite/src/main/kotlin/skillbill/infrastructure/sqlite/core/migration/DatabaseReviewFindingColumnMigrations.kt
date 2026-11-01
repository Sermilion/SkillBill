package skillbill.infrastructure.sqlite.core.migration

import java.sql.Connection

internal object DatabaseReviewFindingColumnMigrations {
  fun ensureFindingColumns(connection: Connection) {
    DatabaseColumnMigrations.ensureColumn(
      connection = connection,
      tableName = "findings",
      columnName = "issue_category",
      definition = "TEXT NOT NULL DEFAULT 'other'",
    )
    ensureFindingLaneColumns(connection)
  }

  fun ensureFindingLaneColumns(connection: Connection) {
    if (!DatabaseColumnMigrations.tableExists(connection, "findings")) return
    DatabaseColumnMigrations.ensureColumn(connection, "findings", "lane_skill_name", "TEXT")
    DatabaseColumnMigrations.ensureColumn(connection, "findings", "lane_area", "TEXT")
    DatabaseColumnMigrations.ensureColumn(connection, "findings", "lane_pack_slug", "TEXT")
    connection.createStatement().use { statement ->
      statement.execute(
        "CREATE INDEX IF NOT EXISTS idx_findings_lane ON findings(lane_skill_name, review_run_id)",
      )
    }
  }

  fun backfillReviewSessionIds(connection: Connection) {
    connection.prepareStatement(
      """
      UPDATE review_runs
      SET review_session_id = review_run_id
      WHERE review_session_id IS NULL OR review_session_id = ''
      """.trimIndent(),
    ).use { statement ->
      statement.executeUpdate()
    }
  }
}
