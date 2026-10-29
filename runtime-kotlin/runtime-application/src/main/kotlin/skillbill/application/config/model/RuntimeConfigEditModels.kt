package skillbill.application.config.model

import skillbill.config.model.CompactionSettings
import skillbill.config.model.CompactionSettingsFields
import skillbill.config.model.ExecutionMatrix
import skillbill.config.model.ExecutionTier
import skillbill.config.model.SpecType
import skillbill.ports.config.model.ConfigDocumentTree
import skillbill.ports.config.model.ConfigFingerprint
import skillbill.review.context.model.accounting.ReviewContextBudgetPolicy
import java.nio.file.Path

data class ConfigDocumentSnapshotRead<out V>(
  val document: ConfigDocumentRead<V>,
  val candidatePaths: List<Path>,
  val projectStorePath: Path? = null,
)

sealed interface ConfigDocumentRead<out V> {
  data class Absent(val path: Path) : ConfigDocumentRead<Nothing>

  data class Unreadable(val path: Path, val keyPath: String?, val reason: String) : ConfigDocumentRead<Nothing>

  data class Present<V>(
    val path: Path,
    val fingerprint: ConfigFingerprint,
    val values: V,
    val hasComments: Boolean,
  ) : ConfigDocumentRead<V>
}

data class MachineConfigValues(
  val executionMatrix: ExecutionMatrix?,
  val compaction: CompactionSettings,
  val telemetryProxyUrl: String?,
  val telemetryBatchSize: Int?,
  val telemetryLevel: String = "anonymous",
  val telemetryInstallId: String? = null,
  val compactionFields: CompactionSettingsFields = CompactionSettingsFields.EMPTY,
)

data class ProjectConfigValues(
  val specType: SpecType?,
  val gradleWrapper: String?,
  val reviewContextBudget: ReviewContextBudgetPolicy,
  val authoredReviewContextBudget: AuthoredReviewContextBudget = AuthoredReviewContextBudget.EMPTY,
)

data class AuthoredReviewContextBudget internal constructor(
  val present: Boolean,
  val tree: ConfigDocumentTree,
) {
  companion object {
    val EMPTY = AuthoredReviewContextBudget(false, ConfigDocumentTree(emptyMap()))
  }
}

enum class CompactionEditField { ENABLED, WINDOW_TOKENS, TRIGGER_PCT }

data class CompactionEditIntent(
  val globalFields: Set<CompactionEditField> = emptySet(),
  val phaseFields: Map<String, Set<CompactionEditField>> = emptyMap(),
  val addedPhaseIds: Set<String> = emptySet(),
  val removedPhaseIds: Set<String> = emptySet(),
)

data class ExecutionMatrixEditIntent(
  val agentTierDirectives: List<ExecutionMatrixAgentTierDirectiveEdit> = emptyList(),
  val phaseTiers: List<ExecutionMatrixPhaseTierEdit> = emptyList(),
  val agentPhaseOverrides: List<ExecutionMatrixAgentPhaseOverrideEdit> = emptyList(),
)

data class ExecutionMatrixAgentTierDirectiveEdit(
  val agentId: String,
  val tier: ExecutionTier,
)

data class ExecutionMatrixPhaseTierEdit(
  val phaseId: String,
)

data class ExecutionMatrixAgentPhaseOverrideEdit(
  val agentId: String,
  val phaseId: String,
)

sealed interface MachineConfigEdit {
  data class SetExecutionMatrix(
    internal val section: Map<String, Any?>,
    val editIntent: ExecutionMatrixEditIntent? = null,
  ) : MachineConfigEdit

  data class SetCompaction(
    internal val section: Map<String, Any?>,
    val editIntent: CompactionEditIntent? = null,
  ) : MachineConfigEdit

  data class SetTelemetryProxyUrl(val value: String?) : MachineConfigEdit

  data class SetTelemetryBatchSize(val value: Int?) : MachineConfigEdit
}

sealed interface ProjectConfigEdit {
  data class SetSpecType(val specType: SpecType) : ProjectConfigEdit

  data class SetGradleWrapper(val value: String?) : ProjectConfigEdit

  data class SetReviewContextBudget(internal val section: Map<String, Any?>) : ProjectConfigEdit
}

sealed interface ConfigWriteResult {
  data class Written(val path: Path, val fingerprint: ConfigFingerprint) : ConfigWriteResult

  data class Invalid(val keyPath: String, val reason: String) : ConfigWriteResult

  data class ChangedOnDisk(val path: Path) : ConfigWriteResult

  data class Unreadable(val path: Path, val reason: String, val keyPath: String?) : ConfigWriteResult {
    constructor(path: Path, reason: String) : this(path, reason, null)
  }
}
