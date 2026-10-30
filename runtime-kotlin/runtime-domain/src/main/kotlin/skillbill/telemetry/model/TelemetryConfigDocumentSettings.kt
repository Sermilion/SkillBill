package skillbill.telemetry.model

data class TelemetryConfigDocumentSettings(
  val level: String,
  val customProxyUrl: String,
  val batchSize: Int,
  val installId: String,
)

sealed interface TelemetryConfigDocumentParse {
  data class Valid(val settings: TelemetryConfigDocumentSettings) : TelemetryConfigDocumentParse

  data class Invalid(val keyPath: String?, val reason: String) : TelemetryConfigDocumentParse
}
