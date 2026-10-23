package skillbill.di.telemetry

import skillbill.application.telemetry.sync.TelemetrySyncRuntime
import skillbill.contracts.telemetry.TelemetryOutboxEvent
import skillbill.contracts.telemetry.TelemetryProxyPayloadKeys
import skillbill.infrastructure.host.concurrency.JvmInterruptSignalPort
import skillbill.infrastructure.http.telemetryProxyBatchPayload
import skillbill.infrastructure.sqlite.TelemetryOutboxTestHandle
import skillbill.infrastructure.sqlite.withTelemetryOutboxStore
import skillbill.ports.concurrency.InterruptSignalPort
import skillbill.ports.repository.toFileLocation
import skillbill.ports.telemetry.model.TELEMETRY_DELIVERY_ATTEMPT_BUDGET
import skillbill.ports.telemetry.model.TelemetryCorrelationStatus
import skillbill.ports.telemetry.model.TelemetryOutboxClaimRequest
import skillbill.ports.telemetry.model.TelemetryOutboxRecord
import skillbill.ports.telemetry.model.TelemetryOutboxSettlementResult
import skillbill.ports.telemetry.transport.TelemetryClient
import skillbill.ports.telemetry.transport.TelemetryOutboxRepository
import skillbill.telemetry.model.RemoteStatsRequest
import skillbill.telemetry.model.TelemetryDeliveryOutcome
import skillbill.telemetry.model.TelemetryDeliveryReport
import skillbill.telemetry.model.TelemetryProxyCapabilities
import skillbill.telemetry.model.TelemetryRemoteStatsResult
import skillbill.telemetry.model.TelemetrySettings
import skillbill.telemetry.model.TelemetrySyncStatus
import java.io.IOException
import java.nio.file.Files
import java.time.Instant
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

private val NOW: Instant = Instant.parse("2026-09-15T10:00:00Z")

class TelemetryDeliveryRecoveryTest {
  @Test
  fun `an unconfirmed delivery keeps one recoverable entry with its original identity`() {
    withOutbox { store ->
      store.enqueue(event = TelemetryOutboxEvent.REVIEW_FINISHED, payloadJson = """{"run":"r-1"}""")
      val mintedIdentity = store.listPending().single().eventUuid
      val unconfirmedRelay = StubTelemetryClient(TelemetryDeliveryOutcome.UNKNOWN)

      val result =
        TelemetrySyncRuntime.syncTelemetry(settings(), store, unconfirmedRelay, { NOW }, JvmInterruptSignalPort)
      TelemetrySyncRuntime.syncTelemetry(settings(), store, unconfirmedRelay, {
        NOW.plusSeconds(301)
      }, JvmInterruptSignalPort)

      assertEquals(TelemetrySyncStatus.FAILED, result.status)
      val pending = store.listPending()
      assertEquals(1, pending.size, "An unconfirmed batch must stay queued exactly once.")
      assertEquals(mintedIdentity, pending.single().eventUuid, "The retry must reuse the original identity.")
      assertEquals(
        listOf<List<Any?>>(listOf(mintedIdentity), listOf(mintedIdentity)),
        unconfirmedRelay.sentBatches,
        "A resend after an unconfirmed delivery must carry the identity the receiver deduplicates on.",
      )
      assertEquals(0, pending.single().deliveryAttempts, "An unconfirmed delivery consumes no attempt.")
      assertTrue(store.latestError().orEmpty().contains("unconfirmed"))
    }
  }

