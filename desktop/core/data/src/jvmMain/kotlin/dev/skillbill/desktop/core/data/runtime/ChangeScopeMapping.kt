package dev.skillbill.desktop.core.data.runtime

import dev.skillbill.desktop.core.domain.model.ChangeScopeTimeline
import dev.skillbill.desktop.core.domain.model.ChangeSet
import dev.skillbill.desktop.core.domain.model.ChangedFile
import dev.skillbill.desktop.core.domain.model.CommitChanges
import dev.skillbill.desktop.core.domain.model.ReadProblem
import dev.skillbill.desktop.core.domain.model.ScopeCommit
import dev.skillbill.desktop.core.domain.model.ScopeSubtask
import dev.skillbill.desktop.core.domain.model.ScopeSubtaskState
import dev.skillbill.desktop.core.domain.model.SubtaskScopesState
import dev.skillbill.desktop.core.domain.model.TimelineProblem
import skillbill.ports.workchanges.model.WorkGoalScopesResult
import skillbill.ports.workchanges.model.WorkGoalTimelineResult
import skillbill.ports.workchanges.model.WorkScopeSubtask
import skillbill.workflow.model.DecompositionStatus

internal fun WorkScopeSubtask.toDomain(): ScopeSubtask = ScopeSubtask(
  id = id,
  name = name,
  state = if (running) ScopeSubtaskState.RUNNING else when (status) {
    DecompositionStatus.COMPLETE, DecompositionStatus.COMPLETED_NO_CHANGE -> ScopeSubtaskState.DONE
    DecompositionStatus.IN_PROGRESS, DecompositionStatus.BLOCKED -> ScopeSubtaskState.INTERRUPTED
    DecompositionStatus.PENDING, DecompositionStatus.SKIPPED -> ScopeSubtaskState.NOT_STARTED
  },
  recordedSha = recordedSha,
  reviewBaseSha = reviewBaseSha,
  commitShas = emptyList(),
  changedAfterFinish = false,
  completedNoChange = status == DecompositionStatus.COMPLETED_NO_CHANGE,
  baselineUntrackedPaths = baselineUntrackedPaths,
)

internal fun WorkGoalScopesResult.toState(): SubtaskScopesState = when (this) {
  is WorkGoalScopesResult.Listed -> SubtaskScopesState.Listed(
    worktreeRoot, targetBranch, rangesSupported, subtasks.map { it.toDomain() }, emptyMap(), committedHeadBranch,
  )
  is WorkGoalScopesResult.NotAGoal -> SubtaskScopesState.NotAGoal
  is WorkGoalScopesResult.Unavailable -> SubtaskScopesState.Unavailable(ReadProblem.Runtime(reason))
}

internal fun SubtaskScopesState.Listed.withTimeline(result: WorkGoalTimelineResult): SubtaskScopesState.Listed = when (result) {
  is WorkGoalTimelineResult.Attributed -> {
    val owners = result.commits.flatMap { commit -> commit.ownerSubtaskIds.map { it to commit.sha } }.groupBy({ it.first }, { it.second })
    val changed = result.changedAfterFinishIds
    copy(
      subtasks = subtasks.map { subtask ->
        subtask.copy(commitShas = owners[subtask.id].orEmpty(), changedAfterFinish = subtask.id in changed)
      },
      timeline = ChangeScopeTimeline(
        result.mergeBaseSha,
        result.headSha,
        result.commits.map { ScopeCommit(it.sha, it.parentSha, it.subject, it.ownerSubtaskIds) },
      ),
      timelineProblem = null,
    )
  }
  is WorkGoalTimelineResult.OnOtherBranch -> copy(
    timeline = null,
    timelineProblem = TimelineProblem.OnOtherBranch(result.currentBranch, result.featureBranch),
  )
  is WorkGoalTimelineResult.BaseUnavailable -> copy(
    timeline = null,
    timelineProblem = TimelineProblem.BaseUnavailable(result.reason),
  )
  is WorkGoalTimelineResult.NotARepository -> copy(
    timeline = null,
    timelineProblem = TimelineProblem.NotARepository(result.worktreeRoot),
  )
  is WorkGoalTimelineResult.Failed -> copy(
    timeline = null,
    timelineProblem = TimelineProblem.Unavailable(ReadProblem.Runtime(result.message)),
  )
}

internal data class IdentifiedCommitFile(
  val commit: ScopeCommit,
  val file: ChangedFile,
  val identityPath: String,
)

internal data class FileIdentityHistory(val files: List<ChangedFile>, val changes: List<IdentifiedCommitFile>)

internal fun List<CommitChanges>.fileIdentityHistory(terminalFiles: List<ChangedFile> = emptyList()): FileIdentityHistory {
  data class FileTotals(
    val id: Int,
    var oldPath: String?,
    var last: ChangedFile,
    var insertions: Int,
    var deletions: Int,
    var fileCount: Int,
    var binaryCount: Int,
  )
  val active = linkedMapOf<String, FileTotals>()
  val totals = mutableListOf<FileTotals>()
  val changes = mutableListOf<Pair<Int, IdentifiedCommitFile>>()
  fun track(commit: ScopeCommit, files: List<ChangedFile>, includePatch: Boolean) {
    for (file in files) {
      val existing = file.oldPath?.let(active::remove) ?: active[file.path]
      if (existing == null) {
        val created = FileTotals(totals.size, file.oldPath, file, file.insertions ?: 0, file.deletions ?: 0, 1, if (file.binary) 1 else 0)
        totals += created
        active[file.path] = created
        if (includePatch) changes += created.id to IdentifiedCommitFile(commit, file, file.path)
      } else {
        existing.oldPath = existing.oldPath ?: file.oldPath
        existing.last = file
        existing.insertions += file.insertions ?: 0
        existing.deletions += file.deletions ?: 0
        existing.fileCount++
        if (file.binary) existing.binaryCount++
        active[file.path] = existing
        if (includePatch) changes += existing.id to IdentifiedCommitFile(commit, file, file.path)
      }
    }
  }
  for (commit in this) track(commit.commit, commit.files, true)
  if (terminalFiles.isNotEmpty()) this.lastOrNull()?.let { track(it.commit, terminalFiles, false) }
  val finalPathById = active.values.associate { it.id to it.last.path }
  val files = totals.map { value ->
    val binary = value.binaryCount == value.fileCount
    ChangedFile(
      path = finalPathById[value.id] ?: value.last.path,
      oldPath = value.oldPath,
      status = value.last.status,
      insertions = if (binary) null else value.insertions,
      deletions = if (binary) null else value.deletions,
    )
  }.sortedBy { it.path }
  val identities = changes.map { (id, change) ->
    change.copy(identityPath = finalPathById[id] ?: change.identityPath)
  }
  return FileIdentityHistory(files, identities)
}

internal fun List<CommitChanges>.aggregate(mergeBaseSha: String): ChangeSet = fileIdentityHistory().files.let { files ->
  ChangeSet(mergeBaseSha, files, files.sumOf { it.insertions ?: 0 }, files.sumOf { it.deletions ?: 0 })
}
