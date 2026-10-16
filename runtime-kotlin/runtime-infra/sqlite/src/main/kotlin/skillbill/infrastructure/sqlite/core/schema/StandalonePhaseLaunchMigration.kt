package skillbill.infrastructure.sqlite.core.schema

import skillbill.error.shellcontent.invalidIdeStatusSchemaError
import skillbill.idestatus.model.IdeStatusLaunchScope
import java.sql.Connection
import java.sql.ResultSet

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

internal const val LAUNCH_SCOPE_COLUMN: String = "launch_scope"

internal fun recordStandalonePhaseLaunchScope(connection: Connection) {
  val exists =
    connection.prepareStatement(
      "SELECT 1 FROM pragma_table_info('standalone_phase_status') WHERE name = ?",
    ).use { statement ->
      statement.setString(1, LAUNCH_SCOPE_COLUMN)
      statement.executeQuery().use { it.next() }
    }
  if (!exists) {
    val scopes = IdeStatusLaunchScope.entries.joinToString(", ") { "'${it.wireValue}'" }
    connection.createStatement().use { statement ->
      statement.execute(
        "ALTER TABLE standalone_phase_status ADD COLUMN $LAUNCH_SCOPE_COLUMN TEXT NOT NULL " +
          "DEFAULT '${IdeStatusLaunchScope.STANDALONE.wireValue}' CHECK ($LAUNCH_SCOPE_COLUMN IN ($scopes))",
      )
    }
  }
}

internal fun ResultSet.readLaunchScope(sourceLabel: String): IdeStatusLaunchScope {
  val raw = getString(LAUNCH_SCOPE_COLUMN)
  return raw?.let(IdeStatusLaunchScope::fromWire)
    ?: throw invalidIdeStatusSchemaError(sourceLabel, LAUNCH_SCOPE_COLUMN, "Invalid launch scope '$raw'.")
}
