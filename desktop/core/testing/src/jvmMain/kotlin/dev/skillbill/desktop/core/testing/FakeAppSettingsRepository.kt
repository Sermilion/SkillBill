package dev.skillbill.desktop.core.testing

import dev.skillbill.desktop.core.common.Outcome
import dev.skillbill.desktop.core.domain.settings.AppSettings
import dev.skillbill.desktop.core.domain.settings.AppSettingsRepository
import dev.skillbill.desktop.core.domain.settings.HistoryWindow
import dev.skillbill.desktop.core.domain.settings.FavoriteSpecKey
import dev.skillbill.desktop.core.domain.settings.FavoriteWorkKey
import dev.skillbill.desktop.core.domain.settings.NewTaskChatLayout
import dev.skillbill.desktop.core.domain.settings.NotificationKind
import dev.skillbill.desktop.core.domain.settings.SettingsRejection
import dev.skillbill.desktop.core.domain.settings.TrayTab
import dev.skillbill.desktop.core.domain.settings.CriterionMark
import dev.skillbill.desktop.core.domain.settings.CriterionMarkKey
import dev.skillbill.desktop.core.domain.qa.QaDevicePick
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update

/** In-memory settings that apply the same blank-path rule as the DataStore repository. */
class FakeAppSettingsRepository(initial: AppSettings = AppSettings.DEFAULT) : AppSettingsRepository {
  override val settings = MutableStateFlow(initial)
  private val favoriteReadFailureState = MutableStateFlow(false)
  private val specFavoriteReadFailureState = MutableStateFlow(false)
  private val mutableCriterionMarks = MutableStateFlow<Outcome<Map<CriterionMarkKey, CriterionMark>, SettingsRejection>>(Outcome.Success(emptyMap()))
  override val criterionMarks = mutableCriterionMarks
  var criterionMarkRejection: SettingsRejection? = null
  override val favoriteWork = combine(settings, favoriteReadFailureState) { current, readFailure ->
    val result: Outcome<Set<FavoriteWorkKey>, SettingsRejection> =
      if (readFailure) Outcome.Failure(SettingsRejection.ReadFailed) else Outcome.Success(current.favoriteWork)
    result
  }
  override val favoriteSpecs = combine(settings, specFavoriteReadFailureState) { current, readFailure ->
    val result: Outcome<Set<FavoriteSpecKey>, SettingsRejection> =
      if (readFailure) Outcome.Failure(SettingsRejection.ReadFailed) else Outcome.Success(current.favoriteSpecs)
    result
  }
  var favoriteWriteFailure = false
  var specFavoriteWriteFailure = false
  var qaDeviceReadFailure = false
  var onboardingTourSeen = false
  var onboardingTourReadFailure = false
  var onboardingTourWriteFailure = false
  var detailsWidthWriteFailure = false
  var favoriteReadFailure: Boolean
    get() = favoriteReadFailureState.value
    set(value) { favoriteReadFailureState.value = value }
  var specFavoriteReadFailure: Boolean
    get() = specFavoriteReadFailureState.value
    set(value) { specFavoriteReadFailureState.value = value }

  override suspend fun setDatabasePath(path: String?): Outcome<Unit, SettingsRejection> {
    if (!path.isNullOrEmpty() && path.isBlank()) return Outcome.Failure(SettingsRejection.BlankDatabasePath)
    return change { it.copy(databasePath = path?.trim()?.ifEmpty { null }) }
  }

  override suspend fun setClaudeConfigDir(directory: String?) =
    change { it.copy(claudeConfigDir = directory?.trim()?.ifEmpty { null }) }

  override suspend fun setHistoryWindow(window: HistoryWindow) = change { it.copy(historyWindow = window) }

  override suspend fun setNewTaskChatLayout(layout: NewTaskChatLayout) = change { it.copy(newTaskChatLayout = layout) }

