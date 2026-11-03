package dev.skillbill.desktop.core.data.repository

import dev.skillbill.desktop.core.common.Diagnostics
import dev.skillbill.desktop.core.common.Outcome
import dev.skillbill.desktop.core.data.runtime.aggregate
import dev.skillbill.desktop.core.data.runtime.readOrProblem
import dev.skillbill.desktop.core.data.runtime.toDomain
import dev.skillbill.desktop.core.data.runtime.toPort
import dev.skillbill.desktop.core.data.runtime.toState
import dev.skillbill.desktop.core.domain.model.ChangeBaseline
import dev.skillbill.desktop.core.domain.model.ChangeScope
import dev.skillbill.desktop.core.domain.model.ChangeScopeTarget
import dev.skillbill.desktop.core.domain.model.ChangeSet
import dev.skillbill.desktop.core.domain.model.ChangedFile
import dev.skillbill.desktop.core.domain.model.CommitRange
import dev.skillbill.desktop.core.domain.model.FileDiffState
import dev.skillbill.desktop.core.domain.model.ReadProblem
import dev.skillbill.desktop.core.domain.model.ScopeChangesState
import dev.skillbill.desktop.core.domain.model.ScopeRead
import dev.skillbill.desktop.core.domain.model.ScopeSubtask
import dev.skillbill.desktop.core.domain.model.ScopeSubtaskState
import dev.skillbill.desktop.core.domain.model.SubtaskScopesState
import dev.skillbill.desktop.core.domain.model.SubtaskStats
import dev.skillbill.desktop.core.domain.model.goalScopeTarget
import dev.skillbill.desktop.core.domain.model.resolveScope
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
import skillbill.ports.workchanges.model.WorkOutsideCommitsResult
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference

internal const val SCOPES_READ_FAILED = "desktop_work_change_scopes_read_failed"

