package skillbill.ports.telemetry.model

data class TelemetryConfigLevelWriteResult(
  val wrote: Boolean,
  val previousLevel: String,
)
