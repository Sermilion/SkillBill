package skillbill.infrastructure.sqlite.core.schema

import skillbill.contracts.JsonCodec
import skillbill.error.core.MalformedJsonTextError
import skillbill.error.shellcontent.invalidIdeStatusSchemaError
import java.sql.Connection
import java.sql.ResultSet

internal const val AGENT_ENVIRONMENT_COLUMN: String = "agent_environment_json"

internal fun recordLaunchAgentEnvironment(connection: Connection) {
  listOf("standalone_phase_status", "ide_status_workflow_execution").forEach { table ->
    val exists =
      connection.prepareStatement(
        "SELECT 1 FROM pragma_table_info('$table') WHERE name = ?",
      ).use { statement ->
        statement.setString(1, AGENT_ENVIRONMENT_COLUMN)
        statement.executeQuery().use { it.next() }
      }
    if (!exists) {
      connection.createStatement().use { statement ->
        statement.execute("ALTER TABLE $table ADD COLUMN $AGENT_ENVIRONMENT_COLUMN TEXT")
      }
    }
  }
}

internal fun agentEnvironmentJson(environment: Map<String, String>): String? =
  environment.takeIf { it.isNotEmpty() }?.let(JsonCodec::valueToJsonString)

internal fun ResultSet.readAgentEnvironment(sourceLabel: String): Map<String, String> {
  val raw = getString(AGENT_ENVIRONMENT_COLUMN) ?: return emptyMap()
  val values =
    try {
      JsonCodec.parseValue(raw) as? Map<*, *>
    } catch (failure: MalformedJsonTextError) {
      throw invalidIdeStatusSchemaError(
        sourceLabel,
        AGENT_ENVIRONMENT_COLUMN,
        "Invalid agent environment: ${failure.message}",
        failure,
      )
    }
  if (values == null || values.any { (key, value) -> key !is String || value !is String }) {
    throw invalidIdeStatusSchemaError(sourceLabel, AGENT_ENVIRONMENT_COLUMN, "Invalid agent environment.")
  }
  return values.entries.associate { (key, value) -> key as String to value as String }
}
