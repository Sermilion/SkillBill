package skillbill.application.telemetry.model

data class TelemetrySnapshotEnvironment(
  val level: String?,
  val enabled: String?,
  val installId: String?,
  val batchSize: String?,
)

enum class TelemetrySnapshotInstallIdSource { CONFIG, ENVIRONMENT, MISSING }

sealed interface TelemetrySnapshotEffectiveLevel {
  data class Known(val level: String) : TelemetrySnapshotEffectiveLevel

  data class Unavailable(val reason: String) : TelemetrySnapshotEffectiveLevel
}

sealed interface TelemetrySnapshotAvailability {
  data class Available(val level: String) : TelemetrySnapshotAvailability

  data class Unavailable(val reason: String) : TelemetrySnapshotAvailability
}

sealed interface TelemetrySnapshotPriorLevel {
  data class Known(val level: String) : TelemetrySnapshotPriorLevel

  data object Unavailable : TelemetrySnapshotPriorLevel
}

data class TelemetrySettingsSnapshot(
  val configuredLevel: String,
  val effectiveLevel: TelemetrySnapshotEffectiveLevel,
  val availability: TelemetrySnapshotAvailability,
  val previousLevel: TelemetrySnapshotPriorLevel,
  val levelEnvironmentOverride: Boolean,
  val installIdSource: TelemetrySnapshotInstallIdSource,
  val configHasInstallId: Boolean,
)
