package dev.skillbill.desktop.core.data.repository

import dev.skillbill.desktop.core.common.Diagnostics
import dev.skillbill.desktop.core.common.Outcome
import dev.skillbill.desktop.core.data.runtime.parseUnifiedDiff
import dev.skillbill.desktop.core.data.runtime.readOrProblem
import dev.skillbill.desktop.core.data.runtime.toDomain
import dev.skillbill.desktop.core.data.runtime.toPort
import dev.skillbill.desktop.core.domain.model.ChangeBaseline
import dev.skillbill.desktop.core.domain.model.ChangeSet
import dev.skillbill.desktop.core.domain.model.ChangeStatus
import dev.skillbill.desktop.core.domain.model.ChangedFile
import dev.skillbill.desktop.core.domain.model.ChangesState
import dev.skillbill.desktop.core.domain.model.FileDiffState
import dev.skillbill.desktop.core.domain.model.OpenOutcome
import dev.skillbill.desktop.core.domain.model.ReadProblem
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import skillbill.di.core.RuntimeComponent
import skillbill.ports.editor.FileEditorLauncherPort
import skillbill.ports.system.CheckedOutBranchSource
import skillbill.ports.workchanges.WorkChangesPort
import skillbill.ports.workchanges.model.WorkChangeListResult
import skillbill.ports.workchanges.model.WorkFileDiffResult
import java.nio.file.InvalidPathException
import java.nio.file.Path
import kotlin.time.Duration.Companion.seconds

internal val CHANGE_REFRESH_INTERVAL = 3.seconds
private const val READ_FAILED = "desktop_work_changes_read_failed"
internal const val DIFF_FAILED = "desktop_work_changes_diff_failed"
internal const val OPEN_FAILED = "desktop_work_changes_open_failed"

/**
 * One session's git change reads. Every read runs on [io] under the caller's app-wide mutex, never on the database dispatcher,
 * so git never holds a database slot and at most one git process for this feature runs at a time.
 */
class ChangeObservations internal constructor(
  private val runtime: RuntimeComponent,
  private val diagnostics: Diagnostics,
  private val environment: Map<String, String>,
  private val port: WorkChangesPort = runtime.workChangesPort,
  private val launcher: FileEditorLauncherPort = runtime.fileEditorLauncherPort,
  private val io: CoroutineDispatcher = Dispatchers.IO,
  private val workingTree: WorkingTreeReads = WorkingTreeReads(),
  private val branches: CheckedOutBranchSource = runtime.checkedOutBranchSource,
) {
  /** Reads once when idle; while [running], reads again [CHANGE_REFRESH_INTERVAL] after each read ends. */
  fun observe(baseline: ChangeBaseline?, running: Boolean, gitReads: Mutex): Flow<ChangesState> {
    if (baseline == null) return flowOf(ChangesState.NoSubtask)
    return flow {
      emit(ChangesState.Loading)
      while (true) {
        emit(read(baseline, gitReads))
        if (!running || baseline is ChangeBaseline.CommittedBranchRange || baseline is ChangeBaseline.CommittedRevisionRange) break
        delay(CHANGE_REFRESH_INTERVAL)
      }
    }.distinctUntilChanged()
  }

  private suspend fun read(baseline: ChangeBaseline, gitReads: Mutex): ChangesState {
    val read = readOrProblem(runtime, diagnostics) {
      when (baseline) {
        is ChangeBaseline.CommittedRevisionRange -> gitReads.withLock {
          withContext(io) { port.committedChanges(baseline.worktreeRoot, baseline.fromSha, baseline.toSha) }
        }
        else -> {
          val portBaseline = baseline.toPort()
          val read: suspend () -> WorkChangeListResult = { gitReads.withLock { withContext(io) { port.changes(portBaseline) } } }
          if (baseline is ChangeBaseline.CommittedBranchRange) read() else workingTree.changes(portBaseline, read)
        }
      }
    }
    val state = when (read) {
      is Outcome.Failure -> ChangesState.Unavailable(read.error)
      is Outcome.Success -> when (val result = read.value) {
        is WorkChangeListResult.Listed ->
          ChangesState.Listed(ChangeSet(result.baseSha, result.files.map { it.toDomain() }, result.insertions, result.deletions))
        is WorkChangeListResult.BaseUnavailable -> ChangesState.BaseUnavailable(result.reason)
        is WorkChangeListResult.NotARepository -> ChangesState.NotARepository(result.worktreeRoot)
        is WorkChangeListResult.Failed -> ChangesState.Unavailable(ReadProblem.Runtime(result.message))
      }
    }
    if (state is ChangesState.Unavailable) diagnostics.record(READ_FAILED)
    return state
  }

  // Parsing a patch up to the runtime's size limit is real work, so it stays on IO too.
  suspend fun fileDiff(baseline: ChangeBaseline, baseSha: String, file: ChangedFile, gitReads: Mutex): FileDiffState {
    val read = readOrProblem(runtime, diagnostics) {
      gitReads.withLock {
        withContext(io) {
          val result = when (baseline) {
            is ChangeBaseline.CommittedBranchRange ->
              port.committedFileDiff(baseline.worktreeRoot, baseSha, baseline.headRef, file.toPort())
            is ChangeBaseline.CommittedRevisionRange ->
              port.committedFileDiff(baseline.worktreeRoot, baseline.fromSha, baseline.toSha, file.toPort())
            else -> port.fileDiff(baseline.worktreeRoot, baseSha, file.toPort())
          }
          when (result) {
            is WorkFileDiffResult.Patch -> FileDiffState.Shown(parseUnifiedDiff(result.text))
            WorkFileDiffResult.Binary -> FileDiffState.Binary
            is WorkFileDiffResult.TooLarge -> FileDiffState.TooLarge(result.limitBytes)
            is WorkFileDiffResult.BaseUnavailable -> FileDiffState.BaseUnavailable(result.reason)
            is WorkFileDiffResult.Failed -> FileDiffState.Unavailable(ReadProblem.Runtime(result.message))
          }
        }
      }
    }
    val state = when (read) {
      is Outcome.Success -> read.value
      is Outcome.Failure -> FileDiffState.Unavailable(read.error)
    }
    if (state is FileDiffState.Unavailable) diagnostics.record(DIFF_FAILED)
    return state
  }

  /** Strips `VISUAL` and `EDITOR` so the launcher falls through to the system opener; a deleted file is never launched. */
  suspend fun open(baseline: ChangeBaseline, file: ChangedFile): OpenOutcome {
    if (baseline is ChangeBaseline.CommittedRevisionRange) return OpenOutcome.NotOpened
    if (file.status == ChangeStatus.Deleted) return OpenOutcome.NotOpened
    val opened = withContext(io) {
      try {
        val root = Path.of(baseline.worktreeRoot)
        // A committed range names the repository root of a removed worktree, so its files are the goal's only while that root is on the goal branch.
        // A branch mismatch is an expected outcome, so it neither launches nor records a failure.
        if (baseline is ChangeBaseline.CommittedBranchRange && branches.checkedOutBranch(root) != baseline.headBranch) {
          return@withContext null
        }
        launcher.open(root.resolve(file.path), environment - EDITOR_VARIABLES)
      } catch (_: InvalidPathException) {
        false
      }
    } ?: return OpenOutcome.NotOpened
    if (!opened) diagnostics.record(OPEN_FAILED)
    return if (opened) OpenOutcome.Opened else OpenOutcome.NotOpened
  }
}
