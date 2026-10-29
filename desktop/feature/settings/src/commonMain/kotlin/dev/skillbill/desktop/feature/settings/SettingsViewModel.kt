package dev.skillbill.desktop.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.skillbill.desktop.core.common.Outcome
import dev.skillbill.desktop.core.domain.repository.ClaudeConfigDirectoriesRead
import dev.skillbill.desktop.core.domain.repository.RuntimeConfigRepository
import dev.skillbill.desktop.core.domain.session.DatabaseSessionController
import dev.skillbill.desktop.core.domain.session.DatabaseSessionState
import dev.skillbill.desktop.core.domain.settings.AppSettingsRepository
import dev.skillbill.desktop.core.domain.settings.HistoryWindow
import dev.skillbill.desktop.core.domain.settings.NotificationKind
import dev.skillbill.desktop.core.domain.settings.SettingsRejection
import dev.skillbill.desktop.core.ui.feedback.ToastMessage
import dev.skillbill.desktop.core.ui.feedback.ToastTone
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import me.tatarka.inject.annotations.Inject

@Inject
class SettingsViewModel(
  private val settings: AppSettingsRepository,
  private val session: DatabaseSessionController,
  private val config: RuntimeConfigRepository,
  private val loginItems: LoginItemRegistrar,
) : ViewModel() {
  val sessionState: StateFlow<DatabaseSessionState> = session.state
  private val mutableState = MutableStateFlow(SettingsUiState())
  val state: StateFlow<SettingsUiState> = mutableState.asStateFlow()
  private val effectChannel = Channel<SettingsEffect>(Channel.BUFFERED)
  val effects: Flow<SettingsEffect> = effectChannel.receiveAsFlow()
  private var activeClaudeSessionId: Long? = null
  private var claudeReadJob: Job? = null

  init {
    settings.settings.onEach(::applySettings).launchIn(viewModelScope)
    session.state.onEach(::applySession).launchIn(viewModelScope)
  }

  fun onAction(action: SettingsAction) {
    when (action) {
      is SettingsAction.PathChanged -> mutableState.update {
        it.copy(draftPath = action.text, pathEdited = true, pathError = if (action.text.isOnlySpacesPath()) PathError.OnlySpaces else null)
      }
      SettingsAction.SavePath -> savePath()
      is SettingsAction.ClaudeDirectoryChosen -> write { setClaudeConfigDir(action.directory) }
      is SettingsAction.RetentionChosen -> write { setHistoryWindow(action.window) }
      is SettingsAction.NotificationToggled -> write { setNotification(action.kind, action.enabled) }
      is SettingsAction.StartAtLoginChanged -> updateLoginItem(action.enabled)
    }
  }

  private fun applySettings(value: dev.skillbill.desktop.core.domain.settings.AppSettings) {
    mutableState.update { current ->
      val changed = !current.ready || current.settings.databasePath != value.databasePath
      val draftPath = if (changed && !current.pathEdited) value.databasePath.orEmpty() else current.draftPath
      current.copy(
        ready = true,
        settings = value,
        draftPath = draftPath,
        pathEdited = if (changed && !current.pathEdited) false else current.pathEdited,
        pathError = draftPath.validationError(current.pathError.takeUnless { changed }),
        writeError = null,
      )
    }
  }

  private fun applySession(value: DatabaseSessionState) {
    if (session.state.value != value) return
    mutableState.update { it.copy(currentDatabase = when (value) {
      is DatabaseSessionState.Opening -> value.path ?: "runtime default"
      is DatabaseSessionState.Open -> value.path
      is DatabaseSessionState.Failed -> value.path
    }) }
    if (value is DatabaseSessionState.Open) {
      discoverClaudeDirectories(value.sessionId)
    } else {
      activeClaudeSessionId = null
      claudeReadJob?.cancel()
      claudeReadJob = null
      mutableState.update { it.copy(claudeDiscovery = ClaudeDirectoryDiscovery.Unavailable) }
    }
  }

  private fun discoverClaudeDirectories(sessionId: Long) {
    if (activeClaudeSessionId == sessionId) return
    claudeReadJob?.cancel()
    activeClaudeSessionId = sessionId
    mutableState.update { it.copy(claudeDiscovery = ClaudeDirectoryDiscovery.Pending(sessionId)) }
    val request = viewModelScope.launch(start = CoroutineStart.LAZY) {
      val result = config.claudeConfigDirectories(sessionId)
      if (claudeReadJob !== currentCoroutineContext().job || activeClaudeSessionId != sessionId) return@launch
      val current = session.state.value as? DatabaseSessionState.Open
      if (current?.sessionId != sessionId) return@launch
      val discovery = when (result) {
        is ClaudeConfigDirectoriesRead.Success -> ClaudeDirectoryDiscovery.Success(sessionId, result.directories)
        ClaudeConfigDirectoriesRead.Unavailable -> ClaudeDirectoryDiscovery.Unavailable
        is ClaudeConfigDirectoriesRead.Failed -> ClaudeDirectoryDiscovery.Failed(sessionId, result.problem)
      }
      mutableState.update { it.copy(claudeDiscovery = discovery) }
    }
    claudeReadJob = request
    request.start()
  }

  private fun savePath() {
    var request: PathSaveRequest? = null
    mutableState.update { current ->
      request = null
      when {
        !current.ready || current.savingPath -> current
        current.draftPath.isOnlySpacesPath() -> current.copy(pathError = PathError.OnlySpaces)
        !current.pathEdited -> current
        else -> {
          val submitted = current.draftPath
          request = PathSaveRequest(
            submitted = submitted,
            normalized = submitted.trim().takeIf { it.isNotEmpty() },
            persistedSelection = current.settings.databasePath to current.settings.claudeConfigDir,
          )
          current.copy(savingPath = true, pathError = null)
        }
      }
    }
    val save = request ?: return
    viewModelScope.launch {
      val result = settings.setDatabasePath(save.normalized)
      when (result) {
        is Outcome.Success -> {
          val latest = settings.settings.first()
          if (latest.databasePath == save.persistedSelection.first && latest.claudeConfigDir == save.persistedSelection.second) session.reopen()
          mutableState.update {
            val unchanged = it.draftPath == save.submitted
            val draftPath = if (unchanged) save.normalized.orEmpty() else it.draftPath
            it.copy(
              savingPath = false,
              draftPath = draftPath,
              pathEdited = if (unchanged) false else it.pathEdited,
              pathError = draftPath.validationError(if (unchanged) null else it.pathError),
            )
          }
          effectChannel.send(SettingsEffect.ShowToast(ToastMessage(ToastTone.Confirmation, "Saved")))
          effectChannel.send(SettingsEffect.ClearFocus)
        }
        is Outcome.Failure -> mutableState.update {
          val error = when {
            it.draftPath.isOnlySpacesPath() -> PathError.OnlySpaces
            it.draftPath == save.submitted -> result.error.toPathError()
            else -> it.draftPath.validationError(it.pathError)
          }
          it.copy(savingPath = false, pathError = error)
        }
      }
    }
  }

  private fun updateLoginItem(enabled: Boolean) {
    if (!state.value.ready || state.value.loginPending) return
    mutableState.update { it.copy(loginPending = true, loginError = null) }
    viewModelScope.launch {
      when (val result = loginItems.setEnabled(enabled)) {
        LoginItemResult.Applied -> when (settings.setStartAtLogin(enabled)) {
          is Outcome.Success -> mutableState.update { it.copy(loginPending = false) }
          is Outcome.Failure -> {
            val compensation = loginItems.setEnabled(!enabled)
            val message = when (compensation) {
              LoginItemResult.Applied -> "The setting could not be saved. Start at login was restored to its previous value."
              is LoginItemResult.Failed -> "The setting could not be saved, and start at login may differ from the saved preference. ${compensation.reason}"
            }
            mutableState.update { it.copy(loginPending = false, loginError = message) }
          }
        }
        is LoginItemResult.Failed -> mutableState.update { it.copy(loginPending = false, loginError = result.reason) }
      }
    }
  }

  private fun write(change: suspend AppSettingsRepository.() -> Outcome<Unit, SettingsRejection>) {
    if (!state.value.ready) return
    viewModelScope.launch {
      when (change(settings)) {
        is Outcome.Success -> mutableState.update { it.copy(writeError = null) }
        is Outcome.Failure -> mutableState.update { it.copy(writeError = "The settings file could not be written.") }
      }
    }
  }

  private fun SettingsRejection.toPathError() = when (this) {
    SettingsRejection.BlankDatabasePath -> PathError.OnlySpaces
    SettingsRejection.WriteFailed -> PathError.WriteFailed
  }

  private data class PathSaveRequest(
    val submitted: String,
    val normalized: String?,
    val persistedSelection: Pair<String?, String?>,
  )
}

internal fun String.isOnlySpacesPath() = isNotEmpty() && isBlank()

private fun String.validationError(existing: PathError?): PathError? = when {
  isOnlySpacesPath() -> PathError.OnlySpaces
  existing == PathError.OnlySpaces -> null
  else -> existing
}