  @Test
  fun `a failed local acknowledgement does not resend the batch without bound`() {
    withOutbox { store ->
      repeat(3) { index ->
        store.enqueue(event = TelemetryOutboxEvent.GOAL_FINISHED, payloadJson = """{"i":$index}""")
      }
      val client = StubTelemetryClient(TelemetryDeliveryOutcome.ACCEPTED)
      val brokenAcknowledgement =
        object : TelemetryOutboxRepository by store {
          override fun markSynced(
            eventIds: List<Long>,
            claimToken: String,
          ): TelemetryOutboxSettlementResult {
            throw IOException("disk full")
          }
        }

      val result =
        TelemetrySyncRuntime.syncTelemetry(settings(batchSize = 1), brokenAcknowledgement, client, {
          NOW
        }, JvmInterruptSignalPort)

      assertEquals(TelemetrySyncStatus.FAILED, result.status)
      assertEquals(1, client.sentBatches.size, "The invocation must stop, not resend on every pass.")
      assertEquals(3, store.pendingCount(), "No row may be lost when the acknowledgement fails.")
      assertTrue(store.latestError().orEmpty().contains("local acknowledgement failed"))
    }
  }

  @Test
  fun `an unconfirmed transport failure leaves the queue drainable once delivery recovers`() {
    withOutbox { store ->
      store.enqueue(event = TelemetryOutboxEvent.GOAL_FINISHED, payloadJson = "{}")
      val unreachable = StubTelemetryClient(TelemetryDeliveryOutcome.UNKNOWN)

      repeat(TELEMETRY_DELIVERY_ATTEMPT_BUDGET * 2) {
        TelemetrySyncRuntime.syncTelemetry(settings(), store, unreachable, { NOW }, JvmInterruptSignalPort)
      }

      assertEquals(
        0,
        store.blockedCount(TELEMETRY_DELIVERY_ATTEMPT_BUDGET),
        "An unconfirmed delivery is not a receiver refusal and must not exhaust the budget.",
      )
      val recovered =
        TelemetrySyncRuntime.syncTelemetry(
          settings(),
          store,
          StubTelemetryClient(TelemetryDeliveryOutcome.ACCEPTED),
          { NOW },
          JvmInterruptSignalPort,
        )

      assertEquals(TelemetrySyncStatus.SYNCED, recovered.status)
      assertEquals(0, store.pendingCount(), "The queue must drain once delivery works again.")
    }
  }

  @Test
  fun `repeated delivery failure enqueues no new telemetry and the drain terminates`() {
    withOutbox { store ->
      store.enqueue(event = TelemetryOutboxEvent.GOAL_FINISHED, payloadJson = "{}")
      val client =
        StubTelemetryClient(TelemetryDeliveryOutcome.REJECTED, detail = "HTTP 422: unknown event property")

      repeat(10) { TelemetrySyncRuntime.syncTelemetry(settings(), store, client, { NOW }, JvmInterruptSignalPort) }

      assertEquals(1, store.pendingCount(), "The failing drain must not enqueue anything of its own.")
      assertTrue(
        client.sentBatches.size < 10,
        "Attempt accounting must stop the replay before every invocation retries it.",
      )
      assertTrue(
        store.latestError().orEmpty().contains("HTTP 422: unknown event property"),
        "A blocked row must carry the receiver's refusal, not only that one happened.",
      )
    }
  }

  @Test
  fun `an unexpected client failure is recorded and releases the batch instead of escaping`() {
    withOutbox { store ->
      store.enqueue(event = TelemetryOutboxEvent.GOAL_FINISHED, payloadJson = "{}")
      val throwingClient =
        object : TelemetryClient by StubTelemetryClient(TelemetryDeliveryOutcome.ACCEPTED) {
          override fun sendBatch(
            settings: TelemetrySettings,
            rows: List<TelemetryOutboxRecord>,
          ): TelemetryDeliveryReport = error("relay handshake blew up")
        }

      val result =
        TelemetrySyncRuntime.autoSyncTelemetry(settings(), store, throwingClient, { NOW }, JvmInterruptSignalPort)

      assertEquals(TelemetrySyncStatus.FAILED, result?.status, "The failure must be reported, not swallowed.")
      assertEquals(1, store.pendingCount(), "The queue must not grow from its own failure.")
      assertTrue(store.latestError().orEmpty().contains("relay handshake blew up"))
      assertEquals(
        1,
        store.claimPending(
          TelemetryOutboxClaimRequest(
            claimToken = "next-drain",
            limit = 10,
            claimedAt = NOW,
            reclaimBefore = NOW,
          ),
        ).size,
        "The batch must be released for the next drain rather than held until the lease expires.",
      )
    }
  }

