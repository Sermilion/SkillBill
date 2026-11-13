package dev.skillbill.desktop.feature.detail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.skillbill.desktop.core.common.Outcome
import dev.skillbill.desktop.core.domain.model.ActivityResult
import dev.skillbill.desktop.core.domain.model.BoardSectionKind
import dev.skillbill.desktop.core.domain.model.BoardState
import dev.skillbill.desktop.core.domain.model.ChangeBaseline
import dev.skillbill.desktop.core.domain.model.ChangeScope
import dev.skillbill.desktop.core.domain.model.ChangesState
import dev.skillbill.desktop.core.domain.model.ControlOutcome
import dev.skillbill.desktop.core.domain.model.DetailList
import dev.skillbill.desktop.core.domain.model.DetailListOutcome
import dev.skillbill.desktop.core.domain.model.DetailSections
import dev.skillbill.desktop.core.domain.model.DetailState
import dev.skillbill.desktop.core.domain.model.OpenOutcome
import dev.skillbill.desktop.core.domain.model.QaPreviewRequest
import dev.skillbill.desktop.core.domain.model.QaPreviewState
import dev.skillbill.desktop.core.domain.model.StandaloneRelaunch
import dev.skillbill.desktop.core.domain.model.SubtaskScopesState
import dev.skillbill.desktop.core.domain.model.selectableSubtaskIds
import dev.skillbill.desktop.core.domain.model.WorkItem
import dev.skillbill.desktop.core.domain.model.WorkKind
import dev.skillbill.desktop.core.domain.model.liveRows
import dev.skillbill.desktop.core.domain.repository.QaPreviewRepository
import dev.skillbill.desktop.core.domain.repository.SpecRepository
import dev.skillbill.desktop.core.domain.repository.WorkActivityRepository
import dev.skillbill.desktop.core.domain.repository.WorkChangeScopesRepository
import dev.skillbill.desktop.core.domain.repository.WorkChangesRepository
import dev.skillbill.desktop.core.domain.repository.WorkBoardRepository
import dev.skillbill.desktop.core.domain.repository.WorkControlRepository
import dev.skillbill.desktop.core.domain.repository.WorkDetailRepository
import dev.skillbill.desktop.core.domain.repository.WorkDetailResolution
import dev.skillbill.desktop.core.domain.settings.AppSettingsRepository
import dev.skillbill.desktop.core.domain.settings.FavoriteWorkKey
import dev.skillbill.desktop.core.domain.session.DatabaseSessionController
import dev.skillbill.desktop.core.domain.session.DatabaseSessionState
import dev.skillbill.desktop.core.ui.board.ShellBoard
import dev.skillbill.desktop.core.ui.feedback.ToastMessage
import dev.skillbill.desktop.core.ui.feedback.ToastQueue
import dev.skillbill.desktop.core.ui.feedback.ToastTone
import dev.skillbill.desktop.core.ui.input.ScreenKey
import dev.skillbill.desktop.core.ui.selection.SelectionHolder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
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
private const val QA_INSTRUCTIONS_NOT_OPENED = "Couldn't open .skill-bill/phases/qa.md. Create it in the repository, then paste the snippet."
private const val QA_INSTRUCTIONS_PATH = ".skill-bill/phases/qa.md"

/**
 * One item's detail. The first state already holds the route's row as the header, so the header paints before any read. Pages a
 * list asks for are appended by the repository into the observed detail; this ViewModel only picks the anchor and never merges.
 */
