package dev.skillbill.tray.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.skillbill.tray.core.domain.model.AppSettings
import dev.skillbill.tray.core.domain.model.SettingsState
import dev.skillbill.tray.core.domain.repository.AgentHomeRepository
import dev.skillbill.tray.core.domain.repository.Outcome
import dev.skillbill.tray.core.domain.repository.SettingsRepository
import dev.skillbill.tray.core.domain.repository.WorkStoreStatusRepository
import dev.skillbill.tray.core.ui.Feedback
import dev.skillbill.tray.core.ui.FeedbackIds
import dev.skillbill.tray.core.ui.FeedbackTone
import dev.skillbill.tray.core.ui.TransientEffects
import dev.skillbill.tray.core.ui.acknowledged
import dev.skillbill.tray.core.ui.withFeedback
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class SettingsViewModel(
  private val settings: SettingsRepository,
  storeStatus: WorkStoreStatusRepository,
  agentHomes: AgentHomeRepository,
) : ViewModel() {
  private val mutable = MutableStateFlow(SettingsUiState())
  private val feedbackIds = FeedbackIds()
  private val transient = TransientEffects<SettingsEffect>()

  val state: StateFlow<SettingsUiState> = mutable.asStateFlow()
  val effects: Flow<SettingsEffect> = transient.flow

  init {
    viewModelScope.launch { settings.settings.collect(::apply) }
    viewModelScope.launch {
      storeStatus.effectiveDatabasePath.collect { path -> mutable.update { it.copy(effectiveDatabasePath = path) } }
    }
    viewModelScope.launch {
      when (val found = agentHomes.claudeConfigDirectories()) {
        is Outcome.Value -> mutable.update { it.copy(claudeConfigDirectories = found.value) }
        is Outcome.Failure -> addFeedback(found.problem.summary)
      }
    }
  }

  fun onAction(action: SettingsAction) {
    when (action) {
      is SettingsAction.PathChanged -> mutable.update { it.copy(draftPath = action.text, pathError = null) }
      is SettingsAction.ClaudeConfigDirChosen -> write { it.copy(claudeConfigDir = action.directory) }
      SettingsAction.SavePath -> savePath()
      is SettingsAction.RetentionChosen -> write { it.copy(retention = action.retention) }
      is SettingsAction.NotificationToggled -> write { it.copy(notifications = it.notifications + (action.key to action.enabled)) }
      is SettingsAction.StartAtLoginChanged -> write { it.copy(startAtLogin = action.enabled) }
      is SettingsAction.AcknowledgeFeedback -> mutable.update { it.copy(pendingFeedback = it.pendingFeedback.acknowledged(action.id)) }
    }
  }

  private fun apply(result: SettingsState) {
    when (result) {
      SettingsState.Loading -> mutable.update { it.copy(readiness = SettingsReadiness.Loading) }
      is SettingsState.Failed -> mutable.update { it.copy(readiness = SettingsReadiness.Unreadable(result.problem)) }
      is SettingsState.Ready -> mutable.update { current ->
        val pathChanged = current.readiness != SettingsReadiness.Ready || current.settings.databasePath != result.settings.databasePath
        current.copy(
          readiness = SettingsReadiness.Ready,
          settings = result.settings,
          draftPath = if (pathChanged) result.settings.databasePath.orEmpty() else current.draftPath,
          pathError = if (pathChanged) null else current.pathError,
        )
      }
    }
  }

  private fun savePath() {
    val current = mutable.value
    if (current.readiness != SettingsReadiness.Ready || current.savingPath) return
    val draft = current.draftPath
    if (draft.isNotEmpty() && draft.isBlank()) {
      mutable.update { it.copy(pathError = PathError.PathBlank) }
      return
    }
    mutable.update { it.copy(savingPath = true, pathError = null) }
    viewModelScope.launch {
      val result = settings.update { it.copy(databasePath = draft.ifEmpty { null }) }
      mutable.update { it.copy(savingPath = false) }
      when (result) {
        is Outcome.Value -> transient.send(SettingsEffect.Saved)
        is Outcome.Failure -> addFeedback(result.problem.summary)
      }
    }
  }

  private fun write(change: (AppSettings) -> AppSettings) {
    if (mutable.value.readiness != SettingsReadiness.Ready) return
    viewModelScope.launch {
      val result = settings.update(change)
      if (result is Outcome.Failure) addFeedback(result.problem.summary)
    }
  }

  private fun addFeedback(message: String) {
    val feedback = Feedback(feedbackIds.next(), FeedbackTone.PROBLEM, message)
    mutable.update { it.copy(pendingFeedback = it.pendingFeedback.withFeedback(feedback)) }
  }
}
