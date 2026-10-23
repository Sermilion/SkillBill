package skillbill.ports.telemetry.model

data class TelemetryCorrelationStatus(
  val synced: Boolean,
  val lastError: String?,
)
