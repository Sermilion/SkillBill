package dev.skillbill.desktop.core.domain.model

import kotlin.jvm.JvmInline

@JvmInline
value class ConfigFingerprint(val value: String)

/** One config document as the runtime read it. */
sealed interface ConfigDocument<out V> {
  data class Absent(
    val path: String,
    val candidatePaths: List<String> = listOf(path),
    val projectStorePath: String? = null,
  ) : ConfigDocument<Nothing>

  data class Unreadable(
    val path: String,
    val keyPath: String?,
    val reason: String,
    val candidatePaths: List<String> = listOf(path),
    val projectStorePath: String? = null,
  ) : ConfigDocument<Nothing>

  data class Present<V>(
    val path: String,
    val fingerprint: ConfigFingerprint,
    val values: V,
    val hasComments: Boolean,
    val candidatePaths: List<String> = listOf(path),
    val projectStorePath: String? = null,
  ) : ConfigDocument<V>
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

sealed interface AuthoredCompactionField<out T> {
  data object Missing : AuthoredCompactionField<Nothing>

  data object Null : AuthoredCompactionField<Nothing>

  data class Value<T>(val value: T) : AuthoredCompactionField<T>
}

data class AuthoredPhaseCompactionFields(
  val windowTokens: AuthoredCompactionField<Int> = AuthoredCompactionField.Missing,
  val triggerPct: AuthoredCompactionField<Int> = AuthoredCompactionField.Missing,
)

data class AuthoredCompactionFields(
  val enabled: AuthoredCompactionField<Boolean> = AuthoredCompactionField.Missing,
  val windowTokens: AuthoredCompactionField<Int> = AuthoredCompactionField.Missing,
  val triggerPct: AuthoredCompactionField<Int> = AuthoredCompactionField.Missing,
  val phases: AuthoredCompactionField<Map<String, AuthoredPhaseCompactionFields>> = AuthoredCompactionField.Missing,
) {
  companion object {
    val EMPTY: AuthoredCompactionFields = AuthoredCompactionFields()
  }
}

data class MachineConfig(
  val executionMatrix: ExecutionMatrixConfig?,
  val compaction: CompactionConfig,
  val telemetryProxyUrl: String?,
  val telemetryBatchSize: Int?,
  val telemetryLevel: String = "anonymous",
  val telemetryInstallId: String? = null,
  val compactionFields: AuthoredCompactionFields = AuthoredCompactionFields.EMPTY,
)

enum class MachineConfigCompactionField { ENABLED, WINDOW_TOKENS, TRIGGER_PCT }

data class MachineConfigCompactionEditIntent(
  val globalFields: Set<MachineConfigCompactionField> = emptySet(),
  val phaseFields: Map<String, Set<MachineConfigCompactionField>> = emptyMap(),
  val addedPhaseIds: Set<String> = emptySet(),
  val removedPhaseIds: Set<String> = emptySet(),
)

data class MachineConfigExecutionMatrixEditIntent(
  val agentTierDirectives: List<MachineConfigAgentTierDirectiveEdit> = emptyList(),
  val phaseTiers: List<MachineConfigPhaseTierEdit> = emptyList(),
  val agentPhaseOverrides: List<MachineConfigAgentPhaseOverrideEdit> = emptyList(),
)

data class MachineConfigAgentTierDirectiveEdit(
  val agentId: String,
  val tier: Tier,
)

data class MachineConfigPhaseTierEdit(
  val phaseId: String,
)

data class MachineConfigAgentPhaseOverrideEdit(
  val agentId: String,
  val phaseId: String,
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

data class ProjectConfig(
  val specSource: SpecSource?,
  val gradleWrapper: String?,
  val reviewBudget: ReviewBudget,
  val authoredReviewContextBudget: AuthoredReviewContextBudget = AuthoredReviewContextBudget.ABSENT,
)

data class AuthoredReviewContextBudget(
  val present: Boolean,
  val entries: List<AuthoredReviewContextBudgetEntry>,
) {
  companion object {
    val ABSENT = AuthoredReviewContextBudget(false, emptyList())
  }
}

data class AuthoredReviewContextBudgetEntry(val key: String, val value: Any?)

/** One edit to the machine document; sections are the document's own key-value shape, validated by the runtime. */
sealed interface MachineConfigChange {
  data class SetExecutionMatrix(
    val section: Map<String, Any?>,
    val editIntent: MachineConfigExecutionMatrixEditIntent? = null,
  ) : MachineConfigChange

  data class SetCompaction(
    val section: Map<String, Any?>,
    val editIntent: MachineConfigCompactionEditIntent? = null,
  ) : MachineConfigChange

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

  data class Unreadable(val reason: String, val keyPath: String?, val path: String?) : ConfigWriteOutcome {
    constructor(reason: String) : this(reason, null, null)
  }

  data class Failed(val problem: ReadProblem) : ConfigWriteOutcome
}

enum class TelemetryLevel(val wireValue: String) { OFF("off"), ANONYMOUS("anonymous"), FULL("full") }

sealed interface TelemetryLevelRead {
  data class Known(val level: TelemetryLevel, val configPath: String) : TelemetryLevelRead

  data class Snapshot(val value: TelemetryLevelSnapshot) : TelemetryLevelRead

  data class Unreadable(val keyPath: String?, val reason: String) : TelemetryLevelRead

  data class Failed(val problem: ReadProblem) : TelemetryLevelRead
}

data class TelemetryLevelSnapshot(
  val configuredLevel: TelemetryLevel,
  val effectiveLevel: TelemetryEffectiveLevel,
  val availability: TelemetryOperationalAvailability,
  val previousLevel: TelemetryPreviousLevel,
  val levelEnvironmentOverride: Boolean,
  val installIdSource: TelemetryInstallIdSource,
  val configHasInstallId: Boolean,
  val configPath: String,
)

sealed interface TelemetryEffectiveLevel {
  data class Known(val level: TelemetryLevel) : TelemetryEffectiveLevel

  data class Unavailable(val reason: String) : TelemetryEffectiveLevel
}

sealed interface TelemetryOperationalAvailability {
  data class Available(val level: TelemetryLevel) : TelemetryOperationalAvailability

  data class Unavailable(val reason: String) : TelemetryOperationalAvailability
}

sealed interface TelemetryPreviousLevel {
  data class Known(val level: TelemetryLevel) : TelemetryPreviousLevel

  data object Unavailable : TelemetryPreviousLevel
}

enum class TelemetryInstallIdSource { CONFIG, ENVIRONMENT, MISSING }

data class DetectedAgent(val name: String, val path: String)

sealed interface DetectedAgentsRead {
  data class Success(val agents: List<DetectedAgent>) : DetectedAgentsRead

  data class Failed(val problem: ReadProblem) : DetectedAgentsRead
}

/** One recognized `<repo>/.skill-bill/phases/<phaseId>.md` entry; metadata fields are null when inspection failed. */
data class PhaseInstructionFile(val phaseId: String, val path: String, val sizeBytes: Long?, val exceedsCap: Boolean?)

sealed interface PhaseInstructionFilesRead {
  val files: List<PhaseInstructionFile>

  data class Complete(override val files: List<PhaseInstructionFile>) : PhaseInstructionFilesRead

  data class Incomplete(
    override val files: List<PhaseInstructionFile>,
    val problems: List<ReadProblem>,
  ) : PhaseInstructionFilesRead
}
