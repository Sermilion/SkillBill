package skillbill.infrastructure.sqlite.core.migration.area.monitor

import java.sql.Connection

internal const val MONITOR_REPAIR_VALIDATION_SNAPSHOTS_TABLE: String = "monitor_repair_validation_snapshots"

internal fun recordMonitorRepairValidationSnapshots(connection: Connection) {
  connection.createStatement().use { statement ->
    statement.execute(
      """
      CREATE TABLE IF NOT EXISTS $MONITOR_REPAIR_VALIDATION_SNAPSHOTS_TABLE (
        workflow_id TEXT NOT NULL,
        cycle INTEGER NOT NULL,
        phase_id TEXT NOT NULL,
        snapshot_json TEXT NOT NULL,
        PRIMARY KEY (workflow_id, cycle, phase_id)
      )
      """.trimIndent(),
    )
  }
}
