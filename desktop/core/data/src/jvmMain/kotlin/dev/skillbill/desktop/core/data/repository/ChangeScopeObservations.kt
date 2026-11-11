package dev.skillbill.desktop.core.data.repository

import dev.skillbill.desktop.core.common.Diagnostics
import dev.skillbill.desktop.core.common.Outcome
import dev.skillbill.desktop.core.data.runtime.aggregate
import dev.skillbill.desktop.core.data.runtime.readOrProblem
import dev.skillbill.desktop.core.data.runtime.toDomain
import dev.skillbill.desktop.core.data.runtime.toPort
import dev.skillbill.desktop.core.data.runtime.toState
import dev.skillbill.desktop.core.data.runtime.withTimeline
import dev.skillbill.desktop.core.domain.model.ChangeBaseline
import dev.skillbill.desktop.core.domain.model.ChangeScope
import dev.skillbill.desktop.core.domain.model.ChangeScopeTarget
import dev.skillbill.desktop.core.domain.model.ChangeSet
import dev.skillbill.desktop.core.domain.model.ChangedFile
import dev.skillbill.desktop.core.domain.model.CommitChanges
import dev.skillbill.desktop.core.domain.model.FileDiffState
import dev.skillbill.desktop.core.domain.model.ReadProblem
import dev.skillbill.desktop.core.domain.model.ScopeChangesState
import dev.skillbill.desktop.core.domain.model.ScopeCommit
import dev.skillbill.desktop.core.domain.model.ScopeRead
import dev.skillbill.desktop.core.domain.model.ScopeSubtask
import dev.skillbill.desktop.core.domain.model.ScopeSubtaskState
import dev.skillbill.desktop.core.domain.model.SubtaskScopesState
import dev.skillbill.desktop.core.domain.model.SubtaskStats
import dev.skillbill.desktop.core.domain.model.TimelineProblem
import dev.skillbill.desktop.core.domain.model.attributedTo
import dev.skillbill.desktop.core.domain.model.goalScopeTarget
import dev.skillbill.desktop.core.domain.model.resolveScope
import dev.skillbill.desktop.core.domain.model.selectedEndSubtask
import dev.skillbill.desktop.core.domain.model.workingBaseSha
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import skillbill.di.core.RuntimeComponent
import skillbill.ports.workchanges.WorkChangesPort
import skillbill.ports.workchanges.model.WorkChangeListResult
import skillbill.ports.workchanges.model.WorkGoalScopesResult
import skillbill.ports.workchanges.model.WorkGoalTimelineResult
import java.util.concurrent.ConcurrentHashMap

internal const val SCOPES_READ_FAILED = "desktop_work_change_scopes_read_failed"
internal const val PER_COMMIT_READ_LIMIT = 200

