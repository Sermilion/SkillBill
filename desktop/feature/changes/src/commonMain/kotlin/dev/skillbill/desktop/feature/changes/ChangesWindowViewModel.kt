package dev.skillbill.desktop.feature.changes

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.skillbill.desktop.core.domain.model.ChangeScope
import dev.skillbill.desktop.core.domain.model.ChangeScopeTarget
import dev.skillbill.desktop.core.domain.model.ChangeBaseline
import dev.skillbill.desktop.core.domain.model.ChangeStatus
import dev.skillbill.desktop.core.domain.model.ChangedFile
import dev.skillbill.desktop.core.domain.model.CommentAuthor
import dev.skillbill.desktop.core.domain.model.CommentRejection
import dev.skillbill.desktop.core.domain.model.CommentState
import dev.skillbill.desktop.core.domain.model.DiffCommentsState
import dev.skillbill.desktop.core.domain.model.Detail
import dev.skillbill.desktop.core.domain.model.DetailSections
import dev.skillbill.desktop.core.domain.model.DetailState
import dev.skillbill.desktop.core.domain.model.FileDiffState
import dev.skillbill.desktop.core.domain.model.OpenOutcome
import dev.skillbill.desktop.core.domain.model.ScopeChangesState
import dev.skillbill.desktop.core.domain.model.ScopeKind
import dev.skillbill.desktop.core.domain.model.ScopeSubtask
import dev.skillbill.desktop.core.domain.model.ScopeSubtaskState
import dev.skillbill.desktop.core.domain.model.SubtaskScopesState
import dev.skillbill.desktop.core.domain.model.SubtaskStats
import dev.skillbill.desktop.core.domain.model.WorkItem
import dev.skillbill.desktop.core.domain.model.WorkKind
import dev.skillbill.desktop.core.domain.model.availableScopes
import dev.skillbill.desktop.core.domain.model.executionScopeTarget
import dev.skillbill.desktop.core.domain.model.goalScopeTarget
import dev.skillbill.desktop.core.domain.model.kind
import dev.skillbill.desktop.core.domain.model.normalizeRange
import dev.skillbill.desktop.core.domain.model.normalizeCommits
import dev.skillbill.desktop.core.domain.model.selectableSubtaskIds
import dev.skillbill.desktop.core.domain.model.selectedSubtaskIds
import dev.skillbill.desktop.core.domain.model.subtasksTouching
import dev.skillbill.desktop.core.domain.repository.WorkChangeScopesRepository
import dev.skillbill.desktop.core.domain.repository.WorkChangesRepository
import dev.skillbill.desktop.core.domain.repository.WorkDetailRepository
import dev.skillbill.desktop.core.domain.repository.DiffCommentRepository
import dev.skillbill.desktop.core.common.Outcome
import kotlinx.coroutines.ExperimentalCoroutinesApi
import dev.skillbill.desktop.core.ui.diff.diffLongestChars
import dev.skillbill.desktop.core.ui.diff.diffRowCount
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import me.tatarka.inject.annotations.Assisted
import me.tatarka.inject.annotations.Inject

