package dev.skillbill.desktop.feature.detail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.skillbill.desktop.core.domain.model.BoardState
import dev.skillbill.desktop.core.domain.model.DetailList
import dev.skillbill.desktop.core.domain.model.DetailListOutcome
import dev.skillbill.desktop.core.domain.model.DetailSections
import dev.skillbill.desktop.core.domain.model.DetailState
import dev.skillbill.desktop.core.domain.model.OpenOutcome
import dev.skillbill.desktop.core.domain.model.WorkItem
import dev.skillbill.desktop.core.domain.repository.SpecRepository
import dev.skillbill.desktop.core.domain.repository.WorkBoardRepository
import dev.skillbill.desktop.core.domain.repository.WorkControlRepository
import dev.skillbill.desktop.core.domain.repository.WorkDetailRepository
import dev.skillbill.desktop.core.domain.session.DatabaseSessionController
import dev.skillbill.desktop.core.domain.session.DatabaseSessionState
import dev.skillbill.desktop.core.ui.board.ShellBoard
import dev.skillbill.desktop.core.ui.feedback.ToastMessage
import dev.skillbill.desktop.core.ui.feedback.ToastQueue
import dev.skillbill.desktop.core.ui.feedback.ToastTone
import dev.skillbill.desktop.core.ui.input.ScreenKey
import dev.skillbill.desktop.core.ui.selection.SelectionHolder
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import me.tatarka.inject.annotations.Assisted
import me.tatarka.inject.annotations.Inject
import kotlin.time.Clock

private const val CHECKOUT_NOT_OPENED = "Could not open the checkout directory. Check that it still exists."
private const val BRANCH_NOT_OPENED = "Could not open the repository in your browser. Check its origin remote and default browser."
private const val FILE_NOT_OPENED = "Could not open the file. Check that it still exists."

/**
 * One item's detail. The first state already holds the route's row as the header, so the header paints before any read. Pages a
 * list asks for are appended by the repository into the observed detail; this ViewModel only picks the anchor and never merges.
 */
