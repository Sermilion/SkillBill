package skillbill.infrastructure.sqlite.core.schema

import java.sql.Connection

internal fun saveStandalonePhaseLaunch(connection: Connection) {
  listOf("launch_agent_id", "launch_arguments_json").forEach { column ->
    val exists =
      connection.prepareStatement(
        "SELECT 1 FROM pragma_table_info('standalone_phase_status') WHERE name = ?",
      ).use { statement ->
        statement.setString(1, column)
        statement.executeQuery().use { it.next() }
      }
    if (!exists) {
      connection.createStatement().use { statement ->
        statement.execute("ALTER TABLE standalone_phase_status ADD COLUMN $column TEXT")
      }
    }
  }
}
