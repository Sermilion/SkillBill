package skillbill.infrastructure.sqlite.core.migration.area.idestatus.qadetails

import skillbill.contracts.qa.QA_PANEL_HISTORY_CONTRACT_VERSION
import skillbill.infrastructure.sqlite.core.migration.DatabaseColumnMigrations
import skillbill.infrastructure.sqlite.core.schema.reinstallStandalonePhaseStatusChangeTriggers
import java.sql.Connection

internal const val QA_DETAILS_COLUMN: String = "qa_details"

internal const val QA_DETAILS_ASSIGNMENT: String = "$QA_DETAILS_COLUMN = COALESCE(?, $QA_DETAILS_COLUMN)"

internal fun recordQaDetails(connection: Connection) {
  listOf("feature_task_qa_outcomes", "standalone_phase_status").forEach { table ->
    DatabaseColumnMigrations.ensureColumn(
      connection,
      table,
      QA_DETAILS_COLUMN,
      "TEXT CHECK ($QA_DETAILS_COLUMN IS NULL OR json_valid($QA_DETAILS_COLUMN))",
    )
  }
  reinstallStandalonePhaseStatusChangeTriggers(connection)
}

internal fun recordQaPanelOccurrences(connection: Connection) {
  connection.createStatement().use { statement ->
    statement.execute(
      """
      CREATE TABLE IF NOT EXISTS feature_task_qa_panel_occurrences (
        workflow_id TEXT NOT NULL,
        occurrence_id TEXT NOT NULL,
        contract_version TEXT NOT NULL CHECK (contract_version = '$QA_PANEL_HISTORY_CONTRACT_VERSION'),
        occurrence_json TEXT NOT NULL CHECK (json_valid(occurrence_json)),
        PRIMARY KEY (workflow_id, occurrence_id),
        FOREIGN KEY (workflow_id) REFERENCES feature_task_qa_outcomes(workflow_id) ON DELETE CASCADE
      )
      """.trimIndent(),
    )
    statement.execute(
      "CREATE INDEX IF NOT EXISTS idx_qa_panel_occurrence_workspace " +
        "ON feature_task_qa_panel_occurrences(" +
        "json_extract(occurrence_json, '$.${SqliteQaDetailsKeys.WORKSPACE_INVOCATION_ID}'))",
    )
    statement.execute(
      """
      INSERT OR IGNORE INTO feature_task_qa_panel_occurrences (
        workflow_id, occurrence_id, contract_version, occurrence_json
      )
      SELECT outcomes.workflow_id,
             json_extract(occurrence.value, '$.${SqliteQaDetailsKeys.OCCURRENCE_ID}'),
             '$QA_PANEL_HISTORY_CONTRACT_VERSION',
             occurrence.value
      FROM feature_task_qa_outcomes AS outcomes,
           json_each(
             CASE WHEN json_valid(outcomes.$QA_DETAILS_COLUMN)
               THEN outcomes.$QA_DETAILS_COLUMN ELSE '{}'
             END,
             '$.${SqliteQaDetailsKeys.OCCURRENCES}'
           ) AS occurrence
      WHERE json_type(
        CASE WHEN json_valid(outcomes.$QA_DETAILS_COLUMN)
          THEN outcomes.$QA_DETAILS_COLUMN ELSE '{}'
        END,
        '$.${SqliteQaDetailsKeys.OCCURRENCES}'
      ) = 'array'
        AND json_type(occurrence.value) = 'object'
        AND json_type(occurrence.value, '$.${SqliteQaDetailsKeys.OCCURRENCE_ID}') = 'text'
        AND json_extract(occurrence.value, '$.${SqliteQaDetailsKeys.OCCURRENCE_ID}') <> ''
      """.trimIndent(),
    )
  }
}
