package dev.skillbill.desktop.core.datastore

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import dev.skillbill.desktop.core.common.Diagnostics
import dev.skillbill.desktop.core.common.di.AppScope
import dev.skillbill.desktop.core.domain.model.ChatModel
import dev.skillbill.desktop.core.domain.repository.ChatModelCache
import kotlinx.coroutines.flow.first
import me.tatarka.inject.annotations.Inject
import software.amazon.lastmile.kotlin.inject.anvil.ContributesBinding
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn
import java.io.IOException

/** Keeps each agent profile's last reported model list in the app's preferences file. */
@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class DataStoreChatModelCache(
  private val store: DataStore<Preferences>,
  private val diagnostics: Diagnostics,
) : ChatModelCache {
  override suspend fun read(agentId: String, profile: String?): List<ChatModel>? = read { preferences ->
    preferences.entries().firstOrNull { it.agentId == agentId && it.profile == profile }?.models
  }

  override suspend fun write(agentId: String, profile: String?, models: List<ChatModel>) = edit { preferences ->
    val entries = preferences.entries()
    val sameProfile: (StoredChatModels) -> Boolean = { it.agentId == agentId && it.profile == profile }
    if (entries.firstOrNull(sameProfile)?.models == models) return@edit
    val next = entries.filterNot(sameProfile) + StoredChatModels(agentId, profile, models)
    preferences[AppPreferenceKeys.CHAT_MODELS] = next.map(ChatModelCacheEncoding::encode).toSet()
  }

  private fun Preferences.entries(): List<StoredChatModels> =
    this[AppPreferenceKeys.CHAT_MODELS].orEmpty().mapNotNull(ChatModelCacheEncoding::decode)

  private suspend fun <T> read(select: (Preferences) -> T?): T? = try {
    select(store.data.first())
  } catch (_: IOException) {
    diagnostics.record("desktop_model_cache_unreadable")
    null
  }

  private suspend fun edit(change: (MutablePreferences) -> Unit) {
    try {
      store.edit(change)
    } catch (_: IOException) {
      diagnostics.record("desktop_model_cache_write_failed")
    }
  }
}
