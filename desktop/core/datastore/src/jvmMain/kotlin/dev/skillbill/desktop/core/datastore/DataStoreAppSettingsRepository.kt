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
import dev.skillbill.desktop.core.domain.settings.FavoriteSpecKey
import dev.skillbill.desktop.core.domain.settings.FavoriteWorkKey
import dev.skillbill.desktop.core.domain.settings.HistoryWindow
import dev.skillbill.desktop.core.domain.settings.NewTaskChatLayout
import dev.skillbill.desktop.core.domain.settings.NotificationKind
import dev.skillbill.desktop.core.domain.settings.SettingsRejection
import dev.skillbill.desktop.core.domain.settings.TrayTab
import dev.skillbill.desktop.core.domain.settings.CriterionMark
import dev.skillbill.desktop.core.domain.settings.CriterionMarkKey
import dev.skillbill.desktop.core.domain.qa.QaDevicePick
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withTimeoutOrNull
import me.tatarka.inject.annotations.Inject
import software.amazon.lastmile.kotlin.inject.anvil.ContributesBinding
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn
import java.io.IOException

private const val FAVORITE_READ_RETRY_MILLIS = 5_000L

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

  override val favoriteWork: Flow<Outcome<Set<FavoriteWorkKey>, SettingsRejection>> = flow {
    while (true) {
      val seenWrites = writes.value
      store.data
        .map { preferences ->
          val decoded = preferences.toAppSettings {}
          val result: Outcome<Set<FavoriteWorkKey>, SettingsRejection> = Outcome.Success(decoded.favoriteWork)
          result
        }
        .catch { failure ->
          if (failure !is IOException) throw failure
          diagnostics.record("desktop_settings_unreadable")
          emit(Outcome.Failure(SettingsRejection.ReadFailed))
        }
        .collect { emit(it) }
      withTimeoutOrNull(FAVORITE_READ_RETRY_MILLIS) { writes.first { it != seenWrites } }
    }
  }.distinctUntilChanged()

  override val favoriteSpecs: Flow<Outcome<Set<FavoriteSpecKey>, SettingsRejection>> = flow {
    while (true) {
      val seenWrites = writes.value
      store.data
        .map { preferences ->
          val decoded = preferences.toAppSettings {}
          Outcome.Success(decoded.favoriteSpecs)
        }
        .catch { failure ->
          if (failure !is IOException) throw failure
          diagnostics.record("desktop_settings_unreadable")
          emit(Outcome.Failure(SettingsRejection.ReadFailed))
        }
        .collect { emit(it) }
      withTimeoutOrNull(FAVORITE_READ_RETRY_MILLIS) { writes.first { it != seenWrites } }
    }
  }.distinctUntilChanged()

  override val criterionMarks: Flow<Outcome<Map<CriterionMarkKey, CriterionMark>, SettingsRejection>> = flow {
    while (true) {
      val seenWrites = writes.value
      store.data
        .map { preferences -> Outcome.Success(preferences.criterionMarks()) }
        .catch { failure ->
          if (failure !is IOException) throw failure
          diagnostics.record("desktop_settings_unreadable")
          emit(Outcome.Failure(SettingsRejection.ReadFailed))
        }
        .collect { emit(it) }
      withTimeoutOrNull(FAVORITE_READ_RETRY_MILLIS) { writes.first { it != seenWrites } }
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

  override suspend fun setNewTaskChatLayout(layout: NewTaskChatLayout) =
    edit { it[AppPreferenceKeys.NEW_TASK_CHAT_LAYOUT] = layout.wireValue }

  override suspend fun setQaDevice(pick: QaDevicePick?): Outcome<Unit, SettingsRejection> {
    if (pick != null && !pick.isValid) {
      return Outcome.Failure(SettingsRejection.InvalidQaDevice)
    }
    return edit { preferences ->
      if (pick == null) {
        preferences.remove(AppPreferenceKeys.QA_DEVICE_KIND)
        preferences.remove(AppPreferenceKeys.QA_DEVICE_VALUE)
        preferences.remove(AppPreferenceKeys.QA_DEVICE_DISPLAY_NAME)
      } else {
        preferences[AppPreferenceKeys.QA_DEVICE_KIND] = pick.kind.wireValue
        preferences[AppPreferenceKeys.QA_DEVICE_VALUE] = pick.value
        preferences[AppPreferenceKeys.QA_DEVICE_DISPLAY_NAME] = pick.displayName
      }
    }
  }

  override suspend fun readQaDevice(): Outcome<QaDevicePick?, SettingsRejection> = try {
    val preferences = store.data.first()
    var unsupported = false
    val value = preferences.qaDevice { unsupported = true }
    if (unsupported) diagnostics.record("desktop_settings_value_unsupported")
    Outcome.Success(value)
  } catch (_: IOException) {
    diagnostics.record("desktop_settings_unreadable")
    Outcome.Failure(SettingsRejection.ReadFailed)
  }

  override suspend fun setDefaultAgent(agentId: String?) = edit {
    if (agentId.isNullOrBlank()) it.remove(AppPreferenceKeys.DEFAULT_AGENT) else it[AppPreferenceKeys.DEFAULT_AGENT] = agentId.trim()
  }

  override suspend fun setNotification(kind: NotificationKind, enabled: Boolean) =
    edit { it[AppPreferenceKeys.notificationKey(kind)] = enabled }

  override suspend fun setStartAtLogin(enabled: Boolean) = edit { it[AppPreferenceKeys.START_AT_LOGIN] = enabled }

  override suspend fun setTerminalHeightDp(heightDp: Int) = edit {
    it[AppPreferenceKeys.TERMINAL_HEIGHT_DP] = heightDp.coerceIn(AppSettings.TERMINAL_HEIGHT_MIN_DP, AppSettings.TERMINAL_HEIGHT_MAX_DP)
  }

  override suspend fun setChatWidthDp(widthDp: Int) = edit {
    it[AppPreferenceKeys.CHAT_WIDTH_DP] = widthDp.coerceIn(AppSettings.CHAT_WIDTH_MIN_DP, AppSettings.CHAT_WIDTH_MAX_DP)
  }

  override suspend fun setDetailsWidthDp(widthDp: Int?): Outcome<Unit, SettingsRejection> {
    if (widthDp != null && widthDp < AppSettings.DETAILS_WIDTH_MIN_DP) {
      return Outcome.Failure(SettingsRejection.InvalidDetailsWidth)
    }
    return edit { preferences ->
      if (widthDp == null) preferences.remove(AppPreferenceKeys.DETAILS_WIDTH_DP)
      else preferences[AppPreferenceKeys.DETAILS_WIDTH_DP] = widthDp
    }
  }

  override suspend fun setTrayLastTab(tab: TrayTab) = edit { it[AppPreferenceKeys.TRAY_LAST_TAB] = tab.wireValue }

  override suspend fun setFavorite(key: FavoriteWorkKey, favorite: Boolean) = edit { preferences ->
    val encoded = FavoriteWorkKeyEncoding.encode(key)
    val stored = preferences[AppPreferenceKeys.FAVORITE_WORK].orEmpty().toMutableSet()
    if (favorite) stored.add(encoded) else stored.remove(encoded)
    if (stored.isEmpty()) preferences.remove(AppPreferenceKeys.FAVORITE_WORK)
    else preferences[AppPreferenceKeys.FAVORITE_WORK] = stored
  }

  override suspend fun setSpecFavorite(key: FavoriteSpecKey, favorite: Boolean) = edit { preferences ->
    val encoded = FavoriteSpecKeyEncoding.encode(key)
    val stored = preferences[AppPreferenceKeys.FAVORITE_SPECS].orEmpty().toMutableSet()
    if (favorite) stored.add(encoded) else stored.remove(encoded)
    if (stored.isEmpty()) preferences.remove(AppPreferenceKeys.FAVORITE_SPECS)
    else preferences[AppPreferenceKeys.FAVORITE_SPECS] = stored
  }

  override suspend fun setCriterionMark(key: CriterionMarkKey, mark: CriterionMark?) = edit { preferences ->
    val stored = preferences[AppPreferenceKeys.CRITERION_MARKS].orEmpty().toMutableSet()
    stored.removeAll { CriterionMarkEncoding.decode(it)?.first == key }
    if (mark != null) stored.add(CriterionMarkEncoding.encode(key, mark))
    if (stored.isEmpty()) preferences.remove(AppPreferenceKeys.CRITERION_MARKS)
    else preferences[AppPreferenceKeys.CRITERION_MARKS] = stored
  }

  private suspend fun edit(change: (MutablePreferences) -> Unit): Outcome<Unit, SettingsRejection> = try {
    store.edit(change)
    writes.update { it + 1 }
    Outcome.Success(Unit)
  } catch (_: IOException) {
    diagnostics.record("desktop_settings_write_failed")
    Outcome.Failure(SettingsRejection.WriteFailed)
  }
}