  @Test
  fun `a drain that claims nothing while rows stay queued does not report a clean sync`() {
    withOutbox { store ->
      store.enqueue(event = TelemetryOutboxEvent.GOAL_FINISHED, payloadJson = "{}")
      store.claimPending(
        TelemetryOutboxClaimRequest(
          claimToken = "concurrent-drain",
          limit = 10,
          claimedAt = NOW,
          reclaimBefore = NOW,
        ),
      )
      val client = StubTelemetryClient(TelemetryDeliveryOutcome.ACCEPTED)

      val result = TelemetrySyncRuntime.syncTelemetry(settings(), store, client, { NOW }, JvmInterruptSignalPort)

      assertNotEquals(TelemetrySyncStatus.SYNCED, result.status)
      assertEquals(1, result.pendingEvents, "The queued row must stay visible as pending.")
      assertTrue(client.sentBatches.isEmpty(), "A claimed row must not be sent twice.")
    }
  }

  @Test
  fun `auto sync propagates cancellation instead of returning null`() {
    val cancellingRepository =
      object : TelemetryOutboxRepository {
        override fun enqueue(
          event: TelemetryOutboxEvent,
          payloadJson: String,
        ): Long = error("unexpected")

        override fun claimPending(request: TelemetryOutboxClaimRequest): List<TelemetryOutboxRecord> =
          throw CancellationException("probe-cancelled")

        override fun pendingCount(): Int = 1

        override fun blockedCount(attemptBudget: Int): Int = 0

        override fun latestError(): String? = null

        override fun lastSyncedAt(): String? = null

        override fun latestByCorrelation(
          events: Set<TelemetryOutboxEvent>,
          incidentToken: String,
        ): TelemetryCorrelationStatus? = null

        override fun markSynced(
          eventIds: List<Long>,
          claimToken: String,
        ): TelemetryOutboxSettlementResult = error("unexpected")

        override fun markFailed(
          eventIds: List<Long>,
          claimToken: String,
          lastError: String,
        ): TelemetryOutboxSettlementResult = error("unexpected")

        override fun markUnconfirmed(
          eventIds: List<Long>,
          claimToken: String,
          lastError: String,
        ): TelemetryOutboxSettlementResult = error("unexpected")

        override fun clear(): Int = 0
      }

    assertFailsWith<CancellationException> {
      TelemetrySyncRuntime.autoSyncTelemetry(
        settings(),
        cancellingRepository,
        StubTelemetryClient(
          TelemetryDeliveryOutcome.ACCEPTED,
        ),
        {
          NOW
        },
        JvmInterruptSignalPort,
      )
    }
  }

  @Test
  fun `transport cancellation propagates without consuming an attempt and the lease can recover`() {
    withOutbox { store ->
      val id = store.enqueue(event = TelemetryOutboxEvent.GOAL_FINISHED, payloadJson = "{}")
      val cancellingClient =
        object : TelemetryClient by StubTelemetryClient(TelemetryDeliveryOutcome.ACCEPTED) {
          override fun sendBatch(
            settings: TelemetrySettings,
            rows: List<TelemetryOutboxRecord>,
          ): TelemetryDeliveryReport = throw CancellationException("transport-cancelled")
        }

      assertFailsWith<CancellationException> {
        TelemetrySyncRuntime.syncTelemetry(settings(), store, cancellingClient, { NOW }, JvmInterruptSignalPort)
      }
      assertEquals(id, store.listPending().single().id)
      assertEquals(0, store.listPending().single().deliveryAttempts)

      val recovered =
        TelemetrySyncRuntime.syncTelemetry(
          settings(),
          store,
          StubTelemetryClient(TelemetryDeliveryOutcome.ACCEPTED),
          { NOW.plusSeconds(301) },
          JvmInterruptSignalPort,
        )
      assertEquals(TelemetrySyncStatus.SYNCED, recovered.status)
      assertEquals(0, store.pendingCount())
    }
  }

