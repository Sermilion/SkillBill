package dev.skillbill.desktop.feature.changes

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.skillbill.desktop.core.domain.model.ChangeScope
import dev.skillbill.desktop.core.domain.model.ChangeScopeTarget
import dev.skillbill.desktop.core.domain.model.ChangedFile
import dev.skillbill.desktop.core.domain.model.Detail
import dev.skillbill.desktop.core.domain.model.DetailSections
import dev.skillbill.desktop.core.domain.model.DetailState
import dev.skillbill.desktop.core.domain.model.ScopeChangesState
import dev.skillbill.desktop.core.domain.model.ScopeSubtask
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
import dev.skillbill.desktop.core.domain.repository.WorkDetailRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
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
) : ViewModel() {
  private val requestedScope = MutableStateFlow(initialScope)
  private val mutableState = MutableStateFlow(ChangesWindowUiState(item, scope = initialScope))
  val state: StateFlow<ChangesWindowUiState> = mutableState.asStateFlow()
  private var currentLoadedDetail: DetailState? = null
  private var observedGoal: GoalObservationKey? = null
  private var notGoalKey: GoalObservationKey? = null
  private var goalObservation: Job? = null
  private var goalObservationVersion = 0
  private var diffRead: Job? = null
  private var diffReadVersion = 0

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
        ChangeScope.All -> true
        is ChangeScope.Subtasks -> id in scope.from..scope.to
        ChangeScope.Outside, ChangeScope.Uncommitted -> false
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
      )
    }
  }

  private fun observeScopeReads() {
    state.map { ui ->
      val target = (ui.content as? WindowContent.Ready)?.target
      target?.let { ScopeReadKey(it, ui.scope, ui.item.running) }
    }.distinctUntilChanged().flatMapLatest { key ->
      if (key == null) emptyFlow() else scopes.observeScope(key.target, key.scope, key.running).map { key to it }
    }.onEach { (key, result) ->
      val current = state.value
      if (current.content != WindowContent.Ready(key.target) || current.scope != key.scope || current.item.running != key.running) return@onEach
      val list = result.toUi()
      mutableState.update { old ->
        if (list == ScopeListUi.Loading && old.list != ScopeListUi.Loading) old
        else project(old, list = list)
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
        mutableState.update { it.copy(selectedPath = action.path) }
        loadDiff()
      }
      is ChangesWindowAction.NarrowToSubtask -> {
        val target = (state.value.content as? WindowContent.Ready)?.target ?: return
        normalizeRange(target, action.id, action.id)?.let(::requestScope)
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
      project(old, list = if (old.scope == effective) old.list else ScopeListUi.Loading, scope = effective)
    }
    if (target != null) setTarget(target)
  }
}

private data class GoalObservationKey(val id: String, val running: Boolean)

private data class ScopeReadKey(val target: ChangeScopeTarget, val scope: ChangeScope, val running: Boolean)

private fun ScopeChangesState.toUi(): ScopeListUi = when (this) {
  ScopeChangesState.Loading -> ScopeListUi.Loading
  is ScopeChangesState.Listed -> ScopeListUi.Listed(changes)
  is ScopeChangesState.BaseUnavailable -> ScopeListUi.BaseUnavailable(reason)
  is ScopeChangesState.NotARepository -> ScopeListUi.NotARepository(worktreeRoot)
  is ScopeChangesState.TooManyCommits -> ScopeListUi.TooManyCommits(limit)
  is ScopeChangesState.Unavailable -> ScopeListUi.Unavailable(problem)
}
