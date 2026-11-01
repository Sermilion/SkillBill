package dev.skillbill.desktop.feature.changes

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.skillbill.desktop.core.domain.model.ChangeScope
import dev.skillbill.desktop.core.domain.model.ChangeScopeTarget
import dev.skillbill.desktop.core.domain.model.ChangeStatus
import dev.skillbill.desktop.core.domain.model.ChangedFile
import dev.skillbill.desktop.core.domain.model.Detail
import dev.skillbill.desktop.core.domain.model.DetailSections
import dev.skillbill.desktop.core.domain.model.DetailState
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
import dev.skillbill.desktop.core.domain.model.subtasksTouching
import dev.skillbill.desktop.core.domain.repository.WorkChangeScopesRepository
import dev.skillbill.desktop.core.domain.repository.WorkChangesRepository
import dev.skillbill.desktop.core.domain.repository.WorkDetailRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.filterNotNull
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
) : ViewModel() {
  private val requestedScope = MutableStateFlow(initialScope)
  private val mutableState = MutableStateFlow(
    ChangesWindowUiState(item, scope = initialScope, anchor = (initialScope as? ChangeScope.Subtasks)?.from),
  )
  val state: StateFlow<ChangesWindowUiState> = mutableState.asStateFlow()
  private var currentLoadedDetail: DetailState? = null
  private var observedGoal: GoalObservationKey? = null
  private var notGoalKey: GoalObservationKey? = null
  private var goalObservation: Job? = null
  private var goalObservationVersion = 0
  private var diffRead: Job? = null
  private var diffReadVersion = 0
  private val quickReads = MutableStateFlow<QuickReads?>(null)

  init {
    detail.observe(item).onEach { observed ->
      val runningChanged = mutableState.value.item.running != observed.header.running
      if (runningChanged) invalidateDiffRead()
      mutableState.update { it.copy(item = observed.header) }
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
            val key = GoalObservationKey(observed.header.id, observed.header.running)
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
      current.header.kind == WorkKind.Goal && current.header.id == key.id && current.header.running == key.running
  }

  private fun DetailState.detailOrNull(): Detail? = (sections as? DetailSections.Loaded)?.detail

  private fun setExecutionTarget(detailState: DetailState) {
    val target = detailState.detailOrNull()?.changeBaseline?.let { executionScopeTarget(detailState.header.id, it) }
    if (target == null) clearReads(WindowContent.NoBaseline) else setTarget(target)
  }

  private fun setTarget(
    target: ChangeScopeTarget,
    subtasks: List<ScopeSubtask>? = null,
    stats: Map<Int, SubtaskStats>? = null,
  ) {
    val effective = effectiveScope(target, requestedScope.value)
    requestedScope.value = effective
    val current = mutableState.value
    if (current.content == WindowContent.Ready(target) && current.scope == effective) {
      if (subtasks != null && stats != null) {
        mutableState.update { old -> project(old.copy(subtasks = subtasks, stats = stats)) }
      }
      return
    }
    invalidateDiffRead()
    mutableState.update { old ->
      project(
        old.copy(
          content = WindowContent.Ready(target),
          scope = effective,
          subtasks = subtasks ?: old.subtasks,
          stats = stats ?: old.stats,
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
      groupFiles(files, filter) { path -> scopeTouchCount(path, scope, stats) }
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
        is ChangeScope.Subtasks -> id in scope.from..scope.to
        ChangeScope.All, ChangeScope.Outside, ChangeScope.Uncommitted -> false
      }
      SubtaskChipUi(id, subtask.name, inScope, normalizeRange(target, id, id) != null)
    }
  }

  private fun effectiveScope(target: ChangeScopeTarget, requested: ChangeScope): ChangeScope {
    if (requested.kind !in availableScopes(target)) return ChangeScope.All
    if (requested is ChangeScope.Subtasks) return normalizeRange(target, requested.from, requested.to) ?: ChangeScope.All
    return requested
  }

  private fun clearReads(content: WindowContent) {
    invalidateDiffRead()
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
   * Uncommitted, and Outside when the target offers it, stay observed for the quick buttons' counts. The shown list reuses these
   * reads when the scope is one of them, so the same scope is never polled twice.
   */
  private fun observeQuickReads() {
    state.map { ui -> ui.target?.let { QuickKey(it, ui.item.running) } }.distinctUntilChanged().flatMapLatest { key ->
      if (key == null) return@flatMapLatest flowOf<QuickReads?>(null)
      val uncommitted = scopes.observeScope(key.target, ChangeScope.Uncommitted, key.running)
      val outside = if (ScopeKind.Outside in availableScopes(key.target)) {
        scopes.observeScope(key.target, ChangeScope.Outside, key.running)
      } else {
        flowOf(null)
      }
      combine(uncommitted, outside) { u, o -> QuickReads(key, u, o) }
    }.onEach { reads ->
      val previous = quickReads.value?.takeIf { it.key == reads?.key }
      // A re-read of the same target keeps the last settled list until the new one lands, like the shown list.
      val settled = reads?.copy(
        uncommitted = reads.uncommitted.settledOver(previous?.uncommitted),
        outside = reads.outside?.settledOver(previous?.outside),
      )
      quickReads.value = settled
      mutableState.update { it.copy(uncommittedRead = settled?.uncommitted, outsideRead = settled?.outside) }
    }.launchIn(viewModelScope)
  }

  /** A panel opened on another scope still shows All's count: one read, not polled, until All is viewed. */
  private fun observeAllCount() {
    state.map { ui -> ui.target?.takeIf { ui.allRead == null && ui.scope != ChangeScope.All } }.filterNotNull().distinctUntilChanged().flatMapLatest { target ->
      scopes.observeScope(target, ChangeScope.All, running = false)
    }.onEach { read ->
      if (read != ScopeChangesState.Loading) mutableState.update { if (it.allRead == null) it.copy(allRead = read.toUi()) else it }
    }.launchIn(viewModelScope)
  }

  private fun observeScopeReads() {
    state.map { ui ->
      val target = (ui.content as? WindowContent.Ready)?.target
      target?.let { ScopeReadKey(it, ui.scope, ui.item.running) }
    }.distinctUntilChanged().flatMapLatest { key ->
      when {
        key == null -> emptyFlow()
        key.scope == ChangeScope.Uncommitted || key.scope == ChangeScope.Outside -> quickReads
          .map { reads -> reads?.takeIf { it.key == QuickKey(key.target, key.running) }?.get(key.scope) ?: ScopeChangesState.Loading }
          .distinctUntilChanged()
          .map { key to it }
        else -> scopes.observeScope(key.target, key.scope, key.running).map { key to it }
      }
    }.onEach { (key, result) ->
      val current = state.value
      if (current.content != WindowContent.Ready(key.target) || current.scope != key.scope || current.item.running != key.running) return@onEach
      val list = result.toUi()
      mutableState.update { old ->
        val read = if (key.scope == ChangeScope.All && list != ScopeListUi.Loading) old.copy(allRead = list) else old
        if (list == ScopeListUi.Loading && old.list != ScopeListUi.Loading) read
        else project(read, list = list)
      }
      if (list != ScopeListUi.Loading) loadDiff()
    }.launchIn(viewModelScope)
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
          old.copy(diff = DiffPaneUi.Shown(file, result, chips))
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
      is ChangesWindowAction.SelectFile -> {
        mutableState.update { it.copy(selectedPath = action.path, openProblem = if (it.selectedPath == action.path) it.openProblem else null) }
        loadDiff()
      }
      is ChangesWindowAction.NarrowToSubtask -> selectSubtask(action.id)
      is ChangesWindowAction.PickSubtask -> pickSubtask(action)
      is ChangesWindowAction.DragSubtasks -> {
        val target = subtaskTarget() ?: return
        val range = dragRange(target, action.fromId, action.toId) ?: return
        mutableState.update { it.copy(anchor = action.fromId) }
        requestScope(range)
      }
      is ChangesWindowAction.SelectQuickScope -> selectQuickScope(action.kind)
      is ChangesWindowAction.Step -> {
        val target = subtaskTarget() ?: return
        stepTarget(target.subtasks, state.value.scope, action.forward)?.let(::selectSubtask)
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

  private fun pickSubtask(action: ChangesWindowAction.PickSubtask) {
    val target = subtaskTarget() ?: return
    if (target.subtasks.none { it.id == action.id && it.selectable }) return
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
      QuickScope.Outside -> requestScope(ChangeScope.Outside)
      QuickScope.Uncommitted -> requestScope(ChangeScope.Uncommitted)
      QuickScope.Running -> state.value.subtasks.firstOrNull { it.state == ScopeSubtaskState.RUNNING }?.let { selectSubtask(it.id) }
    }
  }

  private fun openPicker() {
    if (subtaskTarget() == null) return
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
    if (file.status == ChangeStatus.Deleted) return
    val target = current.target ?: return
    viewModelScope.launch {
      if (changes.open(target.worktreeRoot, file) == OpenOutcome.NotOpened) {
        mutableState.update { if (it.selectedPath == file.path) it.copy(openProblem = FILE_NOT_OPENED) else it }
      }
    }
  }

  fun requestScope(scope: ChangeScope) {
    val target = (state.value.content as? WindowContent.Ready)?.target
    val effective = target?.let { effectiveScope(it, scope) } ?: scope
    if (requestedScope.value == effective && state.value.scope == effective) return
    invalidateDiffRead()
    requestedScope.value = effective
    mutableState.update { old ->
      // A range set from outside the strip (the detail screen, a chip) starts a new anchor.
      val anchor = (effective as? ChangeScope.Subtasks)?.let { range -> old.anchor?.takeIf { it in range.from..range.to } ?: range.from } ?: old.anchor
      project(old.copy(anchor = anchor), list = if (old.scope == effective) old.list else ScopeListUi.Loading, scope = effective)
    }
    if (target != null) setTarget(target)
  }
}

private data class GoalObservationKey(val id: String, val running: Boolean)

internal const val FILE_NOT_OPENED = "Could not open the file. Check that it still exists."

private data class QuickKey(val target: ChangeScopeTarget, val running: Boolean)

private data class QuickReads(val key: QuickKey, val uncommitted: ScopeChangesState, val outside: ScopeChangesState?) {
  operator fun get(scope: ChangeScope): ScopeChangesState? = if (scope == ChangeScope.Outside) outside else uncommitted
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
