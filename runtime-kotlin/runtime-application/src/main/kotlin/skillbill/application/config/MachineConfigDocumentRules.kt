package skillbill.application.config

import skillbill.application.config.model.ConfigDocumentRead
import skillbill.application.config.model.MachineConfigEdit
import skillbill.application.config.model.MachineConfigValues
import skillbill.config.model.COMPACTION_KEY
import skillbill.config.model.CompactionSettingsParse
import skillbill.config.model.EXECUTION_MATRIX_KEY
import skillbill.config.model.ExecutionMatrixParse
import skillbill.config.model.MachineConfigDocumentParse
import skillbill.config.model.parseCompactionSettings
import skillbill.config.model.parseExecutionMatrix
import skillbill.config.parseMachineConfigDocument
import skillbill.ports.config.model.ConfigDocumentTree
import skillbill.ports.config.model.ConfigFingerprint
import skillbill.telemetry.TelemetryConfigFileKeys
import skillbill.telemetry.model.TelemetryOpenDocument
import skillbill.telemetry.positiveTelemetryIntError
import skillbill.telemetry.telemetryProxyUrlError
import java.nio.file.Path

internal fun parseMachineConfigValues(
  path: Path,
  fingerprint: ConfigFingerprint,
  tree: ConfigDocumentTree,
  hasComments: Boolean,
): ConfigDocumentRead<MachineConfigValues> {
  val settings =
    when (
      val parsed =
        parseMachineConfigDocument(
          path.toString(),
          TelemetryOpenDocument.from(tree.orderedEntries().toMap()),
        )
    ) {
      is MachineConfigDocumentParse.Valid -> parsed.settings
      is MachineConfigDocumentParse.Invalid -> return ConfigDocumentRead.Unreadable(path, parsed.keyPath, parsed.reason)
    }
  val telemetrySettings = settings.telemetry
  val proxyUrl = telemetrySettings.customProxyUrl.takeIf(String::isNotBlank)
  return ConfigDocumentRead.Present(
    path,
    fingerprint,
    MachineConfigValues(
      settings.executionMatrix,
      settings.compaction,
      proxyUrl,
      telemetrySettings.batchSize,
      telemetrySettings.level,
      telemetrySettings.installId,
      settings.compactionFields,
    ),
    hasComments,
  )
}

internal fun applyMachineConfigEdits(
  original: ConfigDocumentTree,
  edits: List<MachineConfigEdit>,
): MachineConfigTreeResult {
  var tree = original
  for (edit in edits) {
    when (val result = applyMachineConfigEdit(tree, edit)) {
      is MachineConfigTreeResult.Invalid -> return result
      is MachineConfigTreeResult.Valid -> tree = result.tree
    }
  }
  return MachineConfigTreeResult.Valid(tree)
}

private fun applyMachineConfigEdit(
  original: ConfigDocumentTree,
  edit: MachineConfigEdit,
): MachineConfigTreeResult =
  when (edit) {
    is MachineConfigEdit.SetExecutionMatrix -> applyExecutionMatrix(original, edit)
    is MachineConfigEdit.SetCompaction -> applyCompaction(original, edit)
    is MachineConfigEdit.SetTelemetryProxyUrl -> applyTelemetryProxy(original, edit.value)
    is MachineConfigEdit.SetTelemetryBatchSize -> applyTelemetryBatch(original, edit.value)
  }

private fun applyExecutionMatrix(
  original: ConfigDocumentTree,
  edit: MachineConfigEdit.SetExecutionMatrix,
): MachineConfigTreeResult {
  edit.editIntent?.let { return mergeExecutionMatrixSection(original, edit.section, it) }
  if (edit.section.isEmpty()) return MachineConfigTreeResult.Valid(original.without(EXECUTION_MATRIX_KEY))
  return when (val parsed = parseExecutionMatrix(edit.section)) {
    is ExecutionMatrixParse.Valid -> MachineConfigTreeResult.Valid(original.with(EXECUTION_MATRIX_KEY, edit.section))
    is ExecutionMatrixParse.Invalid -> MachineConfigTreeResult.Invalid(parsed.keyPath, parsed.reason)
  }
}

private fun applyCompaction(
  original: ConfigDocumentTree,
  edit: MachineConfigEdit.SetCompaction,
): MachineConfigTreeResult {
  edit.editIntent?.let { return mergeCompactionSection(original, edit.section, it) }
  if (edit.section.isEmpty()) return MachineConfigTreeResult.Valid(original.without(COMPACTION_KEY))
  return when (val parsed = parseCompactionSettings(edit.section)) {
    is CompactionSettingsParse.Valid -> MachineConfigTreeResult.Valid(original.with(COMPACTION_KEY, edit.section))
    is CompactionSettingsParse.Invalid -> MachineConfigTreeResult.Invalid(parsed.keyPath, parsed.reason)
  }
}

private fun applyTelemetryProxy(
  tree: ConfigDocumentTree,
  rawValue: String?,
): MachineConfigTreeResult {
  val value = rawValue?.trim()?.takeIf(String::isNotBlank)
  val keyPath = "${TelemetryConfigFileKeys.TELEMETRY}.${TelemetryConfigFileKeys.PROXY_URL}"
  value?.let { telemetryProxyUrlError(it) }?.let { return MachineConfigTreeResult.Invalid(keyPath, it) }
  return updateTelemetry(tree, TelemetryConfigFileKeys.PROXY_URL, value)
}

private fun applyTelemetryBatch(
  tree: ConfigDocumentTree,
  value: Int?,
): MachineConfigTreeResult {
  if (value != null && value <= 0) {
    val keyPath = "${TelemetryConfigFileKeys.TELEMETRY}.${TelemetryConfigFileKeys.BATCH_SIZE}"
    return MachineConfigTreeResult.Invalid(keyPath, positiveTelemetryIntError(value.toString(), keyPath))
  }
  return updateTelemetry(tree, TelemetryConfigFileKeys.BATCH_SIZE, value)
}

private fun updateTelemetry(
  tree: ConfigDocumentTree,
  key: String,
  value: Any?,
): MachineConfigTreeResult {
  val raw = tree.value(TelemetryConfigFileKeys.TELEMETRY)
  val parentKey = TelemetryConfigFileKeys.TELEMETRY
  if (tree.contains(parentKey) && raw == null) {
    if (value == null) return MachineConfigTreeResult.Valid(tree)
  }
  if (raw != null && raw !is Map<*, *>) {
    return MachineConfigTreeResult.Invalid(parentKey, "must be a mapping before a telemetry field can be changed.")
  }
  val telemetry = LinkedHashMap<String, Any?>()
  if (raw is Map<*, *>) {
    raw.forEach { (entryKey, entryValue) ->
      if (entryKey is String) telemetry[entryKey] = entryValue
    }
  }
  if (value == null) telemetry.remove(key) else telemetry[key] = value
  return MachineConfigTreeResult.Valid(
    if (telemetry.isEmpty() && !tree.contains(parentKey)) tree else tree.with(parentKey, telemetry),
  )
}

internal sealed interface MachineConfigTreeResult {
  data class Valid(val tree: ConfigDocumentTree) : MachineConfigTreeResult

  data class Invalid(val keyPath: String, val reason: String) : MachineConfigTreeResult
}
