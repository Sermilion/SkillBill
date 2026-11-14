package skillbill.infrastructure.sqlite.config.projectchecks

import java.sql.Connection

internal fun createProjectCheckConfigTable(connection: Connection) {
  connection.createStatement().use { statement ->
    statement.execute(
      "CREATE TABLE IF NOT EXISTS project_config (" +
        "project_id TEXT PRIMARY KEY NOT NULL, contract_version TEXT NOT NULL, " +
        "build_commands TEXT, lint_commands TEXT, test_commands TEXT, " +
        "formatting_commands TEXT, validation_commands TEXT)",
    )
  }
}
