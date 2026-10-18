package skillbill.infrastructure.sqlite.core.migration.area.idestatus

import java.sql.Connection

internal const val QA_OUTCOME_COLUMN: String = "qa_outcome"

internal fun recordStandaloneQaOutcome(connection: Connection) {
  val exists =
    connection.prepareStatement(
      "SELECT 1 FROM pragma_table_info('standalone_phase_status') WHERE name = ?",
    ).use { statement ->
      statement.setString(1, QA_OUTCOME_COLUMN)
      statement.executeQuery().use { it.next() }
    }
  if (exists) return
  connection.createStatement().use { statement ->
    statement.execute(
      "ALTER TABLE standalone_phase_status ADD COLUMN $QA_OUTCOME_COLUMN TEXT " +
        "CHECK ($QA_OUTCOME_COLUMN IN ('attempted', 'passed', 'findings', 'skipped', 'incomplete'))",
    )
  }
}
