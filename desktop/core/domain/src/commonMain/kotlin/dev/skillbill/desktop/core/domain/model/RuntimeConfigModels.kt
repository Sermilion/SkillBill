package dev.skillbill.desktop.core.domain.model

import kotlin.jvm.JvmInline

@JvmInline
value class ConfigFingerprint(val value: String)

/** One config document as the runtime read it. */
sealed interface ConfigDocument<out V> {
  data class Absent(val path: String) : ConfigDocument<Nothing>

  data class Unreadable(val path: String, val keyPath: String?, val reason: String) : ConfigDocument<Nothing>

  data class Present<V>(val path: String, val fingerprint: ConfigFingerprint, val values: V, val hasComments: Boolean) :
    ConfigDocument<V>
}

enum class Tier { REASONING, IMPLEMENTATION }

data class ModelDirective(val model: String, val effort: String?)

/** Agent keys are the runtime's agent ids. */
data class ExecutionMatrixConfig(
  val phaseTiers: Map<String, Tier>,
  val agents: Map<String, Map<Tier, ModelDirective>>,
  val agentPhaseOverrides: Map<String, Map<String, ModelDirective>>,
)

data class PhaseCompaction(val windowTokens: Int, val triggerPct: Int)

data class CompactionConfig(val enabled: Boolean, val windowTokens: Int, val triggerPct: Int, val phases: Map<String, PhaseCompaction>)

data class MachineConfig(
  val executionMatrix: ExecutionMatrixConfig?,
  val compaction: CompactionConfig,
  val telemetryProxyUrl: String?,
  val telemetryBatchSize: Int?,
)

enum class SpecSource { LOCAL, LINEAR }

data class ReviewBudget(
  val maxParentPacketBytes: Long,
  val maxLaneLaunchBytes: Long,
  val maxLaneEvidenceBytes: Long,
  val maxEvidenceResultBytes: Long,
  val maxLaneResultBytes: Long,
  val maxAssignmentExpansions: Int,
  val maxSpecialistToolCalls: Int,
  val maxSpecialistModelTurns: Int,
  val maxRoutingAnalysisPairs: Int,
  val maxRoutingAnalysisBytes: Long,
  val maxSpecIntentProjectionBytes: Long,
)

data class ProjectConfig(val specSource: SpecSource?, val gradleWrapper: String?, val reviewBudget: ReviewBudget)

/** One edit to the machine document; sections are the document's own key-value shape, validated by the runtime. */
sealed interface MachineConfigChange {
  data class SetExecutionMatrix(val section: Map<String, Any?>) : MachineConfigChange

  data class SetCompaction(val section: Map<String, Any?>) : MachineConfigChange

  data class SetTelemetryProxyUrl(val value: String?) : MachineConfigChange

  data class SetTelemetryBatchSize(val value: Int?) : MachineConfigChange
}

sealed interface ProjectConfigChange {
  data class SetSpecType(val source: SpecSource) : ProjectConfigChange

  data class SetGradleWrapper(val value: String?) : ProjectConfigChange

  data class SetReviewContextBudget(val section: Map<String, Any?>) : ProjectConfigChange
}

sealed interface ConfigWriteOutcome {
  data class Written(val fingerprint: ConfigFingerprint) : ConfigWriteOutcome

  data class Invalid(val keyPath: String, val reason: String) : ConfigWriteOutcome

  /** The document changed since it was read; re-read before writing again. */
  data object ChangedOnDisk : ConfigWriteOutcome

  data class Unreadable(val reason: String) : ConfigWriteOutcome

  data class Failed(val problem: ReadProblem) : ConfigWriteOutcome
}

enum class TelemetryLevel(val wireValue: String) { OFF("off"), ANONYMOUS("anonymous"), FULL("full") }

sealed interface TelemetryLevelRead {
  data class Known(val level: TelemetryLevel, val configPath: String) : TelemetryLevelRead

  data class Unreadable(val keyPath: String?, val reason: String) : TelemetryLevelRead

  data class Failed(val problem: ReadProblem) : TelemetryLevelRead
}

data class DetectedAgent(val name: String, val path: String)

/** One `<repo>/.skill-bill/phases/<phaseId>.md` file; [exceedsCap] means the runtime will skip it as too large. */
data class PhaseInstructionFile(val phaseId: String, val path: String, val sizeBytes: Long, val exceedsCap: Boolean)
