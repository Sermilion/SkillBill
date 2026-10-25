package dev.skillbill.tray.feature.terminal

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.skillbill.tray.core.domain.model.AppSettings
import dev.skillbill.tray.core.domain.model.SettingsState
import dev.skillbill.tray.core.domain.model.WorkIdentity
import dev.skillbill.tray.core.domain.repository.Outcome
import dev.skillbill.tray.core.domain.repository.SettingsRepository
import dev.skillbill.tray.core.domain.repository.WorkDetailRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Pane visibility and height persist in settings; the working directory follows the selected work and is never persisted. */
class TerminalViewModel(
  private val details: WorkDetailRepository,
  private val settings: SettingsRepository,
) : ViewModel() {
  private val appSettings = MutableStateFlow(AppSettings())
  private val resolution = MutableStateFlow(Resolution(identity = null, directory = null))
  private var resolving: Job? = null

  val state: StateFlow<TerminalUiState> = combine(appSettings, resolution) { stored, resolved ->
    TerminalUiState(stored.terminalPaneOpen, stored.terminalPaneHeightDp, resolved.directory, resolved.identity)
  }.stateIn(viewModelScope, SharingStarted.Eagerly, TerminalUiState())

  init {
    viewModelScope.launch { settings.settings.collect { if (it is SettingsState.Ready) appSettings.value = it.settings } }
  }

  fun onAction(action: TerminalAction) {
    when (action) {
      TerminalAction.TogglePane -> viewModelScope.launch { settings.update { it.copy(terminalPaneOpen = !it.terminalPaneOpen) } }
      TerminalAction.ClosePane -> viewModelScope.launch { settings.update { it.copy(terminalPaneOpen = false) } }
      is TerminalAction.ResizeTo -> viewModelScope.launch {
        val height = action.heightDp.coerceIn(AppSettings.TERMINAL_PANE_MIN_HEIGHT_DP, AppSettings.TERMINAL_PANE_MAX_HEIGHT_DP)
        settings.update { it.copy(terminalPaneHeightDp = height) }
      }
      is TerminalAction.SelectedWorkChanged -> select(action)
    }
  }

  /** A newer selection cancels the read in flight, so a slow earlier read can never overwrite it. */
  private fun select(action: TerminalAction.SelectedWorkChanged) {
    resolving?.cancel()
    resolving = viewModelScope.launch {
      val directory = action.identity?.let { (details.detail(it) as? Outcome.Value)?.value?.item?.repositoryRoot }
      resolution.value = Resolution(action.identity, directory)
    }
  }

  /** The selection a directory was read for, so a reader can tell a settled null from a read still in flight. */
  private data class Resolution(val identity: WorkIdentity?, val directory: String?)
}