class ChangeScopeObservations internal constructor(
  private val runtime: RuntimeComponent,
  private val diagnostics: Diagnostics,
  private val readSubtasks: suspend (String) -> WorkGoalScopesResult,
  private val port: WorkChangesPort = runtime.workChangesPort,
  private val io: CoroutineDispatcher = Dispatchers.IO,
  private val workingTree: WorkingTreeReads = WorkingTreeReads(),
) {
  private data class InterruptedStatsKey(
    val worktreeRoot: String,
    val subtaskId: Int,
    val startSha: String,
    val baselineUntrackedPaths: List<String>,
  )
  private data class StatsAttemptKey(
    val worktreeRoot: String,
    val subtaskId: Int,
    val startSha: String?,
    val endSha: String?,
    val state: ScopeSubtaskState,
    val baselineUntrackedPaths: List<String>,
  )
  private sealed interface IndividualStatsRead {
    data class Working(val baseline: ChangeBaseline.SubtaskStart) : IndividualStatsRead
    data class Committed(val scope: ScopeRead.Committed) : IndividualStatsRead
    data object Unavailable : IndividualStatsRead
  }
  private data class OutsideKey(val worktreeRoot: String, val targetBranch: String?, val ranges: List<CommitRange>)
  private class OutsideMemo(val key: OutsideKey, val state: ScopeChangesState.Listed)

  private val baseShas = ConcurrentHashMap<ListedBaseKey, String>()
  private val latestSubtaskStates = ConcurrentHashMap<String, SubtaskScopesState.Listed>()
  private val interruptedReadLock = Mutex()
  // Single-slot memo: Outside depends on HEAD, so it stays out of the app cache and is re-read only on a range change.
  private val outsideMemo = AtomicReference<OutsideMemo?>(null)
  private val diffs = ChangeScopeDiffs(runtime, diagnostics, port, io, baseShas, ::readAttributionStats) { target, ranges, lock ->
    readOutside(target, ranges, lock, useMemo = true)
  }

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
      // A non-running collection reads Outside fresh so commits landing after the last subtask show up.
      emit(readScope(target, resolved, lock, cache, useOutsideMemo = running))
      val followsHead =
        (resolved is ScopeRead.Baseline && resolved.baseline !is ChangeBaseline.CommittedBranchRange) ||
          resolved == ScopeRead.Uncommitted
      if (!running || !followsHead) break
      delay(CHANGE_REFRESH_INTERVAL)
    }
  }.distinctUntilChanged()

  fun observeSubtasks(
    goalWorkflowId: String,
    running: Boolean,
    lock: Mutex,
    cache: CommittedChangeCache,
  ): Flow<SubtaskScopesState> = flow {
    emit(SubtaskScopesState.Loading)
    val attempted = mutableSetOf<StatsAttemptKey>()
    val reportedFailures = mutableSetOf<StatsAttemptKey>()
    val interruptedStats = mutableMapOf<InterruptedStatsKey, SubtaskStats>()
    while (true) {
      val read = readOrProblem(runtime, diagnostics) { readSubtasks(goalWorkflowId) }
      val state = when (read) {
        is Outcome.Failure -> {
          diagnostics.record(SCOPES_READ_FAILED)
          SubtaskScopesState.Unavailable(read.error)
        }
        is Outcome.Success -> read.value.toState()
      }
      if (state is SubtaskScopesState.Unavailable && read is Outcome.Success) diagnostics.record(SCOPES_READ_FAILED)
      if (state !is SubtaskScopesState.Listed) {
        interruptedStats.clear()
        attempted.removeAll { it.state == ScopeSubtaskState.INTERRUPTED }
        reportedFailures.removeAll { it.state == ScopeSubtaskState.INTERRUPTED }
        latestSubtaskStates.remove(goalWorkflowId)
        emit(state)
      } else {
        val target = goalScopeTarget(goalWorkflowId, state)
        val currentInterruptedKeys = state.subtasks
          .filter {
            it.selectable && it.state == ScopeSubtaskState.INTERRUPTED &&
              individualStatsRead(target, it) is IndividualStatsRead.Working
          }
          .mapNotNull { subtask -> subtask.startSha?.let { interruptedStatsKey(target.worktreeRoot, subtask, it) } }
          .toSet()
        interruptedStats.keys.retainAll(currentInterruptedKeys)
        val currentInterruptedAttempts = state.subtasks
          .filter { it.selectable && it.state == ScopeSubtaskState.INTERRUPTED }
          .map { statsAttemptKey(target.worktreeRoot, it) }
          .toSet()
        attempted.removeAll { it.state == ScopeSubtaskState.INTERRUPTED && it !in currentInterruptedAttempts }
        reportedFailures.removeAll { it.state == ScopeSubtaskState.INTERRUPTED && it !in currentInterruptedAttempts }
        val stats = linkedMapOf<Int, SubtaskStats>()
        for (subtask in state.subtasks.filter { it.selectable }) {
          val selection = individualStatsRead(target, subtask)
          val cached = (selection as? IndividualStatsRead.Committed)?.scope
            ?.committedKey(target.worktreeRoot)?.let(cache::list)?.toStats()
          val working = (selection as? IndividualStatsRead.Working)?.baseline
            ?.takeIf { subtask.state == ScopeSubtaskState.INTERRUPTED }
            ?.let { baseline ->
              interruptedStats[interruptedStatsKey(target.worktreeRoot, subtask, baseline.baseSha)]
            }
          if (cached != null) stats[subtask.id] = cached else if (working != null) stats[subtask.id] = working
        }
        state.copy(stats = stats.toMap()).also { latestSubtaskStates[goalWorkflowId] = it }.let { emit(it) }
        val ordered = state.subtasks.filter { it.selectable }.let { items ->
          items.filter { it.state == ScopeSubtaskState.RUNNING } + items.filter { it.state != ScopeSubtaskState.RUNNING }.sortedBy { it.id }
        }
        for (subtask in ordered) {
          val readKey = statsAttemptKey(target.worktreeRoot, subtask)
          val selection = individualStatsRead(target, subtask)
          val capturedInterrupted = if (
            selection is IndividualStatsRead.Working && subtask.state == ScopeSubtaskState.INTERRUPTED
          ) {
            latestSubtaskStates[goalWorkflowId]?.takeIf { it.matches(target) }?.stats?.get(subtask.id)
          } else null
          if (capturedInterrupted != null) {
            stats[subtask.id] = capturedInterrupted
            state.copy(stats = stats.toMap()).also { latestSubtaskStates[goalWorkflowId] = it }.let { emit(it) }
            continue
          }
          if (subtask.state == ScopeSubtaskState.INTERRUPTED && stats.containsKey(subtask.id)) continue
          val listKey = (selection as? IndividualStatsRead.Committed)?.scope?.committedKey(target.worktreeRoot)
          if (subtask.state != ScopeSubtaskState.RUNNING && listKey != null && cache.list(listKey) != null) continue
          if (subtask.state != ScopeSubtaskState.RUNNING && readKey in attempted) continue
          val result = if (selection is IndividualStatsRead.Working && subtask.state == ScopeSubtaskState.INTERRUPTED) {
            readInterruptedStats(target, subtask, lock, cache)
          } else {
            readSubtaskStats(target, subtask, lock, cache)
          }
          if (result != null) {
            stats[subtask.id] = result
            if (subtask.state == ScopeSubtaskState.INTERRUPTED) {
              (selection as? IndividualStatsRead.Working)?.baseline?.let {
                interruptedStats[interruptedStatsKey(target.worktreeRoot, subtask, it.baseSha)] = result
              }
            }
          } else {
            attempted += readKey
            if (reportedFailures.add(readKey)) diagnostics.record(SCOPES_READ_FAILED)
          }
          state.copy(stats = stats.toMap()).also { latestSubtaskStates[goalWorkflowId] = it }.let { emit(it) }
        }
      }
      if (!running) break
      delay(CHANGE_REFRESH_INTERVAL)
    }
  }.distinctUntilChanged()

  private suspend fun readSubtaskStats(
    target: ChangeScopeTarget,
    subtask: ScopeSubtask,
    lock: Mutex,
    cache: CommittedChangeCache,
  ): SubtaskStats? {
    return when (val selection = individualStatsRead(target, subtask)) {
      is IndividualStatsRead.Working -> when (val read = readChanges(selection.baseline, lock)) {
        is Outcome.Success -> (read.value as? WorkChangeListResult.Listed)?.toChangeSet()?.toStats()
        is Outcome.Failure -> null
      }
      is IndividualStatsRead.Committed -> readCommittedList(target.worktreeRoot, selection.scope, lock, cache)?.toStats()
      IndividualStatsRead.Unavailable -> null
    }
  }

  private suspend fun readAttributionStats(
    target: ChangeScopeTarget,
    subtask: ScopeSubtask,
    lock: Mutex,
    cache: CommittedChangeCache,
  ): SubtaskStats? {
    val current = latestSubtaskStates[target.workId]?.takeIf { it.matches(target) }
    val snapshot = if (subtask.state == ScopeSubtaskState.INTERRUPTED) current?.stats?.get(subtask.id) else null
    val stats = snapshot ?: if (
      individualStatsRead(target, subtask) is IndividualStatsRead.Working &&
      subtask.state == ScopeSubtaskState.INTERRUPTED
    ) {
      readInterruptedStats(target, subtask, lock, cache)
    } else {
      readSubtaskStats(target, subtask, lock, cache)
    }
    if (stats == null) {
      diagnostics.record(SCOPES_READ_FAILED)
    }
    return stats
  }

  private suspend fun readInterruptedStats(
    target: ChangeScopeTarget,
    subtask: ScopeSubtask,
    lock: Mutex,
    cache: CommittedChangeCache,
  ): SubtaskStats? = interruptedReadLock.withLock {
    val selection = individualStatsRead(target, subtask)
    if (selection !is IndividualStatsRead.Working || subtask.state != ScopeSubtaskState.INTERRUPTED) {
      return@withLock null
    }
    val snapshot = latestSubtaskStates[target.workId]?.takeIf { it.matches(target) }?.stats?.get(subtask.id)
    if (snapshot != null) return@withLock snapshot
    val stats = readSubtaskStats(target, subtask, lock, cache) ?: return@withLock null
    latestSubtaskStates.compute(target.workId) { _, state ->
      when {
        state == null -> SubtaskScopesState.Listed(
          target.worktreeRoot,
          target.targetBranch(),
          target.rangesSupported,
          target.subtasks,
          mapOf(subtask.id to stats),
          target.committedHeadBranch(),
        )
        state.matches(target) -> state.copy(stats = state.stats + (subtask.id to stats))
        else -> SubtaskScopesState.Listed(
          target.worktreeRoot,
          target.targetBranch(),
          target.rangesSupported,
          target.subtasks,
          mapOf(subtask.id to stats),
          target.committedHeadBranch(),
        )
      }
    }
    stats
  }

  private suspend fun readScope(
    target: ChangeScopeTarget,
    scopeRead: ScopeRead,
    lock: Mutex,
    cache: CommittedChangeCache,
    useOutsideMemo: Boolean,
  ): ScopeChangesState = when (scopeRead) {
    is ScopeRead.Baseline -> when (val result = readChanges(scopeRead.baseline, lock)) {
      is Outcome.Failure -> unavailable(result.error)
      is Outcome.Success -> result.value.toScopeState().also { state ->
        if (state is ScopeChangesState.Listed) baseShas[ListedBaseKey(target.worktreeRoot, scopeRead)] = state.changes.baseSha
      }
    }
    ScopeRead.Uncommitted -> when (val result = git(lock) { port.uncommittedChanges(target.worktreeRoot) }) {
      is Outcome.Failure -> unavailable(result.error)
      is Outcome.Success -> result.value.toScopeState().also { state ->
        if (state is ScopeChangesState.Listed) baseShas[ListedBaseKey(target.worktreeRoot, scopeRead)] = state.changes.baseSha
      }
    }
    is ScopeRead.Committed -> {
      readCommittedState(target.worktreeRoot, scopeRead, lock, cache)
    }
    is ScopeRead.Outside -> readOutside(target, scopeRead.ranges, lock, useOutsideMemo)
    is ScopeRead.Unavailable -> ScopeChangesState.Unavailable(ReadProblem.Runtime(scopeRead.reason))
  }

  private suspend fun readCommittedList(
    worktreeRoot: String,
    scope: ScopeRead.Committed,
    lock: Mutex,
    cache: CommittedChangeCache,
  ): ChangeSet? {
    val key = CommittedListKey(worktreeRoot, scope.fromSha, scope.toSha)
    cache.list(key)?.let { return it }
    return when (val result = readOrProblem(runtime, diagnostics) {
      lock.withLock {
        cache.list(key) ?: withContext(io) {
          when (val value = port.committedChanges(worktreeRoot, scope.fromSha, scope.toSha)) {
            is WorkChangeListResult.Listed -> value.toChangeSet().also { cache.putList(key, it) }
            is WorkChangeListResult.BaseUnavailable, is WorkChangeListResult.NotARepository,
            is WorkChangeListResult.Failed -> null
          }
        }
      }
    }) {
      is Outcome.Failure -> null
      is Outcome.Success -> result.value
    }
  }

  private suspend fun readCommittedState(
    worktreeRoot: String,
    scope: ScopeRead.Committed,
    lock: Mutex,
    cache: CommittedChangeCache,
  ): ScopeChangesState {
    val key = CommittedListKey(worktreeRoot, scope.fromSha, scope.toSha)
    cache.list(key)?.let { return ScopeChangesState.Listed(it) }
    return when (val result = readOrProblem(runtime, diagnostics) {
      lock.withLock {
        cache.list(key)?.let { return@withLock ScopeChangesState.Listed(it) }
        withContext(io) {
          when (val value = port.committedChanges(worktreeRoot, scope.fromSha, scope.toSha)) {
            is WorkChangeListResult.Listed -> ScopeChangesState.Listed(value.toChangeSet()).also { cache.putList(key, it.changes) }
            is WorkChangeListResult.BaseUnavailable -> ScopeChangesState.BaseUnavailable(value.reason)
            is WorkChangeListResult.NotARepository -> ScopeChangesState.NotARepository(value.worktreeRoot)
            is WorkChangeListResult.Failed -> {
              diagnostics.record(SCOPES_READ_FAILED)
              ScopeChangesState.Unavailable(ReadProblem.Runtime(value.message))
            }
          }
        }
      }
    }) {
      is Outcome.Failure -> unavailable(result.error)
      is Outcome.Success -> result.value
    }
  }

  private suspend fun readOutside(
    target: ChangeScopeTarget,
    ranges: List<CommitRange>,
    lock: Mutex,
    useMemo: Boolean,
  ): ScopeChangesState {
    val key = OutsideKey(target.worktreeRoot, target.targetBranch(), ranges)
    if (useMemo) outsideMemo.get()?.takeIf { it.key == key }?.let { return it.state }
    val result = git(lock) { port.outsideCommits(target.worktreeRoot, target.targetBranch(), ranges.map { it.toPort() }) }
    return when (result) {
      is Outcome.Failure -> unavailable(result.error)
      is Outcome.Success -> when (val value = result.value) {
        is WorkOutsideCommitsResult.Listed -> ScopeChangesState.Listed(
          value.commits.aggregate(value.mergeBaseSha), value.commits.map { it.toDomain() },
        ).also { outsideMemo.set(OutsideMemo(key, it)) }
        is WorkOutsideCommitsResult.BaseUnavailable -> ScopeChangesState.BaseUnavailable(value.reason)
        is WorkOutsideCommitsResult.NotARepository -> ScopeChangesState.NotARepository(value.worktreeRoot)
        is WorkOutsideCommitsResult.TooMany -> ScopeChangesState.TooManyCommits(value.limit)
        is WorkOutsideCommitsResult.Failed -> {
          diagnostics.record(SCOPES_READ_FAILED)
          ScopeChangesState.Unavailable(ReadProblem.Runtime(value.message))
        }
      }
    }
  }

  private fun interruptedStatsKey(worktreeRoot: String, subtask: ScopeSubtask, startSha: String) =
    InterruptedStatsKey(worktreeRoot, subtask.id, startSha, subtask.baselineUntrackedPaths.toList())

  private fun statsAttemptKey(worktreeRoot: String, subtask: ScopeSubtask) = StatsAttemptKey(
    worktreeRoot,
    subtask.id,
    subtask.startSha,
    subtask.endSha,
    subtask.state,
    subtask.baselineUntrackedPaths.toList(),
  )

  private fun individualStatsRead(target: ChangeScopeTarget, subtask: ScopeSubtask): IndividualStatsRead {
    val startSha = subtask.startSha ?: return IndividualStatsRead.Unavailable
    val endSha = subtask.endSha
    val baseline = ChangeBaseline.SubtaskStart(target.worktreeRoot, startSha, subtask.baselineUntrackedPaths)
    if (target.committedOnly) {
      return endSha?.let { IndividualStatsRead.Committed(ScopeRead.Committed(startSha, it)) }
        ?: IndividualStatsRead.Unavailable
    }
    return when {
      subtask.state == ScopeSubtaskState.RUNNING -> IndividualStatsRead.Working(baseline)
      endSha != null -> IndividualStatsRead.Committed(ScopeRead.Committed(startSha, endSha))
      subtask.state == ScopeSubtaskState.INTERRUPTED -> IndividualStatsRead.Working(baseline)
      else -> IndividualStatsRead.Unavailable
    }
  }

  private suspend fun readChanges(baseline: ChangeBaseline, lock: Mutex): Outcome<WorkChangeListResult, ReadProblem> {
    val portBaseline = baseline.toPort()
    return readOrProblem(runtime, diagnostics) {
      val read: suspend () -> WorkChangeListResult = { lock.withLock { withContext(io) { port.changes(portBaseline) } } }
      if (baseline is ChangeBaseline.CommittedBranchRange) read() else workingTree.changes(portBaseline, read)
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

  private fun WorkChangeListResult.Listed.toChangeSet(): ChangeSet =
    ChangeSet(baseSha, files.map { it.toDomain() }, insertions, deletions)

  private fun ChangeSet.toStats(): SubtaskStats = SubtaskStats(files, insertions, deletions)

  private fun ScopeRead.committedKey(worktreeRoot: String): CommittedListKey? =
    (this as? ScopeRead.Committed)?.let { CommittedListKey(worktreeRoot, it.fromSha, it.toSha) }

  private fun ChangeScopeTarget.targetBranch(): String? =
    when (val baseline = allBaseline) {
      is ChangeBaseline.TargetMergeBase -> baseline.targetBranch
      is ChangeBaseline.CommittedBranchRange -> baseline.targetBranch
      else -> null
    }

  private fun ChangeScopeTarget.committedHeadBranch(): String? =
    (allBaseline as? ChangeBaseline.CommittedBranchRange)?.headBranch

  private fun SubtaskScopesState.Listed.matches(target: ChangeScopeTarget): Boolean =
    worktreeRoot == target.worktreeRoot && targetBranch == target.targetBranch() &&
      rangesSupported == target.rangesSupported && subtasks == target.subtasks
      && committedHeadBranch == target.committedHeadBranch()

}
