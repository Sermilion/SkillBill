package dev.skillbill.tray.feature.specs

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.skillbill.tray.core.domain.model.AppSettings
import dev.skillbill.tray.core.domain.model.SettingsState
import dev.skillbill.tray.core.domain.model.SpecBundleControl
import dev.skillbill.tray.core.domain.model.SpecNode
import dev.skillbill.tray.core.domain.model.SpecProject
import dev.skillbill.tray.core.domain.model.SpecRunState
import dev.skillbill.tray.core.domain.model.WorkIdentity
import dev.skillbill.tray.core.domain.model.WorkItem
import dev.skillbill.tray.core.domain.repository.Outcome
import dev.skillbill.tray.core.domain.repository.SettingsRepository
import dev.skillbill.tray.core.domain.repository.SpecBrowserRepository
import dev.skillbill.tray.core.domain.repository.WorkControlRepository
import dev.skillbill.tray.core.domain.repository.WorkDetailRepository
import dev.skillbill.tray.core.ui.Feedback
import dev.skillbill.tray.core.ui.FeedbackIds
import dev.skillbill.tray.core.ui.FeedbackTone
import dev.skillbill.tray.core.ui.LaunchRequests
import dev.skillbill.tray.core.ui.NavigationEffects
import dev.skillbill.tray.core.ui.acknowledged
import dev.skillbill.tray.core.ui.withFeedback
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
  private val controls: WorkControlRepository,
  private val settings: SettingsRepository,
  private val launches: LaunchRequests,
) : ViewModel() {
  private val local = MutableStateFlow(Local())
  private val appSettings = MutableStateFlow(AppSettings())
  private val outbox = NavigationEffects<SpecsEffect>()
  private val feedbackIds = FeedbackIds()

  private var selectedWork: WorkIdentity? = null
  private var explicitPick = false
  private var following: Job? = null
  private var controlJob: Job? = null
  private var actionJob: Job? = null

  val state: StateFlow<SpecsUiState> = combine(local, appSettings, launches.pending) { local, settings, pending ->
    assemble(local, settings).let { state ->
      val work = (state.toolbar.control as? ControlLoad.Ready)?.control?.work
      state.copy(toolbar = state.toolbar.copy(inFlight = state.toolbar.inFlight || work in pending))
    }
  }
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
        .collect { snapshot ->
          local.update { it.copy(snapshot = snapshot) }
          if (snapshot is SpecSnapshotState.Loaded) loadControl()
        }
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
      is SpecsAction.Select -> select(action.path)
      is SpecsAction.Activate -> activate(action.path)
      is SpecsAction.Click -> click(action.path)
      is SpecsAction.Toggle -> setExpanded(action.path, expand = null)
      SpecsAction.CollapseAll -> collapseAll()
      is SpecsAction.FileOpenFailed -> addFeedback(action.message)
      is SpecsAction.AcknowledgeFeedback -> local.update { it.copy(feedback = it.feedback.acknowledged(action.id)) }
      SpecsAction.Retry -> retry()
      SpecsAction.Reveal -> local.value.project?.specsRoot?.takeIf { it.isNotBlank() }?.let { outbox.send(SpecsEffect.RevealDirectory(it)) }
      SpecsAction.TogglePane -> viewModelScope.launch { settings.update { it.copy(specPaneOpen = !it.specPaneOpen) } }
      SpecsAction.ClosePane -> viewModelScope.launch { settings.update { it.copy(specPaneOpen = false) } }
      SpecsAction.OpenAgentMenu -> setAgentMenu(open = true)
      SpecsAction.DismissAgentMenu -> setAgentMenu(open = false)
      is SpecsAction.ChooseAgent -> chooseAgent(action.agentId)
      SpecsAction.Start -> start()
      SpecsAction.Pause -> pause()
      SpecsAction.RequestCancel -> requestCancel()
      SpecsAction.KeepRunning -> updateToolbar { it.copy(confirmingCancel = false) }
      SpecsAction.ConfirmCancel -> confirmCancel()
      SpecsAction.RequestPurge -> requestPurge()
      SpecsAction.KeepRunHistory -> updateToolbar { it.copy(purgeDialogOpen = false) }
      SpecsAction.ConfirmPurge -> confirmPurge()
    }
  }

  private fun addFeedback(message: String) {
    val feedback = Feedback(feedbackIds.next(), FeedbackTone.PROBLEM, message)
    local.update { it.copy(feedback = it.feedback.withFeedback(feedback)) }
  }

  private fun updateToolbar(change: (SpecToolbarState) -> SpecToolbarState) = local.update { it.copy(toolbar = change(it.toolbar)) }

  private fun readyControl(): SpecBundleControl? = (local.value.toolbar.control as? ControlLoad.Ready)?.control

  private fun setAgentMenu(open: Boolean) {
    if (!local.value.toolbar.inFlight) updateToolbar { it.copy(agentMenuOpen = open) }
  }

  private fun chooseAgent(agentId: String) {
    if (local.value.toolbar.inFlight) return
    local.update { it.copy(toolbar = it.toolbar.copy(chosenAgent = agentId, agentMenuOpen = false), agentPicked = true) }
  }

  /** Never-launched specs start through the spec port; a paused, blocked or canceled run resumes through its work item. */
  private fun start() {
    val control = readyControl()?.takeIf { it.canStart } ?: return
    val toolbar = local.value.toolbar
    if (toolbar.inFlight) return
    val agent = toolbar.chosenAgent
    if (agent == null) {
      updateToolbar { it.copy(agentMenuOpen = true) }
      return
    }
    when (control.runState) {
      SpecRunState.NOT_STARTED -> act { c ->
        val repoRoot = repoRootOf(local.value)
        val key = c.issueKey
        if (repoRoot == null || key == null) {
          startFailure(c.unavailableReason ?: NO_CHECKOUT)
        } else {
          specs.startGoal(repoRoot, key, agent).failureSummary()?.let(::startFailure)
        }
      }
      SpecRunState.PAUSED, SpecRunState.BLOCKED, SpecRunState.CANCELED -> act { c ->
        withItem(c, ::startFailure) { item ->
          when (val result = launches.launch(item, agent)?.await()) {
            null -> Outcome.Value(Unit)
            is Outcome.Failure -> result
            is Outcome.Value -> {
              if (result.value == null) addFeedback("The harness started but has not registered yet. Check its output before trying again.")
              Outcome.Value(Unit)
            }
          }
        }
      }
      else -> Unit
    }
  }

  private fun pause() {
    if (readyControl()?.canPause != true) return
    act { c -> withItem(c) { controls.pause(it) } }
  }

  private fun requestCancel() {
    if (readyControl()?.canCancel == true && !local.value.toolbar.inFlight) updateToolbar { it.copy(confirmingCancel = true) }
  }

  private fun confirmCancel() {
    if (!local.value.toolbar.confirmingCancel) return
    if (readyControl()?.canCancel != true) {
      updateToolbar { it.copy(confirmingCancel = false) }
      return
    }
    act { c -> withItem(c) { controls.cancel(it) } }
  }

  private fun requestPurge() {
    val control = readyControl() ?: return
    if (control.purgeVisible && control.canPurge && !local.value.toolbar.inFlight) updateToolbar { it.copy(purgeDialogOpen = true) }
  }

  private fun confirmPurge() {
    if (!local.value.toolbar.purgeDialogOpen) return
    updateToolbar { it.copy(purgeDialogOpen = false) }
    if (readyControl()?.let { it.purgeVisible && it.canPurge } != true) return
    act { c ->
      val repoRoot = repoRootOf(local.value)
      val key = c.issueKey
      if (repoRoot == null || key == null) {
        purgeFailure(c.unavailableReason ?: NO_CHECKOUT)
      } else {
        specs.purgeGoal(repoRoot, key).failureSummary()?.let(::purgeFailure)
      }
    }
  }

  /**
   * The one in-flight guard: a no-op while a request runs or no control is ready. [block] returns a notice, or null. The
   * message is dropped when the selection moved meanwhile; otherwise the control is read again so the buttons follow the runtime.
   */
  private fun act(block: suspend (SpecBundleControl) -> String?) {
    val control = readyControl() ?: return
    if (local.value.toolbar.inFlight || actionJob?.isActive == true || control.work in launches.pending.value) return
    val origin = selectionKey(local.value)
    updateToolbar { it.copy(inFlight = true, confirmingCancel = false) }
    actionJob = viewModelScope.launch {
      try {
        val message = block(control)
        if (selectionKey(local.value) == origin) {
          message?.let(::addFeedback)
          loadControl()
        }
      } finally {
        updateToolbar { it.copy(inFlight = false) }
      }
    }
  }

  private suspend fun withItem(
    control: SpecBundleControl,
    describe: (String) -> String = { it },
    run: suspend (WorkItem) -> Outcome<*>,
  ): String? {
    val work = control.work ?: return describe(NO_WORK_ITEM)
    return when (val detail = details.detail(work)) {
      is Outcome.Failure -> describe(detail.problem.summary)
      is Outcome.Value -> run(detail.value.item).failureSummary()?.let(describe)
    }
  }

  private fun Outcome<*>.failureSummary(): String? = (this as? Outcome.Failure)?.problem?.summary

  private fun repoRootOf(l: Local): String? = (l.snapshot as? SpecSnapshotState.Loaded)?.snapshot?.project?.repoRoot

  private fun selectionKey(l: Local): Pair<String?, String?> = l.project?.projectId to (selectionFor(l) as? ToolbarSelection.Bundle)?.path

  private fun selectionFor(l: Local): ToolbarSelection {
    val path = l.selectedPath ?: return ToolbarSelection.None
    return findSelection(loadedRoots(l).orEmpty(), path, null) ?: ToolbarSelection.None
  }

  /** Walks the tree in snapshot order; a file takes the key of the nearest bundle above it. */
  private fun findSelection(nodes: List<SpecNode>, path: String, bundle: SpecNode.Bundle?): ToolbarSelection? {
    for (node in nodes) {
      if (node.path == path) {
        return when (node) {
          is SpecNode.Bundle -> ToolbarSelection.Bundle(node.path)
          is SpecNode.File -> bundle?.let { ToolbarSelection.FileInBundle(it.issueKey ?: it.name) }
          is SpecNode.Folder -> null
        }
      }
      val found = when (node) {
        is SpecNode.Folder -> findSelection(node.children, path, bundle)
        is SpecNode.Bundle -> findSelection(node.children, path, node)
        is SpecNode.File -> null
      }
      if (found != null) return found
    }
    return null
  }

  /** A new selection or project drops everything the toolbar showed. An in-flight request is not cancelled; its answer is dropped. */
  private fun resetToolbar(l: Local): Local = l.copy(
    toolbar = SpecToolbarState(
      control = if (selectionFor(l) is ToolbarSelection.Bundle) ControlLoad.Loading else ControlLoad.Idle,
      inFlight = l.toolbar.inFlight,
    ),
    agentPicked = false,
  )

  private fun select(path: String) {
    if (local.value.selectedPath == path) return
    local.update { resetToolbar(it.copy(selectedPath = path)) }
    loadControl()
  }

  /** Reads the selected bundle's control again; the newest read wins, and an answer for a moved selection is dropped. */
  private fun loadControl() {
    controlJob?.cancel()
    val origin = local.value
    val (projectId, path) = selectionKey(origin)
    if (projectId == null || path == null) return
    controlJob = viewModelScope.launch {
      val result = specs.control(projectId, path, origin.followedRoot ?: origin.project?.repoRoot)
      local.update { cur ->
        if (selectionKey(cur) != (projectId to path)) return@update cur
        val load = when (result) {
          is Outcome.Value -> ControlLoad.Ready(result.value)
          is Outcome.Failure -> ControlLoad.Failed(result.problem.summary)
        }
        val agent = if (cur.agentPicked) cur.toolbar.chosenAgent else (result as? Outcome.Value)?.value?.agentId
        val fresh = (load as? ControlLoad.Ready)?.control
        val tb = cur.toolbar
        // A confirmation the runtime no longer allows is closed rather than left to be refused.
        cur.copy(
          toolbar = tb.copy(
            control = load,
            chosenAgent = agent,
            confirmingCancel = tb.confirmingCancel && fresh?.canCancel == true,
            purgeDialogOpen = tb.purgeDialogOpen && fresh?.let { it.purgeVisible && it.canPurge } == true,
          ),
        )
      }
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
    val changed = local.value.project?.projectId != project.projectId
    local.update {
      if (it.project?.projectId == project.projectId) {
        it.copy(project = project, followedRoot = followedRoot)
      } else {
        resetToolbar(
          it.copy(
            project = project,
            followedRoot = followedRoot,
            snapshot = SpecSnapshotState.Loading,
            selectedPath = null,
            shownWhileFiltering = emptyMap(),
          ),
        )
      }
    }
    if (changed) loadControl()
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

  private fun click(path: String) {
    val node = state.value.rows.firstOrNull { it.node.path == path }?.node ?: return
    if (node is SpecNode.File) activate(path) else select(path)
  }

  private fun activate(path: String) {
    val node = state.value.rows.firstOrNull { it.node.path == path }?.node ?: return
    select(path)
    if (node is SpecNode.File) {
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
      pendingFeedback = l.feedback,
      paneOpen = app.specPaneOpen,
      anyInProgress = loaded?.anyInProgress == true,
      toolbar = selectionFor(l).let { selection ->
        l.toolbar.copy(selection = selection, control = if (selection is ToolbarSelection.Bundle) l.toolbar.control else ControlLoad.Idle)
      },
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
    val feedback: List<Feedback> = emptyList(),
    val retries: Int = 0,
    val toolbar: SpecToolbarState = SpecToolbarState(),
    /** True once the user picked an agent for the selected bundle; a later control read then keeps that pick. */
    val agentPicked: Boolean = false,
  ) {
    val filtersActive: Boolean get() = filtersActive(filterText, statusFilter)
  }
}