@Inject
class DetailViewModel(
  @Assisted private val item: WorkItem,
  private val session: DatabaseSessionController,
  private val detail: WorkDetailRepository,
  private val changes: WorkChangesRepository,
  private val scopes: WorkChangeScopesRepository,
  private val activity: WorkActivityRepository,
  private val board: WorkBoardRepository,
  private val specs: SpecRepository,
  private val webPages: RepositoryWebPages,
  private val selection: SelectionHolder,
  private val subtaskSelections: SubtaskSelections,
  private val clock: Clock,
  workControls: WorkControlRepository,
  private val toasts: ToastQueue,
  private val settings: AppSettingsRepository,
  private val qaPreview: QaPreviewRepository,
) : ViewModel() {
  private val mutableState = MutableStateFlow(
    DetailUiState(
      header = item,
      sections = SectionsUi.Pending,
      itemResolved = false,
      stale = false,
      renderedAt = clock.now(),
      subtaskChoice = subtaskSelections.get(item.id),
      phaseChoice = subtaskSelections.phases(item.id),
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
  private var confirmedFavorites = emptySet<FavoriteWorkKey>()
  private var favoritesAvailable = false
  private var resolvedSessionId: Long? = null
  private var favoriteWrite: FavoriteWrite? = null
  private var favoriteRequest: Job? = null
  private var favoriteRequestId = 0L

  private data class FavoriteWrite(
    val requestId: Long,
    val sessionId: Long,
    val key: FavoriteWorkKey,
    val desired: Boolean,
    var writeSucceeded: Boolean = false,
  )

  private val controls = DetailControls(viewModelScope, workControls, toasts, ::launchStarted, ::launchFinished) { mutableState.value }
  private val verifyFixActions = VerifyFixActions(viewModelScope, settings, toasts, mutableState) { effectChannel.trySend(it) }
  private val verifyAgainLauncher = VerifyAgainLauncher(viewModelScope, workControls, session, toasts, mutableState)

  override fun onCleared() {
    updatePreviewInputs { it.copy(qaPreviewVisible = false) }
    super.onCleared()
  }

  init {
    selection.select(null)
    controls.state.onEach { controls -> mutableState.update { it.copy(controls = controls) } }.launchIn(viewModelScope)
    settings.settings.map { it.defaultAgentId }.distinctUntilChanged()
      .onEach { agent -> mutableState.update { it.copy(defaultAgentId = agent) } }
      .catch { mutableState.update { it.copy(defaultAgentId = null) } }
      .launchIn(viewModelScope)
    settings.favoriteWork.onEach { result ->
      when (result) {
        is Outcome.Success -> {
          confirmedFavorites = result.value
          favoritesAvailable = true
          updateFavoriteState()
          favoriteWrite?.takeIf { it.writeSucceeded && (it.key in confirmedFavorites) == it.desired }?.let { finishFavoriteWrite(it.requestId) }
        }
        is Outcome.Failure -> {
          favoritesAvailable = false
          updateFavoriteState()
          toasts.show(ToastMessage(ToastTone.Problem, "Couldn't read saved favorites. Try again later."))
          favoriteWrite?.takeIf { it.writeSucceeded }?.let {
            toasts.show(ToastMessage(ToastTone.Problem, "Couldn't confirm this favorite. Try again."))
            finishFavoriteWrite(it.requestId)
          }
        }
      }
    }.launchIn(viewModelScope)
    session.state.map { (it as? DatabaseSessionState.Open)?.sessionId }.distinctUntilChanged().onEach { sessionId ->
      if (resolvedSessionId != sessionId) invalidateFavoriteForSessionChange() else updatePreviewInputs { it }
    }.launchIn(viewModelScope)
    observe()
    observeQaPreview()
    observeChanges()
    observeActivity()
    board.observe(ShellBoard.query).onEach { boardState ->
      if (boardState is BoardState.Loaded) latestBoardItems = boardState.items
      followRelaunchedPhase()
      val row = (boardState as? BoardState.Loaded)?.items?.firstOrNull { it.id == item.id }
      val changed = row != null && (
        row.lifecycle != latestBoardRow.lifecycle || row.fresh != latestBoardRow.fresh ||
          row.pauseRequested != latestBoardRow.pauseRequested || row.stepLabel != latestBoardRow.stepLabel
        )
      if (row != null) latestBoardRow = row
      updatePreviewInputs { state ->
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
    if (action is DetailAction.SetQaPreviewVisible) {
      updatePreviewInputs { it.copy(qaPreviewVisible = action.visible) }
      return
    }
    if (!state.value.itemResolved && action != DetailAction.Retry && action !is DetailAction.Navigate) return
    if (action is DetailAction.ToggleFix || action is DetailAction.SetFixRoute || action is DetailAction.ToggleSelectAllFixes ||
      action == DetailAction.ClearFixSelection || action == DetailAction.FixInChat || action == DetailAction.StartGoalWithFixes ||
      action is DetailAction.GoalIntakeResult || action is DetailAction.MarkCriterion
    ) {
      verifyFixActions.onAction(action)
      return
    }
    when (action) {
      is ControlsAction -> controls.onAction(action)
      DetailAction.ToggleFavorite -> toggleFavorite()
      DetailAction.VerifyAgain -> verifyAgainLauncher.start()
      is DetailAction.ToggleQaCase -> toggleQaCase(action.key)
      is DetailAction.ToggleValidationCheck -> toggleValidationCheck(action.key)
      is DetailAction.SetAllQaCases -> mutableState.update { current -> current.copy(qaCaseOverrides = current.qaCaseOverrides + action.keys.associateWith { action.open }) }
      is DetailAction.SelectQaRun -> if (action.invocationId != null) updatePreviewInputs { it.copy(selectedQaRun = action.invocationId) }
      is DetailAction.OpenQaReport -> open(FILE_NOT_OPENED) { specs.openFileInDefaultApp(action.path) }
      is DetailAction.OpenQaEvidence -> open(FILE_NOT_OPENED) { specs.openFileUnder(action.path, action.invocationDirectory) }
      DetailAction.OpenQaPhaseInstructions -> openQaPhaseInstructions()
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
      is DetailAction.SelectSubtask -> selectSubtask(action.key)
      is DetailAction.SelectPhase -> selectPhase(action)
      is DetailAction.MoveSubtaskSelection -> moveSubtaskSelection(action.delta)
      DetailAction.OpenSelectedSubtaskSpec -> openSelectedSubtaskSpec()
      DetailAction.FocusSubtaskList -> mutableState.update { it.copy(focusSubtaskList = true) }
      is DetailAction.SubtaskListTargetChanged -> mutableState.update { current ->
        current.copy(subtaskListTargetAvailable = action.available)
      }
      DetailAction.SubtaskListFocused -> if (state.value.focusSubtaskList) mutableState.update { it.copy(focusSubtaskList = false) }
      DetailAction.SubtaskListFocusUnavailable -> Unit
      is DetailAction.OpenChangesWindow -> openChangesWindow(action.scope)
      is DetailAction.SetVerifyCriteriaFilter -> mutableState.update { it.copy(verifyCriteriaFilter = action.filter) }
      is DetailAction.OpenNewerVerifyRun -> openNewerVerifyRun(action.workflowId)
      is DetailAction.ReviewFindings -> {
        val header = state.value.header
        val detail = loadedDetail() ?: return
        if (reviewActionsEnabled(header, detail)) effectChannel.trySend(DetailEffect.ReviewFindings(header, action.action))
      }
    }
  }

  private fun loadedDetail() = (state.value.sections as? SectionsUi.Loaded)?.detail

  private fun observeQaPreview() {
    viewModelScope.launch {
      mutableState.map { it.qaPreviewRequest to it.qaPreviewGeneration }.distinctUntilChanged().collectLatest { (request, generation) ->
        if (!isCurrentPreview(request, generation)) return@collectLatest
        if (request == null || !request.active) return@collectLatest
        qaPreview.observe(request).collect { preview ->
          mutableState.update { current ->
            if (current.qaPreviewGeneration == generation && current.qaPreviewRequest == request &&
              currentPreviewRequest(current) == request
            ) current.copy(qaPreview = preview) else current
          }
        }
      }
    }
  }

  private fun updatePreviewInputs(transform: (DetailUiState) -> DetailUiState) {
    mutableState.update { current ->
      val updated = transform(current)
      val request = currentPreviewRequest(updated)
      if (request == current.qaPreviewRequest) updated else updated.copy(
        qaPreviewRequest = request,
        qaPreviewGeneration = current.qaPreviewGeneration + 1,
        qaPreview = if (request?.active == true) QaPreviewState.Waiting(QaPreviewState.Reason.SourceUnavailable) else QaPreviewState.Ended,
      )
    }
  }

  private fun currentPreviewRequest(detailState: DetailUiState): QaPreviewRequest? {
    val openSessionId = (session.state.value as? DatabaseSessionState.Open)?.sessionId ?: return null
    val detail = (detailState.sections as? SectionsUi.Loaded)?.detail ?: return null
    if (!detailState.qaPreviewVisible || !detailState.itemResolved ||
      detailState.header.control.sessionId != openSessionId
    ) return null
    val preview = selectedQaPreview(detailState.header, detail, detailState.selectedQaRun, detailState.subtaskChoice.key) ?: return null
    val goalFinishOwned = detailState.header.kind == WorkKind.Goal && !isStandaloneQa(detailState.header) && detail.goalQaPreview?.subtaskId == null
    val finishChoice = detailState.phaseChoice[PhaseChoiceKey(detailState.header.id, SubtaskKey.GoalFinish)]
    if (goalFinishOwned && selectedPhase(goalFinishTabs(detail), detail.goalFinishCards, finishChoice) != "qa") return null
    return QaPreviewRequest(
      sessionId = openSessionId,
      workId = detailState.header.id,
      invocationId = preview.invocationId,
      invocationDirectory = preview.directory,
      active = preview.active,
    )
  }

  private fun isCurrentPreview(request: QaPreviewRequest?, generation: Long): Boolean {
    val current = mutableState.value
    return current.qaPreviewGeneration == generation && current.qaPreviewRequest == request && currentPreviewRequest(current) == request
  }

  private fun openNewerVerifyRun(id: String) {
    viewModelScope.launch {
      when (val resolution = detail.resolve(id)) {
        is WorkDetailResolution.Resolved -> effectChannel.send(DetailEffect.OpenItem(resolution.item))
        WorkDetailResolution.Missing -> toasts.show(ToastMessage(ToastTone.Problem, "The newer verify run is no longer in the database."))
        is WorkDetailResolution.Failed -> toasts.show(ToastMessage(ToastTone.Problem, "Couldn't open the newer verify run. Try again."))
      }
    }
  }

  private fun toggleQaCase(key: QaCaseKey) {
    val open = qaCaseIsOpen(state.value.header, loadedDetail() ?: return, key, state.value.qaCaseOverrides) ?: return
    mutableState.update { it.copy(qaCaseOverrides = it.qaCaseOverrides + (key to !open)) }
  }

  private fun toggleValidationCheck(key: ValidationCheckKey) {
    val detail = loadedDetail() ?: return
    val run = detail.execution(key.invocationId) ?: return
    val row = validationChecksSummary(run, qaRunLive(state.value.header, run)).rows.firstOrNull { it.check.checkId == key.checkId } ?: return
    val open = validationCheckIsOpen(row, run.invocationId, qaRunLive(state.value.header, run), state.value.validationCheckOverrides)
    mutableState.update { it.copy(validationCheckOverrides = it.validationCheckOverrides + (key to !open)) }
  }

  private fun effectiveSubtask(): SubtaskKey? = loadedDetail()?.let { selectedSubtask(state.value.header, it, state.value.subtaskChoice.key) }

  private fun chooseSubtask(choice: SubtaskChoice) {
    subtaskSelections.set(item.id, choice)
    updatePreviewInputs { it.copy(subtaskChoice = choice) }
  }

  private fun selectSubtask(key: SubtaskKey) {
    // A chip can name a dependency the index does not list; selecting it would store a choice that falls back to the default.
    val detail = loadedDetail() ?: return
    if (key !in subtaskKeys(state.value.header, detail)) return
    chooseSubtask(SubtaskChoice(key))
  }

  private fun selectPhase(action: DetailAction.SelectPhase) {
    if (action.itemId != item.id) return
    val key = PhaseChoiceKey(action.itemId, action.key)
    val detail = loadedDetail() ?: return
    val cards = subjectPhaseCards(detail, action.key)
    val tabs = subjectPhaseTabs(detail, action.key)
    if (tabs.none { it.id == action.phaseId && it.openable }) return
    val open = selectedPhase(tabs, cards, state.value.phaseChoice[key])
    val choice = if (action.intent == PhaseSelectionIntent.Activate && open == action.phaseId) PhaseDisclosure.Folded else PhaseDisclosure.Open(action.phaseId)
    subtaskSelections.setPhase(key, choice)
    updatePreviewInputs { it.copy(phaseChoice = it.phaseChoice + (key to choice)) }
  }

  private fun moveSubtaskSelection(delta: Int) {
    val detail = loadedDetail() ?: return
    val keys = subtaskKeys(state.value.header, detail)
    if (keys.isEmpty()) return
    val index = keys.indexOf(effectiveSubtask()).coerceAtLeast(0)
    chooseSubtask(SubtaskChoice(keys[(index + delta).coerceIn(0, keys.lastIndex)]))
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
    val currentSessionId = (session.state.value as? DatabaseSessionState.Open)?.sessionId
    if (resolvedSessionId != null && resolvedSessionId != currentSessionId) resetFavoriteForSessionChange()
    val resolved = read.sections is DetailSections.Loaded && currentSessionId != null &&
      currentSessionId == read.header.control.sessionId
    if (resolved) resolvedSessionId = currentSessionId
    val header = read.header.withBoardVerdicts(latestBoardRow)
    if (resolved) selection.select(header)
    updatePreviewInputs { current ->
      val retained = current.itemResolved && resolvedSessionId != null && resolvedSessionId == currentSessionId &&
        current.header.control.sessionId == currentSessionId
      val sections = when (val sections = read.sections) {
        // A detail that has shown its sections never returns to placeholders.
        DetailSections.Pending -> current.sections.takeIf { retained && it is SectionsUi.Loaded } ?: SectionsUi.Pending
        is DetailSections.Loaded -> SectionsUi.Loaded(sections.detail, sections.runOptions, sections.listCursors, sections.moreLists)
        DetailSections.Gone -> SectionsUi.Gone
        is DetailSections.Failed -> SectionsUi.Failed(sections.problem)
      }
      val stale = (read.sections as? DetailSections.Loaded)?.stale ?: (current.stale && sections is SectionsUi.Loaded)
      current.copy(
        header = header,
        sections = sections,
        itemResolved = resolved || (retained && read.sections is DetailSections.Pending),
        stale = stale,
        renderedAt = clock.now(),
      )
    }
    updateFavoriteState()
    verifyFixActions.refresh()
  }

  private fun favoriteKey(header: WorkItem): FavoriteWorkKey? =
    header.control.databaseFile?.let { FavoriteWorkKey(it, header.id) }

  private fun updateFavoriteState() {
    val current = state.value
    val sessionId = (session.state.value as? DatabaseSessionState.Open)?.sessionId
    val isCurrent = current.itemResolved && resolvedSessionId == sessionId && sessionId == current.header.control.sessionId
    val favorite = isCurrent && favoriteKey(current.header)?.let { it in confirmedFavorites } == true
    mutableState.update { it.copy(favorite = favorite, favoriteAvailable = isCurrent && favoritesAvailable) }
  }

  private fun toggleFavorite() {
    val current = state.value
    if (!current.itemResolved || !current.favoriteAvailable || current.favoriteInFlight) return
    val header = current.header
    val sessionId = (session.state.value as? DatabaseSessionState.Open)?.sessionId ?: return
    if (resolvedSessionId != sessionId || sessionId != header.control.sessionId) return
    val key = favoriteKey(header)
    if (key == null) {
      toasts.show(ToastMessage(ToastTone.Problem, "This work item has no database identity, so it cannot be favorited."))
      return
    }
    val desired = key !in confirmedFavorites
    val requestId = ++favoriteRequestId
    val write = FavoriteWrite(requestId, sessionId, key, desired)
    favoriteWrite = write
    mutableState.update { it.copy(favoriteInFlight = true) }
    favoriteRequest = viewModelScope.launch {
      try {
        when (settings.setFavorite(key, desired)) {
          is Outcome.Success -> if (isCurrentFavoriteWrite(write)) {
            write.writeSucceeded = true
            when {
              !favoritesAvailable -> {
                toasts.show(ToastMessage(ToastTone.Problem, "Couldn't confirm this favorite. Try again."))
                finishFavoriteWrite(requestId)
              }
              (key in confirmedFavorites) == desired -> finishFavoriteWrite(requestId)
            }
          }
          is Outcome.Failure -> if (isCurrentFavoriteWrite(write)) {
            toasts.show(ToastMessage(ToastTone.Problem, "Couldn't save this favorite. Try again."))
            finishFavoriteWrite(requestId)
          }
        }
      } catch (cancelled: CancellationException) {
        throw cancelled
      } catch (_: Exception) {
        if (isCurrentFavoriteWrite(write)) {
          toasts.show(ToastMessage(ToastTone.Problem, "Couldn't save this favorite. Try again."))
          finishFavoriteWrite(requestId)
        }
      }
    }
  }

  private fun invalidateFavoriteForSessionChange() {
    resetFavoriteForSessionChange()
    observe()
  }

  private fun resetFavoriteForSessionChange() {
    resolvedSessionId = null
    favoriteRequestId++
    favoriteRequest?.cancel()
    favoriteRequest = null
    favoriteWrite = null
    updatePreviewInputs { it.copy(itemResolved = false, sections = SectionsUi.Pending, favorite = false, favoriteAvailable = false, favoriteInFlight = false) }
  }

  private fun isCurrentFavoriteWrite(write: FavoriteWrite): Boolean = favoriteWrite === write &&
    favoriteRequestId == write.requestId && resolvedSessionId == write.sessionId &&
    state.value.header.control.sessionId == write.sessionId &&
    (session.state.value as? DatabaseSessionState.Open)?.sessionId == write.sessionId

  private fun finishFavoriteWrite(requestId: Long) {
    if (favoriteWrite?.requestId != requestId) return
    favoriteWrite = null
    favoriteRequest = null
    mutableState.update { it.copy(favoriteInFlight = false) }
  }

  /**
   * Only this item's loaded detail feeds the key, so only its worktree is read. A goal reads its subtask scopes keyed on running
   * alone, so a subtask's new baseline does not restart the read; other items except standalone reviews read their change summary from their baseline. A new
   * key cancels the previous observation; an idle item is read once and no longer polled.
   */
  @OptIn(ExperimentalCoroutinesApi::class)
  private fun observeChanges() {
    mutableState
      .map { state ->
        if (!state.header.showsChanges) return@map null
        (state.sections as? SectionsUi.Loaded)?.let {
          val goal = state.header.kind == WorkKind.Goal
          ChangesKey(if (goal) null else it.detail.changeBaseline, state.header.running, goal, state.header.goalWorktree)
        }
      }
      .distinctUntilChanged()
      .flatMapLatest { key ->
        when {
          key == null -> emptyFlow()
          key.goal -> scopes.observeSubtasks(item.id, key.running).map { read -> showSubtasks(read) }
          else -> changes.observe(key.baseline, key.running).map { read -> showChanges(key, read) }
        }
      }
      .onEach { change -> mutableState.update { it.copy(changes = change(it.changes)) } }
      .launchIn(viewModelScope)
  }

  @OptIn(ExperimentalCoroutinesApi::class)
  private fun observeActivity() {
    mutableState.map { state ->
      state.header.running.takeIf { state.header.kind == WorkKind.Goal && state.sections is SectionsUi.Loaded }
    }.distinctUntilChanged().flatMapLatest { running ->
      if (running == null) emptyFlow() else activity.observe(item.id, running)
    }.onEach { result ->
      mutableState.update { current ->
        if (result == ActivityResult.Loading && current.activity is ActivityResult.Listed) current else current.copy(activity = result)
      }
    }.launchIn(viewModelScope)
  }

  // Re-observing after only running changed keeps the shown result until the next real one.
  private fun showSubtasks(read: SubtaskScopesState): (ChangesUi) -> ChangesUi = { current ->
    if (read == SubtaskScopesState.Loading && current.subtasks is SubtaskScopesState.Listed) current else ChangesUi(subtasks = read)
  }

  private fun showChanges(key: ChangesKey, read: ChangesState): (ChangesUi) -> ChangesUi = { current ->
    val keep = read == ChangesState.Loading && current.baseline == key.baseline && current.state is ChangesState.Listed
    if (keep) current else ChangesUi(key.baseline, read)
  }

  /** Standalone reviews have no Changes window. Subtask scopes open only for a subtask the latest list can select. */
  private fun openChangesWindow(scope: ChangeScope) {
    val current = state.value
    val verifyRun = loadedDetail()?.verifyRun
    if (verifyRun != null) {
      if (scope != ChangeScope.All || verifyRun.changedFiles.isEmpty() || verifyRunState(verifyRun) == VerifyRunState.Parked) return
    } else if (!current.header.showsChanges) return
    if (scope is ChangeScope.Subtasks) {
      val listed = state.value.changes.subtasks as? SubtaskScopesState.Listed ?: return
      if (scope.from !in selectableSubtaskIds(listed.subtasks)) return
    }
    effectChannel.trySend(DetailEffect.OpenChangesWindow(state.value.header, scope))
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

  private fun openQaPhaseInstructions() {
    val root = state.value.header.repositoryRoot.trimEnd('/')
    if (root.isBlank()) {
      effectChannel.trySend(DetailEffect.ShowToast(ToastMessage(ToastTone.Problem, QA_INSTRUCTIONS_NOT_OPENED)))
      return
    }
    open(QA_INSTRUCTIONS_NOT_OPENED) { specs.openFileUnder("$root/$QA_INSTRUCTIONS_PATH", root) }
  }
}

private data class ChangesKey(
  val baseline: ChangeBaseline?,
  val running: Boolean,
  val goal: Boolean,
  val goalWorktree: dev.skillbill.desktop.core.domain.model.GoalWorktree?,
)

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
