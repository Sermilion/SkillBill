package skillbill.infrastructure.sqlite

import skillbill.contracts.telemetry.TelemetryOutboxEvent
import skillbill.infrastructure.sqlite.telemetry.outbox.TelemetryOutboxStore
import skillbill.ports.telemetry.model.TELEMETRY_DELIVERY_ATTEMPT_BUDGET
import skillbill.ports.telemetry.model.TelemetryOutboxClaimRequest
import java.nio.file.Files
import java.sql.Connection
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TelemetryOutboxStaleSettlementTest {
  @Test
  fun `a stale owner cannot settle any outcome after another drain reclaims the rows`() {
    withOutbox { connection, store ->
      val ids = List(3) { store.enqueue(event = TelemetryOutboxEvent.GOAL_FINISHED, payloadJson = "{}") }
      val t = Instant.parse("2026-09-15T10:00:00Z")

      val a = store.claimPending(claim("A", t, t.minusSeconds(300), limit = 10))
      val b = store.claimPending(claim("B", t.plusSeconds(301), t.plusSeconds(1), limit = 10))
      assertEquals(ids, a.map { it.id })
      assertEquals(ids, b.map { it.id })

      val beforeLateSettlement = ids.associateWith { rowState(connection, it) }
      val synced = store.markSynced(listOf(ids[0]), "A")
      val failed = store.markFailed(listOf(ids[1]), "A", "late sender A")
      val unconfirmed = store.markUnconfirmed(listOf(ids[2]), "A", "late sender A")
      assertTrue(synced.lostClaim)
      assertTrue(failed.lostClaim)
      assertTrue(unconfirmed.lostClaim)
      assertEquals(
        beforeLateSettlement,
        ids.associateWith { rowState(connection, it) },
        "A late settlement must leave every B-owned field unchanged.",
      )

      val third = store.claimPending(claim("C", t.plusSeconds(302), t.plusSeconds(2), limit = 10))
      assertTrue(third.isEmpty(), "A live B claim must block a third claimant.")
    }
  }

  @Test
  fun `late failure cannot mutate an already acknowledged row`() {
    withOutbox { connection, store ->
      val id = store.enqueue(event = TelemetryOutboxEvent.GOAL_FINISHED, payloadJson = "{}")
      val t = Instant.parse("2026-09-15T10:00:00Z")
      store.claimPending(claim("A", t, t.minusSeconds(300)))
      store.claimPending(claim("B", t.plusSeconds(301), t.plusSeconds(1)))
      assertTrue(!store.markSynced(listOf(id), "B").lostClaim)

      val staleFailure = store.markFailed(listOf(id), "A", "late sender A")
      assertTrue(staleFailure.lostClaim)
      assertEquals(0, deliveryAttempts(connection))
      assertEquals(null, lastError(connection))
      assertTrue(syncedAt(connection) != null)
    }
  }

  @Test
  fun `late sync cannot clear a row owned by another sender`() {
    withOutbox { connection, store ->
      val id = store.enqueue(event = TelemetryOutboxEvent.GOAL_FINISHED, payloadJson = "{}")
      val t = Instant.parse("2026-09-15T10:00:00Z")
      store.claimPending(claim("A", t, t.minusSeconds(300)))
      store.claimPending(claim("B", t.plusSeconds(301), t.plusSeconds(1)))

      val staleSync = store.markSynced(listOf(id), "A")
      assertTrue(staleSync.lostClaim)
      assertEquals("B", claimToken(connection))
      assertEquals(null, syncedAt(connection))
    }
  }

  @Test
  fun `partial stale settlement reports the lost portion of a batch`() {
    withOutbox { connection, store ->
      val ids = List(2) { store.enqueue(event = TelemetryOutboxEvent.GOAL_FINISHED, payloadJson = "{}") }
      val t = Instant.parse("2026-09-15T10:00:00Z")
      store.claimPending(claim("A", t, t.minusSeconds(300), limit = 10))
      store.claimPending(claim("B", t.plusSeconds(301), t.plusSeconds(1), limit = 1))
      val bState = rowState(connection, ids[0])

      val settlement = store.markSynced(ids, "A")

      assertTrue(settlement.lostClaim)
      assertEquals(bState, rowState(connection, ids[0]))
      assertTrue(rowState(connection, ids[1]).syncedAt != null)
    }
  }

  private fun claim(
    token: String,
    claimedAt: Instant,
    reclaimBefore: Instant,
    limit: Int = 1,
  ): TelemetryOutboxClaimRequest =
    TelemetryOutboxClaimRequest(
      claimToken = token,
      limit = limit,
      claimedAt = claimedAt,
      reclaimBefore = reclaimBefore,
      attemptBudget = TELEMETRY_DELIVERY_ATTEMPT_BUDGET,
    )

  private fun claimToken(connection: Connection): String? =
    connection.createStatement().use { statement ->
      statement.executeQuery("SELECT claim_token FROM telemetry_outbox").use { rows ->
        check(rows.next())
        rows.getString(1)
      }
    }

  private fun lastError(connection: Connection): String? =
    connection.createStatement().use { statement ->
      statement.executeQuery("SELECT last_error FROM telemetry_outbox").use { rows ->
        check(rows.next())
        rows.getString(1)
      }
    }

  private fun deliveryAttempts(connection: Connection): Int =
    connection.createStatement().use { statement ->
      statement.executeQuery("SELECT delivery_attempts FROM telemetry_outbox").use { rows ->
        check(rows.next())
        rows.getInt(1)
      }
    }

  private fun syncedAt(connection: Connection): String? =
    connection.createStatement().use { statement ->
      statement.executeQuery("SELECT synced_at FROM telemetry_outbox").use { rows ->
        check(rows.next())
        rows.getString(1)
      }
    }

  private fun rowState(
    connection: Connection,
    id: Long,
  ): RowState =
    connection.prepareStatement(
      "SELECT claim_token, claimed_at, last_error, delivery_attempts, synced_at FROM telemetry_outbox WHERE id = ?",
    ).use { statement ->
      statement.setLong(1, id)
      statement.executeQuery().use { rows ->
        check(rows.next())
        RowState(
          claimToken = rows.getString("claim_token"),
          claimedAt = rows.getString("claimed_at"),
          lastError = rows.getString("last_error"),
          deliveryAttempts = rows.getInt("delivery_attempts"),
          syncedAt = rows.getString("synced_at"),
        )
      }
    }

  private fun withOutbox(block: (Connection, TelemetryOutboxStore) -> Unit) {
    val dbPath = Files.createTempDirectory("telemetry-stale-settlement").resolve("metrics.db")
    DatabaseRuntime.ensureDatabase(dbPath).use { connection ->
      block(connection, TelemetryOutboxStore(connection, version = "test-runtime-version"))
    }
  }
}

private data class RowState(
  val claimToken: String?,
  val claimedAt: String?,
  val lastError: String?,
  val deliveryAttempts: Int,
  val syncedAt: String?,
)
