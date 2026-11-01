package skillbill.infrastructure.sqlite

import skillbill.contracts.telemetry.TelemetryOutboxEvent
import skillbill.infrastructure.sqlite.core.migration.TelemetryOutboxDeliveryIdentityMigration
import skillbill.infrastructure.sqlite.telemetry.outbox.TelemetryOutboxStore
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TelemetryOutboxDeliveryIdentityMigrationTest {
  @Test
  fun `legacy pending rows get an identity once and every unsynced row survives`() {
    val dbPath = newDatabase()
    DatabaseRuntime.ensureDatabase(dbPath).use { connection ->
      val store = TelemetryOutboxStore(connection, version = "test-runtime-version")
      store.enqueue(event = TelemetryOutboxEvent.GOAL_FINISHED, payloadJson = """{"i":1}""")
      store.enqueue(event = TelemetryOutboxEvent.REVIEW_FINISHED, payloadJson = """{"i":2}""")
      val synced = store.enqueue(event = TelemetryOutboxEvent.GOAL_FINISHED, payloadJson = """{"i":3}""")
      store.markSynced(id = synced, syncedAt = "2026-09-01 00:00:00")
      stripIdentities(connection)

      TelemetryOutboxDeliveryIdentityMigration.apply(connection)
      val afterFirstPass = identities(connection)

      assertEquals(2, afterFirstPass.size, "Every unsynced row must carry an identity.")
      assertEquals(2, afterFirstPass.values.toSet().size, "Two rows must not share one identity.")
      assertTrue(afterFirstPass.values.none { it.isBlank() })

      TelemetryOutboxDeliveryIdentityMigration.apply(connection)

      assertEquals(afterFirstPass, identities(connection), "A repeated open must not re-mint.")
      assertEquals(3, rowCount(connection), "The backfill must not drop a row.")
    }
  }

  @Test
  fun `reopening the database preserves a pending row identity`() {
    val dbPath = newDatabase()
    val minted =
      DatabaseRuntime.ensureDatabase(dbPath).use { connection ->
        val store = TelemetryOutboxStore(connection, version = "test-runtime-version")
        store.enqueue(event = TelemetryOutboxEvent.GOAL_FINISHED, payloadJson = "{}")
        store.listPending().single().eventUuid
      }
    assertTrue(minted.isNotBlank(), "Enqueue must mint an identity in the same write.")

    val afterRestart =
      DatabaseRuntime.ensureDatabase(dbPath).use { connection ->
        TelemetryOutboxStore(connection, version = "test-runtime-version").listPending().single().eventUuid
      }

    assertEquals(minted, afterRestart, "A restart must not re-mint a pending row's identity.")
  }

  @Test
  fun `two separate database files do not produce colliding identities`() {
    val identities = List(2) { backfilledIdentity() }

    assertEquals(2, identities.toSet().size, "Identities must be isolated across database files.")
  }

  @Test
  fun `a cleared queue leaves nothing for the migration to reconstruct`() {
    val dbPath = newDatabase()
    DatabaseRuntime.ensureDatabase(dbPath).use { connection ->
      val store = TelemetryOutboxStore(connection, version = "test-runtime-version")
      store.enqueue(event = TelemetryOutboxEvent.GOAL_FINISHED, payloadJson = "{}")
      store.enqueue(event = TelemetryOutboxEvent.REVIEW_FINISHED, payloadJson = "{}")

      assertEquals(2, store.clear())
      TelemetryOutboxDeliveryIdentityMigration.apply(connection)

      assertEquals(0, rowCount(connection), "A discarded event must not be resurrected.")
      assertEquals(0, store.pendingCount())
    }
  }

  private fun backfilledIdentity(): String {
    val dbPath = newDatabase()
    return DatabaseRuntime.ensureDatabase(dbPath).use { connection ->
      TelemetryOutboxStore(connection, version = "test-runtime-version").enqueue(
        event = TelemetryOutboxEvent.GOAL_FINISHED,
        payloadJson = "{}",
      )
      stripIdentities(connection)
      TelemetryOutboxDeliveryIdentityMigration.apply(connection)
      identities(connection).values.single()
    }
  }

  private fun newDatabase(): Path = Files.createTempDirectory("runtime-kotlin-outbox-identity").resolve("metrics.db")

  private fun stripIdentities(connection: Connection) {
    connection.createStatement().use { statement ->
      statement.executeUpdate("UPDATE telemetry_outbox SET event_uuid = NULL")
    }
  }

  private fun identities(connection: Connection): Map<Long, String> =
    connection.createStatement().use { statement ->
      statement.executeQuery(
        "SELECT id, event_uuid FROM telemetry_outbox WHERE synced_at IS NULL ORDER BY id",
      ).use { rows ->
        buildMap {
          while (rows.next()) {
            put(rows.getLong("id"), rows.getString("event_uuid").orEmpty())
          }
        }
      }
    }

  private fun rowCount(connection: Connection): Int =
    connection.createStatement().use { statement ->
      statement.executeQuery("SELECT COUNT(*) FROM telemetry_outbox").use { rows ->
        check(rows.next())
        rows.getInt(1)
      }
    }
}
