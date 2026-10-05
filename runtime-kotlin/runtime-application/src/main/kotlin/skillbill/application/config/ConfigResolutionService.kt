package skillbill.application.config

import me.tatarka.inject.annotations.Inject
import skillbill.config.model.COMPACTION_KEY
import skillbill.config.model.CompactionSettings
import skillbill.config.model.CompactionSettingsParse
import skillbill.config.model.EXECUTION_MATRIX_KEY
import skillbill.config.model.ExecutionMatrix
import skillbill.config.model.ExecutionMatrixParse
import skillbill.config.model.RepoLocalConfigResolution
import skillbill.config.model.SpecType
import skillbill.config.model.parseCompactionSettings
import skillbill.config.model.parseExecutionMatrix
import skillbill.error.shellcontent.malformedMachineConfigError
import skillbill.ports.config.RepoLocalConfigPort
import skillbill.ports.config.model.ReadRepoLocalConfigRequest
import skillbill.ports.telemetry.model.TelemetryConfigRead
import skillbill.ports.telemetry.transport.TelemetryConfigStore
import java.nio.file.Path

@Inject
class ConfigResolutionService(
  private val repoLocalConfigPort: RepoLocalConfigPort,
  private val machineConfigStore: TelemetryConfigStore,
) {
  fun resolveExecutionMatrix(): ExecutionMatrix? {
    val configPath = machineConfigStore.configPath()
    val payload =
      when (val read = machineConfigStore.read()) {
        TelemetryConfigRead.Absent -> return null
        is TelemetryConfigRead.Malformed ->
          throw malformedMachineConfigError(
            path = configPath.toString(),
            key = "",
            value = "<document>",
            reason = "is not valid JSON.",
          )
        is TelemetryConfigRead.Present -> read.document.payload
      }
    if (!payload.containsKey(EXECUTION_MATRIX_KEY)) return null
    return when (val parsed = parseExecutionMatrix(payload[EXECUTION_MATRIX_KEY])) {
      is ExecutionMatrixParse.Valid -> parsed.matrix
      is ExecutionMatrixParse.Invalid -> throw malformedMachineConfigError(
        path = configPath.toString(),
        key = parsed.keyPath,
        value = parsed.value,
        reason = parsed.reason,
      )
    }
  }

  fun resolveCompactionSettings(): CompactionSettings {
    val configPath = machineConfigStore.configPath()
    val payload =
      when (val read = machineConfigStore.read()) {
        TelemetryConfigRead.Absent -> return CompactionSettings.DEFAULT
        is TelemetryConfigRead.Malformed ->
          throw malformedMachineConfigError(
            path = configPath.toString(),
            key = "",
            value = "<document>",
            reason = "is not valid JSON.",
          )
        is TelemetryConfigRead.Present -> read.document.payload
      }
    if (!payload.containsKey(COMPACTION_KEY)) return CompactionSettings.DEFAULT
    return when (val parsed = parseCompactionSettings(payload[COMPACTION_KEY])) {
      is CompactionSettingsParse.Valid -> parsed.settings
      is CompactionSettingsParse.Invalid -> throw malformedMachineConfigError(
        path = configPath.toString(),
        key = parsed.keyPath,
        value = parsed.value,
        reason = parsed.reason,
      )
    }
  }

  fun resolveSpecType(
    repoRoot: Path,
    explicit: SpecType?,
  ): SpecType {
    val config = repoLocalConfigPort.readRepoLocalConfig(ReadRepoLocalConfigRequest(repoRoot)).config
    return RepoLocalConfigResolution.resolve(explicit, config.specType, SpecType.LOCAL)
  }
}
