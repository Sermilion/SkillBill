package dev.skillbill.tray.infrastructure.storage

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import skillbill.contracts.persistence.DesktopReadSchema
import skillbill.infrastructure.sqlite.establishTemporarySchemaReadiness
import java.sql.Connection
import kotlin.test.assertEquals
import kotlin.test.assertFails

class RoomRuntimeCompatibilityTest {
  @Test
  fun `Room reads runtime store without installing metadata or changing durable state`() = runBlocking {
    establishTemporarySchemaReadiness("desktop-room-compatibility").use { fixture ->
      val before = snapshot(fixture.connection)
      val room = openWorkRoomDatabase(fixture.dbPath)
      try {
        assertEquals(DesktopReadSchema.VERSION, room.migrations().latest()?.version)
      } finally {
        room.close()
      }
      assertEquals(before, snapshot(fixture.connection))
      ReadOnlyRoomDriver().open(fixture.dbPath.toString()).use { connection ->
        assertFails { connection.prepare("DELETE FROM schema_migrations").use { it.step() } }
      }
      assertEquals(before, snapshot(fixture.connection))
    }
  }

  private fun snapshot(connection: Connection): List<List<String?>> = listOf(
    "PRAGMA user_version",
    "SELECT type, name, sql FROM sqlite_master ORDER BY name",
    "SELECT * FROM schema_migrations ORDER BY version",
    "SELECT * FROM goal_issue_progress ORDER BY parent_workflow_id",
    "SELECT * FROM goal_runner_controls ORDER BY parent_workflow_id",
  ).flatMap { query ->
    connection.createStatement().use { statement ->
      statement.executeQuery(query).use { rows ->
        buildList {
          while (rows.next()) add((1..rows.metaData.columnCount).map(rows::getString))
        }
      }
    }
  }
}
