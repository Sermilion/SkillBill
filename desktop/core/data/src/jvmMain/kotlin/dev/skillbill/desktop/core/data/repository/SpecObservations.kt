package dev.skillbill.desktop.core.data.repository

import dev.skillbill.desktop.core.common.Diagnostics
import dev.skillbill.desktop.core.common.HostEnvironment
import dev.skillbill.desktop.core.common.Outcome
import dev.skillbill.desktop.core.data.runtime.RuntimeReads
import dev.skillbill.desktop.core.data.runtime.readOrProblem
import dev.skillbill.desktop.core.data.runtime.toDomain
import dev.skillbill.desktop.core.data.runtime.toState
import dev.skillbill.desktop.core.data.runtime.toWorkItem
import dev.skillbill.desktop.core.data.signal.SignalSnapshot
import dev.skillbill.desktop.core.data.signal.SpecDirectoryChanges
import dev.skillbill.desktop.core.domain.model.OpenOutcome
import dev.skillbill.desktop.core.domain.model.ReadProblem
import dev.skillbill.desktop.core.domain.model.SpecBundleRef
import dev.skillbill.desktop.core.domain.model.SpecControlState
import dev.skillbill.desktop.core.domain.model.SpecProject
import dev.skillbill.desktop.core.domain.model.SpecTreeState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withContext
import skillbill.contracts.persistence.DesktopReadSchema
import skillbill.di.core.RuntimeComponent
import skillbill.ports.idestatus.model.WorkDetailRequest
import java.nio.file.InvalidPathException
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap

private const val SHARE_TIMEOUT_MS = 5_000L
private val EDITOR_VARIABLES = setOf("VISUAL", "EDITOR")

