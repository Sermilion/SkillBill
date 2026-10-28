package skillbill.application.config.model

import skillbill.config.model.CompactionSettings
import skillbill.config.model.ExecutionMatrix
import skillbill.config.model.SpecType
import skillbill.ports.config.model.ConfigFingerprint
import skillbill.review.context.model.accounting.ReviewContextBudgetPolicy
import java.nio.file.Path

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
)

data class ProjectConfigValues(
  val specType: SpecType?,
  val gradleWrapper: String?,
  val reviewContextBudget: ReviewContextBudgetPolicy,
)

sealed interface MachineConfigEdit {
  data class SetExecutionMatrix(internal val section: Map<String, Any?>) : MachineConfigEdit

  data class SetCompaction(internal val section: Map<String, Any?>) : MachineConfigEdit

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

  data class Unreadable(val path: Path, val reason: String) : ConfigWriteResult
}
