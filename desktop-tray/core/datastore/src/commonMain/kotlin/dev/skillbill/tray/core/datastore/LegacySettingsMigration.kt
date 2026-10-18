package dev.skillbill.tray.core.datastore

import androidx.datastore.core.DataMigration
import dev.skillbill.tray.core.common.Diagnostics
import dev.skillbill.tray.core.domain.model.Retention
import dev.skillbill.tray.core.domain.model.defaultNotifications

/** Preferences written by earlier app versions, read once and then cleaned up. */
interface LegacyPreferencesSource {
  /** Attributes reports to the store the value came from. */
  val description: String
  fun string(key: String): String?
  fun boolean(key: String, default: Boolean): Boolean
  fun remove(key: String)
  fun flush()
}

/** One retired preference that migration discarded instead of converting. */
data class SettingsMigrationReport(val key: String, val source: String, val reason: String)

fun interface SettingsMigrationSink {
  fun report(report: SettingsMigrationReport)
}

internal const val LEGACY_CLI_OVERRIDE = "cliOverride"
internal const val LEGACY_POLL_SECONDS = "pollSeconds"

internal class LegacySettingsMigration(
  private val legacy: LegacyPreferencesSource,
  private val sink: SettingsMigrationSink,
  private val diagnostics: Diagnostics,
) : DataMigration<StoredSettings> {
  override suspend fun shouldMigrate(currentData: StoredSettings): Boolean = !currentData.migrated

  override suspend fun migrate(currentData: StoredSettings): StoredSettings {
    val retention = legacy.string("retention") ?: Retention.DAY.cliValue
    val tab = legacy.string("lastPopoverTab") ?: "Goals"
    return currentData.copy(
      migrated = true,
      retention = currentData.retention ?: retention.takeIf { value -> Retention.entries.any { it.cliValue == value } }
        ?: Retention.DAY.cliValue.also { diagnostics.record("invalid_retention_preference") },
      notifications = currentData.notifications ?: defaultNotifications.mapValues { (key, default) -> legacy.boolean("notify.$key", default) },
      startAtLogin = currentData.startAtLogin ?: legacy.boolean("startAtLogin", true),
      lastPopoverTab = currentData.lastPopoverTab ?: tab.takeIf { it in validPopoverTabs }
        ?: "Goals".also { diagnostics.record("invalid_last_tab_preference") },
    )
  }

  override suspend fun cleanUp() {
    if (legacy.string(LEGACY_CLI_OVERRIDE) != null) {
      sink.report(SettingsMigrationReport(LEGACY_CLI_OVERRIDE, legacy.description, "The executable override is retired and is never used as a database path."))
    }
    if (legacy.string(LEGACY_POLL_SECONDS) != null) {
      sink.report(SettingsMigrationReport(LEGACY_POLL_SECONDS, legacy.description, "Polling is retired because committed writes are observed."))
    }
    legacy.remove(LEGACY_CLI_OVERRIDE)
    legacy.remove(LEGACY_POLL_SECONDS)
    legacy.flush()
  }
}
