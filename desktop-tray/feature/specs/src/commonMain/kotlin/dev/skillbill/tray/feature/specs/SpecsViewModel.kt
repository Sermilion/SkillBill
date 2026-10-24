package dev.skillbill.tray.feature.specs

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.skillbill.tray.core.domain.model.AppSettings
import dev.skillbill.tray.core.domain.model.SettingsState
import dev.skillbill.tray.core.domain.model.SpecNode
import dev.skillbill.tray.core.domain.model.SpecProject
import dev.skillbill.tray.core.domain.model.WorkIdentity
import dev.skillbill.tray.core.domain.repository.Outcome
import dev.skillbill.tray.core.domain.repository.SettingsRepository
import dev.skillbill.tray.core.domain.repository.SpecBrowserRepository
import dev.skillbill.tray.core.domain.repository.WorkDetailRepository
import dev.skillbill.tray.core.ui.NavigationEffects
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Owns the spec pane for one main-window entry. The pane follows the sidebar's selected work item until the user picks a project,
 * and that pick then holds. Selection here is pane-local: this class has no navigator and no effect that opens a detail.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SpecsViewModel(
  private val specs: SpecBrowserRepository,
  private val details: WorkDetailRepository,
  private val settings: SettingsRepository,
) : ViewModel() {
  private val local = MutableStateFlow(Local())
  private val appSettings = MutableStateFlow(AppSettings())
  private val outbox = NavigationEffects<SpecsEffect>()

  private var selectedWork: WorkIdentity? = null
  private var explicitPick = false
  private var following: Job? = null

  val state: StateFlow<SpecsUiState> = combine(local, appSettings, ::assemble)
    .stateIn(viewModelScope, SharingStarted.Eagerly, SpecsUiState())

  val effects: Flow<SpecsEffect> = outbox.flow

  init {
    viewModelScope.launch { settings.settings.collect { if (it is SettingsState.Ready) appSettings.value = it.settings } }
    viewModelScope.launch {
      var previous: Subscription? = null
      local.map { Subscription(it.project?.projectId, it.followedRoot ?: it.project?.repoRoot, it.retries) }
        .distinctUntilChanged()
        .flatMapLatest { subscription ->
          // Only the preferred root moved: keep the shown tree until the new root answers instead of flashing Loading.
          val rootOnly = previous?.let { it.projectId == subscription.projectId && it.retries == subscription.retries } == true
          previous = subscription
          observe(subscription, announceLoading = !rootOnly)
        }
        .collect { snapshot -> local.update { it.copy(snapshot = snapshot) } }
    }
    follow()
  }

  fun onAction(action: SpecsAction) {
    when (action) {
      is SpecsAction.SelectedWorkChanged -> {
        selectedWork = action.identity
        follow()
      }
      is SpecsAction.PickProject -> pick(action.projectId)
      is SpecsAction.FilterChanged -> local.update {
        it.copy(filterText = action.text, shownWhileFiltering = if (action.text == it.filterText) it.shownWhileFiltering else emptyMap())
      }
      is SpecsAction.ChooseStatus -> local.update {
        it.copy(statusFilter = action.filter, shownWhileFiltering = if (action.filter == it.statusFilter) it.shownWhileFiltering else emptyMap())
      }
      SpecsAction.ClearFilters -> local.update {
        it.copy(filterText = "", statusFilter = SpecStatusFilter.ALL, shownWhileFiltering = emptyMap())
      }
      is SpecsAction.Expand -> setExpanded(action.path, expand = true)
      is SpecsAction.Collapse -> setExpanded(action.path, expand = false)
      is SpecsAction.Select -> local.update { it.copy(selectedPath = action.path) }
      is SpecsAction.Activate -> activate(action.path)
      SpecsAction.CollapseAll -> collapseAll()
      is SpecsAction.FileOpenFailed -> local.update { it.copy(openErrors = it.openErrors + (action.absolutePath to action.message)) }
      SpecsAction.Retry -> retry()
      SpecsAction.Reveal -> local.value.project?.specsRoot?.takeIf { it.isNotBlank() }?.let { outbox.send(SpecsEffect.RevealDirectory(it)) }
      SpecsAction.TogglePane -> viewModelScope.launch { settings.update { it.copy(specPaneOpen = !it.specPaneOpen) } }
      SpecsAction.ClosePane -> viewModelScope.launch { settings.update { it.copy(specPaneOpen = false) } }
    }
  }

  /** Re-subscribes to the snapshot; with no project yet, the failure was the project list, so that is read again. */
  private fun retry() {
    local.update { it.copy(retries = it.retries + 1) }
    if (local.value.project == null) follow()
  }

  private suspend fun loadProjects() {
    when (val result = specs.projects()) {
      is Outcome.Value -> local.update {
        it.copy(projects = result.value, projectsLoaded = true, snapshot = if (it.project == null) SpecSnapshotState.Loading else it.snapshot)
      }
      is Outcome.Failure -> local.update {
        it.copy(projectsLoaded = true, snapshot = if (it.project == null) SpecSnapshotState.Failed(result.problem.summary) else it.snapshot)
      }
    }
  }

  /**
   * Re-resolves the project from the selected work item, unless the user has picked one. Only the latest resolution counts. The
   * project list is read first time, and again when it is empty or lacks the resolved project, so a project created later in the
   * session reaches the picker without polling.
   */
  private fun follow() {
    if (explicitPick) return
    following?.cancel()
    following = viewModelScope.launch {
      val identity = selectedWork
      val found = identity?.let { resolve(it) }
      val known = local.value.projects
      if (!local.value.projectsLoaded || known.isEmpty() || (found != null && known.none { it.projectId == found.first.projectId })) {
        loadProjects()
      }
      if (found != null) {
        choose(found.first, found.second)
      } else if (identity == null || local.value.project == null) {
        local.value.projects.firstOrNull()?.let { choose(it, null) }
      }
    }
  }

  private suspend fun resolve(identity: WorkIdentity): Pair<SpecProject, String>? {
    val detail = details.detail(identity) as? Outcome.Value ?: return null
    val root = detail.value.item.repositoryRoot
    val project = specs.projectFor(root) as? Outcome.Value ?: return null
    return project.value to root
  }

  private fun pick(projectId: String) {
    val project = local.value.projects.firstOrNull { it.projectId == projectId } ?: return
    explicitPick = true
    following?.cancel()
    choose(project, null)
  }

  /** A different project clears the selection and open errors and starts from Loading; the filters stay. */
  private fun choose(project: SpecProject, followedRoot: String?) {
    local.update {
      if (it.project?.projectId == project.projectId) {
        it.copy(project = project, followedRoot = followedRoot)
      } else {
        it.copy(
          project = project,
          followedRoot = followedRoot,
          snapshot = SpecSnapshotState.Loading,
          selectedPath = null,
          openErrors = emptyMap(),
          shownWhileFiltering = emptyMap(),
        )
      }
    }
  }

  private fun observe(subscription: Subscription, announceLoading: Boolean): Flow<SpecSnapshotState> {
    val projectId = subscription.projectId ?: return emptyFlow()
    return flow {
      if (announceLoading) emit(SpecSnapshotState.Loading)
      specs.observeSnapshot(projectId, subscription.preferredRoot).collect { result ->
        emit(
          when (result) {
            is Outcome.Value -> SpecSnapshotState.Loaded(result.value)
            is Outcome.Failure -> SpecSnapshotState.Failed(result.problem.summary)
          },
        )
      }
    }
  }

  /** Writes only the saved expansion, never what a filter shows. A failed write leaves the old state in place. */
  private fun writeExpansion(change: (Set<String>) -> Set<String>) {
    val projectId = local.value.project?.projectId ?: return
    viewModelScope.launch {
      settings.update { it.copy(specExpandedPaths = it.specExpandedPaths + (projectId to change(it.specExpandedPaths[projectId].orEmpty()))) }
    }
  }

  private fun activate(path: String) {
    val node = state.value.rows.firstOrNull { it.node.path == path }?.node ?: return
    local.update { it.copy(selectedPath = path) }
    if (node is SpecNode.File) {
      local.update { it.copy(openErrors = it.openErrors - node.absolutePath) }
      outbox.send(SpecsEffect.OpenFile(node.absolutePath))
    } else {
      setExpanded(path, expand = null)
    }
  }

  /**
   * Expands, collapses or (with a null [expand]) toggles what the row shows. With no filter that is the saved set, changed inside
   * the settings update so rapid clicks stay atomic. While a filter is active the row shows filter-made expansion, so the change is
   * display-only in [Local.shownWhileFiltering], read and written in one update, and the saved set is not touched.
   */
  private fun setExpanded(path: String, expand: Boolean?) {
    if (!local.value.filtersActive) {
      writeExpansion { if (expand ?: (path !in it)) it + path else it - path }
      return
    }
    local.update { l ->
      val current = rowsFor(l, appSettings.value).firstOrNull { it.node.path == path }?.expanded ?: return@update l
      l.copy(shownWhileFiltering = l.shownWhileFiltering + (path to (expand ?: !current)))
    }
  }

  /** Writes the empty saved set; while a filter is active it also collapses every container for display, hidden ones included. */
  private fun collapseAll() {
    writeExpansion { emptySet() }
    local.update { l ->
      val roots = loadedRoots(l)
      if (!l.filtersActive || roots == null) l else l.copy(shownWhileFiltering = roots.flatMap(::containerPaths).associateWith { false })
    }
  }

  private fun containerPaths(node: SpecNode): List<String> {
    val children = when (node) {
      is SpecNode.Folder -> node.children
      is SpecNode.Bundle -> node.children
      is SpecNode.File -> return emptyList()
    }
    return (if (children.isEmpty()) emptyList() else listOf(node.path)) + children.flatMap(::containerPaths)
  }

  private fun loadedRoots(l: Local): List<SpecNode>? = (l.snapshot as? SpecSnapshotState.Loaded)?.snapshot?.roots

  private fun persistedFor(l: Local, app: AppSettings): Set<String> = l.project?.let { app.specExpandedPaths[it.projectId] }.orEmpty()

  private fun rowsFor(l: Local, app: AppSettings): List<SpecRow> =
    loadedRoots(l)?.let { visibleRows(it, persistedFor(l, app), l.filterText, l.statusFilter, l.shownWhileFiltering) }.orEmpty()

  private fun assemble(l: Local, app: AppSettings): SpecsUiState {
    val loaded = (l.snapshot as? SpecSnapshotState.Loaded)?.snapshot
    val expanded = persistedFor(l, app)
    return SpecsUiState(
      projects = l.projects,
      project = l.project,
      projectsLoaded = l.projectsLoaded,
      snapshot = l.snapshot,
      filterText = l.filterText,
      statusFilter = l.statusFilter,
      expandedPaths = expanded,
      rows = rowsFor(l, app),
      selectedPath = l.selectedPath,
      openErrors = l.openErrors,
      paneOpen = app.specPaneOpen,
      anyInProgress = loaded?.anyInProgress == true,
    )
  }

  private data class Subscription(val projectId: String?, val preferredRoot: String?, val retries: Int)

  private data class Local(
    val projects: List<SpecProject> = emptyList(),
    val projectsLoaded: Boolean = false,
    val project: SpecProject? = null,
    /** The followed work item's repository root while the project came from it; null for a picked or default project. */
    val followedRoot: String? = null,
    val snapshot: SpecSnapshotState = SpecSnapshotState.Loading,
    val filterText: String = "",
    val statusFilter: SpecStatusFilter = SpecStatusFilter.ALL,
    /** Expansion the user set while the current filter is active; never saved, cleared when the filter or project changes. */
    val shownWhileFiltering: Map<String, Boolean> = emptyMap(),
    val selectedPath: String? = null,
    val openErrors: Map<String, String> = emptyMap(),
    val retries: Int = 0,
  ) {
    val filtersActive: Boolean get() = filtersActive(filterText, statusFilter)
  }
}
