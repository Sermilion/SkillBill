package dev.skillbill.tray.core.datastore

import dev.skillbill.tray.core.domain.model.AppSettings
import dev.skillbill.tray.core.domain.model.HistoryFilter
import dev.skillbill.tray.core.domain.model.Retention
import dev.skillbill.tray.core.domain.model.defaultNotifications
import kotlinx.serialization.Serializable

internal val validPopoverTabs = setOf("Goals", "Phases")

@Serializable
internal data class StoredSettings(
  val migrated: Boolean = false,
  val databasePath: String? = null,
  val retention: String? = null,
  val historyFilter: HistoryFilter = HistoryFilter.ALL,
  val notifications: Map<String, Boolean>? = null,
  val startAtLogin: Boolean? = null,
  val lastPopoverTab: String? = null,
) {
  fun toDomain(): AppSettings = AppSettings(
    databasePath = databasePath,
    historyFilter = historyFilter,
    retention = Retention.entries.single { it.cliValue == (retention ?: Retention.DAY.cliValue) },
    notifications = defaultNotifications + notifications.orEmpty(),
    startAtLogin = startAtLogin ?: true,
    lastPopoverTab = lastPopoverTab ?: "Goals",
  )

  fun withSettings(settings: AppSettings): StoredSettings = copy(
    migrated = true,
    databasePath = settings.databasePath,
    retention = settings.retention.cliValue,
    historyFilter = settings.historyFilter,
    notifications = settings.notifications,
    startAtLogin = settings.startAtLogin,
    lastPopoverTab = settings.lastPopoverTab,
  )

  /** True when a persisted value is outside the supported vocabulary, so the file must be reported corrupt. */
  fun unsupportedValue(): String? = when {
    retention != null && Retention.entries.none { it.cliValue == retention } -> "Unsupported history retention in settings."
    lastPopoverTab != null && lastPopoverTab !in validPopoverTabs -> "Unsupported tray tab in settings."
    else -> null
  }
}