@OptIn(ExperimentalCoroutinesApi::class)
@Inject
class ChangesWindowViewModel(
  @Assisted item: WorkItem,
  @Assisted initialScope: ChangeScope,
  detail: WorkDetailRepository,
  private val scopes: WorkChangeScopesRepository,
  private val changes: WorkChangesRepository,
  private val comments: DiffCommentRepository,
) : ViewModel() {
  private val requestedScope = MutableStateFlow(initialScope)
  private val mutableState = MutableStateFlow(
    ChangesWindowUiState(item, scope = initialScope, anchor = (initialScope as? ChangeScope.Subtasks)?.from),
  )
  val state: StateFlow<ChangesWindowUiState> = mutableState.asStateFlow()
  private val authoringActions = DiffCommentAuthoringActions(viewModelScope, comments, { state.value }, { change -> mutableState.update(change) })
  private var currentLoadedDetail: DetailState? = null
  private var observedGoal: GoalObservationKey? = null
  private var notGoalKey: GoalObservationKey? = null
  private var goalObservation: Job? = null
  private var goalObservationVersion = 0
  private var diffRead: Job? = null
  private var diffReadVersion = 0
  private val quickReads = MutableStateFlow<QuickReads?>(null)
  private var wantedDiffs: List<String> = emptyList()
  private var streamRead: Job? = null
  private var streamKey: ScopeReadKey? = null
  init {
    detail.observe(item).onEach { observed ->
      val runningChanged = mutableState.value.item.running != observed.header.running
      if (runningChanged) invalidateDiffRead()
      mutableState.update {
        it.copy(item = observed.header, allRead = if (runningChanged) null else it.allRead)
      }
      when (val sections = observed.sections) {
        DetailSections.Pending -> {
          currentLoadedDetail = null
          notGoalKey = null
          stopGoalObservation()
          clearReads(WindowContent.Loading)
        }
        DetailSections.Gone -> {
          currentLoadedDetail = null
          notGoalKey = null
          stopGoalObservation()
          clearReads(WindowContent.Missing)
        }
        is DetailSections.Failed -> {
          currentLoadedDetail = null
          notGoalKey = null
          stopGoalObservation()
          clearReads(WindowContent.Problem(sections.problem))
        }
        is DetailSections.Loaded -> {
          currentLoadedDetail = observed
          if (observed.header.kind == WorkKind.Goal) {
            val key = GoalObservationKey(observed.header.id, observed.header.running, observed.header.goalWorktree)
            if (notGoalKey == key) setExecutionTarget(observed) else observeGoal(key)
          } else {
            notGoalKey = null
            stopGoalObservation()
            setExecutionTarget(observed)
          }
        }
      }
    }.launchIn(viewModelScope)
    observeQuickReads()
    observeAllCount()
    observeScopeReads()
    state.map { it.target?.allBaseline }.distinctUntilChanged().flatMapLatest { baseline ->
      baseline?.let(comments::observe) ?: flowOf(DiffCommentsState.Loading)
    }.onEach { value ->
      val priorBranch = (mutableState.value.comments as? DiffCommentsState.Available)?.branch
      val nextBranch = (value as? DiffCommentsState.Available)?.branch
      if (priorBranch != nextBranch) authoringActions.cancelToast()
      mutableState.update { old ->
      val changedBranch = (old.comments as? DiffCommentsState.Available)?.branch != (value as? DiffCommentsState.Available)?.branch
      val edit = old.editing
      val editingPending = edit != null && (value as? DiffCommentsState.Available)?.threads?.any { thread ->
        thread.id == edit.threadId && thread.state == CommentState.Pending && thread.comments.any { it.id == edit.commentId && it.author == CommentAuthor.User }
      } == true
      old.copy(comments = value, composer = if (changedBranch || (value as? DiffCommentsState.Available)?.canAuthor != true) null else old.composer,
        editing = old.editing?.takeIf { editingPending && !changedBranch }, review = if (changedBranch) ReviewBarUi() else old.review,
        reviewToast = if (changedBranch) null else old.reviewToast)
      }
    }.launchIn(viewModelScope)
    observeCommitStats()
  }

  private fun stopGoalObservation() {
    goalObservationVersion++
    observedGoal = null
    goalObservation?.cancel()
    goalObservation = null
  }

  private fun observeGoal(key: GoalObservationKey) {
    if (observedGoal == key) return
    if (notGoalKey != key) notGoalKey = null
    stopGoalObservation()
    observedGoal = key
    val version = goalObservationVersion
    val currentTarget = (mutableState.value.content as? WindowContent.Ready)?.target
    if (currentTarget?.workId != key.id) clearReads(WindowContent.Loading) else invalidateDiffRead()
    goalObservation = scopes.observeSubtasks(key.id, key.running).onEach { result ->
      if (!isCurrentGoal(key, version)) return@onEach
      when (result) {
        SubtaskScopesState.Loading -> Unit
        SubtaskScopesState.NotAGoal -> {
          notGoalKey = key
          stopGoalObservation()
          mutableState.update { it.copy(subtasks = emptyList(), stats = emptyMap()) }
          currentLoadedDetail?.let(::setExecutionTarget)
        }
        is SubtaskScopesState.Unavailable -> {
          clearReads(WindowContent.Problem(result.problem))
        }
        is SubtaskScopesState.Listed -> {
          val target = goalScopeTarget(key.id, result)
          setTarget(target, result.subtasks, result.stats)
        }
      }
    }.launchIn(viewModelScope)
  }

  private fun isCurrentGoal(key: GoalObservationKey, version: Int): Boolean {
    val current = currentLoadedDetail ?: return false
    return goalObservationVersion == version && observedGoal == key &&
      current.header.kind == WorkKind.Goal && current.header.id == key.id && current.header.running == key.running &&
      current.header.goalWorktree == key.goalWorktree
  }

  private fun DetailState.detailOrNull(): Detail? = (sections as? DetailSections.Loaded)?.detail

  private fun setExecutionTarget(detailState: DetailState) {
    val detail = detailState.detailOrNull()
    val verifyRun = detail?.verifyRun
    val target = if (verifyRun != null) {
      val root = verifyRun.repoRoot?.takeIf(String::isNotBlank)
      val base = verifyRun.baseRevision?.takeIf(String::isNotBlank)
      val head = verifyRun.headRevision?.takeIf(String::isNotBlank)
      if (root != null && base != null && head != null) {
        executionScopeTarget(detailState.header.id, ChangeBaseline.CommittedRevisionRange(root, base, head)).copy(committedOnly = true)
      } else null
    } else {
      detail?.changeBaseline?.let { executionScopeTarget(detailState.header.id, it) }
    }
    if (target == null) clearReads(WindowContent.NoBaseline) else setTarget(target)
  }

  private fun setTarget(
    target: ChangeScopeTarget,
    subtasks: List<ScopeSubtask>? = null,
    stats: Map<Int, SubtaskStats>? = null,
  ) {
    val resetView = ScopeKind.Commits !in availableScopes(target)
    if (resetView && mutableState.value.view == ChangesView.Commits) mutableState.update { it.copy(view = ChangesView.Subtasks, pickerOpen = false) }
    val requested = requestedScope.value
    val view = if (resetView) ChangesView.Subtasks else mutableState.value.view
    val mapped = if (view == ChangesView.Commits && (requested is ChangeScope.Subtasks || requested == ChangeScope.Operator)) {
      commitSelectionOf(target, requested)
    } else requested
    val effective = effectiveScope(target, mapped)
    requestedScope.value = effective
    val current = mutableState.value
    val targetChanged = current.target != target
    val commitAnchor = if (!targetChanged) current.commitAnchor else {
      val previousTarget = current.target
      val sameWork = previousTarget != null && previousTarget.workId == target.workId && previousTarget.worktreeRoot == target.worktreeRoot
      val selected = effective as? ChangeScope.Commits
      if (sameWork && !resetView && selected != null) {
        val timelineShas = target.timeline?.commits?.mapTo(mutableSetOf()) { it.sha }.orEmpty()
        current.commitAnchor?.takeIf { it in selected.shas && it in timelineShas }
          ?: selected.shas.firstOrNull()?.takeIf { it in timelineShas }
      } else null
    }
    if (current.content == WindowContent.Ready(target) && current.scope == effective) {
      if (subtasks != null && stats != null) {
        mutableState.update { old -> project(old.copy(subtasks = subtasks, stats = stats)) }
      }
      return
    }
    if (current.target != target) authoringActions.cancelToast()
    invalidateDiffRead()
    mutableState.update { old ->
      val targetChanged = old.target != target
      project(
        old.copy(
          content = WindowContent.Ready(target),
          scope = effective,
          view = if (resetView) ChangesView.Subtasks else old.view,
          subtasks = subtasks ?: old.subtasks,
          stats = stats ?: old.stats,
          commitAnchor = commitAnchor,
          allRead = if (targetChanged) null else old.allRead,
          composer = if (targetChanged) null else old.composer,
          editing = if (targetChanged) null else old.editing,
          review = if (targetChanged) ReviewBarUi() else old.review,
          reviewToast = if (targetChanged) null else old.reviewToast,
          comments = if (targetChanged) DiffCommentsState.Loading else old.comments,
          threadUi = if (targetChanged) emptyMap() else old.threadUi,
        ),
        list = if (old.scope == effective) old.list else ScopeListUi.Loading,
      )
    }
  }

  private fun project(
    state: ChangesWindowUiState,
    list: ScopeListUi = state.list,
    filter: String = state.filter,
    scope: ChangeScope = state.scope,
    stats: Map<Int, SubtaskStats> = state.stats,
  ): ChangesWindowUiState {
    val groups = (list as? ScopeListUi.Listed)?.changes?.files?.let { files ->
      groupFiles(files, filter) { path -> scopeTouchCount(path, scope, stats, state.subtasks) }
    }.orEmpty()
    val visible = groups.flatMap { it.rows }.map { it.file.path }
    val selected = if (list !is ScopeListUi.Listed) state.selectedPath
    else state.selectedPath?.takeIf { it in visible } ?: visible.firstOrNull()
    val selectedFile = groups.asSequence().flatMap { it.rows.asSequence() }.firstOrNull { it.file.path == selected }?.file
    val target = (state.content as? WindowContent.Ready)?.target
    val diff = (state.diff as? DiffPaneUi.Shown)?.let { shown ->
      if (selectedFile == null || shown.file.path != selectedFile.path) shown
      else shown.copy(file = selectedFile, chips = projectChips(selectedFile, target, scope, stats))
    } ?: state.diff
    return state.copy(
      list = list,
      filter = filter,
      scope = scope,
      stats = stats,
      groups = groups,
      selectedPath = selected,
      diff = diff,
      openProblem = state.openProblem.takeIf { selected == state.selectedPath },
    )
  }

  private fun projectChips(
    file: ChangedFile,
    target: ChangeScopeTarget?,
    scope: ChangeScope,
    stats: Map<Int, SubtaskStats>,
  ): List<SubtaskChipUi> {
    if (target == null || target.subtasks.isEmpty()) return emptyList()
    return subtasksTouching(file.path, stats).mapNotNull { id ->
      val subtask = target.subtasks.firstOrNull { it.id == id } ?: return@mapNotNull null
      val inScope = when (scope) {
        is ChangeScope.Subtasks -> id in selectedSubtaskIds(target.subtasks, scope)
        is ChangeScope.Commits -> target.timeline?.commits?.any { it.sha in scope.shas && id in it.ownerIds } == true
        ChangeScope.All, ChangeScope.Operator, ChangeScope.Uncommitted -> false
      }
      SubtaskChipUi(id, subtask.name, inScope, normalizeRange(target, id, id) != null)
    }
  }

  private fun effectiveScope(target: ChangeScopeTarget, requested: ChangeScope): ChangeScope {
    if (requested.kind !in availableScopes(target)) return ChangeScope.All
    if (requested is ChangeScope.Subtasks) return normalizeRange(target, requested.from, requested.to) ?: ChangeScope.All
    if (requested is ChangeScope.Commits) return normalizeCommits(target, requested.shas) ?: ChangeScope.All
    return requested
  }

  private fun clearReads(content: WindowContent) {
    invalidateDiffRead()
    resetStream()
    mutableState.update {
      it.copy(
        content = content,
        subtasks = emptyList(),
        stats = emptyMap(),
        list = ScopeListUi.Loading,
        groups = emptyList(),
        selectedPath = null,
        diff = DiffPaneUi.NoSelection,
        allRead = null,
        pickerOpen = false,
        openProblem = null,
      )
    }
  }

  /**
   * Uncommitted, and Operator when the target offers it, stay observed for the quick buttons' counts. The shown list reuses these
   * reads when the scope is one of them, so the same scope is never polled twice.
   */
  private fun observeQuickReads() {
    state.map { ui -> ui.target?.let { QuickKey(it, ui.item.running) } }.distinctUntilChanged().flatMapLatest { key ->
      if (key == null) return@flatMapLatest flowOf<QuickReads?>(null)
      val uncommitted = if (ScopeKind.Uncommitted in availableScopes(key.target)) {
        scopes.observeScope(key.target, ChangeScope.Uncommitted, key.running).map { it as ScopeChangesState? }
      } else {
        flowOf(null)
      }
      val operator = if (ScopeKind.Operator in availableScopes(key.target)) {
        scopes.observeScope(key.target, ChangeScope.Operator, key.running)
      } else {
        flowOf(null)
      }
      combine(uncommitted, operator) { u, o -> QuickReads(key, u, o) }
    }.onEach { reads ->
      val previous = quickReads.value?.takeIf { it.key == reads?.key }
      // A re-read of the same target keeps the last settled list until the new one lands, like the shown list.
      val settled = reads?.copy(
        uncommitted = reads.uncommitted?.settledOver(previous?.uncommitted),
        operator = reads.operator?.settledOver(previous?.operator),
      )
      quickReads.value = settled
      mutableState.update { it.copy(uncommittedRead = settled?.uncommitted, operatorRead = settled?.operator) }
    }.launchIn(viewModelScope)
  }

  /** A panel opened on another scope still shows All's count: one read, not polled, until All is viewed. */
  private fun observeAllCount() {
    state.map { ui ->
      if (ui.scope != ChangeScope.All && ui.allRead == null) ui.target?.let { AllCountKey(it, ui.item.running) } else null
    }.distinctUntilChanged().flatMapLatest { key ->
      if (key == null) emptyFlow() else scopes.observeScope(key.target, ChangeScope.All, running = false).map { key to it }
    }.onEach { (key, read) ->
      if (read == ScopeChangesState.Loading) return@onEach
      mutableState.update { current ->
        if (current.target == key.target && current.item.running == key.running && current.scope != ChangeScope.All && current.allRead == null) {
          current.copy(allRead = read.toUi())
        } else current
      }
    }.launchIn(viewModelScope)
  }

  private fun observeScopeReads() {
    state.map { ui ->
      val target = (ui.content as? WindowContent.Ready)?.target
      target?.let { ScopeReadKey(it, ui.scope, ui.item.running) }
    }.distinctUntilChanged().flatMapLatest { key ->
      when {
        key == null -> emptyFlow()
        key.scope == ChangeScope.Uncommitted || key.scope == ChangeScope.Operator -> quickReads
          .map { reads -> reads?.takeIf { it.key == QuickKey(key.target, key.running) }?.get(key.scope) ?: ScopeChangesState.Loading }
          .distinctUntilChanged()
          .map { key to it }
        else -> scopes.observeScope(key.target, key.scope, key.running).map { key to it }
      }
    }.onEach { (key, result) ->
      val current = state.value
      if (current.content != WindowContent.Ready(key.target) || current.scope != key.scope || current.item.running != key.running) return@onEach
      val list = result.toUi()
      if (streamKey?.target != key.target || streamKey?.scope != key.scope) resetStream()
      streamKey = key
      mutableState.update { old ->
        val read = if (key.scope == ChangeScope.All && list != ScopeListUi.Loading) old.copy(allRead = list) else old
        if (list == ScopeListUi.Loading && old.list != ScopeListUi.Loading) read
        else project(read, list = list)
      }
      if (list != ScopeListUi.Loading) {
        loadDiff()
        (list as? ScopeListUi.Listed)?.let { dropStaleDiffs(it) }
      }
    }.launchIn(viewModelScope)
  }

  private fun observeCommitStats() {
    state.map { ui -> ui.target?.takeIf { ui.view == ChangesView.Commits } }
      .distinctUntilChanged()
      .flatMapLatest { target ->
        if (target == null) flowOf(null) else scopes.observeCommitStats(target).map { target to it }
      }
      .onEach { result ->
        if (result == null) mutableState.update { it.copy(commitStats = emptyMap()) }
        else {
          val (target, stats) = result
          mutableState.update { old ->
            if (old.target != target) old else old.copy(commitStats = old.commitStats.filterKeys { sha -> target.timeline?.commits?.any { it.sha == sha } == true } + stats)
          }
        }
      }.launchIn(viewModelScope)
  }

  /** A refreshed list keeps only diffs whose file is unchanged, then re-reads whichever of those the stream still wants. */
  private fun dropStaleDiffs(list: ScopeListUi.Listed) {
    val files = list.changes.files.associateBy { it.path }
    mutableState.update { old -> old.copy(fileDiffs = old.fileDiffs.filter { (path, loaded) -> files[path] == loaded.file }) }
    readWantedDiffs()
  }

  private fun resetStream() {
    streamRead?.cancel()
    streamRead = null
    wantedDiffs = emptyList()
    mutableState.update { it.copy(fileDiffs = emptyMap()) }
  }

  /** Reads the wanted diffs one at a time, re-checking the list after each so a newer request takes over. */
  private fun readWantedDiffs() {
    if (streamRead?.isActive == true) return
    streamRead = viewModelScope.launch {
      while (true) {
        val current = state.value
        val target = current.target ?: return@launch
        val file = nextWantedFile(current) ?: return@launch
        val scope = current.scope
        val result = scopes.fileDiff(target, scope, file)
        mutableState.update { old -> if (old.target == target && old.scope == scope) old.withLoadedDiff(file, result) else old }
      }
    }
  }

  private fun nextWantedFile(current: ChangesWindowUiState): ChangedFile? {
    val files = current.visibleFiles.associateBy { it.path }
    return wantedDiffs.firstNotNullOfOrNull { path -> files[path]?.takeIf { current.fileDiffs[path]?.file != it } }
  }

  /** Shows the selected file's diff from the stream when it is loaded, else reads it. */
  private fun showSelected() {
    val current = state.value
    val file = current.selectedFile
    val loaded = file?.let { current.fileDiffs[it.path] }?.takeIf { it.file == file }
    if (file == null || loaded == null) {
      loadDiff()
      return
    }
    invalidateDiffRead()
    val chips = projectChips(file, current.target, current.scope, current.stats)
    mutableState.update { it.copy(diff = DiffPaneUi.Shown(file, loaded.state, chips)) }
  }

  private fun invalidateDiffRead() {
    diffReadVersion++
    diffRead?.cancel()
    diffRead = null
  }

  private fun loadDiff() {
    invalidateDiffRead()
    val version = diffReadVersion
    val current = state.value
    val target = (current.content as? WindowContent.Ready)?.target ?: run {
      mutableState.update { it.copy(diff = DiffPaneUi.NoSelection) }
      return
    }
    val retained = when (val diff = current.diff) {
      is DiffPaneUi.Shown -> diff.file
      is DiffPaneUi.Loading -> diff.file
      DiffPaneUi.NoSelection -> null
    }?.takeIf { current.list !is ScopeListUi.Listed && it.path == current.selectedPath }
    val file = current.groups.flatMap { it.rows }.firstOrNull { it.file.path == current.selectedPath }?.file ?: retained ?: run {
      mutableState.update { it.copy(diff = DiffPaneUi.NoSelection) }
      return
    }
    val scope = current.scope
    val previous = current.diff
    if ((previous as? DiffPaneUi.Shown)?.file?.path != file.path) mutableState.update { it.copy(diff = DiffPaneUi.Loading(file)) }
    diffRead = viewModelScope.launch {
      val result = scopes.fileDiff(target, scope, file)
      val latest = state.value
      if (diffReadVersion != version || latest.content != WindowContent.Ready(target) ||
        latest.scope != scope || latest.selectedPath != file.path
      ) return@launch
      val chips = projectChips(file, (latest.content as? WindowContent.Ready)?.target, latest.scope, latest.stats)
      mutableState.update { old ->
        if (diffReadVersion == version && old.content == WindowContent.Ready(target) && old.scope == scope && old.selectedPath == file.path) {
          old.copy(diff = DiffPaneUi.Shown(file, result, chips)).withLoadedDiff(file, result)
        } else old
      }
    }
  }

  fun onAction(action: ChangesWindowAction) {
    when (action) {
      is ChangesWindowAction.SetFilter -> {
        val selectedBefore = state.value.selectedPath
        mutableState.update { old -> project(old, filter = action.text) }
        if (state.value.selectedPath != selectedBefore) loadDiff()
      }
      is ChangesWindowAction.SetView -> setView(action.view)
      is ChangesWindowAction.SelectFile -> {
        mutableState.update {
          it.copy(
            selectedPath = action.path,
            openProblem = if (it.selectedPath == action.path) it.openProblem else null,
            scrollRequest = it.scrollRequest + 1,
          )
        }
        showSelected()
      }
      is ChangesWindowAction.FollowScroll -> if (state.value.selectedPath != action.path) {
        mutableState.update { it.copy(selectedPath = action.path, openProblem = null) }
        showSelected()
      }
      is ChangesWindowAction.EnsureDiffs -> {
        wantedDiffs = action.paths
        readWantedDiffs()
      }
      is ChangesWindowAction.NarrowToSubtask -> selectSubtask(action.id)
      is ChangesWindowAction.PickSubtask -> pickSubtask(action)
      is ChangesWindowAction.DragSubtasks -> {
        val target = subtaskTarget() ?: return
        val range = dragRange(target, action.fromId, action.toId) ?: return
        mutableState.update { it.copy(anchor = action.fromId) }
        requestScope(range)
      }
      is ChangesWindowAction.PickCommit -> pickCommit(action)
      is ChangesWindowAction.DragCommits -> {
        val current = state.value
        val span = commitSpan(current.commits, action.fromSha, action.toSha)
        if (span.isNotEmpty()) {
          mutableState.update { it.copy(commitAnchor = action.fromSha) }
          requestScope(ChangeScope.Commits(span))
        }
      }
      is ChangesWindowAction.SelectQuickScope -> selectQuickScope(action.kind)
      is ChangesWindowAction.Step -> {
        val current = state.value
        val target = current.target ?: return
        if (current.view == ChangesView.Commits) {
          commitStepTarget(current.commits, current.scope, action.forward)?.let { requestScope(ChangeScope.Commits(listOf(it))) }
        } else stepTarget(target.subtasks, current.scope, action.forward)?.let(::selectSubtask)
      }
      ChangesWindowAction.TogglePicker -> if (state.value.pickerOpen) closePicker() else openPicker()
      ChangesWindowAction.OpenPicker -> openPicker()
      ChangesWindowAction.ClosePicker -> closePicker()
      is ChangesWindowAction.PickerQuery -> mutableState.update { it.copy(pickerQuery = action.text) }
      ChangesWindowAction.PickAll -> {
        closePicker()
        requestScope(ChangeScope.All)
      }
      is ChangesWindowAction.MoveFile -> moveFile(action.delta)
      ChangesWindowAction.OpenSelectedFile -> openSelectedFile()
      is ChangesWindowAction.ToggleThread -> mutableState.update { old ->
        val current = old.threadUi[action.threadId] ?: ThreadUi()
        val openByDefault = old.comments.thread(action.threadId)?.state.let { it == CommentState.Open || it == CommentState.Pending }
        old.copy(threadUi = old.threadUi + (action.threadId to current.copy(expanded = !(current.expanded ?: openByDefault))))
      }
      is ChangesWindowAction.EditReply -> mutableState.update { old ->
        val current = old.threadUi[action.threadId] ?: ThreadUi()
        old.copy(threadUi = old.threadUi + (action.threadId to current.copy(draft = action.text, problem = null)))
      }
      is ChangesWindowAction.SendReply -> sendReply(action.threadId)
      is ChangesWindowAction.SetThreadState -> updateThread(action.threadId) { comments.setState(action.threadId, action.state) }
      is ChangesWindowAction.DeleteComment -> updateThread(action.threadId) { comments.deleteComment(action.commentId) }
      else -> authoringActions.handle(action)
    }
  }

  private fun sendReply(threadId: String) {
    val rawDraft = state.value.threadUi[threadId]?.draft ?: return
    val body = rawDraft.trim()
    if (body.isBlank()) return
    updateThread(threadId, rawDraft) { comments.reply(threadId, body) }
  }

  private fun updateThread(threadId: String, submittedDraft: String? = null, operation: suspend () -> Outcome<Unit, CommentRejection>) {
    if (state.value.threadUi[threadId]?.busy == true) return
    mutableState.update { old ->
      val current = old.threadUi[threadId] ?: ThreadUi()
      old.copy(threadUi = old.threadUi + (threadId to current.copy(busy = true, problem = null)))
    }
    viewModelScope.launch {
      val result = operation()
      mutableState.update { old ->
        val current = old.threadUi[threadId] ?: ThreadUi()
        val updated = when (result) {
          is Outcome.Success -> current.copy(busy = false, draft = if (submittedDraft != null && current.draft == submittedDraft) null else current.draft, problem = null)
          is Outcome.Failure -> current.copy(busy = false, problem = result.error.reason)
        }
        old.copy(threadUi = old.threadUi + (threadId to updated))
      }
    }
  }

  /** The target when it offers subtask scopes; every subtask action is a no-op otherwise. */
  private fun subtaskTarget(): ChangeScopeTarget? = state.value.target?.takeIf { ScopeKind.Subtasks in availableScopes(it) }

  private fun selectSubtask(id: Int) {
    val target = subtaskTarget() ?: return
    val scope = normalizeRange(target, id, id) ?: return
    mutableState.update { it.copy(anchor = id) }
    requestScope(scope)
  }

  private fun setView(view: ChangesView) {
    val current = state.value
    val target = current.target?.takeIf { ScopeKind.Subtasks in availableScopes(it) && ScopeKind.Commits in availableScopes(it) } ?: return
    if (current.view == view) return
    val next = if (view == ChangesView.Commits) commitSelectionOf(target, current.scope) else subtaskSelectionOf(target, current.scope)
    mutableState.update { it.copy(view = view, pickerOpen = false) }
    requestScope(next)
  }

  private fun pickCommit(action: ChangesWindowAction.PickCommit) {
    val current = state.value
    if (current.commits.none { it.sha == action.sha }) return
    if (!action.extend) {
      if (action.fromPicker) closePicker()
      mutableState.update { it.copy(commitAnchor = action.sha) }
      requestScope(ChangeScope.Commits(listOf(action.sha)))
      return
    }
    val anchor = current.commitAnchor ?: action.sha
    val span = commitSpan(current.commits, anchor, action.sha)
    if (span.isNotEmpty()) {
      mutableState.update { it.copy(commitAnchor = anchor) }
      requestScope(ChangeScope.Commits(span))
    }
  }

  private fun pickSubtask(action: ChangesWindowAction.PickSubtask) {
    val target = subtaskTarget() ?: return
    if (action.id !in selectableSubtaskIds(target.subtasks)) return
    if (!action.extend) {
      if (action.fromPicker) closePicker()
      selectSubtask(action.id)
      return
    }
    val anchor = state.value.anchor ?: action.id
    val range = normalizeRange(target, anchor, action.id) ?: return
    mutableState.update { it.copy(anchor = anchor) }
    requestScope(range)
  }

  private fun selectQuickScope(kind: QuickScope) {
    if (state.value.quick.none { it.kind == kind && it.enabled }) return
    when (kind) {
      QuickScope.All -> requestScope(ChangeScope.All)
      QuickScope.Operator -> requestScope(ChangeScope.Operator)
      QuickScope.Uncommitted -> requestScope(ChangeScope.Uncommitted)
      QuickScope.Running -> state.value.subtasks.firstOrNull { it.state == ScopeSubtaskState.RUNNING }?.let { selectSubtask(it.id) }
    }
  }

  private fun openPicker() {
    if (state.value.target?.let { ScopeKind.Subtasks in availableScopes(it) } != true) return
    mutableState.update { it.copy(pickerOpen = true, pickerQuery = "") }
  }

  private fun closePicker() = mutableState.update { it.copy(pickerOpen = false) }

  /** Moves the selection through the visible files without wrapping. */
  private fun moveFile(delta: Int) {
    val visible = state.value.visibleFiles
    if (visible.isEmpty()) return
    val index = visible.indexOfFirst { it.path == state.value.selectedPath }
    val next = if (index < 0) 0 else (index + delta).coerceIn(0, visible.lastIndex)
    if (next != index) onAction(ChangesWindowAction.SelectFile(visible[next].path))
  }

  private fun openSelectedFile() {
    val current = state.value
    val file = current.selectedFile ?: return
    val target = current.target ?: return
    if (target.allBaseline is ChangeBaseline.CommittedRevisionRange) {
      mutableState.update { if (it.selectedPath == file.path) it.copy(openProblem = HISTORICAL_FILE_NOT_OPENED) else it }
      return
    }
    if (file.status == ChangeStatus.Deleted && !target.committedOnly) return
    viewModelScope.launch {
      if (changes.open(target.allBaseline, file) == OpenOutcome.NotOpened) {
        val message = if (target.committedOnly) REMOVED_WORKTREE_FILE_NOT_OPENED else FILE_NOT_OPENED
        mutableState.update { if (it.selectedPath == file.path) it.copy(openProblem = message) else it }
      }
    }
  }

  fun requestScope(scope: ChangeScope) {
    val target = (state.value.content as? WindowContent.Ready)?.target
    val mapped = if (state.value.view == ChangesView.Commits && target != null && (scope is ChangeScope.Subtasks || scope == ChangeScope.Operator)) commitSelectionOf(target, scope) else scope
    val effective = target?.let { effectiveScope(it, mapped) } ?: mapped
    if (requestedScope.value == effective && state.value.scope == effective) return
    invalidateDiffRead()
    resetStream()
    requestedScope.value = effective
    mutableState.update { old ->
      // A range set from outside the strip (the detail screen, a chip) starts a new anchor.
      val anchor = (effective as? ChangeScope.Subtasks)?.let { range -> old.anchor?.takeIf { it in range.from..range.to } ?: range.from } ?: old.anchor
      val commitAnchor = (effective as? ChangeScope.Commits)?.let { selected -> old.commitAnchor?.takeIf { it in selected.shas } ?: selected.shas.firstOrNull() } ?: old.commitAnchor
      project(old.copy(anchor = anchor, commitAnchor = commitAnchor), list = if (old.scope == effective) old.list else ScopeListUi.Loading, scope = effective)
    }
    if (target != null) setTarget(target)
  }
}

