package dev.skillbill.desktop.feature.tray

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.skillbill.desktop.core.domain.model.BoardState
import dev.skillbill.desktop.core.domain.repository.WorkBoardRepository
import dev.skillbill.desktop.core.domain.settings.AppSettingsRepository
import dev.skillbill.desktop.core.domain.settings.TrayTab
import dev.skillbill.desktop.core.ui.board.ShellBoard
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import me.tatarka.inject.annotations.Inject

@Inject
class TrayViewModel(private val boards: WorkBoardRepository, private val settings: AppSettingsRepository) : ViewModel() {
  private val actions = Channel<TrayEffect>(Channel.BUFFERED)
  private val selectedTab = MutableStateFlow<TrayTab?>(null)
  val effects: Flow<TrayEffect> = actions.receiveAsFlow()

  val state = combine(boards.observe(ShellBoard.query), settings.settings, selectedTab) { board, current, selected ->
      val trayBoard = when (board) {
        BoardState.Loading -> TrayBoard.Loading
        is BoardState.Failed -> TrayBoard.Failed
        is BoardState.Loaded -> board.toTrayBoard()
      }
      TrayUiState(trayBoard, selected ?: current.trayLastTab, (board as? BoardState.Failed)?.problem, (board as? BoardState.Loaded)?.stale == true)
    }
    .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TrayUiState(TrayBoard.Loading, TrayTab.DEFAULT))

  fun onAction(action: TrayAction) {
    when (action) {
      is TrayAction.ChooseTab -> { selectedTab.value = action.tab; viewModelScope.launch { settings.setTrayLastTab(action.tab) } }
      is TrayAction.Select -> actions.trySend(TrayEffect.OpenDetail(action.item))
      TrayAction.Refresh -> boards.refresh()
    }
  }
}
