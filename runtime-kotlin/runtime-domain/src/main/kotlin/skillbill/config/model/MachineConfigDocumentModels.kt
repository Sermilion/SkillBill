package skillbill.config.model

import skillbill.telemetry.model.TelemetryConfigDocumentSettings

data class MachineConfigDocumentSettings(
  val executionMatrix: ExecutionMatrix?,
  val compaction: CompactionSettings,
  val telemetry: TelemetryConfigDocumentSettings,
  val compactionFields: CompactionSettingsFields = CompactionSettingsFields.EMPTY,
)

sealed interface MachineConfigDocumentParse {
  data class Valid(val settings: MachineConfigDocumentSettings) : MachineConfigDocumentParse

  data class Invalid(val keyPath: String?, val reason: String) : MachineConfigDocumentParse
}