private fun ChangesWindowUiState.withLoadedDiff(file: ChangedFile, result: FileDiffState): ChangesWindowUiState {
  val diff = (result as? FileDiffState.Shown)?.diff
  val loaded = LoadedDiff(file, result, diff?.takeIf { it.hunks.isNotEmpty() }?.let(::diffRowCount) ?: 1, diff?.let(::diffLongestChars) ?: 0)
  return copy(fileDiffs = fileDiffs + (file.path to loaded))
}

private fun DiffCommentsState.thread(id: String) = (this as? DiffCommentsState.Available)?.threads?.firstOrNull { it.id == id }

private data class GoalObservationKey(val id: String, val running: Boolean, val goalWorktree: dev.skillbill.desktop.core.domain.model.GoalWorktree?)

internal const val FILE_NOT_OPENED = "Could not open the file. Check that it still exists."
internal const val REMOVED_WORKTREE_FILE_NOT_OPENED = "The goal's worktree was removed and the main checkout is not on its branch, so this file cannot be opened."
private const val HISTORICAL_FILE_NOT_OPENED = "This file belongs to a historical revision and is not opened from the current checkout."

private data class QuickKey(val target: ChangeScopeTarget, val running: Boolean)

private data class AllCountKey(val target: ChangeScopeTarget, val running: Boolean)

private data class QuickReads(val key: QuickKey, val uncommitted: ScopeChangesState?, val operator: ScopeChangesState?) {
  operator fun get(scope: ChangeScope): ScopeChangesState? = if (scope == ChangeScope.Operator) operator else uncommitted
}

private fun ScopeChangesState.settledOver(previous: ScopeChangesState?): ScopeChangesState =
  if (this == ScopeChangesState.Loading && previous != null) previous else this

private data class ScopeReadKey(val target: ChangeScopeTarget, val scope: ChangeScope, val running: Boolean)

private fun ScopeChangesState.toUi(): ScopeListUi = when (this) {
  ScopeChangesState.Loading -> ScopeListUi.Loading
  is ScopeChangesState.Listed -> ScopeListUi.Listed(changes)
  is ScopeChangesState.BaseUnavailable -> ScopeListUi.BaseUnavailable(reason)
  is ScopeChangesState.NotARepository -> ScopeListUi.NotARepository(worktreeRoot)
  is ScopeChangesState.TooManyCommits -> ScopeListUi.TooManyCommits(limit)
  is ScopeChangesState.Unavailable -> ScopeListUi.Unavailable(problem)
}
