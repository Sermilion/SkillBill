package skillbill.infrastructure.sqlite

import skillbill.contracts.telemetry.TelemetryOutboxEvent
import skillbill.infrastructure.sqlite.telemetry.outbox.TelemetryOutboxStore
import skillbill.ports.telemetry.model.TELEMETRY_DELIVERY_ATTEMPT_BUDGET
import skillbill.ports.telemetry.model.TelemetryOutboxClaimRequest
import skillbill.telemetry.model.GoalStartedRecord
import java.nio.file.Files
import java.sql.Connection
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class TelemetryOutboxStoreTest {
  @Test
  fun `telemetry outbox tracks pending rows until they are marked synced`() {
    val dbPath = Files.createTempDirectory("runtime-kotlin-db-outbox").resolve("metrics.db")

    DatabaseRuntime.ensureDatabase(dbPath).use { connection ->
      val store = TelemetryOutboxStore(connection, version = "test-runtime-version")

      val firstId = store.enqueue(event = TelemetryOutboxEvent.GOAL_STARTED, payloadJson = """{"id":"1"}""")
      val secondId = store.enqueue(event = TelemetryOutboxEvent.FEATURE_VERIFY_STARTED, payloadJson = """{"id":"2"}""")

      assertEquals(2, store.pendingCount())
      assertEquals(listOf(firstId, secondId), store.listPending().map { it.id })

      val token = "drain-test"
      store.claimPending(claimRequest(token, limit = 10))
      store.markFailed(listOf(firstId), token, "connection refused")
      assertEquals("connection refused", store.listPending().first().lastError)

      store.markSynced(id = firstId, syncedAt = "2026-04-23 00:00:00")

      val pendingRows = store.listPending()
      assertEquals(1, store.pendingCount())
      assertEquals(listOf(secondId), pendingRows.map { it.id })
      assertTrue(pendingRows.all { it.syncedAt == null })

      assertEquals(2, store.clear())
      assertEquals(0, store.pendingCount())
    }
  }

  @Test
  fun `marking an event synced clears last_error to SQL NULL`() {
    withOutbox { connection, store ->
      val id = store.enqueue(event = TelemetryOutboxEvent.REVIEW_FINISHED, payloadJson = "{}")
      val token = "error-test"
      store.claimPending(claimRequest(token, limit = 10))
      store.markFailed(listOf(id), token, "connection refused")
      assertEquals("connection refused", store.latestError())

      store.markSynced(id = id, syncedAt = "2026-04-23 00:00:00")

      assertEquals(
        0,
        countWhere(connection, "last_error IS NOT NULL"),
        "A delivered event must leave no error signal behind at all.",
      )
      assertEquals(null, store.latestError(), "A drained outbox must report no delivery error.")
    }
  }

  @Test
  fun `latestError reports a real failure while healthy rows stay NULL`() {
    withOutbox { connection, store ->
      val healthy = store.enqueue(event = TelemetryOutboxEvent.GOAL_FINISHED, payloadJson = "{}")
      val failed = store.enqueue(event = TelemetryOutboxEvent.REVIEW_FINISHED, payloadJson = "{}")
      val token = "latest-error"
      store.claimPending(claimRequest(token, limit = 10))
      store.markFailed(listOf(failed), token, "boom")

      assertEquals("boom", store.latestError())
      assertEquals(2, store.pendingCount(), "Recording a failure must not retire either pending row.")
      assertEquals(
        1,
        countWhere(connection, "id = $healthy AND last_error IS NULL"),
        "The untouched row must stay NULL rather than being reclassified as failed.",
      )
      assertEquals("", store.listPending().single { it.id == healthy }.lastError)
    }
  }

  @Test
  fun `latestError ignores legacy empty-string rows`() {
    withOutbox { connection, store ->
      store.enqueue(event = TelemetryOutboxEvent.GOAL_FINISHED, payloadJson = "{}")
      connection.createStatement().use { statement ->
        statement.executeUpdate("UPDATE telemetry_outbox SET last_error = ''")
      }

      assertEquals(null, store.latestError(), "A legacy empty-string row is healthy, not a delivery failure.")
    }
  }

  @Test
  fun `the outbox drains fully after every row is marked synced`() {
    withOutbox { _, store ->
      val ids =
        List(3) { index -> store.enqueue(event = TelemetryOutboxEvent.GOAL_FINISHED, payloadJson = """{"i":$index}""") }
      val token = "drain-all"
      store.claimPending(claimRequest(token, limit = 10))
      store.markFailed(ids, token, "transient")
      store.claimPending(claimRequest(token, limit = 10))
      store.markSynced(ids, token)

      assertTrue(store.listPending().isEmpty(), "Every synced row must leave the pending set.")
      assertEquals(0, store.pendingCount())
      assertEquals(null, store.latestError(), "A fully drained outbox reports no error.")
    }
  }

  @Test
  fun `enqueue records the version supplied by the test outbox fixture`() {
    withOutbox { connection, store ->
      val id = store.enqueue(event = TelemetryOutboxEvent.GOAL_FINISHED, payloadJson = "{}")

      assertEquals(
        "test-runtime-version",
        scalarString(connection, "SELECT skill_bill_version FROM telemetry_outbox WHERE id = $id"),
      )
    }
  }

  @Test
  fun `enqueue records the store's injected version and listPending surfaces it`() {
    val dbPath = Files.createTempDirectory("runtime-kotlin-db-outbox-version").resolve("metrics.db")
    DatabaseRuntime.ensureDatabase(dbPath).use { connection ->
      val store = TelemetryOutboxStore(connection, version = "9.9.9-injected")

      val id = store.enqueue(event = TelemetryOutboxEvent.GOAL_FINISHED, payloadJson = "{}")

      assertEquals(
        "9.9.9-injected",
        scalarString(connection, "SELECT skill_bill_version FROM telemetry_outbox WHERE id = $id"),
      )
      assertEquals("9.9.9-injected", store.listPending().single { it.id == id }.skillBillVersion)
    }
  }

  @Test
  fun `production session factory carries its injected version into repository outbox rows`() {
    val root = Files.createTempDirectory("runtime-kotlin-db-factory-version")
    val database =
      sqliteDatabaseSessionFactory(
        userHome = root,
        environment = emptyMap(),
        runtimeVersion = "factory-injected-version",
      )

    database.transaction { unit ->
      unit.telemetryOutbox.enqueue(TelemetryOutboxEvent.GOAL_FINISHED, "{}")
      unit.lifecycleTelemetry.goalStarted(
        GoalStartedRecord(
          issueKey = "SKILL-372",
          featureName = "version injection",
          workflowId = "workflow-version",
          subtaskTotal = 1,
          resumed = false,
          startedAt = "2026-09-24T00:00:00Z",
          status = "running",
          mode = "runtime",
        ),
        "anonymous",
      )
    }

    val claimedRows =
      database.transaction { unit ->
        unit.telemetryOutbox.claimPending(claimRequest("factory-version", limit = 10))
      }
    assertEquals(
      setOf("factory-injected-version"),
      claimedRows.map { it.skillBillVersion }.toSet(),
    )
  }

  @Test
  fun `lastSyncedAt is null until a row is marked synced`() {
    withOutbox { _, store ->
      val id = store.enqueue(event = TelemetryOutboxEvent.GOAL_FINISHED, payloadJson = "{}")
      store.enqueue(event = TelemetryOutboxEvent.REVIEW_FINISHED, payloadJson = "{}")

      assertEquals(null, store.lastSyncedAt(), "An outbox that never delivered has no successful sync timestamp.")

      val token = "last-synced"
      store.claimPending(claimRequest(token, limit = 10))
      store.markSynced(listOf(id), token)

      assertNotNull(store.lastSyncedAt(), "A delivered row must expose a successful sync timestamp.")
      assertEquals(1, store.pendingCount(), "The still-pending row must remain queued alongside the sync timestamp.")
    }
  }

  @Test
  fun `identical payloads enqueued at the same timestamp get distinct identities`() {
    withOutbox { connection, store ->
      val first = store.enqueue(event = TelemetryOutboxEvent.GOAL_FINISHED, payloadJson = """{"same":"payload"}""")
      val second = store.enqueue(event = TelemetryOutboxEvent.GOAL_FINISHED, payloadJson = """{"same":"payload"}""")
      connection.createStatement().use { statement ->
        statement.executeUpdate("UPDATE telemetry_outbox SET created_at = '2026-04-23 00:00:00'")
      }

      val identities = store.listPending().associate { it.id to it.eventUuid }

      assertTrue(identities.getValue(first).isNotBlank())
      assertNotEquals(
        identities.getValue(first),
        identities.getValue(second),
        "Two real attempts must stay two logical events.",
      )
    }
  }

  @Test
  fun `an identity survives a payload rewrite after enqueue`() {
    withOutbox { connection, store ->
      val id = store.enqueue(event = TelemetryOutboxEvent.REVIEW_FINISHED, payloadJson = """{"v":"1"}""")
      val minted = store.listPending().single().eventUuid

      connection.prepareStatement("UPDATE telemetry_outbox SET payload_json = ? WHERE id = ?").use { statement ->
        statement.setString(1, """{"v":"2","regenerated":true}""")
        statement.setLong(2, id)
        statement.executeUpdate()
      }

      assertEquals(minted, store.listPending().single().eventUuid)
    }
  }

  @Test
  fun `concurrent drainers claim disjoint rows and lose none`() {
    val dbPath = Files.createTempDirectory("runtime-kotlin-db-outbox-claim").resolve("metrics.db")
    DatabaseRuntime.ensureDatabase(dbPath).use { seed ->
      repeat(4) { index ->
        TelemetryOutboxStore(seed, version = "test-runtime-version").enqueue(
          event = TelemetryOutboxEvent.GOAL_FINISHED,
          payloadJson = """{"i":$index}""",
        )
      }
    }

    DatabaseRuntime.ensureDatabase(dbPath).use { first ->
      DatabaseRuntime.ensureDatabase(dbPath).use { second ->
        val firstClaim =
          TelemetryOutboxStore(first, version = "test-runtime-version")
            .claimPending(claimRequest("drainer-a", limit = 2))
        val secondClaim =
          TelemetryOutboxStore(second, version = "test-runtime-version")
            .claimPending(claimRequest("drainer-b", limit = 2))

        val firstIds = firstClaim.map { it.id }
        val secondIds = secondClaim.map { it.id }
        assertTrue(firstIds.intersect(secondIds.toSet()).isEmpty(), "No row may be claimed by both drainers.")
        assertEquals(
          setOf(1L, 2L, 3L, 4L),
          (firstIds + secondIds).toSet(),
          "Partitioning the queue must not drop a queued record.",
        )
      }
    }
  }

  @Test
  fun `an expired claim is reclaimable so a crashed drainer strands nothing`() {
    withOutbox { _, store ->
      store.enqueue(event = TelemetryOutboxEvent.GOAL_FINISHED, payloadJson = "{}")
      val abandoned = store.claimPending(claimRequest("crashed-drainer", limit = 10))
      assertEquals(1, abandoned.size)

      val stillHeld = store.claimPending(claimRequest("fresh-drainer", limit = 10))
      assertTrue(stillHeld.isEmpty(), "A live claim must not be stolen.")

      val reclaimed =
        store.claimPending(
          claimRequest("fresh-drainer", limit = 10, reclaimBefore = Instant.parse("2026-09-15T11:00:00Z")),
        )

      assertEquals(listOf(1L), reclaimed.map { it.id })
      assertEquals(
        abandoned.single().eventUuid,
        reclaimed.single().eventUuid,
        "Reclaiming must preserve the original identity, not mint a new one.",
      )
    }
  }

  @Test
  fun `a row past the attempt budget stops being claimed and reports as blocked`() {
    withOutbox { _, store ->
      val id = store.enqueue(event = TelemetryOutboxEvent.GOAL_FINISHED, payloadJson = "{}")
      repeat(3) { attempt ->
        val token = "budget-$attempt"
        store.claimPending(claimRequest(token, limit = 10))
        store.markFailed(listOf(id), token, "receiver rejected the batch")
      }

      assertEquals(1, store.claimPending(claimRequest("drainer", limit = 10, attemptBudget = 5)).size)
      assertTrue(store.claimPending(claimRequest("drainer", limit = 10, attemptBudget = 3)).isEmpty())
      assertEquals(1, store.blockedCount(attemptBudget = 3))
      assertEquals(1, store.pendingCount(), "A blocked row stays queued for recovery, it is not discarded.")
    }
  }

  private fun claimRequest(
    token: String,
    limit: Int,
    reclaimBefore: Instant = Instant.parse("2026-09-15T09:55:00Z"),
    attemptBudget: Int = TELEMETRY_DELIVERY_ATTEMPT_BUDGET,
  ): TelemetryOutboxClaimRequest =
    TelemetryOutboxClaimRequest(
      claimToken = token,
      limit = limit,
      claimedAt = Instant.parse("2026-09-15T10:00:00Z"),
      reclaimBefore = reclaimBefore,
      attemptBudget = attemptBudget,
    )

  private fun scalarString(
    connection: Connection,
    sql: String,
  ): String? =
    connection.createStatement().use { st ->
      st.executeQuery(sql).use { rows ->
        check(rows.next())
        rows.getString(1)
      }
    }

  private fun withOutbox(block: (Connection, TelemetryOutboxStore) -> Unit) {
    val dbPath = Files.createTempDirectory("runtime-kotlin-db-outbox").resolve("metrics.db")
    DatabaseRuntime.ensureDatabase(dbPath).use { connection ->
      block(connection, TelemetryOutboxStore(connection, version = "test-runtime-version"))
    }
  }

  private fun countWhere(
    connection: Connection,
    predicate: String,
  ): Int =
    connection.createStatement().use { st ->
      st.executeQuery("SELECT COUNT(*) FROM telemetry_outbox WHERE $predicate").use { rows ->
        check(rows.next())
        rows.getInt(1)
      }
    }
}
