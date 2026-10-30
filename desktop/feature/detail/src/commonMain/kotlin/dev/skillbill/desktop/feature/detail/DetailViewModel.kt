package dev.skillbill.desktop.feature.detail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.skillbill.desktop.core.domain.model.StandaloneRelaunch
import dev.skillbill.desktop.core.domain.model.ControlOutcome
import dev.skillbill.desktop.core.domain.model.BoardState
import dev.skillbill.desktop.core.domain.model.liveRows
import dev.skillbill.desktop.core.domain.model.BoardSectionKind
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
  private val subtaskSelections: SubtaskSelections,
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
      subtaskChoice = subtaskSelections.get(item.id),
      focusSubtaskList = subtaskSelections.takeListFocus(),
    ),
  )
  val state: StateFlow<DetailUiState> = mutableState.asStateFlow()

  private val effectChannel = Channel<DetailEffect>(Channel.BUFFERED)
  val effects: Flow<DetailEffect> = effectChannel.receiveAsFlow()

  private var observation: Job? = null
  private var latestBoardRow = item
  private var latestBoardItems = listOf(item)
  private var relaunchedPhase: StandaloneRelaunch? = null
  private var launchAccepted = false

  private val controls = DetailControls(viewModelScope, workControls, toasts, ::launchStarted, ::launchFinished) { mutableState.value }

  init {
    selection.select(null)
    controls.state.onEach { controls -> mutableState.update { it.copy(controls = controls) } }.launchIn(viewModelScope)
    observe()
    board.observe(ShellBoard.query).onEach { boardState ->
      if (boardState is BoardState.Loaded) latestBoardItems = boardState.items
      followRelaunchedPhase()
      val row = (boardState as? BoardState.Loaded)?.items?.firstOrNull { it.id == item.id }
      val changed = row != null && (
        row.lifecycle != latestBoardRow.lifecycle || row.fresh != latestBoardRow.fresh ||
          row.pauseRequested != latestBoardRow.pauseRequested || row.stepLabel != latestBoardRow.stepLabel
        )
      if (row != null) latestBoardRow = row
      mutableState.update { state ->
        val header = row?.let(state.header::withBoardVerdicts) ?: state.header
        state.copy(header = header, boardPosition = boardPosition(boardState, state.header.id))
      }
      if (changed) detail.refresh(item.id)
    }
      .launchIn(viewModelScope)
  }

  private fun launchStarted(source: WorkItem) {
    relaunchedPhase = if (source.standaloneExecution) StandaloneRelaunch(source, latestBoardItems.mapTo(HashSet()) { it.id }) else null
    launchAccepted = false
  }

  private fun launchFinished(outcome: ControlOutcome) {
    launchAccepted = outcome == ControlOutcome.RequestAccepted
    if (!launchAccepted) relaunchedPhase = null
    followRelaunchedPhase()
  }

  private fun followRelaunchedPhase() {
    if (!launchAccepted) return
    val successor = relaunchedPhase?.successor(latestBoardItems) ?: return
    relaunchedPhase = null
    effectChannel.trySend(DetailEffect.OpenItem(successor))
  }

  /**
   * Opens the next or previous board item; returns false when there is none, so the key goes unhandled. [keepListFocus] (J or K
   * pressed in the focused Subtasks list) hands that focus to the next detail's list.
   */
  fun step(next: Boolean, keepListFocus: Boolean = false): Boolean {
    if (!state.value.itemResolved) return false
    val position = state.value.boardPosition
    val target = (if (next) position?.next else position?.previous) ?: return false
    if (keepListFocus) subtaskSelections.carryListFocus()
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
      is DetailAction.SelectSubtask -> selectSubtask(action.key, action.inline)
      is DetailAction.MoveSubtaskSelection -> moveSubtaskSelection(action.delta)
      DetailAction.OpenSelectedSubtaskSpec -> openSelectedSubtaskSpec()
      DetailAction.SubtaskListFocused -> if (state.value.focusSubtaskList) mutableState.update { it.copy(focusSubtaskList = false) }
    }
  }

  private fun loadedDetail() = (state.value.sections as? SectionsUi.Loaded)?.detail

  private fun effectiveSubtask(): SubtaskKey? = loadedDetail()?.let { selectedSubtask(state.value.header, it, state.value.subtaskChoice.key) }

  private fun chooseSubtask(choice: SubtaskChoice) {
    subtaskSelections.set(item.id, choice)
    mutableState.update { it.copy(subtaskChoice = choice) }
  }

  /** Inline, selecting the selected row again folds or unfolds its inspector; any other selection opens it. */
  private fun selectSubtask(key: SubtaskKey, inline: Boolean) {
    // A chip can name a dependency the index does not list; selecting it would store a choice that falls back to the default.
    val detail = loadedDetail() ?: return
    if (key !in subtaskKeys(state.value.header, detail)) return
    val choice = state.value.subtaskChoice
    if (inline && key == effectiveSubtask()) chooseSubtask(choice.copy(key = key, expanded = !choice.expanded)) else chooseSubtask(SubtaskChoice(key, expanded = true))
  }

  private fun moveSubtaskSelection(delta: Int) {
    val detail = loadedDetail() ?: return
    val keys = subtaskKeys(state.value.header, detail)
    if (keys.isEmpty()) return
    val index = keys.indexOf(effectiveSubtask()).coerceAtLeast(0)
    chooseSubtask(SubtaskChoice(keys[(index + delta).coerceIn(0, keys.lastIndex)], expanded = true))
  }

  private fun openSelectedSubtaskSpec() {
    val detail = loadedDetail() ?: return
    val key = effectiveSubtask() as? SubtaskKey.Subtask ?: return
    subtaskSpecPath(detail, key.id)?.let { onAction(DetailAction.OpenSpecFile(it)) }
  }

  private fun observe() {
    observation?.cancel()
    observation = detail.observe(item).onEach(::show).launchIn(viewModelScope)
  }

  private fun show(read: DetailState) {
    val resolved = read.sections is DetailSections.Loaded &&
      (session.state.value as? DatabaseSessionState.Open)?.sessionId == read.header.control.sessionId
    val header = read.header.withBoardVerdicts(latestBoardRow)
    if (resolved) selection.select(header)
    mutableState.update { current ->
      val sections = when (val sections = read.sections) {
        // A detail that has shown its sections never returns to placeholders.
        DetailSections.Pending -> current.sections.takeIf { it is SectionsUi.Loaded } ?: SectionsUi.Pending
        is DetailSections.Loaded -> SectionsUi.Loaded(sections.detail, sections.runOptions, sections.listCursors, sections.moreLists)
        DetailSections.Gone -> SectionsUi.Gone
        is DetailSections.Failed -> SectionsUi.Failed(sections.problem)
      }
      val stale = (read.sections as? DetailSections.Loaded)?.stale ?: (current.stale && sections is SectionsUi.Loaded)
      current.copy(header = header, sections = sections, itemResolved = current.itemResolved || resolved, stale = stale, renderedAt = clock.now())
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

private fun WorkItem.withBoardVerdicts(row: WorkItem): WorkItem = copy(
  running = row.running,
  recentlyFinishedPhase = row.recentlyFinishedPhase,
  doneWithinDay = row.doneWithinDay,
)

/**
 * The item's place in the board order the pager walks: the live rows, the same rows Work lists and counts, or the Finished today
 * rows for a finished item. Null when the board does not show the item.
 */
internal fun boardPosition(board: BoardState, id: String): BoardPosition? {
  val loaded = board as? BoardState.Loaded ?: return null
  val live = loaded.liveRows()
  val rows = if (live.any { it.id == id }) live else loaded.sections.firstOrNull { it.kind == BoardSectionKind.Finished }?.items.orEmpty()
  val index = rows.indexOfFirst { it.id == id }
  if (index < 0) return null
  return BoardPosition(index, rows.size, rows.getOrNull(index - 1), rows.getOrNull(index + 1))
}
