package skillbill.infrastructure.sqlite.core.migration.diagnosticevidence

import skillbill.infrastructure.sqlite.core.ops.bindAll
import java.sql.Connection

internal fun rekeyDiagnosticEvidenceByRepairTurn(connection: Connection) {
  rekeyProducerOutputEvidenceByRepairTurn(connection)
  rekeyRejectedOutputDiagnosticsByRepairTurn(connection)
}

private fun rekeyProducerOutputEvidenceByRepairTurn(connection: Connection) {
  if (tableDdlMentions(connection, "producer_output_evidence", "repair_turn")) return
  connection.createStatement().use {
    it.execute("ALTER TABLE producer_output_evidence RENAME TO producer_output_evidence_pre_repair_turn")
    it.execute(
      """
      CREATE TABLE IF NOT EXISTS producer_output_evidence (
        workflow_id TEXT NOT NULL, phase_id TEXT NOT NULL,
        generation INTEGER NOT NULL DEFAULT 0 CHECK (generation >= 0),
        attempt INTEGER NOT NULL CHECK (attempt > 0),
        repair_turn INTEGER NOT NULL DEFAULT 0 CHECK (repair_turn >= 0),
        agent_id TEXT NOT NULL, model TEXT NOT NULL, recorded_at TEXT NOT NULL,
        byte_size INTEGER NOT NULL CHECK (byte_size >= 0), sha256 TEXT NOT NULL, payload BLOB,
        PRIMARY KEY (workflow_id, phase_id, generation, attempt, repair_turn, agent_id)
      )
      """.trimIndent(),
    )
    it.execute(
      """
      INSERT INTO producer_output_evidence (
        workflow_id, phase_id, generation, attempt, repair_turn, agent_id, model, recorded_at,
        byte_size, sha256, payload
      )
      SELECT workflow_id, phase_id, generation, attempt, 0, agent_id, model, recorded_at,
             byte_size, sha256, payload
      FROM producer_output_evidence_pre_repair_turn
      """.trimIndent(),
    )
    it.execute("DROP TABLE producer_output_evidence_pre_repair_turn")
  }
}

private fun rekeyRejectedOutputDiagnosticsByRepairTurn(connection: Connection) {
  if (tableDdlMentions(connection, "rejected_output_diagnostics", "repair_turn")) return
  rebuildRejectedOutputDiagnosticsForRepairTurn(connection)
}

private fun tableDdlMentions(
  connection: Connection,
  table: String,
  column: String,
): Boolean {
  val ddl =
    connection.prepareStatement(
      "SELECT sql FROM sqlite_master WHERE type = 'table' AND name = ?",
    ).use { statement ->
      statement.bindAll(table)
      statement.executeQuery().use { rows ->

        if (!rows.next()) return true
        rows.getString("sql").orEmpty()
      }
    }
  return Regex("""\b$column\b""", RegexOption.IGNORE_CASE).containsMatchIn(ddl)
}