  @Test
  fun `acknowledgement cancellation propagates without consuming an attempt or writing an error`() {
    withOutbox { store ->
      val id = store.enqueue(event = TelemetryOutboxEvent.GOAL_FINISHED, payloadJson = "{}")
      val cancellingAcknowledgement =
        object : TelemetryOutboxRepository by store {
          override fun markSynced(
            eventIds: List<Long>,
            claimToken: String,
          ): TelemetryOutboxSettlementResult = throw CancellationException("ack-cancelled")
        }

      assertFailsWith<CancellationException> {
        TelemetrySyncRuntime.syncTelemetry(
          settings(),
          cancellingAcknowledgement,
          StubTelemetryClient(TelemetryDeliveryOutcome.ACCEPTED),
          { NOW },
          JvmInterruptSignalPort,
        )
      }
      assertEquals(id, store.listPending().single().id)
      assertEquals(0, store.listPending().single().deliveryAttempts)
      assertEquals("", store.listPending().single().lastError)
    }
  }

  @Test
  fun `interrupted delivery is rethrown with the thread interrupt signal preserved`() {
    withOutbox { store ->
      store.enqueue(event = TelemetryOutboxEvent.GOAL_FINISHED, payloadJson = "{}")
      val interruptSignal = RecordingInterruptSignalPort()
      val interruptedClient =
        object : TelemetryClient by StubTelemetryClient(TelemetryDeliveryOutcome.ACCEPTED) {
          override fun sendBatch(
            settings: TelemetrySettings,
            rows: List<TelemetryOutboxRecord>,
          ): TelemetryDeliveryReport = throw InterruptedException("transport-interrupted")
        }

      try {
        assertFailsWith<InterruptedException> {
          TelemetrySyncRuntime.syncTelemetry(settings(), store, interruptedClient, { NOW }, interruptSignal)
        }
        assertTrue(Thread.currentThread().isInterrupted)
        assertEquals(1, interruptSignal.restoreCount)
        assertEquals(0, store.listPending().single().deliveryAttempts)
      } finally {
        Thread.interrupted()
      }
    }
  }

  private fun withOutbox(block: (TelemetryOutboxTestHandle) -> Unit) {
    val tempDir = Files.createTempDirectory("telemetry-delivery-recovery")
    val dbPath = tempDir.resolve("metrics.db")
    withTelemetryOutboxStore(tempDir, dbPath, block = block)
  }

  private fun settings(batchSize: Int = 50): TelemetrySettings =
    TelemetrySettings(
      configPath = Files.createTempFile("telemetry-recovery", ".json").toFileLocation(),
      level = "anonymous",
      enabled = true,
      installId = "test-install-id",
      proxyUrl = "https://telemetry.example.dev/ingest",
      customProxyUrl = "https://telemetry.example.dev/ingest",
      batchSize = batchSize,
    )
}

private class RecordingInterruptSignalPort : InterruptSignalPort {
  var restoreCount = 0

  override fun restore() {
    restoreCount++
    Thread.currentThread().interrupt()
  }
}

private class StubTelemetryClient(
  private val outcome: TelemetryDeliveryOutcome,
  private val detail: String = "",
) : TelemetryClient {
  val sentBatches = mutableListOf<List<Any?>>()

  override fun sendBatch(
    settings: TelemetrySettings,
    rows: List<TelemetryOutboxRecord>,
  ): TelemetryDeliveryReport {
    sentBatches +=
      telemetryProxyBatchPayload(settings, rows).batch.map { event ->
        event.toPayload()[TelemetryProxyPayloadKeys.EVENT_IDENTITY]
      }
    return TelemetryDeliveryReport(outcome, detail)
  }

  override fun fetchProxyCapabilities(settings: TelemetrySettings): TelemetryProxyCapabilities =
    error("Unexpected fetchProxyCapabilities")

  override fun fetchRemoteStats(
    settings: TelemetrySettings,
    request: RemoteStatsRequest,
  ): TelemetryRemoteStatsResult = error("Unexpected fetchRemoteStats")
}
