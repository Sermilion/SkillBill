package dev.skillbill.desktop.core.domain.repository

import dev.skillbill.desktop.core.domain.model.ConfigDocument
import dev.skillbill.desktop.core.domain.model.ConfigFingerprint
import dev.skillbill.desktop.core.domain.model.ConfigDefaults
import dev.skillbill.desktop.core.domain.model.ConfigWriteOutcome
import dev.skillbill.desktop.core.domain.model.DetectedAgentsRead
import dev.skillbill.desktop.core.domain.model.MachineConfig
import dev.skillbill.desktop.core.domain.model.MachineConfigChange
import dev.skillbill.desktop.core.domain.model.ModelMatrixView
import dev.skillbill.desktop.core.domain.model.PhaseInstructionFilesRead
import dev.skillbill.desktop.core.domain.model.ProjectConfig
import dev.skillbill.desktop.core.domain.model.ProjectConfigChange
import dev.skillbill.desktop.core.domain.model.ReadProblem
import dev.skillbill.desktop.core.domain.model.TelemetryLevel
import dev.skillbill.desktop.core.domain.model.TelemetryLevelRead
import dev.skillbill.desktop.core.domain.model.TelemetryPreviousLevel

/** The runtime's machine and project config, telemetry level, agent detection and phase instruction files. */
interface RuntimeConfigRepository {
  /** Runtime defaults and the ordered phase and agent catalogs. */
  fun configDefaults(): ConfigDefaults

  /** Reads the machine document and resolves each agent's phase directives. */
  suspend fun modelMatrix(): ModelMatrixRead

  /** A [ReadProblem] when the runtime could not be reached; the document's own state otherwise. */
  suspend fun machineConfig(): ConfigRead<MachineConfig>

  suspend fun projectConfig(repoRoot: String): ConfigRead<ProjectConfig>

  /** [expected] is the fingerprint the edits were made against; null when the document was absent. */
  suspend fun writeMachineConfig(changes: List<MachineConfigChange>, expected: ConfigFingerprint?): ConfigWriteOutcome

  /** Writes only when the selected config path and fingerprint still match the supplied snapshot. */
  suspend fun writeMachineConfigFromSnapshot(path: String, changes: List<MachineConfigChange>, expected: ConfigFingerprint?): ConfigWriteOutcome =
    ConfigWriteOutcome.Failed(ReadProblem.Runtime("This config repository cannot validate a path-aware machine snapshot."))

  suspend fun writeProjectConfig(repoRoot: String, changes: List<ProjectConfigChange>, expected: ConfigFingerprint?): ConfigWriteOutcome

  /** Writes only when the selected project config path and fingerprint still match the supplied snapshot. */
  suspend fun writeProjectConfigFromSnapshot(
    repoRoot: String,
    path: String,
    changes: List<ProjectConfigChange>,
    expected: ConfigFingerprint?,
  ): ConfigWriteOutcome =
    ConfigWriteOutcome.Failed(ReadProblem.Runtime("This config repository cannot validate a path-aware project snapshot."))

  suspend fun telemetryLevel(): TelemetryLevelRead

  /** Resolves the effective telemetry level from the same machine document read as the page rows. */
  suspend fun telemetryLevel(document: ConfigDocument<MachineConfig>): TelemetryLevelRead = telemetryLevel()

  suspend fun setTelemetryLevel(level: TelemetryLevel, expected: ConfigFingerprint?): ConfigWriteOutcome

  /** Writes the telemetry level against the path, fingerprint, and effective level from one page read. */
  suspend fun setTelemetryLevelFromSnapshot(
    level: TelemetryLevel,
    path: String,
    expected: ConfigFingerprint?,
    previousLevel: String,
  ): ConfigWriteOutcome = ConfigWriteOutcome.Failed(
    ReadProblem.Runtime("This config repository cannot validate a path-aware telemetry snapshot."),
  )

  suspend fun setTelemetryLevelFromSnapshot(
    level: TelemetryLevel,
    path: String,
    expected: ConfigFingerprint?,
    previousLevel: TelemetryPreviousLevel,
  ): ConfigWriteOutcome = when (previousLevel) {
    is TelemetryPreviousLevel.Known -> setTelemetryLevelFromSnapshot(level, path, expected, previousLevel.level.wireValue)
    TelemetryPreviousLevel.Unavailable -> ConfigWriteOutcome.Failed(
      ReadProblem.Runtime("This config repository cannot validate a path-aware telemetry snapshot."),
    )
  }

  /** Preserves the distinction between a successful empty detection and a read failure. */
  suspend fun detectedAgentsRead(): DetectedAgentsRead

  suspend fun claudeConfigDirectories(sessionId: Long): ClaudeConfigDirectoriesRead

  /** Lists known step stems in runtime order, with aliases after their target step; incomplete reads retain observed entries. */
  suspend fun phaseInstructionFiles(repoRoot: String): PhaseInstructionFilesRead
}

sealed interface ClaudeConfigDirectoriesRead {
  data class Success(val directories: List<String>) : ClaudeConfigDirectoriesRead

  data object Unavailable : ClaudeConfigDirectoriesRead

  data class Failed(val problem: ReadProblem) : ClaudeConfigDirectoriesRead
}

sealed interface ConfigRead<out V> {
  data class Document<V>(val document: ConfigDocument<V>) : ConfigRead<V>

  data class Failed(val problem: ReadProblem) : ConfigRead<Nothing>
}

sealed interface ModelMatrixRead {
  data class Success(val value: ModelMatrixView) : ModelMatrixRead

  data class Failed(val problem: ReadProblem) : ModelMatrixRead
}
