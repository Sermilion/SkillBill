package dev.skillbill.desktop.core.datastore

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import dev.skillbill.desktop.core.common.Diagnostics
import dev.skillbill.desktop.core.common.di.AppScope
import dev.skillbill.desktop.core.domain.usage.LastUsageStore
import dev.skillbill.desktop.core.domain.usage.StoredUsageReading
import dev.skillbill.desktop.core.domain.usage.model.UsageProvider
import kotlinx.coroutines.flow.first
import me.tatarka.inject.annotations.Inject
import software.amazon.lastmile.kotlin.inject.anvil.ContributesBinding
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn
import java.io.IOException

/** Keeps the usage card's last agent and readings in the app's preferences file. */
@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class DataStoreLastUsageStore(
  private val store: DataStore<Preferences>,
  private val diagnostics: Diagnostics,
) : LastUsageStore {
  override suspend fun lastAgent(): String? = read { it[AppPreferenceKeys.USAGE_LAST_AGENT]?.takeIf(String::isNotBlank) }

  override suspend fun setLastAgent(agentId: String) {
    if (agentId.isBlank()) return
    edit { it[AppPreferenceKeys.USAGE_LAST_AGENT] = agentId.trim() }
  }

  override suspend fun lastReading(provider: UsageProvider, configurationReference: String?): StoredUsageReading? = read { preferences ->
    preferences.readings().firstOrNull { it.provider == provider && it.configurationReference == configurationReference }?.reading
  }

  override suspend fun setLastReading(provider: UsageProvider, configurationReference: String?, reading: StoredUsageReading) = edit { preferences ->
    val entries = preferences.readings()
    val sameProfile: (StoredUsageEntry) -> Boolean = { it.provider == provider && it.configurationReference == configurationReference }
    if (entries.any { sameProfile(it) && it.reading.observedAt > reading.observedAt }) return@edit
    val next = entries.filterNot(sameProfile) + StoredUsageEntry(provider, configurationReference, reading)
    preferences[AppPreferenceKeys.USAGE_LAST_READINGS] = next.map(UsageReadingEncoding::encode).toSet()
  }

  private fun Preferences.readings(): List<StoredUsageEntry> =
    this[AppPreferenceKeys.USAGE_LAST_READINGS].orEmpty().mapNotNull(UsageReadingEncoding::decode)

  private suspend fun <T> read(select: (Preferences) -> T?): T? = try {
    select(store.data.first())
  } catch (_: IOException) {
    diagnostics.record("desktop_usage_memory_unreadable")
    null
  }

  private suspend fun edit(change: (MutablePreferences) -> Unit) {
    try {
      store.edit(change)
    } catch (_: IOException) {
      diagnostics.record("desktop_usage_memory_write_failed")
    }
  }
}
