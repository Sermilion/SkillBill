package skillbill.infrastructure.sqlite.core.migration.area.blockincident

import java.sql.Connection

internal object BlockIncidentNullableGenerationMigration {
  fun apply(connection: Connection) {
    val incidentsTable = storedDefinition(connection, "table", INCIDENTS)
    val aliasesTable = storedDefinition(connection, "table", ALIASES)
    val dependents = dependentDefinitions(connection)
    check(incidentsTable.split(STRICT_GENERATION).size == 2) {
      "block_incidents.generation definition is not the expected contract 0.2 column."
    }
    val incidentColumns = columns(connection, INCIDENTS)
    val aliasColumns = columns(connection, ALIASES)
    connection.createStatement().use { statement ->
      statement.execute("ALTER TABLE $ALIASES RENAME TO ${ALIASES}_pre_nullable_generation")
      statement.execute("ALTER TABLE $INCIDENTS RENAME TO ${INCIDENTS}_pre_nullable_generation")
      statement.execute(incidentsTable.replace(STRICT_GENERATION, NULLABLE_GENERATION))
      statement.execute(aliasesTable)
      statement.execute(
        "INSERT INTO $INCIDENTS ($incidentColumns) " +
          "SELECT $incidentColumns FROM ${INCIDENTS}_pre_nullable_generation",
      )
      statement.execute(
        "INSERT INTO $ALIASES ($aliasColumns) SELECT $aliasColumns FROM ${ALIASES}_pre_nullable_generation",
      )
      statement.execute("DROP TABLE ${ALIASES}_pre_nullable_generation")
      statement.execute("DROP TABLE ${INCIDENTS}_pre_nullable_generation")
      dependents.forEach(statement::execute)
    }
  }

  private fun storedDefinition(
    connection: Connection,
    type: String,
    name: String,
  ): String =
    connection.prepareStatement("SELECT sql FROM sqlite_master WHERE type = ? AND name = ?").use { statement ->
      statement.setString(1, type)
      statement.setString(2, name)
      statement.executeQuery().use { rows -> if (rows.next()) rows.getString(1) else null }
    } ?: error("Stored definition for $type $name is missing.")

  private fun dependentDefinitions(connection: Connection): List<String> =
    connection.prepareStatement(
      "SELECT sql FROM sqlite_master WHERE type IN ('index', 'trigger') AND tbl_name IN (?, ?) " +
        "AND sql IS NOT NULL ORDER BY rowid",
    ).use { statement ->
      statement.setString(1, INCIDENTS)
      statement.setString(2, ALIASES)
      statement.executeQuery().use { rows -> buildList { while (rows.next()) add(rows.getString(1)) } }
    }

  private fun columns(
    connection: Connection,
    table: String,
  ): String =
    connection.createStatement().use { statement ->
      statement.executeQuery("PRAGMA table_info($table)").use { rows ->
        buildList { while (rows.next()) add(rows.getString("name")) }
      }
    }.joinToString(", ")

  private const val INCIDENTS = "block_incidents"
  private const val ALIASES = "block_incident_aliases"
  private const val STRICT_GENERATION = "generation INTEGER NOT NULL CHECK (generation > 0)"
  private const val NULLABLE_GENERATION = "generation INTEGER CHECK (generation IS NULL OR generation > 0)"
}
