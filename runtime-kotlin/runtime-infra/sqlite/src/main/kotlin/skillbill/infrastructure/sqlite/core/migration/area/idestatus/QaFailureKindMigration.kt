package skillbill.infrastructure.sqlite.core.migration.area.idestatus

import skillbill.infrastructure.sqlite.core.schema.addQaOutcomeRevisionTriggers
import java.sql.Connection

internal const val QA_FAILURE_KIND_COLUMN: String = "failure_kind"

internal fun recordQaFailureKind(connection: Connection) {
  if (!hasQaFailureKindColumn(connection)) {
    connection.createStatement().use { statement ->
      statement.execute(
        "ALTER TABLE feature_task_qa_outcomes ADD COLUMN $QA_FAILURE_KIND_COLUMN TEXT " +
          "CHECK ($QA_FAILURE_KIND_COLUMN IS NULL OR $QA_FAILURE_KIND_COLUMN IN ('environment','defect'))",
      )
    }
  }
  addQaOutcomeRevisionTriggers(connection)
}

private fun hasQaFailureKindColumn(connection: Connection): Boolean =
  connection.prepareStatement(
    "SELECT 1 FROM pragma_table_info('feature_task_qa_outcomes') WHERE name = ?",
  ).use { statement ->
    statement.setString(1, QA_FAILURE_KIND_COLUMN)
    statement.executeQuery().use { rows -> rows.next() }
  }
