package dev.skillbill.desktop.feature.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.skillbill.desktop.core.domain.model.BoardQuery
import dev.skillbill.desktop.core.domain.model.BoardState
import dev.skillbill.desktop.core.domain.model.LoadMoreOutcome
import dev.skillbill.desktop.core.domain.repository.WorkBoardRepository
import dev.skillbill.desktop.core.domain.settings.AppSettingsRepository
import dev.skillbill.desktop.core.ui.board.WORK_PAGE_SIZE
import dev.skillbill.desktop.core.ui.selection.SelectionHolder
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import me.tatarka.inject.annotations.Inject
import kotlin.time.Clock

/** Reads the board over the `history_retention` window and keeps its finished items in runtime order. */
@Inject
class HistoryViewModel(
  private val board: WorkBoardRepository,
  settings: AppSettingsRepository,
  private val selection: SelectionHolder,
  private val clock: Clock,
) : ViewModel() {
  private val mutableState = MutableStateFlow(HistoryUiState())
  val state: StateFlow<HistoryUiState> = mutableState.asStateFlow()

  private val effectChannel = Channel<HistoryEffect>(Channel.BUFFERED)
  val effects: Flow<HistoryEffect> = effectChannel.receiveAsFlow()

  private var loadingCursor: String? = null

  init {
    val windows = settings.settings.map { it.historyWindow }.distinctUntilChanged()
    val filters = mutableState.map { it.filter }.distinctUntilChanged()
    @OptIn(ExperimentalCoroutinesApi::class)
    combine(windows, filters) { window, filter -> BoardQuery(window, filter, WORK_PAGE_SIZE) }
      .flatMapLatest { query -> board.observe(query).map { query to it } }
      .onEach { (query, board) -> mutableState.update { it.copy(window = query.window, board = board, renderedAt = clock.now()) } }
      .launchIn(viewModelScope)
  }

  fun onAction(action: HistoryAction) {
    when (action) {
      is HistoryAction.FilterChanged -> mutableState.update { it.copy(filter = action.text) }
      is HistoryAction.ChooseTab -> mutableState.update { it.copy(tab = action.tab) }
      HistoryAction.Refresh -> board.refresh()
      HistoryAction.LoadMore -> loadMore()
      is HistoryAction.MoveFocus -> moveFocus(action.delta)
      is HistoryAction.Open -> {
        focus(action.item.id)
        effectChannel.trySend(HistoryEffect.OpenDetail(action.item))
      }
      HistoryAction.OpenFocused -> state.value.run { rows.firstOrNull { it.id == focusedId } }?.let { effectChannel.trySend(HistoryEffect.OpenDetail(it)) }
    }
  }

  private fun moveFocus(delta: Int) {
    val rows = state.value.rows
    if (rows.isEmpty()) return
    val current = rows.indexOfFirst { it.id == state.value.focusedId }
    val next = if (current < 0) (if (delta > 0) 0 else rows.lastIndex) else (current + delta).coerceIn(0, rows.lastIndex)
    focus(rows[next].id)
  }

  private fun focus(id: String) {
    mutableState.update { it.copy(focusedId = id) }
    selection.select(state.value.rows.firstOrNull { it.id == id })
  }

  private fun loadMore() {
    val cursor = (state.value.board as? BoardState.Loaded)?.nextCursor ?: return
    if (cursor == loadingCursor) return
    loadingCursor = cursor
    val query = BoardQuery(state.value.window, state.value.filter, WORK_PAGE_SIZE)
    viewModelScope.launch {
      // A failed page asks the board to refresh; the same cursor may be tried again once it is back.
      if (board.loadMore(query, cursor) is LoadMoreOutcome.Failed) loadingCursor = null
    }
  }
}
