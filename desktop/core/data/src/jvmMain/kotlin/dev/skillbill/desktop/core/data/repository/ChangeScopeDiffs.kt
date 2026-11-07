package dev.skillbill.desktop.core.data.repository

import dev.skillbill.desktop.core.common.Diagnostics
import dev.skillbill.desktop.core.common.Outcome
import dev.skillbill.desktop.core.data.runtime.fileIdentityHistory
import dev.skillbill.desktop.core.data.runtime.parseUnifiedDiff
import dev.skillbill.desktop.core.data.runtime.readOrProblem
import dev.skillbill.desktop.core.data.runtime.toDomain
import dev.skillbill.desktop.core.data.runtime.toPort
import dev.skillbill.desktop.core.domain.model.ChangeBaseline
import dev.skillbill.desktop.core.domain.model.ChangeScope
import dev.skillbill.desktop.core.domain.model.ChangeScope.Subtasks
import dev.skillbill.desktop.core.domain.model.ChangeScopeTarget
import dev.skillbill.desktop.core.domain.model.ChangeSet
import dev.skillbill.desktop.core.domain.model.ChangedFile
import dev.skillbill.desktop.core.domain.model.CommitChanges
import dev.skillbill.desktop.core.domain.model.ScopeCommit
import dev.skillbill.desktop.core.domain.model.FileDiff
import dev.skillbill.desktop.core.domain.model.FileDiffState
import dev.skillbill.desktop.core.domain.model.ReadProblem
import dev.skillbill.desktop.core.domain.model.ScopeChangesState
import dev.skillbill.desktop.core.domain.model.ScopeRead
import dev.skillbill.desktop.core.domain.model.ScopeHunkOwner
import dev.skillbill.desktop.core.domain.model.attributeHunks
import dev.skillbill.desktop.core.domain.model.hunkOwnerLabel
import dev.skillbill.desktop.core.domain.model.resolveScope
import dev.skillbill.desktop.core.domain.model.scopeHunkGroups
import dev.skillbill.desktop.core.domain.model.scopeCommits
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
  private val readPerCommit: suspend (ChangeScopeTarget, List<ScopeCommit>, Mutex, CommittedChangeCache) -> ScopeChangesState,
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
          is Outcome.Success ->
            when (val baseline = resolved.baseline) {
              is ChangeBaseline.CommittedBranchRange -> readBranchRangePatch(target.worktreeRoot, base.value, baseline, file, lock)
              is ChangeBaseline.CommittedRevisionRange -> readCommittedPatch(target.worktreeRoot, baseline.fromSha, baseline.toSha, file, lock, cache)
              else -> readWorkingPatch(target.worktreeRoot, base.value, file, lock)
            }
        }
      }
      ScopeRead.Uncommitted -> {
        when (val base = uncommittedBase(ScopeRead.Uncommitted, target.worktreeRoot, lock)) {
          is Outcome.Failure -> base.error
          is Outcome.Success -> readWorkingPatch(target.worktreeRoot, base.value, file, lock)
        }
      }
      is ScopeRead.Committed -> readCommittedPatch(target.worktreeRoot, resolved.fromSha, resolved.toSha, file, lock, cache)
      is ScopeRead.PerCommit -> {
        val baseline = runningWorkingBaseline(target, scope)
        if (baseline == null) perCommitFileDiff(target, resolved.commits, file, lock, cache)
        else perCommitFileDiffWithWorking(target, resolved.commits, baseline, file, lock, cache)
      }
      is ScopeRead.Unavailable -> FileDiffState.Unavailable(ReadProblem.Runtime(resolved.reason))
    }
    if ((scope is Subtasks || scope is ChangeScope.Commits) && result is FileDiffState.Shown &&
      (resolved is ScopeRead.Committed || resolved is ScopeRead.Baseline)
    ) {
      return attributeRangeDiff(target, scope, file, result.diff, lock, cache)
    }
    return result
  }

  private suspend fun baselineFileBase(worktreeRoot: String, scope: ScopeRead.Baseline, lock: Mutex): Outcome<String, FileDiffState> {
    when (val baseline = scope.baseline) {
      is ChangeBaseline.SubtaskStart -> return Outcome.Success(baseline.baseSha)
      is ChangeBaseline.RunStart -> return Outcome.Success(baseline.baseSha)
      is ChangeBaseline.TargetMergeBase -> Unit
      is ChangeBaseline.CommittedBranchRange ->
        return when (val result = readChanges(scope.baseline, lock)) {
          is Outcome.Failure -> Outcome.Failure(FileDiffState.Unavailable(result.error)).also { diagnostics.record(SCOPES_READ_FAILED) }
          is Outcome.Success -> result.value.toFileDiffBase()
        }
      is ChangeBaseline.CommittedRevisionRange -> return Outcome.Success(baseline.fromSha)
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

  private suspend fun readBranchRangePatch(
    worktreeRoot: String,
    baseSha: String,
    baseline: ChangeBaseline.CommittedBranchRange,
    file: ChangedFile,
    lock: Mutex,
  ): FileDiffState =
    when (val result = git(lock) {
      port.committedFileDiff(worktreeRoot, baseSha, baseline.headRef, file.toPort()).toFileDiffState()
    }) {
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

  private suspend fun perCommitFileDiff(
    target: ChangeScopeTarget,
    commits: List<ScopeCommit>,
    file: ChangedFile,
    lock: Mutex,
    cache: CommittedChangeCache,
    latestFiles: List<ChangedFile> = emptyList(),
  ): FileDiffState {
    val perCommit = readPerCommit(target, commits, lock, cache)
    if (perCommit is ScopeChangesState.TooManyCommits) {
      return FileDiffState.Unavailable(ReadProblem.Runtime("More than ${perCommit.limit} commits to read one by one."))
    }
    if (perCommit !is ScopeChangesState.Listed) return perCommit.toFileDiffState()
    val matching = perCommit.perCommit.fileIdentityHistory(latestFiles).changes
      .filter { it.identityPath == file.path }
      .map { it.commit to it.file }
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

  private suspend fun perCommitFileDiffWithWorking(
    target: ChangeScopeTarget,
    commits: List<ScopeCommit>,
    baseline: ChangeBaseline.SubtaskStart,
    file: ChangedFile,
    lock: Mutex,
    cache: CommittedChangeCache,
  ): FileDiffState {
    val workingFiles = when (val result = git(lock) { port.changes(baseline.toPort()) }) {
      is Outcome.Failure -> return FileDiffState.Unavailable(result.error)
      is Outcome.Success -> when (val listed = result.value) {
        is WorkChangeListResult.Listed -> listed.files.map { it.toDomain() }
        is WorkChangeListResult.BaseUnavailable -> return FileDiffState.BaseUnavailable(listed.reason)
        is WorkChangeListResult.NotARepository -> return FileDiffState.Unavailable(ReadProblem.Runtime("${listed.worktreeRoot} is not a repository."))
        is WorkChangeListResult.Failed -> {
          diagnostics.record(SCOPES_READ_FAILED)
          return FileDiffState.Unavailable(ReadProblem.Runtime(listed.message))
        }
      }
    }
    val committed = perCommitFileDiff(target, commits, file, lock, cache, workingFiles)
    if (committed !is FileDiffState.Shown) return committed
    return when (val working = readWorkingPatch(target.worktreeRoot, baseline.baseSha, file, lock)) {
      is FileDiffState.Shown -> FileDiffState.Shown(
        FileDiff(
          committed.diff.hunks + working.diff.hunks,
          committed.diff.insertions + working.diff.insertions,
          committed.diff.deletions + working.diff.deletions,
        ),
      )
      else -> working
    }
  }

  private suspend fun readCommittedFileList(
    worktreeRoot: String,
    fromSha: String,
    toSha: String,
    lock: Mutex,
    cache: CommittedChangeCache,
  ): Outcome<ChangeSet, FileDiffState> {
    val key = CommittedListKey(worktreeRoot, fromSha, toSha)
    cache.list(key)?.let { return Outcome.Success(it) }
    return when (val result = git(lock) { port.committedChanges(worktreeRoot, fromSha, toSha) }) {
      is Outcome.Failure -> Outcome.Failure(FileDiffState.Unavailable(result.error))
      is Outcome.Success -> when (val listed = result.value) {
        is WorkChangeListResult.Listed -> {
          val changeSet = ChangeSet(listed.baseSha, listed.files.map { it.toDomain() }, listed.insertions, listed.deletions)
          cache.putList(key, changeSet)
          Outcome.Success(changeSet)
        }
        is WorkChangeListResult.BaseUnavailable -> Outcome.Failure(FileDiffState.BaseUnavailable(listed.reason))
        is WorkChangeListResult.NotARepository -> Outcome.Failure(FileDiffState.Unavailable(ReadProblem.Runtime("${listed.worktreeRoot} is not a repository.")))
        is WorkChangeListResult.Failed -> Outcome.Failure(FileDiffState.Unavailable(ReadProblem.Runtime(listed.message)))
      }
    }
  }

  private suspend fun attributeRangeDiff(
    target: ChangeScopeTarget,
    scope: ChangeScope,
    file: ChangedFile,
    diff: FileDiff,
    lock: Mutex,
    cache: CommittedChangeCache,
  ): FileDiffState {
    val groups = if (scope is Subtasks) scopeHunkGroups(target, scope) else {
      scopeCommits(target, scope).groupBy { commit ->
        if (commit.operator) ScopeHunkOwner.OperatorCommit(commit.sha) else ScopeHunkOwner.Subtasks(commit.ownerIds.sorted())
      }.map { (owner, commits) -> dev.skillbill.desktop.core.domain.model.ScopeHunkGroup(owner, commits, false) }
    }
    if (groups.size < 2) return FileDiffState.Shown(diff)
    fun failedRead(): FileDiffState {
      diagnostics.record(SCOPES_READ_FAILED)
      return FileDiffState.Shown(diff)
    }
    if (groups.sumOf { it.commits.size } > PER_COMMIT_READ_LIMIT) return failedRead()
    val perOwner = linkedMapOf<ScopeHunkOwner, FileDiff>()
    val groupedShas = groups.flatMap { it.commits }.map(ScopeCommit::sha).toSet()
    val orderedCommits = target.timeline?.commits?.filter { it.sha in groupedShas }
      ?: groups.flatMap { it.commits }
    val committed = mutableListOf<CommitChanges>()
    for (commit in orderedCommits) {
      when (val listed = readCommittedFileList(target.worktreeRoot, commit.parentSha, commit.sha, lock, cache)) {
        is Outcome.Failure -> return failedRead()
        is Outcome.Success -> committed += CommitChanges(commit, listed.value.files)
      }
    }
    val identityHistory = committed.fileIdentityHistory().changes
    for (group in groups) {
      val patches = mutableListOf<FileDiff>()
      val groupShas = group.commits.map(ScopeCommit::sha).toSet()
      val entries = identityHistory.filter { it.commit.sha in groupShas && it.identityPath == file.path }
      for ((commit, entry) in entries.map { it.commit to it.file }) {
        when (val patch = readCommittedPatch(target.worktreeRoot, commit.parentSha, commit.sha, entry, lock, cache)) {
          is FileDiffState.Shown -> patches += patch.diff
          is FileDiffState.Unavailable -> return FileDiffState.Shown(diff)
          else -> return failedRead()
        }
      }
      if (group.workingTree) {
        val headSha = target.timeline?.headSha ?: return failedRead()
        when (val working = readWorkingPatch(target.worktreeRoot, headSha, file, lock)) {
          is FileDiffState.Shown -> patches += working.diff
          is FileDiffState.Unavailable -> return FileDiffState.Shown(diff)
          else -> return failedRead()
        }
      }
      if (patches.isEmpty()) continue
      val merged = FileDiff(
        patches.flatMap { it.hunks },
        patches.sumOf { it.insertions },
        patches.sumOf { it.deletions },
      )
      if (merged.hunks.isNotEmpty()) perOwner[group.owner] = merged
    }
    if (perOwner.isEmpty()) return FileDiffState.Shown(diff)
    if (perOwner.size == 1) {
      val label = hunkOwnerLabel(perOwner.keys.single(), target.subtasks)
      return FileDiffState.Shown(diff.copy(hunks = diff.hunks.map { it.copy(source = label) }))
    }
    val owners = attributeHunks(diff, perOwner)
    return FileDiffState.Shown(diff.copy(hunks = diff.hunks.mapIndexed { index, hunk ->
      owners[index]?.let { owner -> hunk.copy(source = hunkOwnerLabel(owner, target.subtasks)) } ?: hunk
    }))
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
    is ScopeChangesState.TooManyCommits -> FileDiffState.Unavailable(ReadProblem.Runtime("More than $limit commits to read one by one."))
    else -> FileDiffState.Unavailable(ReadProblem.Runtime("Unable to read the commits one by one."))
  }

  private suspend fun readChanges(baseline: ChangeBaseline, lock: Mutex): Outcome<WorkChangeListResult, ReadProblem> =
    if (baseline is ChangeBaseline.CommittedRevisionRange) {
      git(lock) { port.committedChanges(baseline.worktreeRoot, baseline.fromSha, baseline.toSha) }
    } else {
      git(lock) { port.changes(baseline.toPort()) }
    }

  private suspend fun <T> git(lock: Mutex, block: () -> T): Outcome<T, ReadProblem> = readOrProblem(runtime, diagnostics) {
    lock.withLock { withContext(io) { block() } }
  }
}
