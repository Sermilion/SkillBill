package skillbill.ports.blockincident.model

enum class BlockIncidentTelemetryState(val wireValue: String) {
  DISABLED("disabled"),
  PENDING("pending"),
  SYNCED("synced"),
  FAILED("failed"),
  UNAVAILABLE("unavailable"),
}

data class BlockIncidentTelemetryAvailability(
  val state: BlockIncidentTelemetryState,
  val installId: String?,
  val incidentToken: String,
  val workflowId: String?,
  val invocationId: String?,
)
