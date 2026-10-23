package skillbill.ports.telemetry.transport

import skillbill.contracts.telemetry.TelemetryOutboxEvent
import skillbill.ports.telemetry.model.TelemetryOutboxClaimRequest
import skillbill.ports.telemetry.model.TelemetryOutboxRecord
import skillbill.ports.telemetry.model.TelemetryOutboxSettlementResult

interface TelemetryOutboxRepository : TelemetryOutboxStatusReader {
  fun enqueue(
    event: TelemetryOutboxEvent,
    payloadJson: String,
  ): Long

  fun claimPending(request: TelemetryOutboxClaimRequest): List<TelemetryOutboxRecord>

  fun markSynced(
    eventIds: List<Long>,
    claimToken: String,
  ): TelemetryOutboxSettlementResult

  fun markFailed(
    eventIds: List<Long>,
    claimToken: String,
    lastError: String,
  ): TelemetryOutboxSettlementResult

  fun markUnconfirmed(
    eventIds: List<Long>,
    claimToken: String,
    lastError: String,
  ): TelemetryOutboxSettlementResult

  fun clear(): Int
}
