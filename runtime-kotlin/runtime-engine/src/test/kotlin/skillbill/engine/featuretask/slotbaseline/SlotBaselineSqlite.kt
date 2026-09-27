package skillbill.engine.featuretask.slotbaseline

import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.sql.ResultSet

internal object SlotBaselineSqlite {
  fun rows(
    databasePath: Path,
    table: String,
  ): List<Map<String, Any?>> =
    connect(databasePath) { connection ->
      connection.createStatement().use { statement ->
        statement.executeQuery("SELECT * FROM $table ORDER BY rowid").use(::readRows)
      }
    }

  fun tablesWithPrefix(
    databasePath: Path,
    prefix: String,
  ): Map<String, List<Map<String, Any?>>> =
    connect(databasePath) { connection ->
      connection.prepareStatement(
        "SELECT name FROM sqlite_master WHERE type = 'table' AND name LIKE ? ORDER BY name",
      ).use { statement ->
        statement.setString(1, "$prefix%")
        statement.executeQuery().use { result ->
          generateSequence { if (result.next()) result.getString(1) else null }.toList()
        }
      }
    }.associateWith { table -> rows(databasePath, table) }

  fun latestOutboxPayload(
    databasePath: Path,
    eventName: String,
  ): Any? =
    rows(databasePath, "telemetry_outbox")
      .lastOrNull { row -> row["event_name"] == eventName }
      ?.get("payload_json")
      ?: error("missing telemetry outbox event $eventName")

  private fun <T> connect(
    databasePath: Path,
    block: (Connection) -> T,
  ): T = DriverManager.getConnection("jdbc:sqlite:$databasePath").use(block)

  private fun readRows(result: ResultSet): List<Map<String, Any?>> {
    val columns = (1..result.metaData.columnCount).map(result.metaData::getColumnName)
    return generateSequence {
      if (!result.next()) return@generateSequence null
      columns.associateWith { column ->
        when (val value = result.getObject(column)) {
          is String -> SlotBaselineJson.parseEmbedded(value)
          else -> value
        }
      }
    }.toList()
  }
}
