package skillbill.application.telemetry.config

import skillbill.application.telemetry.model.TelemetrySnapshotPriorLevel
import skillbill.ports.telemetry.transport.TelemetryConfigStore
import skillbill.ports.telemetry.transport.TelemetryOutboxRepository
import skillbill.ports.telemetry.transport.TelemetrySettingsProvider
import skillbill.telemetry.model.TelemetrySettings
import skillbill.telemetry.telemetryLevels

object TelemetryConfigMutations {
  fun setTelemetryLevel(
    level: String,
    configStore: TelemetryConfigStore,
    settingsProvider: TelemetrySettingsProvider,
    outbox: TelemetryOutboxRepository? = null,
  ): Pair<TelemetrySettings, Int> {
    require(level in telemetryLevels) {
      "Telemetry level must be one of: ${telemetryLevels.joinToString(", ")}."
    }
    return configStore.withConfigTransaction {
      val currentLevel = settingsProvider.load(materialize = false).level
      configStore.mutateTelemetryLevelAtomically(level)
      val clearedEvents = if (clearsPendingOutbox(currentLevel, level)) outbox?.clear().orEmpty() else 0
      settingsProvider.load(materialize = false) to clearedEvents
    }
  }

  fun setTelemetryEnabled(
    enabled: Boolean,
    configStore: TelemetryConfigStore,
    settingsProvider: TelemetrySettingsProvider,
    outbox: TelemetryOutboxRepository? = null,
  ): Pair<TelemetrySettings, Int> =
    setTelemetryLevel(
      level = if (enabled) "anonymous" else "off",
      configStore = configStore,
      settingsProvider = settingsProvider,
      outbox = outbox,
    )
}

internal fun clearsPendingOutbox(
  currentLevel: String,
  newLevel: String,
): Boolean = clearsPendingOutbox(TelemetrySnapshotPriorLevel.Known(currentLevel), newLevel)

internal fun clearsPendingOutbox(
  currentLevel: TelemetrySnapshotPriorLevel,
  newLevel: String,
): Boolean {
  if (newLevel == "off") return true
  val current =
    when (currentLevel) {
      is TelemetrySnapshotPriorLevel.Known ->
        telemetryLevels.indexOf(currentLevel.level).takeIf {
          it >= 0
        } ?: telemetryLevels.lastIndex
      TelemetrySnapshotPriorLevel.Unavailable -> telemetryLevels.lastIndex
    }
  return telemetryLevels.indexOf(newLevel) < current
}

private fun Int?.orEmpty(): Int = this ?: 0