class ChangeScopeObservations internal constructor(
  private val runtime: RuntimeComponent,
  private val diagnostics: Diagnostics,
  private val readSubtasks: suspend (String) -> WorkGoalScopesResult,
  private val port: WorkChangesPort = runtime.workChangesPort,
  private val io: CoroutineDispatcher = Dispatchers.IO,
  private val workingTree: WorkingTreeReads = WorkingTreeReads(),
  private val readTimeline: (WorkGoalScopesResult.Listed) -> WorkGoalTimelineResult = runtime.workChangeScopesService::goalSubtaskTimeline,
) {
  private data class InterruptedStatsKey(val worktreeRoot: String, val subtaskId: Int, val baseSha: String)
  private data class StatsAttemptKey(
    val worktreeRoot: String,
    val subtaskId: Int,
    val read: IndividualStatsRead,
    val state: ScopeSubtaskState,
  )
  private sealed interface IndividualStatsRead {
    data class Working(val baseline: ChangeBaseline.SubtaskStart) : IndividualStatsRead
    data class Committed(val scope: ScopeRead.Committed) : IndividualStatsRead
    data class PerCommit(val commits: List<ScopeCommit>) : IndividualStatsRead
    data object Unavailable : IndividualStatsRead
  }

  private val baseShas = ConcurrentHashMap<ListedBaseKey, String>()
  private val reportedCommitFailures = ConcurrentHashMap.newKeySet<String>()
  private val latestSubtaskStates = ConcurrentHashMap<String, SubtaskScopesState.Listed>()
  private val interruptedStats = ConcurrentHashMap<InterruptedStatsKey, SubtaskStats>()
  private val diffs = ChangeScopeDiffs(runtime, diagnostics, port, io, baseShas, ::readPerCommit)

  fun observeScope(
    target: ChangeScopeTarget,
    scope: ChangeScope,
    running: Boolean,
    lock: Mutex,
    cache: CommittedChangeCache,
  ): Flow<ScopeChangesState> = flow {
    emit(ScopeChangesState.Loading)
    val resolved = resolveScope(target, scope)
    while (true) {
      emit(readScope(target, scope, resolved, lock, cache).attributedTo(target.paths))
      val followsHead = (resolved is ScopeRead.Baseline && resolved.baseline !is ChangeBaseline.CommittedBranchRange &&
        resolved.baseline !is ChangeBaseline.CommittedRevisionRange) ||
        resolved == ScopeRead.Uncommitted ||
        (resolved is ScopeRead.PerCommit && runningWorkingBaseline(target, scope) != null)
      if (!running || !followsHead) break
      delay(CHANGE_REFRESH_INTERVAL)
    }
  }.distinctUntilChanged()

  fun observeCommitStats(
    target: ChangeScopeTarget,
    lock: Mutex,
    cache: CommittedChangeCache,
  ): Flow<Map<String, SubtaskStats>> = flow {
    val stats = linkedMapOf<String, SubtaskStats>()
    emit(emptyMap())
    for (commit in target.timeline?.commits.orEmpty()) {
      val result = readCommitStats(target, commit, lock, cache)
      if (result == null) {
        if (reportedCommitFailures.add("${target.worktreeRoot}:${commit.sha}")) diagnostics.record(SCOPES_READ_FAILED)
      } else stats[commit.sha] = result.attributedTo(target.paths)
      emit(stats.toMap())
    }
  }.distinctUntilChanged()

  private suspend fun readCommitStats(
    target: ChangeScopeTarget,
    commit: ScopeCommit,
    lock: Mutex,
    cache: CommittedChangeCache,
  ): SubtaskStats? {
    val key = CommittedListKey(target.worktreeRoot, commit.parentSha, commit.sha)
    cache.list(key)?.let { return it.toStats() }
    return when (val outcome = git(lock) { port.committedChanges(target.worktreeRoot, commit.parentSha, commit.sha) }) {
      is Outcome.Failure -> null
      is Outcome.Success -> when (val result = outcome.value) {
        is WorkChangeListResult.Listed -> result.toChangeSet().also { cache.putList(key, it) }.toStats()
        is WorkChangeListResult.Failed, is WorkChangeListResult.BaseUnavailable, is WorkChangeListResult.NotARepository -> null
      }
    }
  }

  fun observeSubtasks(
    goalWorkflowId: String,
    running: Boolean,
    lock: Mutex,
    cache: CommittedChangeCache,
  ): Flow<SubtaskScopesState> = flow {
    emit(SubtaskScopesState.Loading)
    val attempted = mutableSetOf<StatsAttemptKey>()
    var previousTimelineProblem: TimelineProblem? = null
    while (true) {
      val read = readOrProblem(runtime, diagnostics) { readSubtasks(goalWorkflowId) }
      val scopes = when (read) {
        is Outcome.Success -> read.value.toState()
        is Outcome.Failure -> {
          diagnostics.record(SCOPES_READ_FAILED)
          SubtaskScopesState.Unavailable(read.error)
        }
      }
      if (scopes !is SubtaskScopesState.Listed) {
        latestSubtaskStates.remove(goalWorkflowId)
        emit(scopes)
      } else {
        val mapped = if (scopes.rangesSupported) {
          val listed = (read as Outcome.Success).value as WorkGoalScopesResult.Listed
          when (val timeline = git(lock) { readTimeline(listed) }) {
            is Outcome.Success -> scopes.withTimeline(timeline.value)
            is Outcome.Failure -> scopes.copy(
              timeline = null,
              timelineProblem = TimelineProblem.Unavailable(timeline.error),
            )
          }
        } else scopes.copy(timeline = null, timelineProblem = null)
        val problem = mapped.timelineProblem
        if (problem != null && problem != previousTimelineProblem) diagnostics.record(SCOPES_READ_FAILED)
        previousTimelineProblem = problem
        val target = goalScopeTarget(goalWorkflowId, mapped)
        val stats = linkedMapOf<Int, SubtaskStats>()
        if (mapped.timeline != null || !mapped.rangesSupported) {
          for (subtask in mapped.subtasks.filter(ScopeSubtask::selectable)) {
            val selection = individualStatsRead(target, subtask)
            val key = statsAttemptKey(target, subtask, selection)
            val cached = cachedStats(target, selection, subtask, cache)
            val value = cached ?: if (subtask.state != ScopeSubtaskState.RUNNING && key in attempted) null
            else readSubtaskStats(target, subtask, selection, lock, cache)
            if (value != null) {
              stats[subtask.id] = value
              if (selection is IndividualStatsRead.Working && subtask.state == ScopeSubtaskState.INTERRUPTED) {
                interruptedStats[InterruptedStatsKey(target.worktreeRoot, subtask.id, selection.baseline.baseSha)] = value
              }
            } else if (key !in attempted && subtask.state != ScopeSubtaskState.RUNNING) {
              attempted += key
              diagnostics.record(SCOPES_READ_FAILED)
            }
          }
        } else {
          attempted.clear()
          interruptedStats.clear()
        }
        mapped.copy(stats = stats).also { latestSubtaskStates[goalWorkflowId] = it }.let { emit(it) }
      }
      if (!running) break
      delay(CHANGE_REFRESH_INTERVAL)
    }
  }.distinctUntilChanged()

  private fun statsAttemptKey(target: ChangeScopeTarget, subtask: ScopeSubtask, selection: IndividualStatsRead) =
    StatsAttemptKey(target.worktreeRoot, subtask.id, selection, subtask.state)

  private fun cachedStats(
    target: ChangeScopeTarget,
    selection: IndividualStatsRead,
    subtask: ScopeSubtask,
    cache: CommittedChangeCache,
  ): SubtaskStats? = when (selection) {
    is IndividualStatsRead.Committed -> cache.list(selection.scope.key(target.worktreeRoot))?.toStats()
    is IndividualStatsRead.PerCommit -> {
      val keys = selection.commits.map { CommittedListKey(target.worktreeRoot, it.parentSha, it.sha) }
      val changes = keys.mapNotNull(cache::list)
      if (changes.size != keys.size) null else changes.mapIndexed { index, set -> CommitChanges(selection.commits[index], set.files) }
        .aggregate(target.timeline!!.mergeBaseSha).toStats()
    }
    is IndividualStatsRead.Working -> if (subtask.state == ScopeSubtaskState.INTERRUPTED) {
      interruptedStats[InterruptedStatsKey(target.worktreeRoot, subtask.id, selection.baseline.baseSha)]
    } else null
    IndividualStatsRead.Unavailable -> null
  }

  private fun individualStatsRead(target: ChangeScopeTarget, subtask: ScopeSubtask): IndividualStatsRead {
    if (!target.rangesSupported) {
      val reviewBaseSha = subtask.reviewBaseSha ?: return IndividualStatsRead.Unavailable
      if (!target.committedOnly && (subtask.state == ScopeSubtaskState.RUNNING ||
        (subtask.state == ScopeSubtaskState.INTERRUPTED && subtask.recordedSha == null)
      )) {
        return IndividualStatsRead.Working(
          ChangeBaseline.SubtaskStart(target.worktreeRoot, reviewBaseSha, subtask.baselineUntrackedPaths),
        )
      }
      val endCommitSha = if (subtask.completedNoChange) reviewBaseSha else subtask.recordedSha
      return endCommitSha?.let { IndividualStatsRead.Committed(ScopeRead.Committed(reviewBaseSha, it)) }
        ?: IndividualStatsRead.Unavailable
    }
    val timeline = target.timeline ?: return IndividualStatsRead.Unavailable
    if (!target.committedOnly && (subtask.state == ScopeSubtaskState.RUNNING ||
      (subtask.state == ScopeSubtaskState.INTERRUPTED && subtask.recordedSha == null)
    )) {
      return IndividualStatsRead.Working(
        ChangeBaseline.SubtaskStart(target.worktreeRoot, workingBaseSha(timeline, subtask), subtask.baselineUntrackedPaths),
      )
    }
    val commits = timeline.commits.filter { subtask.id in it.ownerIds }
    if (commits.isEmpty()) return IndividualStatsRead.Unavailable
    val indices = commits.map { commit -> timeline.commits.indexOf(commit) }
    return if (indices.zipWithNext().all { (first, second) -> second == first + 1 }) {
      IndividualStatsRead.Committed(ScopeRead.Committed(commits.first().parentSha, commits.last().sha))
    } else {
      IndividualStatsRead.PerCommit(commits)
    }
  }

  private suspend fun readSubtaskStats(
    target: ChangeScopeTarget,
    subtask: ScopeSubtask,
    selection: IndividualStatsRead,
    lock: Mutex,
    cache: CommittedChangeCache,
  ): SubtaskStats? = when (selection) {
    is IndividualStatsRead.Working -> when (val result = readChanges(selection.baseline, lock)) {
      is Outcome.Success -> (result.value as? WorkChangeListResult.Listed)?.toChangeSet()?.toStats()
      is Outcome.Failure -> null
    }
    is IndividualStatsRead.Committed -> readCommittedList(target.worktreeRoot, selection.scope, lock, cache)?.toStats()
    is IndividualStatsRead.PerCommit -> when (val result = readPerCommit(target, selection.commits, lock, cache)) {
      is ScopeChangesState.Listed -> result.changes.toStats()
      else -> null
    }
    IndividualStatsRead.Unavailable -> null
  }

  private suspend fun readScope(
    target: ChangeScopeTarget,
    scope: ChangeScope,
    scopeRead: ScopeRead,
    lock: Mutex,
    cache: CommittedChangeCache,
  ): ScopeChangesState = when (scopeRead) {
    is ScopeRead.Baseline -> when (val result = readChanges(scopeRead.baseline, lock)) {
      is Outcome.Failure -> unavailable(result.error)
      is Outcome.Success -> result.value.toScopeState().also { state ->
        if (state is ScopeChangesState.Listed) baseShas[ListedBaseKey(target.worktreeRoot, scopeRead)] = state.changes.baseSha
      }
    }
    ScopeRead.Uncommitted -> when (val result = git(lock) { port.uncommittedChanges(target.worktreeRoot) }) {
      is Outcome.Failure -> unavailable(result.error)
      is Outcome.Success -> result.value.toScopeState()
    }
    is ScopeRead.Committed -> readCommittedState(target.worktreeRoot, scopeRead, lock, cache)
    is ScopeRead.PerCommit -> {
      val commits = readPerCommit(target, scopeRead.commits, lock, cache)
      val baseline = runningWorkingBaseline(target, scope)
      if (baseline == null || commits !is ScopeChangesState.Listed) commits
      else when (val working = readChanges(baseline, lock)) {
        is Outcome.Failure -> unavailable(working.error)
        is Outcome.Success -> when (val state = working.value.toScopeState()) {
          is ScopeChangesState.Listed -> commits.withWorkingChanges(state.changes)
          else -> state
        }
      }
    }
    is ScopeRead.Unavailable -> ScopeChangesState.Unavailable(ReadProblem.Runtime(scopeRead.reason))
  }

  private suspend fun readPerCommit(
    target: ChangeScopeTarget,
    commits: List<ScopeCommit>,
    lock: Mutex,
    cache: CommittedChangeCache,
  ): ScopeChangesState {
    if (commits.size > PER_COMMIT_READ_LIMIT) return ScopeChangesState.TooManyCommits(PER_COMMIT_READ_LIMIT)
    val files = mutableListOf<CommitChanges>()
    for (commit in commits) {
      when (val state = readCommittedState(target.worktreeRoot, ScopeRead.Committed(commit.parentSha, commit.sha), lock, cache)) {
        is ScopeChangesState.Listed -> files += CommitChanges(commit, state.changes.files)
        else -> return state
      }
    }
    return ScopeChangesState.Listed(
      files.aggregate(target.timeline!!.mergeBaseSha),
      perCommit = files,
    )
  }

  private suspend fun readCommittedList(
    worktreeRoot: String,
    scope: ScopeRead.Committed,
    lock: Mutex,
    cache: CommittedChangeCache,
  ): ChangeSet? = when (val state = readCommittedState(worktreeRoot, scope, lock, cache)) {
    is ScopeChangesState.Listed -> state.changes
    else -> null
  }

  private suspend fun readCommittedState(
    worktreeRoot: String,
    scope: ScopeRead.Committed,
    lock: Mutex,
    cache: CommittedChangeCache,
  ): ScopeChangesState {
    val key = scope.key(worktreeRoot)
    cache.list(key)?.let { return ScopeChangesState.Listed(it) }
    return when (val result = git(lock) { port.committedChanges(worktreeRoot, scope.fromSha, scope.toSha) }) {
      is Outcome.Failure -> unavailable(result.error)
      is Outcome.Success -> result.value.toScopeState().also { state ->
        if (state is ScopeChangesState.Listed) cache.putList(key, state.changes)
      }
    }
  }

  private suspend fun readChanges(baseline: ChangeBaseline, lock: Mutex): Outcome<WorkChangeListResult, ReadProblem> {
    return readOrProblem(runtime, diagnostics) {
      if (baseline is ChangeBaseline.CommittedRevisionRange) {
        lock.withLock { withContext(io) { port.committedChanges(baseline.worktreeRoot, baseline.fromSha, baseline.toSha) } }
      } else {
        val portBaseline = baseline.toPort()
        val read: suspend () -> WorkChangeListResult = { lock.withLock { withContext(io) { port.changes(portBaseline) } } }
        if (baseline is ChangeBaseline.CommittedBranchRange) read() else workingTree.changes(portBaseline, read)
      }
    }
  }

  suspend fun fileDiff(
    target: ChangeScopeTarget,
    scope: ChangeScope,
    file: ChangedFile,
    lock: Mutex,
    cache: CommittedChangeCache,
  ): FileDiffState = diffs.fileDiff(target, scope, file, lock, cache)

  private suspend fun <T> git(lock: Mutex, block: () -> T): Outcome<T, ReadProblem> = readOrProblem(runtime, diagnostics) {
    lock.withLock { withContext(io) { block() } }
  }

  private fun unavailable(problem: ReadProblem): ScopeChangesState {
    diagnostics.record(SCOPES_READ_FAILED)
    return ScopeChangesState.Unavailable(problem)
  }

  private fun WorkChangeListResult.toScopeState(): ScopeChangesState = when (this) {
    is WorkChangeListResult.Listed -> ScopeChangesState.Listed(toChangeSet())
    is WorkChangeListResult.BaseUnavailable -> ScopeChangesState.BaseUnavailable(reason)
    is WorkChangeListResult.NotARepository -> ScopeChangesState.NotARepository(worktreeRoot)
    is WorkChangeListResult.Failed -> {
      diagnostics.record(SCOPES_READ_FAILED)
      ScopeChangesState.Unavailable(ReadProblem.Runtime(message))
    }
  }

  private fun WorkChangeListResult.Listed.toChangeSet() = ChangeSet(baseSha, files.map { it.toDomain() }, insertions, deletions)

  private fun ScopeChangesState.Listed.withWorkingChanges(working: ChangeSet): ScopeChangesState.Listed {
    val mergedChanges = perCommit + perCommit.lastOrNull()?.let { last -> listOf(CommitChanges(last.commit, working.files)) }.orEmpty()
    val merged = mergedChanges.aggregate(changes.baseSha).files
    return copy(changes = ChangeSet(
      changes.baseSha,
      merged,
      merged.sumOf { it.insertions ?: 0 },
      merged.sumOf { it.deletions ?: 0 },
    ))
  }

  private fun ChangeSet.toStats() = SubtaskStats(files, insertions, deletions)

  private fun ChangeSet.attributedTo(paths: Set<String>?): ChangeSet =
    if (paths == null) this else files.attributedTo(paths).let { kept -> ChangeSet(baseSha, kept, kept.sumOf { it.insertions ?: 0 }, kept.sumOf { it.deletions ?: 0 }) }

  private fun SubtaskStats.attributedTo(paths: Set<String>?): SubtaskStats =
    if (paths == null) this else files.attributedTo(paths).let { kept -> SubtaskStats(kept, kept.sumOf { it.insertions ?: 0 }, kept.sumOf { it.deletions ?: 0 }) }

  private fun ScopeChangesState.attributedTo(paths: Set<String>?): ScopeChangesState =
    if (paths == null || this !is ScopeChangesState.Listed) this
    else ScopeChangesState.Listed(changes.attributedTo(paths), perCommit.map { CommitChanges(it.commit, it.files.attributedTo(paths)) })
  private fun ScopeRead.Committed.key(worktreeRoot: String) = CommittedListKey(worktreeRoot, fromSha, toSha)
  private fun ChangeScopeTarget.targetBranch(): String? = when (val baseline = allBaseline) {
    is ChangeBaseline.TargetMergeBase -> baseline.targetBranch
    is ChangeBaseline.CommittedBranchRange -> baseline.targetBranch
    is ChangeBaseline.CommittedRevisionRange -> null
    else -> null
  }
}

internal fun runningWorkingBaseline(target: ChangeScopeTarget, scope: ChangeScope): ChangeBaseline.SubtaskStart? {
  if (target.committedOnly || scope !is ChangeScope.Subtasks) return null
  val timeline = target.timeline ?: return null
  val subtasks = target.subtasks
  val end = selectedEndSubtask(subtasks, scope) ?: return null
  val endIndex = subtasks.indexOf(end)
  if (endIndex < 0) return null
  if (end.recordedSha != null || end.state !in setOf(ScopeSubtaskState.RUNNING, ScopeSubtaskState.INTERRUPTED) ||
    subtasks.drop(endIndex + 1).any { it.state != ScopeSubtaskState.NOT_STARTED }
  ) return null
  val start = subtasks.firstOrNull { it.id == scope.from } ?: return null
  return ChangeBaseline.SubtaskStart(target.worktreeRoot, timeline.headSha, start.baselineUntrackedPaths)
}
