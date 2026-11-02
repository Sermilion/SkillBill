package dev.skillbill.desktop.core.data.repository

import dev.skillbill.desktop.core.common.Diagnostics
import dev.skillbill.desktop.core.common.Outcome
import dev.skillbill.desktop.core.data.runtime.parseUnifiedDiff
import dev.skillbill.desktop.core.data.runtime.readOrProblem
import dev.skillbill.desktop.core.data.runtime.toPort
import dev.skillbill.desktop.core.domain.model.ChangeBaseline
import dev.skillbill.desktop.core.domain.model.ChangeScope
import dev.skillbill.desktop.core.domain.model.ChangeScope.Subtasks
import dev.skillbill.desktop.core.domain.model.ChangeScopeTarget
import dev.skillbill.desktop.core.domain.model.ChangedFile
import dev.skillbill.desktop.core.domain.model.CommitRange
import dev.skillbill.desktop.core.domain.model.FileDiff
import dev.skillbill.desktop.core.domain.model.FileDiffState
import dev.skillbill.desktop.core.domain.model.ReadProblem
import dev.skillbill.desktop.core.domain.model.ScopeChangesState
import dev.skillbill.desktop.core.domain.model.ScopeRead
import dev.skillbill.desktop.core.domain.model.ScopeSubtask
import dev.skillbill.desktop.core.domain.model.ScopeSubtaskState
import dev.skillbill.desktop.core.domain.model.SubtaskStats
import dev.skillbill.desktop.core.domain.model.attributeHunks
import dev.skillbill.desktop.core.domain.model.resolveScope
import dev.skillbill.desktop.core.domain.model.scopeSubtaskLabel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import skillbill.di.core.RuntimeComponent
import skillbill.ports.workchanges.WorkChangesPort
import skillbill.ports.workchanges.model.WorkChangeListResult
import skillbill.ports.workchanges.model.WorkFileDiffResult
import java.util.concurrent.ConcurrentHashMap

internal data class ListedBaseKey(val worktreeRoot: String, val scope: ScopeRead)

