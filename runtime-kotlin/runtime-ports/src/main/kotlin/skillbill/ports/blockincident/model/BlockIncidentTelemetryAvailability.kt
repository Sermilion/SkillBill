package skillbill.ports.blockincident.model

enum class BlockIncidentTelemetryState {
  DISABLED,
  PENDING,
  SYNCED,
  FAILED,
  UNAVAILABLE,
}

data class BlockIncidentTelemetryAvailability(
  val state: BlockIncidentTelemetryState,
  val installId: String?,
  val incidentToken: String,
  val workflowId: String?,
  val invocationId: String?,
)
