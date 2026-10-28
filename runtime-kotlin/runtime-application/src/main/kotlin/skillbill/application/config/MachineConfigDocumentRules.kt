package skillbill.application.config

import skillbill.application.config.model.ConfigDocumentRead
import skillbill.application.config.model.MachineConfigEdit
import skillbill.application.config.model.MachineConfigValues
import skillbill.config.model.COMPACTION_KEY
import skillbill.config.model.CompactionSettings
import skillbill.config.model.CompactionSettingsParse
import skillbill.config.model.EXECUTION_MATRIX_KEY
import skillbill.config.model.ExecutionMatrixParse
import skillbill.config.model.parseCompactionSettings
import skillbill.config.model.parseExecutionMatrix
import skillbill.ports.config.model.ConfigDocumentTree
import skillbill.ports.config.model.ConfigFingerprint
import skillbill.telemetry.TelemetryConfigFileKeys
import skillbill.telemetry.parsePositiveTelemetryIntOrNull
import skillbill.telemetry.positiveTelemetryIntError
import skillbill.telemetry.telemetryProxyUrlError
import java.nio.file.Path

internal fun parseMachineConfigValues(
  path: Path,
  fingerprint: ConfigFingerprint,
  tree: ConfigDocumentTree,
  hasComments: Boolean,
): ConfigDocumentRead<MachineConfigValues> {
  val matrix =
    if (!tree.contains(EXECUTION_MATRIX_KEY)) {
      null
    } else {
      when (val parsed = parseExecutionMatrix(tree.value(EXECUTION_MATRIX_KEY))) {
        is ExecutionMatrixParse.Valid -> parsed.matrix
        is ExecutionMatrixParse.Invalid -> return ConfigDocumentRead.Unreadable(path, parsed.keyPath, parsed.reason)
      }
    }
  val compaction =
    if (!tree.contains(COMPACTION_KEY)) {
      CompactionSettings.DEFAULT
    } else {
      when (val parsed = parseCompactionSettings(tree.value(COMPACTION_KEY))) {
        is CompactionSettingsParse.Valid -> parsed.settings
        is CompactionSettingsParse.Invalid -> return ConfigDocumentRead.Unreadable(path, parsed.keyPath, parsed.reason)
      }
    }
  val telemetryResult = parseTelemetry(path, tree)
  if (telemetryResult is TelemetryRead.Invalid) return telemetryResult.error
  val telemetry = (telemetryResult as TelemetryRead.Valid).values
  val batchSize = telemetryResult.batchSize

  val proxyUrl = telemetry[TelemetryConfigFileKeys.PROXY_URL]?.toString()?.trim()?.takeIf(String::isNotBlank)
  return ConfigDocumentRead.Present(
    path,
    fingerprint,
    MachineConfigValues(
      matrix,
      compaction,
      proxyUrl,
      batchSize,
    ),
    hasComments,
  )
}

private sealed interface TelemetryRead {
  data class Valid(val values: Map<String, Any?>, val batchSize: Int?) : TelemetryRead

  data class Invalid(val error: ConfigDocumentRead.Unreadable) : TelemetryRead
}

