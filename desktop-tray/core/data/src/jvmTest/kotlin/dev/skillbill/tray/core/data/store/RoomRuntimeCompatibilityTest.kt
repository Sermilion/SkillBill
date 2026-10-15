package dev.skillbill.tray.core.data.store

import dev.skillbill.tray.core.data.RecordingDiagnostics
import dev.skillbill.tray.core.data.durableStoreSnapshot
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.sqlite.SQLiteConfig
import skillbill.contracts.persistence.DesktopReadSchema
import skillbill.infrastructure.sqlite.establishTemporarySchemaReadiness
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RoomRuntimeCompatibilityTest {
  @Test
  fun `Room reads runtime store without installing metadata or changing durable state`() = runBlocking {
    establishTemporarySchemaReadiness("desktop-room-compatibility").use { fixture ->
      val before = durableStoreSnapshot(fixture.connection)
      val room = openWorkRoomDatabase(fixture.dbPath, RecordingDiagnostics())
      try {
        assertEquals(DesktopReadSchema.VERSION, room.migrations().latest()?.version)
      } finally {
        room.close()
      }
      assertEquals(before, durableStoreSnapshot(fixture.connection))
      ReadOnlyRoomDriver(RecordingDiagnostics()).open(fixture.dbPath.toString()).use { connection ->
        assertFails { connection.prepare("DELETE FROM schema_migrations").use { it.step() } }
      }
      assertEquals(before, durableStoreSnapshot(fixture.connection))
    }
  }

  @Test
  fun `Room keeps reading after short lived runtime connections close`() = runBlocking {
    establishTemporarySchemaReadiness("desktop-room-shared-native-library").use { fixture ->
      fixture.connection.close()
      val room = openWorkRoomDatabase(fixture.dbPath, RecordingDiagnostics())
      try {
        repeat(30) {
          assertEquals(DesktopReadSchema.VERSION, room.migrations().latest()?.version)
          SQLiteConfig().createConnection("jdbc:sqlite:${fixture.dbPath}").use { writer ->
            writer.createStatement().use { it.execute("PRAGMA user_version = ${DesktopReadSchema.VERSION}") }
          }
        }
        assertEquals(DesktopReadSchema.VERSION, room.migrations().latest()?.version)
      } finally { room.close() }
    }
  }

  @Test
  fun `Room cached statements retain metadata and rebound values after reset`() {
    establishTemporarySchemaReadiness("desktop-room-cached-statement").use { fixture ->
      ReadOnlyRoomDriver(RecordingDiagnostics()).open(fixture.dbPath.toString()).use { connection ->
        connection.prepare("SELECT ? AS value, ? AS optional").use { statement ->
          assertEquals(2, statement.getColumnCount())
          assertEquals("value", statement.getColumnName(0))
          statement.bindLong(1, 41)
          statement.bindNull(2)
          assertTrue(statement.step())
          assertEquals(41L, statement.getLong(0))
          assertTrue(statement.isNull(1))
          assertFalse(statement.step())
          assertFalse(statement.step())
          statement.reset()
          statement.clearBindings()
          assertEquals(2, statement.getColumnCount())
          statement.bindLong(1, 42)
          statement.bindText(2, "updated")
          assertTrue(statement.step())
          assertEquals(42L, statement.getLong(0))
          assertEquals("updated", statement.getText(1))
        }
      }
    }
  }
}
