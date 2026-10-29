package dev.skillbill.desktop.feature.specs

import dev.skillbill.desktop.core.common.AppDispatchers
import dev.skillbill.desktop.core.common.Outcome
import dev.skillbill.desktop.core.domain.model.ControlOutcome
import dev.skillbill.desktop.core.domain.model.OpenOutcome
import dev.skillbill.desktop.core.domain.model.PurgeOutcome
import dev.skillbill.desktop.core.domain.model.ReadProblem
import dev.skillbill.desktop.core.domain.model.SpecBundleRef
import dev.skillbill.desktop.core.domain.model.SpecBundleControlView
import dev.skillbill.desktop.core.domain.model.SpecControlState
import dev.skillbill.desktop.core.domain.model.SpecNode
import dev.skillbill.desktop.core.domain.model.SpecProject
import dev.skillbill.desktop.core.domain.model.SpecRunState
import dev.skillbill.desktop.core.domain.model.SpecTreeState
import dev.skillbill.desktop.core.domain.model.WorkItem
import dev.skillbill.desktop.core.domain.repository.SpecRepository
import dev.skillbill.desktop.core.domain.repository.WorkControlRepository
import dev.skillbill.desktop.core.domain.session.DatabaseSessionController
import dev.skillbill.desktop.core.domain.session.DatabaseSessionState
import dev.skillbill.desktop.core.ui.di.WindowScope
import dev.skillbill.desktop.core.ui.feedback.ControlAction
import dev.skillbill.desktop.core.ui.feedback.FeedbackSubject
import dev.skillbill.desktop.core.ui.feedback.ToastMessage
import dev.skillbill.desktop.core.ui.feedback.ToastQueue
import dev.skillbill.desktop.core.ui.feedback.ToastTone
import dev.skillbill.desktop.core.ui.feedback.controlFeedback
import dev.skillbill.desktop.core.ui.feedback.readProblemText
import dev.skillbill.desktop.core.ui.selection.SelectionHolder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import me.tatarka.inject.annotations.Inject
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn

private object DirectSessionController : DatabaseSessionController {
  override val state = MutableStateFlow<DatabaseSessionState>(DatabaseSessionState.Open("", 0L))

  override fun reopen() = Unit
}

private sealed interface RetryFailure {
  data class ProjectList(val problem: ReadProblem) : RetryFailure

  data class FollowedProject(val repoRoot: String, val problem: ReadProblem) : RetryFailure

  data class Tree(val project: SpecProject, val problem: ReadProblem) : RetryFailure

  data class Session(val state: DatabaseSessionState.Failed) : RetryFailure

  data object SessionOpening : RetryFailure
}

private data class SelectionIdentity(
  val projectId: String,
  val repoRoot: String?,
  val path: String,
  val nodeKind: String,
  val issueKey: String?,
)

private data class ControlTarget(
  val identity: SelectionIdentity,
  val project: SpecProject,
  val control: SpecBundleControlView,
  val liveGoal: WorkItem?,
  val agent: String?,
)

private class TreePresentation(
  private val local: MutableStateFlow<SpecsUiState>,
  private val roots: () -> List<SpecNode>,
) {
  private val expansions = mutableMapOf<String, Set<String>>()
  private val filterExpansion = mutableMapOf<String, Map<String, Boolean>>()

  fun updateFilters(text: String = local.value.filterText, status: SpecStatusFilter = local.value.statusFilter) {
    val current = local.value
    local.value = current.copy(
      filterText = text,
      statusFilter = status,
      filtersActive = text.isNotBlank() || status != SpecStatusFilter.ALL,
    )
    local.value = local.value.copy(rows = rows(roots()))
  }

  fun rows(roots: List<SpecNode>): List<SpecRow> {
    val projectId = local.value.project?.projectId ?: return emptyList()
    val current = local.value
    return visibleRows(roots, expansions[projectId].orEmpty(), current.filterText, current.statusFilter, filterExpansion[projectId].orEmpty())
  }

  fun setExpansion(path: String, expanded: Boolean) {
    val projectId = local.value.project?.projectId ?: return
    if (local.value.filtersActive) {
      filterExpansion[projectId] = filterExpansion[projectId].orEmpty() + (path to expanded)
    } else {
      val current = expansions[projectId].orEmpty()
      expansions[projectId] = if (expanded) current + path else current - path
    }
    local.value = local.value.copy(rows = rows(roots()))
  }

  fun collapseAll() {
    val projectId = local.value.project?.projectId ?: return
    expansions.remove(projectId)
    if (local.value.filtersActive) {
      val paths = local.value.rows.filter { it.hasChildren }.map { it.node.path }
      filterExpansion[projectId] = paths.associateWith { false }
    }
    local.value = local.value.copy(rows = rows(roots()))
  }

  fun isExpanded(path: String) = local.value.rows.firstOrNull { it.node.path == path }?.expanded
    ?: path in expansions[local.value.project?.projectId].orEmpty()
}

