package skillbill.config

import skillbill.config.model.COMPACTION_KEY
import skillbill.config.model.CompactionSettings
import skillbill.config.model.CompactionSettingsFields
import skillbill.config.model.CompactionSettingsParse
import skillbill.config.model.EXECUTION_MATRIX_KEY
import skillbill.config.model.ExecutionMatrixParse
import skillbill.config.model.MachineConfigDocumentParse
import skillbill.config.model.MachineConfigDocumentSettings
import skillbill.config.model.parseCompactionSettings
import skillbill.config.model.parseExecutionMatrix
import skillbill.telemetry.model.TelemetryConfigDocument
import skillbill.telemetry.model.TelemetryConfigDocumentParse
import skillbill.telemetry.model.TelemetryOpenDocument
import skillbill.telemetry.parseTelemetryConfigDocument

fun parseMachineConfigDocument(
  path: String,
  payload: TelemetryOpenDocument,
): MachineConfigDocumentParse {
  val executionMatrix =
    if (!payload.containsKey(EXECUTION_MATRIX_KEY)) {
      null
    } else {
      when (val parsed = parseExecutionMatrix(payload[EXECUTION_MATRIX_KEY])) {
        is ExecutionMatrixParse.Valid -> parsed.matrix
        is ExecutionMatrixParse.Invalid -> return MachineConfigDocumentParse.Invalid(parsed.keyPath, parsed.reason)
      }
    }
  val (compaction, compactionFields) =
    if (!payload.containsKey(COMPACTION_KEY)) {
      CompactionSettings.DEFAULT to CompactionSettingsFields.EMPTY
    } else {
      when (val parsed = parseCompactionSettings(payload[COMPACTION_KEY])) {
        is CompactionSettingsParse.Valid -> parsed.settings to parsed.fields
        is CompactionSettingsParse.Invalid -> return MachineConfigDocumentParse.Invalid(parsed.keyPath, parsed.reason)
      }
    }
  val telemetry =
    when (
      val parsed = parseTelemetryConfigDocument(path, TelemetryConfigDocument(payload))
    ) {
      is TelemetryConfigDocumentParse.Valid -> parsed.settings
      is TelemetryConfigDocumentParse.Invalid -> return MachineConfigDocumentParse.Invalid(
        parsed.keyPath,
        parsed.reason,
      )
    }
  return MachineConfigDocumentParse.Valid(
    MachineConfigDocumentSettings(executionMatrix, compaction, telemetry, compactionFields),
  )
}
