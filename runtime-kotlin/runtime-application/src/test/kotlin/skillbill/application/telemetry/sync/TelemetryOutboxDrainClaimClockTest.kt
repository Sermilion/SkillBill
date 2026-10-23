package skillbill.application.telemetry.sync

import skillbill.application.telemetry.sync.TelemetrySyncRuntime.syncTelemetry
import skillbill.contracts.telemetry.TelemetryOutboxEvent
import skillbill.ports.concurrency.InterruptSignalPort
import skillbill.ports.repository.toFileLocation
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
import java.nio.file.Files
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertTrue

class TelemetryOutboxDrainClaimClockTest {
  @Test
  fun `each batch claim uses the current clock reading from the drain`() {
    var now = Instant.parse("2026-09-15T10:00:00Z")
    val repository = RecordingClaimClockRepository()
    val settings =
      TelemetrySettings(
        configPath = Files.createTempFile("drain-clock", ".json").toFileLocation(),
        level = "anonymous",
        enabled = true,
        installId = "install",
        proxyUrl = "https://telemetry.example.dev/ingest",
        customProxyUrl = "https://telemetry.example.dev/ingest",
        batchSize = 1,
      )
    repository.seed(id = 1L)
    repository.seed(id = 2L)

    TelemetrySyncRuntime.syncTelemetry(
      settings,
      repository,
      AcceptingTelemetryRelay(),
      nowSupplier = {
        val current = now
        now = now.plusSeconds(120)
        current
      },
      interruptSignal = NoopInterruptSignalPort,
    )

    assertTrue(repository.claimedAtTimestamps.size >= 2)
    assertTrue(
      repository.claimedAtTimestamps[1].isAfter(repository.claimedAtTimestamps[0]),
      "the second batch must receive a fresher claim timestamp than the first",
    )
  }
}

private class RecordingClaimClockRepository : TelemetryOutboxRepository {
  val claimedAtTimestamps = mutableListOf<Instant>()
  private val rows = mutableMapOf<Long, TelemetryOutboxRecord>()
  private val claimTokens = mutableMapOf<Long, String>()

  fun seed(id: Long) {
    rows[id] =
      TelemetryOutboxRecord(
        id = id,
        eventName = "skillbill_goal_finished",
        payloadJson = "{}",
        createdAt = "2026-09-15T10:00:00Z",
        syncedAt = null,
        lastError = "",
      )
  }

  override fun enqueue(
    event: TelemetryOutboxEvent,
    payloadJson: String,
  ): Long = error("unexpected")

  override fun claimPending(request: TelemetryOutboxClaimRequest): List<TelemetryOutboxRecord> {
    claimedAtTimestamps += request.claimedAt
    val claimed =
      rows.values.filter { it.syncedAt == null && it.deliveryAttempts < request.attemptBudget }.take(request.limit)
    claimed.forEach { claimTokens[it.id] = request.claimToken }
    return claimed
  }

  override fun pendingCount(): Int = rows.values.count { it.syncedAt == null }

  override fun blockedCount(attemptBudget: Int): Int =
    rows.values.count { it.syncedAt == null && it.deliveryAttempts >= attemptBudget }

  override fun latestError(): String? = null

  override fun lastSyncedAt(): String? = null

  override fun latestByCorrelation(
    events: Set<TelemetryOutboxEvent>,
    incidentToken: String,
  ): TelemetryCorrelationStatus? = null

  override fun markSynced(
    eventIds: List<Long>,
    claimToken: String,
  ): TelemetryOutboxSettlementResult {
    var updated = 0
    eventIds.forEach { id ->
      val row = rows[id] ?: return@forEach
      if (claimTokens[id] == claimToken && row.syncedAt == null) {
        updated++
        rows[id] = row.copy(syncedAt = "2026-09-15T10:05:00Z")
        claimTokens.remove(id)
      }
    }
    return TelemetryOutboxSettlementResult.forRequest(eventIds, updatedRows = updated)
  }

  override fun markFailed(
    eventIds: List<Long>,
    claimToken: String,
    lastError: String,
  ): TelemetryOutboxSettlementResult = TelemetryOutboxSettlementResult.forRequest(eventIds, updatedRows = 0)

  override fun markUnconfirmed(
    eventIds: List<Long>,
    claimToken: String,
    lastError: String,
  ): TelemetryOutboxSettlementResult = TelemetryOutboxSettlementResult.forRequest(eventIds, updatedRows = 0)

  override fun clear(): Int = 0
}

private object NoopInterruptSignalPort : InterruptSignalPort {
  override fun restore() = Unit
}

private class AcceptingTelemetryRelay : TelemetryClient {
  override fun sendBatch(
    settings: TelemetrySettings,
    rows: List<TelemetryOutboxRecord>,
  ): TelemetryDeliveryReport = TelemetryDeliveryReport(TelemetryDeliveryOutcome.ACCEPTED, "")

  override fun fetchProxyCapabilities(settings: TelemetrySettings): TelemetryProxyCapabilities = error("unexpected")

  override fun fetchRemoteStats(
    settings: TelemetrySettings,
    request: RemoteStatsRequest,
  ): TelemetryRemoteStatsResult = error("unexpected")
}
