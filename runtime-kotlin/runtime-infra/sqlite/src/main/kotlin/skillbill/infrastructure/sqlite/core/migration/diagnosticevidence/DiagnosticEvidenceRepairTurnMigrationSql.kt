package skillbill.infrastructure.sqlite.core.migration.diagnosticevidence

import java.sql.Connection

internal fun rebuildRejectedOutputDiagnosticsForRepairTurn(connection: Connection) {
  connection.createStatement().use { statement ->
    statement.execute("DROP INDEX IF EXISTS idx_rejected_output_diagnostics_selector")
    statement.execute("ALTER TABLE rejected_output_diagnostics RENAME TO rejected_output_diagnostics_pre_repair_turn")
    statement.execute(REJECTED_OUTPUT_DIAGNOSTICS_REPAIR_TURN_DDL)
    statement.execute(REJECTED_OUTPUT_DIAGNOSTICS_REPAIR_TURN_INSERT)
    statement.execute("DROP TABLE rejected_output_diagnostics_pre_repair_turn")
    statement.execute(REJECTED_OUTPUT_DIAGNOSTICS_SELECTOR_INDEX)
    statement.execute(REJECTED_OUTPUT_DIAGNOSTICS_SELECTION_INDEX)
    statement.execute(REJECTED_OUTPUT_DIAGNOSTICS_RETENTION_INDEX)
  }
}

private const val REJECTED_OUTPUT_DIAGNOSTICS_REPAIR_TURN_DDL = """
CREATE TABLE IF NOT EXISTS rejected_output_diagnostics (
  identity TEXT PRIMARY KEY,
  workflow_id TEXT NOT NULL,
  phase_id TEXT NOT NULL,
  attempt INTEGER NOT NULL CHECK (attempt > 0),
  repair_turn INTEGER NOT NULL DEFAULT 0 CHECK (repair_turn >= 0),
  rule TEXT NOT NULL,
  rejection_path TEXT NOT NULL,
  reason TEXT NOT NULL,
  agent_id TEXT NOT NULL,
  model TEXT NOT NULL,
  recorded_at TEXT NOT NULL,
  byte_size INTEGER NOT NULL CHECK (byte_size >= 0),
  sha256 TEXT NOT NULL,
  lifecycle TEXT NOT NULL CHECK (lifecycle IN ('stored', 'oversized', 'expired')),
  payload BLOB,
  UNIQUE (workflow_id, phase_id, attempt, repair_turn),
  CHECK (
    (lifecycle = 'stored' AND payload IS NOT NULL) OR
    (lifecycle IN ('oversized', 'expired') AND payload IS NULL)
  )
)
"""

private const val REJECTED_OUTPUT_DIAGNOSTICS_REPAIR_TURN_INSERT = """
INSERT INTO rejected_output_diagnostics (
  identity, workflow_id, phase_id, attempt, repair_turn, rule, rejection_path, reason,
  agent_id, model, recorded_at, byte_size, sha256, lifecycle, payload
)
SELECT identity, workflow_id, phase_id, attempt, 0, rule, rejection_path, reason,
       agent_id, model, recorded_at, byte_size, sha256, lifecycle, payload
FROM rejected_output_diagnostics_pre_repair_turn
"""

private const val REJECTED_OUTPUT_DIAGNOSTICS_SELECTOR_INDEX = """
CREATE INDEX IF NOT EXISTS idx_rejected_output_diagnostics_selector
  ON rejected_output_diagnostics(workflow_id, phase_id, attempt, repair_turn)
"""

private const val REJECTED_OUTPUT_DIAGNOSTICS_SELECTION_INDEX =
  "CREATE INDEX IF NOT EXISTS idx_rejected_output_diagnostic_selection " +
    "ON rejected_output_diagnostics(workflow_id, phase_id, attempt)"

private const val REJECTED_OUTPUT_DIAGNOSTICS_RETENTION_INDEX =
  "CREATE INDEX IF NOT EXISTS idx_rejected_output_diagnostic_retention " +
    "ON rejected_output_diagnostics(lifecycle, recorded_at)"
