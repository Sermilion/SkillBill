package dev.skillbill.desktop.core.domain.repository

import dev.skillbill.desktop.core.domain.model.ConfigDocument
import dev.skillbill.desktop.core.domain.model.ConfigFingerprint
import dev.skillbill.desktop.core.domain.model.ConfigDefaults
import dev.skillbill.desktop.core.domain.model.ConfigWriteOutcome
import dev.skillbill.desktop.core.domain.model.DetectedAgent
import dev.skillbill.desktop.core.domain.model.MachineConfig
import dev.skillbill.desktop.core.domain.model.MachineConfigChange
import dev.skillbill.desktop.core.domain.model.ModelMatrixView
import dev.skillbill.desktop.core.domain.model.PhaseInstructionFile
import dev.skillbill.desktop.core.domain.model.ProjectConfig
import dev.skillbill.desktop.core.domain.model.ProjectConfigChange
import dev.skillbill.desktop.core.domain.model.ReadProblem
import dev.skillbill.desktop.core.domain.model.TelemetryLevel
import dev.skillbill.desktop.core.domain.model.TelemetryLevelRead

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

  suspend fun writeProjectConfig(repoRoot: String, changes: List<ProjectConfigChange>, expected: ConfigFingerprint?): ConfigWriteOutcome

  suspend fun telemetryLevel(): TelemetryLevelRead

  suspend fun setTelemetryLevel(level: TelemetryLevel): ConfigWriteOutcome

  suspend fun detectedAgents(): List<DetectedAgent>

  suspend fun claudeConfigDirectories(sessionId: Long): ClaudeConfigDirectoriesRead

  /** Every `*.md` file directly under `<repoRoot>/.skill-bill/phases`, sorted by name; empty when there are none. */
  suspend fun phaseInstructionFiles(repoRoot: String): List<PhaseInstructionFile>
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
