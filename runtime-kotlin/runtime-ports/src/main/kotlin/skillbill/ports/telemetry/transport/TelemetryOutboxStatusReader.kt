package skillbill.ports.telemetry.transport

import skillbill.contracts.telemetry.TelemetryOutboxEvent
import skillbill.ports.telemetry.model.TelemetryCorrelationStatus

/** Reads delivery state without claiming or changing outbox events. */
interface TelemetryOutboxStatusReader {
  fun pendingCount(): Int

  fun blockedCount(attemptBudget: Int): Int

  fun latestError(): String?

  fun lastSyncedAt(): String?

  fun latestByCorrelation(
    events: Set<TelemetryOutboxEvent>,
    incidentToken: String,
  ): TelemetryCorrelationStatus?
}