@Inject
class DetailViewModel(
  @Assisted private val item: WorkItem,
  private val session: DatabaseSessionController,
  private val detail: WorkDetailRepository,
  private val board: WorkBoardRepository,
  private val specs: SpecRepository,
  private val webPages: RepositoryWebPages,
  private val selection: SelectionHolder,
  private val clock: Clock,
  workControls: WorkControlRepository,
  toasts: ToastQueue,
) : ViewModel() {
  private val mutableState = MutableStateFlow(
    DetailUiState(
      header = item,
      sections = SectionsUi.Pending,
      itemResolved = false,
      stale = false,
      renderedAt = clock.now(),
    ),
  )
  val state: StateFlow<DetailUiState> = mutableState.asStateFlow()

  private val effectChannel = Channel<DetailEffect>(Channel.BUFFERED)
  val effects: Flow<DetailEffect> = effectChannel.receiveAsFlow()

  private var observation: Job? = null

  private val controls = DetailControls(viewModelScope, workControls, toasts) { mutableState.value }

  init {
    selection.select(null)
    controls.state.onEach { controls -> mutableState.update { it.copy(controls = controls) } }.launchIn(viewModelScope)
    observe()
    board.observe(ShellBoard.query).onEach { board -> mutableState.update { it.copy(boardPosition = boardPosition(board, it.header.id)) } }
      .launchIn(viewModelScope)
  }

  /** Opens the next or previous board item; returns false when there is none, so the key goes unhandled. */
  fun step(next: Boolean): Boolean {
    if (!state.value.itemResolved) return false
    val position = state.value.boardPosition
    val target = (if (next) position?.next else position?.previous) ?: return false
    effectChannel.trySend(DetailEffect.OpenItem(target))
    return true
  }

  /** P, Shift+P and R run the matching run-actions button; false when it is absent or disabled. */
  fun onKey(key: ScreenKey): Boolean = if (!state.value.itemResolved) false else when (key) {
    ScreenKey.PauseAfterSubtask -> controls.onAction(ControlsAction.PauseAfterSubtask)
    ScreenKey.PauseNow -> controls.onAction(ControlsAction.PauseNow)
    ScreenKey.Resume -> controls.onAction(ControlsAction.Launch)
    else -> false
  }

  fun onAction(action: DetailAction) {
    if (!state.value.itemResolved && action != DetailAction.Retry && action !is DetailAction.Navigate) return
    when (action) {
      is ControlsAction -> controls.onAction(action)
      is DetailAction.LoadMore -> loadMore(action.list)
      DetailAction.Next -> step(next = true)
      DetailAction.Previous -> step(next = false)
      DetailAction.Retry -> observe()
      is DetailAction.Copy -> effectChannel.trySend(DetailEffect.CopyToClipboard(action.text))
      DetailAction.RevealFolder -> open(CHECKOUT_NOT_OPENED) { specs.revealFolder(state.value.header.repositoryRoot) }
      DetailAction.OpenBranch -> state.value.header.branch?.let { branch ->
        open(BRANCH_NOT_OPENED) { webPages.openBranch(state.value.header.repositoryRoot, branch) }
      }
      is DetailAction.OpenSpecFile -> open(FILE_NOT_OPENED) { specs.openFile(action.path) }
      is DetailAction.Navigate -> effectChannel.trySend(DetailEffect.OpenRoute(action.route))
    }
  }

  private fun observe() {
    observation?.cancel()
    observation = detail.observe(item).onEach(::show).launchIn(viewModelScope)
  }

  private fun show(read: DetailState) {
    val resolved = read.sections is DetailSections.Loaded &&
      (session.state.value as? DatabaseSessionState.Open)?.sessionId == read.header.control.sessionId
    if (resolved) selection.select(read.header)
    mutableState.update { current ->
      val sections = when (val sections = read.sections) {
        // A detail that has shown its sections never returns to placeholders.
        DetailSections.Pending -> current.sections.takeIf { it is SectionsUi.Loaded } ?: SectionsUi.Pending
        is DetailSections.Loaded -> SectionsUi.Loaded(sections.detail, sections.runOptions, sections.listCursors, sections.moreLists)
        DetailSections.Gone -> SectionsUi.Gone
        is DetailSections.Failed -> SectionsUi.Failed(sections.problem)
      }
      val stale = (read.sections as? DetailSections.Loaded)?.stale ?: (current.stale && sections is SectionsUi.Loaded)
      current.copy(header = read.header, sections = sections, itemResolved = current.itemResolved || resolved, stale = stale, renderedAt = clock.now())
    }
  }

  private fun loadMore(list: DetailList) {
    val loaded = state.value.sections as? SectionsUi.Loaded ?: return
    if (list !in loaded.moreLists) return
    val anchor = loaded.listAnchors[list]
    val previous = state.value.listLoads[list]
    // The same anchor is read once: a second request while it loads or after it landed would repeat the page.
    if (previous != null && previous.anchor == anchor && previous !is ListLoad.Failed) return
    mutableState.update { it.copy(listLoads = it.listLoads + (list to ListLoad.Loading(anchor))) }
    viewModelScope.launch {
      val load = when (val outcome = detail.loadList(item.id, list, anchor)) {
        DetailListOutcome.Appended, DetailListOutcome.End -> ListLoad.Done(anchor)
        is DetailListOutcome.Failed -> ListLoad.Failed(anchor, outcome.problem)
      }
      mutableState.update { it.copy(listLoads = it.listLoads + (list to load)) }
    }
  }

  private fun open(failure: String, request: suspend () -> OpenOutcome) {
    viewModelScope.launch {
      if (request() == OpenOutcome.NotOpened) effectChannel.send(DetailEffect.ShowToast(ToastMessage(ToastTone.Problem, failure)))
    }
  }
}

/** The item's place in the shell board's order, which only flattens the runtime's sections; null when the board does not show it. */
internal fun boardPosition(board: BoardState, id: String): BoardPosition? {
  val rows = (board as? BoardState.Loaded)?.sections?.flatMap { it.items } ?: return null
  val index = rows.indexOfFirst { it.id == id }
  if (index < 0) return null
  return BoardPosition(index, rows.size, rows.getOrNull(index - 1), rows.getOrNull(index + 1))
}