@Inject
@SingleIn(WindowScope::class)
class SpecsPaneModel(
  private val specs: SpecRepository,
  private val controls: WorkControlRepository,
  private val selection: SelectionHolder,
  private val toasts: ToastQueue,
  dispatchers: AppDispatchers,
  private val session: DatabaseSessionController = DirectSessionController,
) {
  private val scope = CoroutineScope(SupervisorJob() + dispatchers.ui)
  private val local = MutableStateFlow(SpecsUiState())
  private val treePresentation = TreePresentation(local, ::roots)
  private var explicitPick = false
  private var started = false
  private var waitingForSessionOpen = false
  private var failure: RetryFailure? = null
  private var followedRepoRoot: String? = null
  private var observedSelection = false
  private var userAgentFor: SelectionIdentity? = null
  private var selectedIdentity: SelectionIdentity? = null
  private var controlIdentity: SelectionIdentity? = null
  private var cancelConfirmationFor: SelectionIdentity? = null
  private var purgeConfirmationFor: SelectionIdentity? = null
  private var controlLifetime = 0L
  private var operationSequence = 0L
  private val activeOperations = mutableSetOf<Long>()
  private var treeJob: Job? = null
  private var controlJob: Job? = null
  private var resolutionJob: Job? = null
  private var retryJob: Job? = null
  private var treeGeneration = 0
  val state = combine(local, controls.reservations) { current, reservations ->
    val key = selectedBundleKey(current)
    current.copy(toolbar = current.toolbar.copy(reserved = key != null && key in reservations))
  }.stateIn(scope, SharingStarted.Eagerly, SpecsUiState())

  fun open() {
    if (started) return
    started = true
    session.let { controller ->
      scope.launch {
        controller.state.collect { sessionState ->
          when (sessionState) {
            is DatabaseSessionState.Opening -> {
              waitingForSessionOpen = true
              invalidateSelection(clearPath = true)
              local.value = local.value.copy(tree = SpecTreeState.Loading, storePath = null, rows = emptyList())
            }
            is DatabaseSessionState.Failed -> {
              waitingForSessionOpen = false
              showUnavailable(ReadProblem.Storage(sessionState.failure), RetryFailure.Session(sessionState))
            }
            is DatabaseSessionState.Open -> {
              val shouldResolve = waitingForSessionOpen || failure is RetryFailure.Session || failure is RetryFailure.SessionOpening
              waitingForSessionOpen = false
              if (shouldResolve && retryJob?.isActive != true) {
                failure = null
                local.value = local.value.copy(projectsLoaded = false)
                resolutionJob?.cancel()
                resolutionJob = scope.launch {
                  loadProjects()
                  if (failure !is RetryFailure.ProjectList) follow(selection.item.value?.repositoryRoot)
                }
              }
            }
          }
        }
      }
    }
    scope.launch {
      loadProjects()
      selection.item.distinctUntilChanged { old, new -> old?.repositoryRoot == new?.repositoryRoot }
        .collect { item ->
          val repoRoot = item?.repositoryRoot
          val sessionClearedSelection = observedSelection && followedRepoRoot != null && repoRoot == null
          val changed = !observedSelection || followedRepoRoot != repoRoot
          followedRepoRoot = repoRoot
          observedSelection = true
          if (!explicitPick) {
            if (changed) {
              retryJob?.cancel()
              resolutionJob?.cancel()
              val sessionState = session.state.value
              if (sessionState is DatabaseSessionState.Failed) {
                showUnavailable(ReadProblem.Storage(sessionState.failure), RetryFailure.Session(sessionState))
              } else if (failure is RetryFailure.ProjectList) {
                treeJob?.cancel()
                treeJob = null
                treeGeneration++
                controlJob?.cancel()
                controlJob = null
                local.value = local.value.copy(
                  project = if (explicitPick) local.value.project else null,
                  storePath = null,
                  rows = emptyList(),
                  selectedPath = null,
                  toolbar = SpecToolbarState(),
                )
              } else {
                treeJob?.cancel()
                treeJob = null
                treeGeneration++
                controlJob?.cancel()
                controlJob = null
                failure = null
                local.value = local.value.copy(
                  project = null,
                  tree = SpecTreeState.Loading,
                  storePath = null,
                  rows = emptyList(),
                  selectedPath = null,
                  toolbar = SpecToolbarState(),
                )
              }
              resolutionJob = scope.launch {
                if (sessionClearedSelection && failure !is RetryFailure.ProjectList) loadProjects()
                if (session.state.value is DatabaseSessionState.Open && !explicitPick && selection.item.value?.repositoryRoot == repoRoot) {
                  follow(repoRoot)
                }
              }
            }
          }
        }
    }
  }

  fun onAction(action: SpecsAction) {
    when (action) {
      is SpecsAction.PickProject -> {
        retryJob?.cancel()
        resolutionJob?.cancel()
        local.value = local.value.copy(projectMenuOpen = false)
        local.value.projects.firstOrNull { it.projectId == action.projectId }?.let {
          explicitPick = true
          failure = null
          selectProject(it)
        }
      }
      SpecsAction.OpenProjectMenu -> local.value = local.value.copy(projectMenuOpen = true)
      SpecsAction.DismissProjectMenu -> local.value = local.value.copy(projectMenuOpen = false)
      is SpecsAction.FilterChanged -> treePresentation.updateFilters(text = action.text)
      is SpecsAction.ChooseStatus -> treePresentation.updateFilters(status = action.filter)
      SpecsAction.ClearFilters -> treePresentation.updateFilters(text = "", status = SpecStatusFilter.ALL)
      is SpecsAction.Expand -> treePresentation.setExpansion(action.path, true)
      is SpecsAction.Collapse -> treePresentation.setExpansion(action.path, false)
      is SpecsAction.Toggle -> findNode(roots(), action.path)?.let { node ->
        treePresentation.setExpansion(node.path, !treePresentation.isExpanded(node.path))
      }
      is SpecsAction.Select -> selectPath(action.path)
      is SpecsAction.Click -> click(action.path)
      is SpecsAction.Activate -> click(action.path)
      SpecsAction.CollapseAll -> treePresentation.collapseAll()
      SpecsAction.Retry -> retry()
      SpecsAction.Reveal -> reveal()
      SpecsAction.OpenAgentMenu -> if (controlContext()?.let { !local.value.toolbar.inFlight && !isReserved(it) } == true) {
        local.value = local.value.copy(toolbar = local.value.toolbar.copy(agentMenuOpen = true))
      }
      SpecsAction.DismissAgentMenu -> local.value = local.value.copy(toolbar = local.value.toolbar.copy(agentMenuOpen = false))
      is SpecsAction.ChooseAgent -> {
        controlContext()?.let { target ->
          if (!local.value.toolbar.inFlight && !isReserved(target) && action.agentId in target.control.agentChoices) {
            userAgentFor = target.identity
            local.value = local.value.copy(toolbar = local.value.toolbar.copy(chosenAgent = action.agentId, agentMenuOpen = false))
          }
        }
      }
      SpecsAction.Start -> start()
      SpecsAction.Pause -> pause()
      SpecsAction.RequestCancel -> controlContext()?.let { target ->
        if (!local.value.toolbar.inFlight && target.control.canCancel) {
          cancelConfirmationFor = target.identity
          setToolbar { copy(confirmingCancel = true) }
        }
      }
      SpecsAction.KeepRunning -> if (cancelConfirmationFor != null && cancelConfirmationFor == selectedIdentity) {
        cancelConfirmationFor = null
        setToolbar { copy(confirmingCancel = false) }
      }
      SpecsAction.ConfirmCancel -> cancel()
      SpecsAction.RequestPurge -> controlContext()?.let { target ->
        if (!local.value.toolbar.inFlight && target.control.purgeVisible && target.control.canPurge) {
          purgeConfirmationFor = target.identity
          setToolbar { copy(purgeDialogOpen = true) }
        }
      }
      SpecsAction.KeepRunHistory -> if (purgeConfirmationFor != null && purgeConfirmationFor == selectedIdentity) {
        purgeConfirmationFor = null
        setToolbar { copy(purgeDialogOpen = false) }
      }
      SpecsAction.ConfirmPurge -> purge()
    }
  }

  fun close() = scope.cancel()

  private suspend fun loadProjects() {
    when (val result = specs.projects()) {
      is Outcome.Success -> {
        local.value = local.value.copy(projects = result.value, projectsLoaded = true)
        if (failure is RetryFailure.ProjectList) failure = null
        if (result.value.isEmpty() && local.value.project == null && failure == null) {
          local.value = local.value.copy(tree = SpecTreeState.NoSpecs(null), storePath = null, rows = emptyList())
        }
      }
      is Outcome.Failure -> {
        local.value = local.value.copy(projectsLoaded = true)
        val sessionState = session.state.value
        when (sessionState) {
          is DatabaseSessionState.Failed -> showUnavailable(ReadProblem.Storage(sessionState.failure), RetryFailure.Session(sessionState))
          is DatabaseSessionState.Opening -> showUnavailable(result.error, RetryFailure.SessionOpening, clearFollowedProject = !explicitPick)
          is DatabaseSessionState.Open -> showUnavailable(result.error, RetryFailure.ProjectList(result.error), clearFollowedProject = !explicitPick)
        }
      }
    }
  }

  private suspend fun follow(repoRoot: String?) {
    if (session.state.value is DatabaseSessionState.Failed) return
    if (failure is RetryFailure.ProjectList) return
    if (!local.value.projectsLoaded) loadProjects()
    if (failure is RetryFailure.ProjectList) return
    if (explicitPick) return
    if (repoRoot != null) {
      when (val resolved = specs.projectFor(repoRoot)) {
        is Outcome.Success -> {
          if (!isCurrentFollow(repoRoot)) return
          if (resolved.value.projectId !in local.value.projects.map { it.projectId }) {
            loadProjects()
            if (failure is RetryFailure.ProjectList || !isCurrentFollow(repoRoot)) return
          }
          selectProject(resolved.value)
        }
        is Outcome.Failure -> if (isCurrentFollow(repoRoot)) {
          showUnavailable(resolved.error, RetryFailure.FollowedProject(repoRoot, resolved.error), clearFollowedProject = true)
        }
      }
    } else if (selection.item.value?.repositoryRoot == null && !explicitPick) local.value.projects.firstOrNull()?.let(::selectProject)
  }

  private fun selectProject(project: SpecProject) {
    if (local.value.project == project && treeJob?.isActive == true) return
    if (local.value.project == project) {
      invalidateSelection(clearPath = true)
      local.value = local.value.copy(tree = SpecTreeState.Loading, storePath = null, rows = emptyList())
      observeProject(project)
      return
    }
    treeJob?.cancel()
    invalidateSelection(clearPath = true)
    local.value = local.value.copy(
      project = project,
      tree = SpecTreeState.Loading,
      storePath = null,
      selectedPath = null,
      projectMenuOpen = false,
      rows = emptyList(),
      toolbar = emptyToolbar(),
    )
    observeProject(project)
  }

  private fun observeProject(project: SpecProject) {
    val generation = ++treeGeneration
    treeJob = scope.launch {
      specs.observeTree(project.projectId).collect { tree ->
        if (generation != treeGeneration || local.value.project?.projectId != project.projectId) return@collect
        val current = local.value
        if (tree is SpecTreeState.Unavailable) {
          val sessionState = session.state.value
          failure = if (sessionState is DatabaseSessionState.Failed) RetryFailure.Session(sessionState)
          else RetryFailure.Tree(project, tree.problem)
        } else if (failure is RetryFailure.Tree || failure is RetryFailure.Session) {
          failure = null
        }
        val storePath = when (tree) {
          is SpecTreeState.Loaded -> tree.storePath
          is SpecTreeState.NoSpecs -> tree.storePath
          SpecTreeState.Loading, SpecTreeState.NoCheckout, is SpecTreeState.Unavailable -> null
        }
        val roots = (tree as? SpecTreeState.Loaded)?.tree?.roots.orEmpty()
        reconcileSelection(
          if (tree is SpecTreeState.Loaded) current.selectedPath else null,
          treeUpdate = tree,
          storePath = storePath,
          rowsUpdate = treePresentation.rows(roots),
        )
      }
    }
  }

  private fun showUnavailable(problem: ReadProblem, cause: RetryFailure, clearFollowedProject: Boolean = false) {
    treeJob?.cancel()
    treeJob = null
    treeGeneration++
    failure = cause
    invalidateSelection(clearPath = true)
    local.value = local.value.copy(
      project = if (clearFollowedProject && !explicitPick) null else local.value.project,
      tree = SpecTreeState.Unavailable(problem),
      storePath = null,
      rows = emptyList(),
      selectedPath = null,
      toolbar = emptyToolbar(),
    )
  }

  private fun click(path: String) {
    val node = findNode(roots(), path) ?: return
    selectPath(path)
    when (node) {
      is SpecNode.Folder, is SpecNode.Bundle -> treePresentation.setExpansion(path, !treePresentation.isExpanded(path))
      is SpecNode.File -> scope.launch {
        if (specs.openFile(node.absolutePath) == OpenOutcome.NotOpened) problem("Couldn't open ${node.name}.")
      }
    }
  }

  private fun selectPath(path: String) {
    if (selectionAt(roots(), path) != null) reconcileSelection(path)
  }

  private fun reconcileSelection(
    path: String?,
    treeUpdate: SpecTreeState? = null,
    storePath: String? = local.value.storePath,
    rowsUpdate: List<SpecRow>? = null,
  ) {
    val current = local.value
    val treeProject = (treeUpdate as? SpecTreeState.Loaded)?.tree?.project
      ?.takeIf { it.projectId == current.project?.projectId }
    val base = if (treeUpdate == null) current else current.copy(
      project = treeProject ?: current.project,
      tree = treeUpdate,
      storePath = storePath,
      rows = rowsUpdate.orEmpty(),
    )
    val project = base.project
    val roots = (treeUpdate as? SpecTreeState.Loaded)?.tree?.roots ?: roots()
    if (project == null || path == null) {
      invalidateSelection(clearPath = true, base = base)
      return
    }
    val selected = selectionAt(roots, path) ?: run {
      invalidateSelection(clearPath = true, base = base)
      return
    }
    val bundleSelection = selected.toolbar is ToolbarSelection.Bundle
    val bundle = selected.bundle
    val identity = if (bundleSelection && bundle != null) bundleIdentity(project, bundle) else SelectionIdentity(
      project.projectId,
      project.repoRoot,
      selected.node.path,
      when (selected.node) {
        is SpecNode.Bundle -> "bundle"
        is SpecNode.File -> "file"
        is SpecNode.Folder -> "folder"
      },
      bundle?.issueKey,
    )
    val sameIdentity = selectedIdentity == identity
    val previous = base.toolbar
    val keepAgent = sameIdentity && userAgentFor == identity
    if (!sameIdentity) {
      controlJob?.cancel()
      controlJob = null
      controlLifetime++
      controlIdentity = null
      userAgentFor = null
      cancelConfirmationFor = null
      purgeConfirmationFor = null
    }
    val nextControl = if (!bundleSelection) null else if (sameIdentity && controlIdentity == identity) previous.control ?: SpecControlState.Loading else SpecControlState.Loading
    local.value = base.copy(
      selectedPath = selected.node.path,
      toolbar = SpecToolbarState(
        selection = selected.toolbar,
        control = nextControl,
        bundleKey = bundle?.issueKey ?: bundle?.name,
        bundleTitle = bundle?.title,
        chosenAgent = if (keepAgent) previous.chosenAgent else if (sameIdentity) previous.chosenAgent else null,
        agentMenuOpen = sameIdentity && previous.agentMenuOpen,
        confirmingCancel = sameIdentity && previous.confirmingCancel,
        purgeDialogOpen = sameIdentity && previous.purgeDialogOpen,
        inFlight = activeOperations.isNotEmpty(),
      ),
    )
    selectedIdentity = identity
    if (bundleSelection && bundle != null && project != null) {
      if (sameIdentity && controlIdentity == identity && controlJob?.isActive == true) return
      startControl(project, bundle, identity)
    }
  }

  private fun startControl(project: SpecProject, bundle: SpecNode.Bundle, identity: SelectionIdentity) {
    controlJob?.cancel()
    val lifetime = ++controlLifetime
    controlIdentity = identity
    local.value = local.value.copy(toolbar = local.value.toolbar.copy(control = SpecControlState.Loading))
    controlJob = scope.launch {
      specs.control(SpecBundleRef(project.projectId, bundle.path, project.repoRoot)).collect { result ->
        if (lifetime != controlLifetime || controlIdentity != identity || selectedIdentity != identity) return@collect
        val accepted = when (result) {
          is SpecControlState.Loaded -> result.takeIf { it.control.issueKey == identity.issueKey }
          else -> null
        }
        val toolbar = local.value.toolbar
        val next = accepted ?: result.takeUnless { it is SpecControlState.Loaded } ?: SpecControlState.Loading
        val keepAgent = accepted != null && userAgentFor == identity && toolbar.chosenAgent in accepted.control.agentChoices
        if (accepted != null && userAgentFor == identity && !keepAgent) userAgentFor = null
        val keepCancel = accepted?.control?.canCancel == true
        val keepPurge = accepted?.control?.let { it.purgeVisible && it.canPurge } == true
        if (!keepCancel) cancelConfirmationFor = null
        if (!keepPurge) purgeConfirmationFor = null
        local.value = local.value.copy(toolbar = toolbar.copy(
          control = next,
          chosenAgent = when {
            accepted == null -> toolbar.chosenAgent
            keepAgent -> toolbar.chosenAgent
            else -> accepted.control.agentId
          },
          agentMenuOpen = toolbar.agentMenuOpen && accepted != null,
          confirmingCancel = toolbar.confirmingCancel && keepCancel,
          purgeDialogOpen = toolbar.purgeDialogOpen && keepPurge,
        ))
      }
    }
  }

  private fun invalidateSelection(clearPath: Boolean, base: SpecsUiState = local.value) {
    controlJob?.cancel()
    controlJob = null
    controlLifetime++
    controlIdentity = null
    selectedIdentity = null
    userAgentFor = null
    cancelConfirmationFor = null
    purgeConfirmationFor = null
    local.value = base.copy(
      selectedPath = if (clearPath) null else base.selectedPath,
      toolbar = emptyToolbar(),
    )
  }

  private fun bundleIdentity(project: SpecProject, bundle: SpecNode.Bundle) =
    SelectionIdentity(project.projectId, project.repoRoot, bundle.path, "bundle", bundle.issueKey)

  private fun retry() {
    retryJob?.cancel()
    resolutionJob?.cancel()
    val repoRoot = selection.item.value?.repositoryRoot
    val wasExplicit = explicitPick
    val pickedProjectId = local.value.project?.projectId
    retryJob = scope.launch {
      val controller = session
      val sessionState = controller.state.value
      var reopenedSession = false
      when (sessionState) {
        is DatabaseSessionState.Failed, is DatabaseSessionState.Opening -> {
          val stateChange = async(start = CoroutineStart.UNDISPATCHED) {
            controller.state.drop(1).first { state ->
              state is DatabaseSessionState.Open || state is DatabaseSessionState.Failed
            }
          }
          val currentSession = controller.state.value
          val reopened = if (currentSession is DatabaseSessionState.Open) {
            stateChange.cancel()
            currentSession
          } else {
            if (sessionState is DatabaseSessionState.Failed) controller.reopen()
            stateChange.await()
          }
          if (!retryStillCurrent(repoRoot, wasExplicit, pickedProjectId)) return@launch
          if (reopened !is DatabaseSessionState.Open) {
            showUnavailable(ReadProblem.Storage((reopened as DatabaseSessionState.Failed).failure), RetryFailure.Session(reopened))
            return@launch
          }
          reopenedSession = true
        }
        is DatabaseSessionState.Open -> Unit
      }

      if (!retryStillCurrent(repoRoot, wasExplicit, pickedProjectId)) return@launch
      if (reopenedSession || !local.value.projectsLoaded || failure is RetryFailure.ProjectList ||
        failure is RetryFailure.Session || failure is RetryFailure.SessionOpening
      ) {
        loadProjects()
        if (failure is RetryFailure.ProjectList || !retryStillCurrent(repoRoot, wasExplicit, pickedProjectId)) return@launch
      }

      val project = when {
        wasExplicit -> local.value.project?.takeIf { it.projectId == pickedProjectId }
        repoRoot != null -> when (val resolved = specs.projectFor(repoRoot)) {
          is Outcome.Success -> {
            if (!retryStillCurrent(repoRoot, wasExplicit, pickedProjectId)) return@launch
            if (resolved.value.projectId !in local.value.projects.map { it.projectId }) {
              loadProjects()
              if (failure is RetryFailure.ProjectList || !retryStillCurrent(repoRoot, wasExplicit, pickedProjectId)) return@launch
            }
            resolved.value
          }
          is Outcome.Failure -> {
            if (retryStillCurrent(repoRoot, wasExplicit, pickedProjectId)) {
              showUnavailable(resolved.error, RetryFailure.FollowedProject(repoRoot, resolved.error), clearFollowedProject = true)
            }
            return@launch
          }
        }
        else -> local.value.projects.firstOrNull()
      }

      if (!retryStillCurrent(repoRoot, wasExplicit, pickedProjectId)) return@launch
      if (project == null) {
        if (local.value.projectsLoaded && local.value.projects.isEmpty() && repoRoot == null && !wasExplicit) {
          failure = null
          local.value = local.value.copy(tree = SpecTreeState.NoSpecs(null), storePath = null, rows = emptyList(), selectedPath = null, toolbar = emptyToolbar())
        }
        return@launch
      }
      selectProject(project)
      failure = null
      specs.refreshTree(project.projectId)
    }
  }

  private fun retryStillCurrent(repoRoot: String?, wasExplicit: Boolean, pickedProjectId: String?): Boolean =
    if (wasExplicit) explicitPick && local.value.project?.projectId == pickedProjectId
    else !explicitPick && selection.item.value?.repositoryRoot == repoRoot && followedRepoRoot == repoRoot

  private fun isCurrentFollow(repoRoot: String) =
    !explicitPick && selection.item.value?.repositoryRoot == repoRoot && followedRepoRoot == repoRoot

  private fun reveal() {
    val path = local.value.storePath ?: return
    scope.launch { if (specs.revealFolder(path) == OpenOutcome.NotOpened) problem("Couldn't reveal the specs folder.") }
  }

  private fun start() {
    val target = controlContext() ?: return
    val current = local.value
    if (current.toolbar.inFlight || !target.control.canStart || isReserved(target)) return
    if (target.agent == null) {
      local.value = current.copy(toolbar = current.toolbar.copy(agentMenuOpen = true))
      return
    }
    if (target.agent !in target.control.agentChoices) return
    runControl(target) { captured ->
      if (isReserved(captured) || !captured.control.canStart || captured.agent !in captured.control.agentChoices) return@runControl
      val control = captured.control
      val key = control.issueKey
      val agent = captured.agent ?: return@runControl
      val outcome = if (control.runState == SpecRunState.NotStarted) {
        val repoRoot = captured.project.repoRoot
        if (repoRoot == null || key == null) {
          problem(startFailure(control.unavailableReason ?: NO_CHECKOUT))
          return@runControl
        }
        controls.startGoal(repoRoot, key, agent)
      } else {
        val liveGoal = captured.liveGoal
        if (liveGoal == null) {
          problem(startFailure(NO_WORK_ITEM))
          return@runControl
        }
        controls.launch(liveGoal, agent)
      }
      val subject = FeedbackSubject(key ?: "spec", control.startLabel, agent)
      if (outcome is ControlOutcome.Failed || outcome is ControlOutcome.Refused || outcome == ControlOutcome.AlreadyLaunching) {
        problem(startFailure(controlFailure(outcome, subject)))
      } else toasts.show(controlFeedback(ControlAction.Launch, outcome, subject))
    }
  }

  private fun pause() {
    val target = controlContext() ?: return
    if (local.value.toolbar.inFlight || !target.control.canPause || target.control.pauseRequested || target.liveGoal == null) return
    runControl(target) { captured ->
      val liveGoal = captured.liveGoal ?: return@runControl
      val subject = FeedbackSubject(captured.control.issueKey ?: "spec", captured.control.startLabel, captured.agent)
      toasts.show(controlFeedback(ControlAction.SpecPause, controls.pauseAfterSubtask(liveGoal), subject))
    }
  }

  private fun cancel() {
    val target = controlContext() ?: return
    if (local.value.toolbar.inFlight || cancelConfirmationFor != target.identity || !target.control.canCancel || target.liveGoal == null) return
    cancelConfirmationFor = null
    setToolbar { copy(confirmingCancel = false) }
    runControl(target) { captured ->
      val liveGoal = captured.liveGoal ?: return@runControl
      val subject = FeedbackSubject(captured.control.issueKey ?: "spec", captured.control.startLabel, null)
      toasts.show(controlFeedback(ControlAction.Cancel, controls.cancel(liveGoal), subject))
    }
  }

  private fun purge() {
    val target = controlContext() ?: return
    val key = target.control.issueKey ?: return
    if (local.value.toolbar.inFlight || purgeConfirmationFor != target.identity || !target.control.purgeVisible || !target.control.canPurge) return
    purgeConfirmationFor = null
    setToolbar { copy(purgeDialogOpen = false) }
    runControl(target) { captured ->
      when (val outcome = controls.purge(key, captured.project.repoRoot)) {
        is PurgeOutcome.Purged, PurgeOutcome.NothingToRemove -> Unit
        is PurgeOutcome.Refused -> problem(purgeFailure(outcome.reason))
        is PurgeOutcome.Incomplete -> problem(purgeFailure("Run data is still present: ${outcome.leftovers.joinToString(", ")}"))
        is PurgeOutcome.Failed -> problem(purgeFailure(readProblemText(outcome.problem)))
      }
    }
  }

  private fun runControl(target: ControlTarget, action: suspend (ControlTarget) -> Unit) {
    val current = local.value
    if (current.toolbar.inFlight || !isCurrentTarget(target)) return
    val operation = ++operationSequence
    activeOperations += operation
    local.value = current.copy(toolbar = current.toolbar.copy(inFlight = true))
    scope.launch {
      try {
        if (isCurrentTarget(target)) action(target)
      } finally {
        activeOperations -= operation
        local.value = local.value.copy(toolbar = local.value.toolbar.copy(inFlight = activeOperations.isNotEmpty()))
      }
    }
  }

  private fun controlContext(): ControlTarget? {
    val project = local.value.project ?: return null
    val identity = selectedIdentity ?: return null
    if (identity.nodeKind != "bundle" || controlIdentity != identity || selectedIdentity != identity) return null
    val selectedPath = local.value.selectedPath ?: return null
    val selected = selectionAt(roots(), selectedPath) ?: return null
    val bundle = selected.bundle ?: return null
    if (selected.toolbar !is ToolbarSelection.Bundle || bundleIdentity(project, bundle) != identity) return null
    val loaded = local.value.toolbar.control as? SpecControlState.Loaded ?: return null
    if (loaded.control.issueKey != bundle.issueKey) return null
    return ControlTarget(identity, project, loaded.control, loaded.liveGoal, local.value.toolbar.chosenAgent)
  }

  private fun isCurrentTarget(target: ControlTarget): Boolean =
    selectedIdentity == target.identity && controlIdentity == target.identity && controlContext()?.let {
      it.project == target.project && it.identity == target.identity && it.control == target.control && it.liveGoal == target.liveGoal
    } == true

  private fun isReserved(target: ControlTarget) = target.control.issueKey?.let { it in controls.reservations.value } == true

  private fun controlFailure(outcome: ControlOutcome, subject: FeedbackSubject) = when (outcome) {
    is ControlOutcome.Failed -> readProblemText(outcome.problem)
    is ControlOutcome.Refused, ControlOutcome.AlreadyLaunching -> controlFeedback(ControlAction.Launch, outcome, subject).text
    else -> "The runtime did not start the run."
  }

  private fun setToolbar(transform: SpecToolbarState.() -> SpecToolbarState) {
    local.value = local.value.copy(toolbar = local.value.toolbar.transform())
  }

  private fun problem(text: String) = toasts.show(ToastMessage(ToastTone.Problem, text))

  private fun roots() = (local.value.tree as? SpecTreeState.Loaded)?.tree?.roots.orEmpty()
  private fun selectedBundleKey(current: SpecsUiState): String? {
    val path = (current.toolbar.selection as? ToolbarSelection.Bundle)?.path ?: return null
    return (findNode((current.tree as? SpecTreeState.Loaded)?.tree?.roots.orEmpty(), path) as? SpecNode.Bundle)?.issueKey
  }
  private fun emptyToolbar() = SpecToolbarState(inFlight = activeOperations.isNotEmpty())
}
