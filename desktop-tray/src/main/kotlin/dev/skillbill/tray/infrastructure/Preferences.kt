package dev.skillbill.tray.infrastructure

import dev.skillbill.tray.application.SettingsStore
import dev.skillbill.tray.domain.*
import dev.skillbill.tray.domain.Retention
import dev.skillbill.tray.infrastructure.cli.RuntimeDiagnostics
import java.util.prefs.Preferences

class PreferencesSettingsStore(private val preferences: Preferences = Preferences.userNodeForPackage(PreferencesSettingsStore::class.java)) : SettingsStore {
  override fun load(): AppSettings {
    val retentionValue = preferences.get("retention", "24h")
    val retention = Retention.entries.firstOrNull { it.cliValue == retentionValue } ?: Retention.DAY.also { RuntimeDiagnostics.record("invalid_retention_preference") }
    val notifications = defaultNotifications.mapValues { (key, default) -> preferences.getBoolean("notify.$key", default) }
    val pollValue = preferences.getInt("pollSeconds", 15)
    val poll = pollValue.takeIf { it in setOf(5, 15, 30, 60) } ?: 15.also { RuntimeDiagnostics.record("invalid_poll_preference") }
    val tab = preferences.get("lastPopoverTab", "Goals").takeIf { it in setOf("Goals", "Phases") }
      ?: "Goals".also { RuntimeDiagnostics.record("invalid_last_tab_preference") }
    return AppSettings(preferences.get("cliOverride", "").ifBlank { null }, poll, retention, notifications,
      preferences.getBoolean("startAtLogin", true), tab)
  }

  override fun save(settings: AppSettings) {
    if (settings.cliOverride.isNullOrBlank()) preferences.remove("cliOverride") else preferences.put("cliOverride", settings.cliOverride)
    preferences.putInt("pollSeconds", settings.pollSeconds)
    preferences.put("retention", settings.retention.cliValue)
    allowedTransitions.forEach { key -> preferences.putBoolean("notify.$key", settings.notifications[key] ?: defaultNotifications.getValue(key)) }
    preferences.putBoolean("startAtLogin", settings.startAtLogin)
    preferences.put("lastPopoverTab", settings.lastPopoverTab)
    preferences.flush()
  }
}
