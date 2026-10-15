package dev.skillbill.tray.feature.work

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.skillbill.tray.core.domain.repository.SettingsRepository
import dev.skillbill.tray.core.domain.repository.WorkBoardRepository
import dev.skillbill.tray.core.ui.NavigationEffects
import dev.skillbill.tray.core.ui.TrackedBoardSections
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Owns the live work sections (needs you, goals, standalone phases) one main window shows. There is one instance per main
 * window; the tray and the transition notifications observe the board with their own owners, so none of them reads this one.
 * The window passes the sidebar filter text in with [WorkAction.FilterChanged]; it narrows only what this screen shows.
 */
class WorkViewModel(
  boards: WorkBoardRepository,
  private val settings: SettingsRepository,
) : ViewModel() {
  private val sections = TrackedBoardSections(viewModelScope, boards, includeRecent = false)
  private val navigation = NavigationEffects<WorkEffect>()

  val state: StateFlow<WorkUiState> = sections.state
    .map { WorkUiState(needsYou = it.needsYou, goals = it.goals, phases = it.phases, store = it.store) }
    .stateIn(viewModelScope, SharingStarted.Eagerly, WorkUiState())

  val effects: Flow<WorkEffect> = navigation.flow

  init {
    viewModelScope.launch { settings.settings.collect(sections::apply) }
  }

  fun onAction(action: WorkAction) {
    when (action) {
      is WorkAction.FilterChanged -> sections.changeFilter(action.text)
      WorkAction.Refresh -> sections.refresh()
      is WorkAction.Retry -> sections.retry(action.section)
      is WorkAction.LoadMore -> sections.loadMore(action.section)
      is WorkAction.Select -> navigation.send(WorkEffect.OpenDetail(action.identity))
    }
  }
}
