package dev.skillbill.desktop.core.datastore

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import dev.skillbill.desktop.core.common.Diagnostics
import dev.skillbill.desktop.core.common.Outcome
import dev.skillbill.desktop.core.common.di.AppScope
import dev.skillbill.desktop.core.domain.settings.AppSettings
import dev.skillbill.desktop.core.domain.settings.AppSettingsRepository
import dev.skillbill.desktop.core.domain.settings.HistoryWindow
import dev.skillbill.desktop.core.domain.settings.NotificationKind
import dev.skillbill.desktop.core.domain.settings.SettingsRejection
import dev.skillbill.desktop.core.domain.settings.TrayTab
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.update
import me.tatarka.inject.annotations.Inject
import software.amazon.lastmile.kotlin.inject.anvil.ContributesBinding
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn
import java.io.IOException

@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class DataStoreAppSettingsRepository(
  private val store: DataStore<Preferences>,
  private val diagnostics: Diagnostics,
) : AppSettingsRepository {
  /** Bumped after every successful edit, so a read that ended on an [IOException] is collected again once the store writes. */
  private val writes = MutableStateFlow(0L)

  override val settings: Flow<AppSettings> = flow {
    var reported = emptySet<String>()
    while (true) {
      val seenWrites = writes.value
      store.data
        .catch { failure ->
          if (failure !is IOException) throw failure
          diagnostics.record("desktop_settings_unreadable")
          emit(emptyPreferences())
        }
        .collect { preferences ->
          val unsupported = mutableSetOf<String>()
          val decoded = preferences.toAppSettings { unsupported += it }
          // Record once per newly seen bad value, not again on every edit to another key.
          if (!reported.containsAll(unsupported)) diagnostics.record("desktop_settings_value_unsupported")
          reported = unsupported
          emit(decoded)
        }
      // store.data only completes after a caught read failure; wait for a write instead of polling.
      writes.first { it != seenWrites }
    }
  }.distinctUntilChanged()

  override suspend fun setDatabasePath(path: String?): Outcome<Unit, SettingsRejection> {
    if (!path.isNullOrEmpty() && path.isBlank()) return Outcome.Failure(SettingsRejection.BlankDatabasePath)
    return edit { if (path.isNullOrEmpty()) it.remove(AppPreferenceKeys.DATABASE_PATH) else it[AppPreferenceKeys.DATABASE_PATH] = path.trim() }
  }

  override suspend fun setClaudeConfigDir(directory: String?) = edit {
    if (directory.isNullOrBlank()) it.remove(AppPreferenceKeys.CLAUDE_CONFIG_DIR) else it[AppPreferenceKeys.CLAUDE_CONFIG_DIR] = directory.trim()
  }

  override suspend fun setHistoryWindow(window: HistoryWindow) = edit { it[AppPreferenceKeys.HISTORY_RETENTION] = window.wireValue }

  override suspend fun setNotification(kind: NotificationKind, enabled: Boolean) =
    edit { it[AppPreferenceKeys.notificationKey(kind)] = enabled }

  override suspend fun setStartAtLogin(enabled: Boolean) = edit { it[AppPreferenceKeys.START_AT_LOGIN] = enabled }

  override suspend fun setTerminalHeightDp(heightDp: Int) = edit {
    it[AppPreferenceKeys.TERMINAL_HEIGHT_DP] = heightDp.coerceIn(AppSettings.TERMINAL_HEIGHT_MIN_DP, AppSettings.TERMINAL_HEIGHT_MAX_DP)
  }

  override suspend fun setTrayLastTab(tab: TrayTab) = edit { it[AppPreferenceKeys.TRAY_LAST_TAB] = tab.wireValue }

  private suspend fun edit(change: (MutablePreferences) -> Unit): Outcome<Unit, SettingsRejection> = try {
    store.edit(change)
    writes.update { it + 1 }
    Outcome.Success(Unit)
  } catch (_: IOException) {
    diagnostics.record("desktop_settings_write_failed")
    Outcome.Failure(SettingsRejection.WriteFailed)
  }
}
