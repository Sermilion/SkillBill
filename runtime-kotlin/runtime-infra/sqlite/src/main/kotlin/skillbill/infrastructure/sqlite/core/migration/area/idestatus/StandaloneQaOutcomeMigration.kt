package skillbill.infrastructure.sqlite.core.migration.area.idestatus

import skillbill.error.shellcontent.invalidIdeStatusSchemaError
import skillbill.ports.idestatus.model.StandaloneQaOutcome
import java.sql.Connection
import java.sql.ResultSet

internal const val QA_OUTCOME_COLUMN: String = "qa_outcome"

internal const val QA_OUTCOME_ASSIGNMENT: String = "$QA_OUTCOME_COLUMN = COALESCE(?, $QA_OUTCOME_COLUMN)"

internal fun recordStandaloneQaOutcome(connection: Connection) {
  val exists =
    connection.prepareStatement(
      "SELECT 1 FROM pragma_table_info('standalone_phase_status') WHERE name = ?",
    ).use { statement ->
      statement.setString(1, QA_OUTCOME_COLUMN)
      statement.executeQuery().use { it.next() }
    }
  if (exists) return
  val allowed = StandaloneQaOutcome.entries.joinToString(", ") { "'${it.wireValue}'" }
  connection.createStatement().use { statement ->
    statement.execute(
      "ALTER TABLE standalone_phase_status ADD COLUMN $QA_OUTCOME_COLUMN TEXT CHECK ($QA_OUTCOME_COLUMN IN ($allowed))",
    )
  }
}

internal fun ResultSet.readQaOutcome(sourceLabel: String): StandaloneQaOutcome? {
  val raw = getString(QA_OUTCOME_COLUMN) ?: return null
  return StandaloneQaOutcome.fromWire(raw)
    ?: throw invalidIdeStatusSchemaError(sourceLabel, QA_OUTCOME_COLUMN, "Unknown QA outcome '$raw'.")
}

internal fun recordFeatureTaskQaOutcomes(connection: Connection) {
  connection.createStatement().use { statement ->
    statement.execute(
      """
      CREATE TABLE IF NOT EXISTS feature_task_qa_outcomes (
        workflow_id TEXT PRIMARY KEY,
        outcome TEXT NOT NULL CHECK (outcome IN ('attempted', 'passed', 'findings', 'skipped', 'incomplete')),
        reason TEXT CHECK (reason IS NULL OR reason <> ''),
        qa_fix_attempts INTEGER NOT NULL CHECK (qa_fix_attempts BETWEEN 0 AND 3),
        tested_tree_identity TEXT,
        recorded_at TEXT NOT NULL
      )
      """.trimIndent(),
    )
  }
}
