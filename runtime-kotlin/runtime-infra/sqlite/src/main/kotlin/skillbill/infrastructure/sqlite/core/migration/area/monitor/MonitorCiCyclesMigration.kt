package skillbill.infrastructure.sqlite.core.migration.area.monitor

import skillbill.infrastructure.sqlite.core.schema.installLaterDesktopChangeTriggers
import java.sql.Connection

internal const val MONITOR_CI_CYCLES_TABLE: String = "monitor_ci_cycles"

internal fun recordMonitorCiCycles(connection: Connection) {
  connection.createStatement().use { statement ->
    statement.execute(
      """
      CREATE TABLE IF NOT EXISTS $MONITOR_CI_CYCLES_TABLE (
        workflow_id TEXT NOT NULL,
        cycle INTEGER NOT NULL,
        phase_id TEXT NOT NULL,
        started_at TEXT NOT NULL,
        observed_at TEXT NOT NULL,
        finished_at TEXT,
        outcome TEXT,
        head_sha TEXT,
        pull_request_number INTEGER,
        pull_request_url TEXT,
        conflict_base_branch TEXT,
        repair_commit_sha TEXT,
        checks_json TEXT NOT NULL,
        PRIMARY KEY (workflow_id, cycle)
      )
      """.trimIndent(),
    )
  }
  installLaterDesktopChangeTriggers(connection, setOf(MONITOR_CI_CYCLES_TABLE))
}