/**
 * One session's spec reads. Each project's tree is one snapshot read, re-read when the platform watcher reports a change
 * under its spec folder or the `specs` revision moves; owners of one project share it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SpecObservations internal constructor(
  private val scope: CoroutineScope,
  private val sessionId: Long,
  private val signals: StateFlow<SignalSnapshot>,
  private val reads: RuntimeReads,
  private val runtime: RuntimeComponent,
  private val diagnostics: Diagnostics,
  private val changes: SpecDirectoryChanges,
  private val host: HostEnvironment,
) {
  private val trees = ConcurrentHashMap<String, StateFlow<SpecTreeState>>()
  private val refreshes = ConcurrentHashMap<String, MutableSharedFlow<Unit>>()

  fun refreshTree(projectId: String) {
    refreshes.computeIfAbsent(projectId) { MutableSharedFlow(replay = 1, extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST) }
      .tryEmit(Unit)
  }

  suspend fun projects(): Outcome<List<SpecProject>, ReadProblem> = readOrProblem(runtime, diagnostics) {
    reads.read("spec_projects") { component -> component.specBrowserService.projects().map { it.toDomain() } }
  }

  suspend fun projectFor(repoRoot: String): Outcome<SpecProject, ReadProblem> = readOrProblem(runtime, diagnostics) {
    reads.read("spec_project") { component -> component.specBrowserService.projectFor(Path.of(repoRoot)).toDomain() }
  }

  /**
   * [preferredRepoRoot] is the checkout the project was resolved from; a new value re-reads the tree. The watch follows the
   * spec folder the runtime names for the project, whatever the tree state, and survives a thrown read.
   */
  fun observeTree(projectId: String, preferredRepoRoot: StateFlow<String?>): StateFlow<SpecTreeState> =
    trees.computeIfAbsent(projectId) { id ->
      val storePath = MutableStateFlow<String?>(null)
      val directoryChanges = storePath.flatMapLatest { path -> path?.let(::folderChanges) ?: emptyFlow() }
      merge(
        refreshes.computeIfAbsent(id) { MutableSharedFlow(replay = 1, extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST) }
          .onStart { emit(Unit) },
        preferredRepoRoot.map { }, directoryChanges, revisionChanges(DesktopReadSchema.SPECS_TOPIC).drop(1),
      )
        .conflate()
        .mapLatest {
          when (val read = readTree(id, preferredRepoRoot.value)) {
            is Outcome.Success -> {
              storePath.value = read.value.specsRoot
              read.value.state
            }
            is Outcome.Failure -> SpecTreeState.Unavailable(read.error)
          }
        }
        .distinctUntilChanged()
        .stateIn(scope, SharingStarted.WhileSubscribed(SHARE_TIMEOUT_MS), SpecTreeState.Loading)
    }

  // A watcher that cannot start leaves the root and `specs` revision triggers running.
  private fun folderChanges(root: String): Flow<Unit> = changes.changes(root).catch { failure ->
    if (failure is CancellationException) throw failure
    diagnostics.record("spec_directory_signal_unavailable")
  }

  /** A cold background read of [bundle]'s control and its live goal's header, re-read when specs or goals change. */
  fun control(bundle: SpecBundleRef): Flow<SpecControlState> =
    merge(revisionChanges(DesktopReadSchema.SPECS_TOPIC), revisionChanges(DesktopReadSchema.GOALS_TOPIC))
      .conflate()
      .mapLatest { readControl(bundle) }
      .onStart { emit(SpecControlState.Loading) }
      .distinctUntilChanged()

  suspend fun openFile(path: String): OpenOutcome = open(path, host.variables)

  suspend fun openFileInDefaultApp(path: String): OpenOutcome = withContext(Dispatchers.IO) {
    val outcome = if (path.isBlank()) OpenOutcome.NotOpened else openOnIo(path, host.variables - EDITOR_VARIABLES)
    if (outcome == OpenOutcome.NotOpened) diagnostics.record("desktop_phase_instruction_default_open_failed")
    outcome
  }

  /** Without `VISUAL` and `EDITOR` the port falls through to the system opener, which shows a folder in the file manager. */
  suspend fun revealFolder(path: String): OpenOutcome = open(path, host.variables - EDITOR_VARIABLES)

  // File opens wait on the launched process, so they run on IO and never hold a database dispatcher slot.
  private suspend fun open(path: String, environment: Map<String, String>): OpenOutcome = withContext(Dispatchers.IO) {
    openOnIo(path, environment)
  }

  private fun openOnIo(path: String, environment: Map<String, String>): OpenOutcome {
    val target = try {
      Path.of(path)
    } catch (_: InvalidPathException) {
      return OpenOutcome.NotOpened
    }
    return if (runtime.fileEditorLauncherPort.open(target, environment)) OpenOutcome.Opened else OpenOutcome.NotOpened
  }

  private fun revisionChanges(topic: String): Flow<Unit> =
    signals.map { it.revisions[topic] to it.resyncEpoch }.distinctUntilChanged().map { }

  private suspend fun readTree(projectId: String, repoRoot: String?): Outcome<TreeRead, ReadProblem> =
    readOrProblem(runtime, diagnostics) {
      reads.read("spec_tree") { component ->
        val snapshot = component.specBrowserService.snapshot(projectId, repoRoot?.let(Path::of))
        TreeRead(snapshot.toState(), snapshot.project.specsRoot?.toString()?.takeIf(String::isNotBlank))
      }
    }

  private suspend fun readControl(bundle: SpecBundleRef): SpecControlState {
    val read = readOrProblem(runtime, diagnostics) {
      reads.read("spec_control") { component ->
        val control = component.specBrowserService.control(bundle.projectId, bundle.path, bundle.repoRoot?.let(Path::of))
        val liveGoal = control.workflowId?.let { workflowId ->
          component.ideStatusService.detail(WorkDetailRequest(workflowId)).item?.toWorkItem(sessionId)
        }
        SpecControlState.Loaded(control.toDomain(), liveGoal)
      }
    }
    return when (read) {
      is Outcome.Success -> read.value
      is Outcome.Failure -> SpecControlState.Unavailable(read.error)
    }
  }
}

private class TreeRead(val state: SpecTreeState, val specsRoot: String?)