  override suspend fun setQaDevice(pick: QaDevicePick?): Outcome<Unit, SettingsRejection> {
    if (pick != null && !pick.isValid) return Outcome.Failure(SettingsRejection.InvalidQaDevice)
    return change { it.copy(qaDevice = pick) }
  }

  override suspend fun readQaDevice(): Outcome<QaDevicePick?, SettingsRejection> =
    if (qaDeviceReadFailure) Outcome.Failure(SettingsRejection.ReadFailed) else Outcome.Success(settings.value.qaDevice)

  override suspend fun readOnboardingTourSeen(): Outcome<Boolean, SettingsRejection> =
    if (onboardingTourReadFailure) Outcome.Failure(SettingsRejection.ReadFailed) else Outcome.Success(onboardingTourSeen)

  override suspend fun markOnboardingTourSeen(): Outcome<Unit, SettingsRejection> {
    if (onboardingTourWriteFailure) return Outcome.Failure(SettingsRejection.WriteFailed)
    onboardingTourSeen = true
    return Outcome.Success(Unit)
  }

  override suspend fun setDefaultAgent(agentId: String?) =
    change { it.copy(defaultAgentId = agentId?.trim()?.ifEmpty { null }) }

  override suspend fun setNotification(kind: NotificationKind, enabled: Boolean) =
    change { it.copy(notifications = it.notifications + (kind to enabled)) }

  override suspend fun setStartAtLogin(enabled: Boolean) = change { it.copy(startAtLogin = enabled) }

  override suspend fun setTerminalHeightDp(heightDp: Int) = change {
    it.copy(terminalHeightDp = heightDp.coerceIn(AppSettings.TERMINAL_HEIGHT_MIN_DP, AppSettings.TERMINAL_HEIGHT_MAX_DP))
  }

  override suspend fun setChatWidthDp(widthDp: Int) = change {
    it.copy(chatWidthDp = widthDp.coerceIn(AppSettings.CHAT_WIDTH_MIN_DP, AppSettings.CHAT_WIDTH_MAX_DP))
  }

  override suspend fun setDetailsWidthDp(widthDp: Int?): Outcome<Unit, SettingsRejection> {
    if (widthDp != null && widthDp < AppSettings.DETAILS_WIDTH_MIN_DP) return Outcome.Failure(SettingsRejection.InvalidDetailsWidth)
    if (detailsWidthWriteFailure) return Outcome.Failure(SettingsRejection.WriteFailed)
    return change { it.copy(detailsWidthDp = widthDp) }
  }

  override suspend fun setTrayLastTab(tab: TrayTab) = change { it.copy(trayLastTab = tab) }

  override suspend fun setFavorite(key: FavoriteWorkKey, favorite: Boolean) = if (favoriteWriteFailure) {
    Outcome.Failure(SettingsRejection.WriteFailed)
  } else change { it.copy(favoriteWork = if (favorite) it.favoriteWork + key else it.favoriteWork - key) }

  override suspend fun setSpecFavorite(key: FavoriteSpecKey, favorite: Boolean) = if (specFavoriteWriteFailure) {
    Outcome.Failure(SettingsRejection.WriteFailed)
  } else change { it.copy(favoriteSpecs = if (favorite) it.favoriteSpecs + key else it.favoriteSpecs - key) }

  override suspend fun setCriterionMark(key: CriterionMarkKey, mark: CriterionMark?): Outcome<Unit, SettingsRejection> {
    criterionMarkRejection?.let { return Outcome.Failure(it) }
    val current = (mutableCriterionMarks.value as? Outcome.Success)?.value.orEmpty()
    mutableCriterionMarks.value = Outcome.Success(if (mark == null) current - key else current + (key to mark))
    return Outcome.Success(Unit)
  }

  private fun change(transform: (AppSettings) -> AppSettings): Outcome<Unit, SettingsRejection> {
    settings.update(transform)
    return Outcome.Success(Unit)
  }
}