internal class ChangeScopeDiffs(
  private val runtime: RuntimeComponent,
  private val diagnostics: Diagnostics,
  private val port: WorkChangesPort,
  private val io: CoroutineDispatcher,
  private val baseShas: ConcurrentHashMap<ListedBaseKey, String>,
  private val readAttributionStats: suspend (ChangeScopeTarget, ScopeSubtask, Mutex, CommittedChangeCache) -> SubtaskStats?,
  private val readOutside: suspend (ChangeScopeTarget, List<CommitRange>, Mutex) -> ScopeChangesState,
) {
  suspend fun fileDiff(
    target: ChangeScopeTarget,
    scope: ChangeScope,
    file: ChangedFile,
    lock: Mutex,
    cache: CommittedChangeCache,
  ): FileDiffState {
    val resolved = resolveScope(target, scope)
    val result = when (resolved) {
      is ScopeRead.Baseline -> {
        when (val base = baselineFileBase(target.worktreeRoot, resolved, lock)) {
          is Outcome.Failure -> base.error
          is Outcome.Success -> readWorkingPatch(target.worktreeRoot, base.value, file, lock)
        }
      }
      ScopeRead.Uncommitted -> {
        when (val base = uncommittedBase(ScopeRead.Uncommitted, target.worktreeRoot, lock)) {
          is Outcome.Failure -> base.error
          is Outcome.Success -> readWorkingPatch(target.worktreeRoot, base.value, file, lock)
        }
      }
      is ScopeRead.Committed -> readCommittedPatch(target.worktreeRoot, resolved.fromSha, resolved.toSha, file, lock, cache)
      is ScopeRead.Outside -> outsideFileDiff(target, resolved.ranges, file, lock, cache)
      is ScopeRead.Unavailable -> FileDiffState.Unavailable(ReadProblem.Runtime(resolved.reason))
    }
    if (scope is Subtasks && scope.to > scope.from && result is FileDiffState.Shown) {
      return attributeRangeDiff(target, scope, file, result.diff, lock, cache)
    }
    return result
  }

  private suspend fun baselineFileBase(worktreeRoot: String, scope: ScopeRead.Baseline, lock: Mutex): Outcome<String, FileDiffState> {
    when (val baseline = scope.baseline) {
      is ChangeBaseline.SubtaskStart -> return Outcome.Success(baseline.baseSha)
      is ChangeBaseline.RunStart -> return Outcome.Success(baseline.baseSha)
      is ChangeBaseline.TargetMergeBase -> Unit
    }
    val key = ListedBaseKey(worktreeRoot, scope)
    baseShas[key]?.let { return Outcome.Success(it) }
    return when (val result = readChanges(scope.baseline, lock)) {
      is Outcome.Failure -> Outcome.Failure(FileDiffState.Unavailable(result.error)).also { diagnostics.record(SCOPES_READ_FAILED) }
      is Outcome.Success -> result.value.toFileDiffBase().also { base ->
        if (base is Outcome.Success) baseShas[key] = base.value
      }
    }
  }

  private suspend fun uncommittedBase(
    scope: ScopeRead.Uncommitted,
    worktreeRoot: String,
    lock: Mutex,
  ): Outcome<String, FileDiffState> {
    val key = ListedBaseKey(worktreeRoot, scope)
    baseShas[key]?.let { return Outcome.Success(it) }
    return when (val result = git(lock) { port.uncommittedChanges(worktreeRoot) }) {
      is Outcome.Failure -> Outcome.Failure(FileDiffState.Unavailable(result.error)).also { diagnostics.record(SCOPES_READ_FAILED) }
      is Outcome.Success -> result.value.toFileDiffBase().also { base ->
        if (base is Outcome.Success) baseShas[key] = base.value
      }
    }
  }

  private fun WorkChangeListResult.toFileDiffBase(): Outcome<String, FileDiffState> = when (this) {
    is WorkChangeListResult.Listed -> Outcome.Success(baseSha)
    is WorkChangeListResult.BaseUnavailable -> Outcome.Failure(FileDiffState.BaseUnavailable(reason))
    is WorkChangeListResult.NotARepository -> Outcome.Failure(FileDiffState.Unavailable(ReadProblem.Runtime("$worktreeRoot is not a repository.")))
    is WorkChangeListResult.Failed -> {
      diagnostics.record(SCOPES_READ_FAILED)
      Outcome.Failure(FileDiffState.Unavailable(ReadProblem.Runtime(message)))
    }
  }

  private suspend fun readWorkingPatch(worktreeRoot: String, baseSha: String, file: ChangedFile, lock: Mutex): FileDiffState =
    when (val result = git(lock) { port.fileDiff(worktreeRoot, baseSha, file.toPort()).toFileDiffState() }) {
      is Outcome.Failure -> FileDiffState.Unavailable(result.error).also { diagnostics.record(SCOPES_READ_FAILED) }
      is Outcome.Success -> result.value.also { state ->
        if (state is FileDiffState.Unavailable) diagnostics.record(SCOPES_READ_FAILED)
      }
    }

  private suspend fun readCommittedPatch(
    worktreeRoot: String,
    fromSha: String,
    toSha: String,
    file: ChangedFile,
    lock: Mutex,
    cache: CommittedChangeCache,
  ): FileDiffState {
    val key = CommittedPatchKey(worktreeRoot, fromSha, toSha, file.path, file.oldPath)
    cache.patch(key)?.let { return it }
    val result = readOrProblem(runtime, diagnostics) {
      lock.withLock {
        cache.patch(key)?.let { return@withLock it }
        withContext(io) {
          val state = port.committedFileDiff(worktreeRoot, fromSha, toSha, file.toPort()).toFileDiffState()
          cache.putPatch(key, state)
          state
        }
      }
    }
    return when (result) {
      is Outcome.Failure -> FileDiffState.Unavailable(result.error).also { diagnostics.record(SCOPES_READ_FAILED) }
      is Outcome.Success -> result.value.also { state ->
        if (state is FileDiffState.Unavailable) diagnostics.record(SCOPES_READ_FAILED)
      }
    }
  }

  private suspend fun outsideFileDiff(
    target: ChangeScopeTarget,
    ranges: List<CommitRange>,
    file: ChangedFile,
    lock: Mutex,
    cache: CommittedChangeCache,
  ): FileDiffState {
    val outside = readOutside(target, ranges, lock)
    if (outside is ScopeChangesState.TooManyCommits) {
      return FileDiffState.Unavailable(ReadProblem.Runtime("More than ${outside.limit} commits outside subtasks."))
    }
    if (outside !is ScopeChangesState.Listed) return outside.toFileDiffState()
    val matching = outside.outsideCommits.mapNotNull { commit ->
      commit.files.firstOrNull { entry ->
        entry.path == file.path || entry.path == file.oldPath || entry.oldPath == file.path
      }?.let { commit to it }
    }
    if (matching.isEmpty()) return FileDiffState.Shown(FileDiff(emptyList(), 0, 0))
    val patches = matching.map { (commit, entry) ->
      commit to readCommittedPatch(target.worktreeRoot, commit.parentSha, commit.sha, entry, lock, cache)
    }
    patches.firstNotNullOfOrNull { (_, state) ->
      when (state) {
        is FileDiffState.BaseUnavailable, is FileDiffState.Unavailable -> state
        else -> null
      }
    }?.let { return it }
    patches.firstNotNullOfOrNull { (_, state) -> state as? FileDiffState.TooLarge }?.let { return it }
    if (patches.all { it.second == FileDiffState.Binary }) return FileDiffState.Binary
    val shown = patches.mapNotNull { (commit, state) ->
      (state as? FileDiffState.Shown)?.diff?.let { commit to it }
    }
    return FileDiffState.Shown(
      FileDiff(
        shown.flatMap { (commit, diff) -> diff.hunks.map { it.copy(source = commit.sha.take(7)) } },
        shown.sumOf { it.second.insertions },
        shown.sumOf { it.second.deletions },
      ),
    )
  }

  private suspend fun attributeRangeDiff(
    target: ChangeScopeTarget,
    scope: ChangeScope.Subtasks,
    file: ChangedFile,
    diff: FileDiff,
    lock: Mutex,
    cache: CommittedChangeCache,
  ): FileDiffState {
    val subtasks = target.subtasks.filter { it.selectable && it.id in scope.from..scope.to }
    val ordered = subtasks.filter { it.state == ScopeSubtaskState.RUNNING } +
      subtasks.filter { it.state != ScopeSubtaskState.RUNNING }.sortedBy { it.id }
    val stats = linkedMapOf<Int, SubtaskStats>()
    for (subtask in ordered) {
      val subtaskStats = readAttributionStats(target, subtask, lock, cache) ?: return FileDiffState.Shown(diff)
      stats[subtask.id] = subtaskStats
    }
    val paths = setOfNotNull(file.path, file.oldPath)
    val ids = stats.filterValues { subtaskStats ->
      subtaskStats.files.any { entry -> paths.any { it == entry.path || it == entry.oldPath } }
    }.keys.sorted()
    if (ids.isEmpty()) return FileDiffState.Shown(diff)
    if (ids.size == 1) {
      val owner = target.subtasks.firstOrNull { it.id == ids.single() } ?: return FileDiffState.Shown(diff)
      return FileDiffState.Shown(diff.copy(hunks = diff.hunks.map { it.copy(source = scopeSubtaskLabel(owner)) }))
    }
    val ownDiffs = linkedMapOf<Int, FileDiff>()
    for (id in ids) {
      val ownFile = stats[id]?.files?.firstOrNull { entry ->
        paths.any { it == entry.path || it == entry.oldPath }
      } ?: file
      val ownScope = ChangeScope.Subtasks(id, id)
      val own = fileDiffWithoutAttribution(target, ownScope, ownFile, lock, cache)
      if (own !is FileDiffState.Shown) return FileDiffState.Shown(diff)
      ownDiffs[id] = own.diff
    }
    val owners = attributeHunks(diff, ownDiffs)
    val names = target.subtasks.associateBy { it.id }
    return FileDiffState.Shown(diff.copy(hunks = diff.hunks.mapIndexed { index, hunk ->
      owners[index]?.let(names::get)?.let { hunk.copy(source = scopeSubtaskLabel(it)) } ?: hunk
    }))
  }

  private suspend fun fileDiffWithoutAttribution(
    target: ChangeScopeTarget,
    scope: ChangeScope,
    file: ChangedFile,
    lock: Mutex,
    cache: CommittedChangeCache,
  ): FileDiffState {
    if (scope is Subtasks && scope.from == scope.to) {
      val subtask = target.subtasks.firstOrNull { it.id == scope.from }
      val subtaskRead = resolveScope(target, scope)
      val workingSubtask = subtask?.takeIf {
        it.state == ScopeSubtaskState.RUNNING ||
          (it.state == ScopeSubtaskState.INTERRUPTED && subtaskRead !is ScopeRead.Committed)
      }
      if (workingSubtask != null) {
        val startSha = workingSubtask.startSha ?: return FileDiffState.Unavailable(ReadProblem.Runtime("no end commit"))
        return readWorkingPatch(
          target.worktreeRoot,
          startSha,
          file,
          lock,
        )
      }
    }
    return when (val read = resolveScope(target, scope)) {
      is ScopeRead.Committed -> readCommittedPatch(target.worktreeRoot, read.fromSha, read.toSha, file, lock, cache)
      is ScopeRead.Baseline -> when (val base = baselineFileBase(target.worktreeRoot, read, lock)) {
        is Outcome.Failure -> base.error
        is Outcome.Success -> readWorkingPatch(target.worktreeRoot, base.value, file, lock)
      }
      else -> FileDiffState.Unavailable(ReadProblem.Runtime("scope not available"))
    }
  }

  private fun WorkFileDiffResult.toFileDiffState(): FileDiffState = when (this) {
    is WorkFileDiffResult.Patch -> FileDiffState.Shown(parseUnifiedDiff(text))
    WorkFileDiffResult.Binary -> FileDiffState.Binary
    is WorkFileDiffResult.TooLarge -> FileDiffState.TooLarge(limitBytes)
    is WorkFileDiffResult.BaseUnavailable -> FileDiffState.BaseUnavailable(reason)
    is WorkFileDiffResult.Failed -> FileDiffState.Unavailable(ReadProblem.Runtime(message))
  }

  private fun ScopeChangesState.toFileDiffState(): FileDiffState = when (this) {
    is ScopeChangesState.BaseUnavailable -> FileDiffState.BaseUnavailable(reason)
    is ScopeChangesState.NotARepository -> FileDiffState.Unavailable(ReadProblem.Runtime("${worktreeRoot} is not a repository."))
    is ScopeChangesState.Unavailable -> FileDiffState.Unavailable(problem)
    is ScopeChangesState.TooManyCommits -> FileDiffState.Unavailable(ReadProblem.Runtime("More than $limit commits outside subtasks."))
    else -> FileDiffState.Unavailable(ReadProblem.Runtime("Unable to read outside commits."))
  }
  private suspend fun readChanges(baseline: ChangeBaseline, lock: Mutex): Outcome<WorkChangeListResult, ReadProblem> =
    git(lock) { port.changes(baseline.toPort()) }

  private suspend fun <T> git(lock: Mutex, block: () -> T): Outcome<T, ReadProblem> = readOrProblem(runtime, diagnostics) {
    lock.withLock { withContext(io) { block() } }
  }
}
