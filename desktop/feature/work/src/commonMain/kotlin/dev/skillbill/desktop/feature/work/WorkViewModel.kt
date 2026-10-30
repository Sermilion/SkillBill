package dev.skillbill.desktop.feature.work

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.skillbill.desktop.core.domain.model.BoardQuery
import dev.skillbill.desktop.core.domain.model.BoardState
import dev.skillbill.desktop.core.domain.model.ControlOutcome
import dev.skillbill.desktop.core.domain.model.LoadMoreOutcome
import dev.skillbill.desktop.core.domain.model.WorkItem
import dev.skillbill.desktop.core.domain.repository.WorkBoardRepository
import dev.skillbill.desktop.core.domain.repository.WorkControlRepository
import dev.skillbill.desktop.core.domain.repository.WorkDetailRepository
import dev.skillbill.desktop.core.ui.board.ShellBoard
import dev.skillbill.desktop.core.ui.feedback.ControlAction
import dev.skillbill.desktop.core.ui.feedback.FeedbackSubject
import dev.skillbill.desktop.core.ui.feedback.controlFeedback
import dev.skillbill.desktop.core.ui.selection.SelectionHolder
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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

/** A blank filter shares the shell's board observation; any text re-queries the runtime, which filters before the page limit. */
internal fun workQuery(filter: String): BoardQuery = ShellBoard.query.copy(filter = filter)

/** Reads the board, moves focus and runs row controls. It never edits rows: they change when the runtime's next read shows it. */
@Inject
class WorkViewModel(
  private val board: WorkBoardRepository,
  private val details: WorkDetailRepository,
  private val controls: WorkControlRepository,
  private val selection: SelectionHolder,
  private val clock: Clock,
) : ViewModel() {
  private val mutableState = MutableStateFlow(WorkUiState())
  val state: StateFlow<WorkUiState> = mutableState.asStateFlow()

  private val effectChannel = Channel<WorkEffect>(Channel.BUFFERED)
  val effects: Flow<WorkEffect> = effectChannel.receiveAsFlow()

  private var loadingCursor: String? = null

  init {
    @OptIn(ExperimentalCoroutinesApi::class)
    mutableState.map { it.filter }.distinctUntilChanged()
      .flatMapLatest { filter -> board.observe(workQuery(filter)) }
      .onEach { board ->
        if (board is BoardState.Loaded) {
          selection.item.value?.let { selected ->
            selection.select(board.items.firstOrNull { it.id == selected.id })
          }
        }
        mutableState.update { it.copy(board = board, renderedAt = clock.now()) }
      }
      .launchIn(viewModelScope)
    controls.reservations.onEach { reserved -> mutableState.update { it.copy(reserved = reserved) } }.launchIn(viewModelScope)
  }

  fun onAction(action: WorkAction) {
    when (action) {
      is WorkAction.ShowSection -> mutableState.update { it.copy(section = action.section) }
      is WorkAction.FilterChanged -> mutableState.update { it.copy(filter = action.text) }
      WorkAction.ClearFilter -> mutableState.update { it.copy(filter = "") }
      WorkAction.Refresh -> board.refresh()
      WorkAction.LoadMore -> loadMore()
      is WorkAction.MoveFocus -> moveFocus(action.delta)
      is WorkAction.Focus -> focus(action.item)
      is WorkAction.Open -> effectChannel.trySend(WorkEffect.OpenDetail(action.item))
      WorkAction.OpenFocused -> focusedRow()?.let { effectChannel.trySend(WorkEffect.OpenDetail(it)) }
      is WorkAction.OpenMenu -> {
        mutableState.update { it.copy(menu = action.item) }
        prefetch(action.item.id)
      }
      WorkAction.CloseMenu -> mutableState.update { state -> state.copy(menu = null, runOptions = state.runOptions.filterKeys { it == state.focusedId }) }
      is WorkAction.PauseAfterSubtask -> pauseAfterSubtask(action.item)
      is WorkAction.PauseNow -> pauseNow(action.item)
      is WorkAction.Launch -> launch(action.item)
      is WorkAction.Cancel -> cancel(action.item)
      is WorkAction.FocusedKey -> focusedRow()?.let { item ->
        when (action.key) {
          RowKey.Pause -> pauseAfterSubtask(item)
          RowKey.PauseNow -> pauseNow(item)
          RowKey.Launch -> launch(item)
        }
      }
    }
  }

  /** The focused row as the latest board read has it; null when the board no longer shows it. */
  private fun focusedRow(): WorkItem? = state.value.run { visibleRows.firstOrNull { it.id == focusedId } }

  private fun moveFocus(delta: Int) {
    val rows = state.value.visibleRows
    if (rows.isEmpty()) return
    val current = rows.indexOfFirst { it.id == state.value.focusedId }
    val next = if (current < 0) (if (delta > 0) 0 else rows.lastIndex) else (current + delta).coerceIn(0, rows.lastIndex)
    focus(rows[next])
  }

  private fun focus(item: WorkItem) {
    mutableState.update { it.copy(focusedId = item.id) }
    selection.select(item)
    prefetch(item.id)
  }

  private fun prefetch(id: String) {
    viewModelScope.launch {
      val options = details.prefetch(id) ?: return@launch
      mutableState.update { state ->
        state.copy(runOptions = (state.runOptions + (id to options)).filterKeys { it == state.focusedId || it == state.menu?.id })
      }
    }
  }

  private fun pauseAfterSubtask(item: WorkItem) {
    if (state.value.canPauseAfterSubtask(item)) control(item, ControlAction.PauseAfterSubtask) { controls.pauseAfterSubtask(item) }
  }

  private fun pauseNow(item: WorkItem) {
    if (state.value.canPauseNow(item)) control(item, ControlAction.PauseNow) { controls.pauseNow(item) }
  }

  private fun launch(item: WorkItem) {
    if (!state.value.canLaunch(item)) return
    val agentId = state.value.runOptions[item.id]?.agentId
    control(item, ControlAction.Launch) { controls.launch(item, agentId) }
  }

  private fun cancel(item: WorkItem) {
    if (state.value.canCancel(item)) control(item, ControlAction.Cancel) { controls.cancel(item) }
  }

  private fun control(item: WorkItem, action: ControlAction, request: suspend () -> ControlOutcome) {
    val options = state.value.runOptions[item.id]
    val subject = FeedbackSubject(item.issueKey ?: item.id, options?.launchLabel, options?.agentId)
    viewModelScope.launch { effectChannel.send(WorkEffect.ShowToast(controlFeedback(action, request(), subject))) }
  }

  private fun loadMore() {
    val loaded = state.value.board as? BoardState.Loaded ?: return
    val cursor = loaded.nextCursor ?: return
    if (cursor == loadingCursor) return
    loadingCursor = cursor
    val query = workQuery(state.value.filter)
    viewModelScope.launch {
      // A failed page asks the board to refresh; the same cursor may be tried again once it is back.
      if (board.loadMore(query, cursor) is LoadMoreOutcome.Failed) loadingCursor = null
    }
  }
}
