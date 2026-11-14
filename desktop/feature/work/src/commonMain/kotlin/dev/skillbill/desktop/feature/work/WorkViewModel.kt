package dev.skillbill.desktop.feature.work

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.skillbill.desktop.core.common.Outcome
import dev.skillbill.desktop.core.domain.model.ArchiveInspectOutcome
import dev.skillbill.desktop.core.domain.model.BoardQuery
import dev.skillbill.desktop.core.domain.model.BoardState
import dev.skillbill.desktop.core.domain.model.ControlOutcome
import dev.skillbill.desktop.core.domain.model.ExportOutcome
import dev.skillbill.desktop.core.domain.model.ExportPreflightOutcome
import dev.skillbill.desktop.core.domain.model.ExportRefusalCategory
import dev.skillbill.desktop.core.domain.model.ImportOutcome
import dev.skillbill.desktop.core.domain.model.LoadMoreOutcome
import dev.skillbill.desktop.core.domain.model.WorkItem
import dev.skillbill.desktop.core.domain.model.WorkKind
import dev.skillbill.desktop.core.domain.repository.ArchiveFileChooser
import dev.skillbill.desktop.core.domain.repository.SpecRepository
import dev.skillbill.desktop.core.domain.repository.WorkBoardRepository
import dev.skillbill.desktop.core.domain.repository.WorkControlRepository
import dev.skillbill.desktop.core.domain.repository.WorkDetailRepository
import dev.skillbill.desktop.core.domain.session.DatabaseSessionController
import dev.skillbill.desktop.core.domain.session.DatabaseSessionState
import dev.skillbill.desktop.core.ui.board.ShellBoard
import dev.skillbill.desktop.core.ui.feedback.ControlAction
import dev.skillbill.desktop.core.ui.feedback.FeedbackSubject
import dev.skillbill.desktop.core.ui.feedback.controlFeedback
import dev.skillbill.desktop.core.ui.feedback.ToastMessage
import dev.skillbill.desktop.core.ui.feedback.ToastTone
import dev.skillbill.desktop.core.ui.feedback.readProblemText
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
  private val archiveChooser: ArchiveFileChooser,
  private val session: DatabaseSessionController,
  private val specs: SpecRepository,
) : ViewModel() {
  private var importSession: DatabaseSessionState.Open? = null
  private var importGeneration = 0L
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
      is WorkAction.Finalize -> finalize(action.item)
      is WorkAction.Cancel -> cancel(action.item)
      is WorkAction.RequestExport -> requestExport(action.item)
      WorkAction.DismissExportLive -> mutableState.update { it.copy(exportLiveTarget = null) }
      WorkAction.RequestImport -> requestImport()
      WorkAction.DismissImport -> {
        if (state.value.importDialog?.importing != true) dismissImport()
      }
      is WorkAction.ChooseImportRepository -> mutableState.update { current ->
        val target = current.importDialog ?: return@update current
        if (target.retryPending || target.importing || target.repositories.none { it.root == action.root }) current
        else current.copy(importDialog = target.copy(repositoryRoot = action.root, destinationPath = action.root, destinationError = null))
      }
      is WorkAction.ImportDestinationPathChanged -> mutableState.update { current ->
        val target = current.importDialog ?: return@update current
        if (target.retryPending || target.importing) current
        else current.copy(importDialog = target.copy(destinationPath = action.path, repositoryRoot = null, destinationError = null))
      }
      WorkAction.ResolveImportDestination -> resolveImportDestination()
      WorkAction.ConfirmImport -> confirmImport()
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

  private fun finalize(item: WorkItem) {
    if (state.value.canFinalize(item)) control(item, ControlAction.Finalize) { controls.finalize(item) }
  }

  private fun cancel(item: WorkItem) {
    if (state.value.canCancel(item)) control(item, ControlAction.Cancel) { controls.cancel(item) }
  }

  private fun requestExport(item: WorkItem) {
    if (item.kind != WorkKind.Goal) return
    val selectedSession = session.state.value as? DatabaseSessionState.Open ?: return
    viewModelScope.launch {
      if (session.state.value != selectedSession) return@launch
      when (val preflight = controls.preflightExport(item.issueKey ?: item.id, item.repositoryRoot)) {
        is ExportPreflightOutcome.Ready -> if (session.state.value == selectedSession) archiveChooser.saveArchive(
          "${item.issueKey ?: item.id}-archive.zip",
        ) { path ->
          if (path != null && session.state.value == selectedSession) viewModelScope.launch {
            if (session.state.value != selectedSession) return@launch
            when (val result = controls.export(item.issueKey ?: item.id, item.repositoryRoot, path)) {
              is ExportOutcome.Exported ->
                showToast(ToastTone.Confirmation, "Exported ${result.issueKey} to ${result.archivePath}")
              is ExportOutcome.Refused -> if (requiresPause(result.category)) {
                mutableState.update { it.copy(exportLiveTarget = item) }
              } else showToast(ToastTone.Problem, result.reason)
              is ExportOutcome.Failed -> showToast(ToastTone.Problem, readProblemText(result.problem))
            }
          }
        }
        is ExportPreflightOutcome.Refused -> if (requiresPause(preflight.category)) {
          mutableState.update { it.copy(exportLiveTarget = item) }
        } else showToast(ToastTone.Problem, preflight.reason)
        is ExportPreflightOutcome.Failed -> showToast(ToastTone.Problem, readProblemText(preflight.problem))
      }
    }
  }

  private fun requestImport() {
    if (state.value.importDialog != null) return
    val selectedSession = session.state.value as? DatabaseSessionState.Open ?: return
    val generation = ++importGeneration
    archiveChooser.openArchive { path ->
      if (path != null && importGeneration == generation && session.state.value == selectedSession) viewModelScope.launch {
        if (importGeneration != generation || session.state.value != selectedSession) return@launch
        when (val inspected = controls.inspectArchive(path, null)) {
          is ArchiveInspectOutcome.Ready -> if (importGeneration == generation && session.state.value == selectedSession) {
            when (val projects = specs.projects()) {
              is Outcome.Failure -> if (importGeneration == generation && session.state.value == selectedSession) {
                showToast(ToastTone.Problem, readProblemText(projects.error))
              }
              is Outcome.Success -> if (importGeneration == generation && session.state.value == selectedSession) {
                val repositories = buildList {
                  projects.value.mapNotNullTo(this) { project ->
                    project.repoRoot?.takeIf(String::isNotBlank)?.let { WorkImportRepository(it, project.displayName) }
                  }
                  state.value.visibleRows.forEach { item ->
                    if (item.repositoryRoot.isNotBlank() && none { it.root == item.repositoryRoot }) {
                      add(WorkImportRepository(item.repositoryRoot, item.repositoryName))
                    }
                  }
                  selection.item.value?.let { item ->
                    if (item.repositoryRoot.isNotBlank() && none { it.root == item.repositoryRoot }) {
                      add(WorkImportRepository(item.repositoryRoot, item.repositoryName))
                    }
                  }
                }.distinctBy(WorkImportRepository::root)
                importSession = selectedSession
                val selectedRoot = selection.item.value?.repositoryRoot
                  ?.takeIf { root -> repositories.any { it.root == root } }
                  ?: repositories.firstOrNull()?.root
                mutableState.update {
                  if (importGeneration != generation || session.state.value != selectedSession) it
                  else it.copy(
                    importDialog = WorkImportConfirmation(
                      path,
                      inspected.fingerprint,
                      inspected.issueKey,
                      inspected.featureBranch,
                      inspected.commitSha,
                      repositories,
                      selectedRoot,
                    ),
                  )
                }
              }
            }
          }
          is ArchiveInspectOutcome.Refused -> if (importGeneration == generation && session.state.value == selectedSession) {
            showToast(ToastTone.Problem, inspected.reason)
          }
          is ArchiveInspectOutcome.Failed -> if (importGeneration == generation && session.state.value == selectedSession) {
            showToast(ToastTone.Problem, readProblemText(inspected.problem))
          }
        }
      }
    }
  }

  private fun resolveImportDestination() {
    val target = state.value.importDialog ?: return
    if (target.retryPending || target.importing || target.resolvingDestination) return
    val path = target.destinationPath.trim()
    if (path.isEmpty()) {
      mutableState.update { current ->
        current.copy(importDialog = current.importDialog?.copy(destinationError = "Enter a checkout path."))
      }
      return
    }
    val selectedSession = importSession ?: return
    val generation = importGeneration
    mutableState.update { current ->
      current.copy(importDialog = current.importDialog?.copy(resolvingDestination = true, destinationError = null))
    }
    viewModelScope.launch {
      if (importGeneration != generation) return@launch
      if (session.state.value != selectedSession) {
        dismissImport()
        return@launch
      }
      val resolved = specs.projectFor(path)
      if (importGeneration != generation) return@launch
      if (session.state.value != selectedSession) {
        dismissImport()
        return@launch
      }
      mutableState.update { current ->
        val pending = current.importDialog?.takeIf {
          it.archivePath == target.archivePath && it.fingerprint == target.fingerprint && it.resolvingDestination
        } ?: return@update current
        when (resolved) {
          is Outcome.Success -> {
            val project = resolved.value
            val root = project.repoRoot?.takeIf(String::isNotBlank)
            if (root == null) current.copy(importDialog = pending.copy(
              repositoryRoot = null,
              resolvingDestination = false,
              destinationError = "That path did not resolve to a Git checkout.",
            )) else current.copy(importDialog = pending.copy(
              repositoryRoot = root,
              destinationPath = root,
              repositories = (pending.repositories + WorkImportRepository(root, project.displayName)).distinctBy(WorkImportRepository::root),
              resolvingDestination = false,
              destinationError = null,
            ))
          }
          is Outcome.Failure -> current.copy(importDialog = pending.copy(
            repositoryRoot = null,
            resolvingDestination = false,
            destinationError = "Could not resolve that Git checkout: ${readProblemText(resolved.error)}",
          ))
        }
      }
    }
  }

  private fun confirmImport() {
    val target = state.value.importDialog ?: return
    if (target.importing) return
    val selectedSession = importSession ?: return
    val repoRoot = target.repositoryRoot ?: return
    if (session.state.value != selectedSession) {
      importSession = null
      mutableState.update { it.copy(importDialog = null) }
      return
    }
    mutableState.update { current ->
      current.importDialog?.takeIf { it.archivePath == target.archivePath && it.fingerprint == target.fingerprint }
        ?.let { pending -> current.copy(importDialog = pending.copy(importing = true)) }
        ?: current
    }
    viewModelScope.launch {
      if (session.state.value != selectedSession) {
        dismissImport()
        return@launch
      }
      when (val result = controls.import(target.archivePath, repoRoot, target.fingerprint)) {
        is ImportOutcome.Imported -> {
          importGeneration++
          importSession = null
          mutableState.update { it.copy(importDialog = null) }
          board.refresh()
        }
        is ImportOutcome.Incomplete -> {
          mutableState.update { current ->
            current.importDialog?.takeIf { it.archivePath == target.archivePath && it.fingerprint == target.fingerprint }
              ?.let { pending -> current.copy(importDialog = pending.copy(retryPending = true, importing = false)) }
              ?: current
          }
          showToast(
            ToastTone.Problem,
            "Import is incomplete. Missing specs: ${result.leftovers.joinToString()}. Retry here to continue in $repoRoot.",
          )
        }
        is ImportOutcome.Refused -> {
          showToast(
            ToastTone.Problem,
            "${result.reason} Recorded branch ${target.featureBranch}, commit ${target.commitSha}.",
          )
          dismissImport()
        }
        is ImportOutcome.Failed -> {
          showToast(ToastTone.Problem, readProblemText(result.problem))
          mutableState.update { current ->
            current.importDialog?.takeIf { it.archivePath == target.archivePath && it.fingerprint == target.fingerprint }
              ?.let { pending -> current.copy(importDialog = pending.copy(retryPending = true, importing = false)) }
              ?: current
          }
        }
      }
    }
  }

  private fun dismissImport() {
    importGeneration++
    importSession = null
    mutableState.update { it.copy(importDialog = null) }
  }

  private fun showToast(tone: ToastTone, message: String) {
    effectChannel.trySend(WorkEffect.ShowToast(ToastMessage(tone, message)))
  }

  private fun requiresPause(category: ExportRefusalCategory): Boolean = category == ExportRefusalCategory.LIVENESS

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
