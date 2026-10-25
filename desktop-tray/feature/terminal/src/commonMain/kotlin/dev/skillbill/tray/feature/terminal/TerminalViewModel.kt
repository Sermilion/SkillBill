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

/**
 * Pane visibility and height persist in settings; the working directory follows the latest selection, sidebar work or a spec, and
 * is never persisted. The tabs are window-lifetime state and are never persisted either: the host mints their ids, and this
 * keeps the list, the labels and which tab was used most recently.
 */
class TerminalViewModel(
  private val details: WorkDetailRepository,
  private val settings: SettingsRepository,
) : ViewModel() {
  private val appSettings = MutableStateFlow(AppSettings())
  private val resolution = MutableStateFlow(Resolution(identity = null, directory = null))
  private val tabs = MutableStateFlow(Tabs(emptyList(), null, emptyList()))
  private var resolving: Job? = null
  private var latestWork: WorkIdentity? = null

  // The host reports every composition of an opening; only the first one picks a tab.
  private var openingApplied = false

  val state: StateFlow<TerminalUiState> = combine(appSettings, resolution, tabs) { stored, resolved, tabState ->
    TerminalUiState(
      stored.terminalPaneOpen,
      stored.terminalPaneHeightDp,
      resolved.directory,
      resolved.identity,
      tabState.list,
      tabState.activeId,
    )
  }.stateIn(viewModelScope, SharingStarted.Eagerly, TerminalUiState())

  init {
    viewModelScope.launch {
      settings.settings.collect {
        if (it !is SettingsState.Ready) return@collect
        appSettings.value = it.settings
        if (!it.settings.terminalPaneOpen) openingApplied = false
      }
    }
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
      is TerminalAction.SpecSelected -> {
        // The spec is now the latest selection, so the sidebar read in flight no longer decides the directory.
        resolving?.cancel()
        resolution.value = Resolution(latestWork, action.repositoryRoot)
      }
      is TerminalAction.NewTab -> add(action.id, action.directory, action.isHome)
      is TerminalAction.SelectTab -> if (tabs.value.list.any { it.id == action.id }) activate(action.id)
      is TerminalAction.PaneOpened -> paneOpened(action)
      is TerminalAction.CloseTab -> remove(setOf(action.id))
      is TerminalAction.TabsEnded -> remove(action.ids)
    }
  }

  /** A newer selection cancels the read in flight, so a slow earlier read can never overwrite it. */
  private fun select(action: TerminalAction.SelectedWorkChanged) {
    latestWork = action.identity
    resolving?.cancel()
    resolving = viewModelScope.launch {
      val directory = action.identity?.let { (details.detail(it) as? Outcome.Value)?.value?.item?.repositoryRoot }
      resolution.value = Resolution(action.identity, directory)
    }
  }

  private fun paneOpened(action: TerminalAction.PaneOpened) {
    if (openingApplied) return
    openingApplied = true
    val existing = tabs.value.recent.firstOrNull { id -> tabs.value.list.any { it.id == id && it.directory == action.directory } }
    if (existing != null) activate(existing) else add(action.id, action.directory, action.isHome)
  }

  private fun add(id: Long, directory: String, isHome: Boolean) {
    val list = terminalTabLabels(tabs.value.list + TerminalTab(id, directory, isHome, label = ""))
    tabs.value = Tabs(list, id, tabs.value.recent.touched(id))
  }

  private fun activate(id: Long) {
    tabs.value = tabs.value.let { it.copy(activeId = id, recent = it.recent.touched(id)) }
  }

  private fun List<Long>.touched(id: Long) = listOf(id) + (this - id)

  /** Drops the tabs that exist among [ids]. Losing the last one closes the pane, the way [TerminalAction.ClosePane] does. */
  private fun remove(ids: Set<Long>) {
    val current = tabs.value
    if (current.list.none { it.id in ids }) return
    val list = terminalTabLabels(current.list.filterNot { it.id in ids })
    val recent = current.recent - ids
    tabs.value = Tabs(list, current.activeId?.takeIf { it !in ids } ?: recent.firstOrNull(), recent)
    if (list.isEmpty()) viewModelScope.launch { settings.update { it.copy(terminalPaneOpen = false) } }
  }

  /** The sidebar selection a directory is settled for, so a reader can tell a settled null from a read still in flight. */
  private data class Resolution(val identity: WorkIdentity?, val directory: String?)

  // [recent] holds tab ids, most recently used first. It stays out of the UI state: only the successor rule and PaneOpened read it.
  private data class Tabs(val list: List<TerminalTab>, val activeId: Long?, val recent: List<Long>)
}
