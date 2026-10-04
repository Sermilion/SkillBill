package skillbill.ports.taskruntime.model

sealed interface FeatureTaskRuntimePhaseOutputMigrationResult {
  data class Current(val payload: String) : FeatureTaskRuntimePhaseOutputMigrationResult

  data class Migrated(
    val payload: String,
    val sourceVersion: String,
    val targetVersion: String,
  ) : FeatureTaskRuntimePhaseOutputMigrationResult

  data class Refused(
    val sourceVersion: String?,
    val targetVersion: String?,
    val result: Refusal,
  ) : FeatureTaskRuntimePhaseOutputMigrationResult

  enum class Refusal {
    UNSUPPORTED,
    CORRUPT,
    NON_CONVERTIBLE,
  }
}