private fun parseTelemetry(
  path: Path,
  tree: ConfigDocumentTree,
): TelemetryRead {
  val telemetryRaw = tree.value(TelemetryConfigFileKeys.TELEMETRY)
  if (tree.contains(TelemetryConfigFileKeys.TELEMETRY) && telemetryRaw !is Map<*, *>) {
    return TelemetryRead.Invalid(
      ConfigDocumentRead.Unreadable(
        path,
        TelemetryConfigFileKeys.TELEMETRY,
        "must be a mapping.",
      ),
    )
  }
  val telemetry =
    (telemetryRaw as? Map<*, *>)
      ?.entries
      ?.filter { it.key is String }
      ?.associate { it.key as String to it.value }
      .orEmpty()
  val batchRaw = telemetry[TelemetryConfigFileKeys.BATCH_SIZE]
  val batchSize =
    when (batchRaw) {
      is Number -> batchRaw.toInt()
      null -> null
      else ->
        parsePositiveTelemetryIntOrNull(batchRaw.toString())
          ?: return TelemetryRead.Invalid(
            ConfigDocumentRead.Unreadable(
              path,
              "${TelemetryConfigFileKeys.TELEMETRY}.${TelemetryConfigFileKeys.BATCH_SIZE}",
              positiveTelemetryIntError(
                batchRaw.toString(),
                "${TelemetryConfigFileKeys.TELEMETRY}.${TelemetryConfigFileKeys.BATCH_SIZE}",
              ),
            ),
          )
    }
  return TelemetryRead.Valid(telemetry, batchSize)
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
): MachineConfigTreeResult {
  var tree = original
  when (edit) {
    is MachineConfigEdit.SetExecutionMatrix -> {
      if (edit.section.isEmpty()) {
        tree = tree.without(EXECUTION_MATRIX_KEY)
      } else {
        when (val parsed = parseExecutionMatrix(edit.section)) {
          is ExecutionMatrixParse.Valid -> tree = tree.with(EXECUTION_MATRIX_KEY, edit.section)
          is ExecutionMatrixParse.Invalid -> return MachineConfigTreeResult.Invalid(parsed.keyPath, parsed.reason)
        }
      }
    }
    is MachineConfigEdit.SetCompaction -> {
      if (edit.section.isEmpty()) {
        tree = tree.without(COMPACTION_KEY)
      } else {
        when (val parsed = parseCompactionSettings(edit.section)) {
          is CompactionSettingsParse.Valid -> tree = tree.with(COMPACTION_KEY, edit.section)
          is CompactionSettingsParse.Invalid -> return MachineConfigTreeResult.Invalid(parsed.keyPath, parsed.reason)
        }
      }
    }
    is MachineConfigEdit.SetTelemetryProxyUrl, is MachineConfigEdit.SetTelemetryBatchSize ->
      return applyTelemetryEdit(tree, edit)
  }
  return MachineConfigTreeResult.Valid(tree)
}

private fun applyTelemetryEdit(
  original: ConfigDocumentTree,
  edit: MachineConfigEdit,
): MachineConfigTreeResult {
  var tree = original
  when (edit) {
    is MachineConfigEdit.SetTelemetryProxyUrl -> {
      val value = edit.value?.trim()?.takeIf(String::isNotBlank)
      val keyPath = "${TelemetryConfigFileKeys.TELEMETRY}.${TelemetryConfigFileKeys.PROXY_URL}"
      if (value != null) {
        val error = telemetryProxyUrlError(value)
        if (error != null) return MachineConfigTreeResult.Invalid(keyPath, error)
      }
      tree = updateTelemetry(tree, TelemetryConfigFileKeys.PROXY_URL, value)
    }
    is MachineConfigEdit.SetTelemetryBatchSize -> {
      val value = edit.value
      if (value != null && value <= 0) {
        val keyPath = "${TelemetryConfigFileKeys.TELEMETRY}.${TelemetryConfigFileKeys.BATCH_SIZE}"
        return MachineConfigTreeResult.Invalid(
          keyPath,
          positiveTelemetryIntError(value.toString(), keyPath),
        )
      }
      tree = updateTelemetry(tree, TelemetryConfigFileKeys.BATCH_SIZE, value)
    }
    else -> error("Expected a telemetry edit")
  }
  return MachineConfigTreeResult.Valid(tree)
}

private fun updateTelemetry(
  tree: ConfigDocumentTree,
  key: String,
  value: Any?,
): ConfigDocumentTree {
  val raw = tree.value(TelemetryConfigFileKeys.TELEMETRY)
  val telemetry = LinkedHashMap<String, Any?>()
  if (raw is Map<*, *>) {
    raw.forEach { (entryKey, entryValue) ->
      if (entryKey is String) telemetry[entryKey] = entryValue
    }
  }
  if (value == null) telemetry.remove(key) else telemetry[key] = value
  return if (telemetry.isEmpty() && raw == null) tree else tree.with(TelemetryConfigFileKeys.TELEMETRY, telemetry)
}

internal sealed interface MachineConfigTreeResult {
  data class Valid(val tree: ConfigDocumentTree) : MachineConfigTreeResult

  data class Invalid(val keyPath: String, val reason: String) : MachineConfigTreeResult
}
