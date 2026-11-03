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

internal fun List<CommitChanges>.aggregate(mergeBaseSha: String): ChangeSet {
  data class FileTotals(
    val first: ChangedFile,
    var last: ChangedFile,
    var insertions: Int,
    var deletions: Int,
    var fileCount: Int,
    var binaryCount: Int,
  )
  val totals = linkedMapOf<String, FileTotals>()
  for (commit in this) {
    for (file in commit.files) {
      val existing = totals[file.path]
      if (existing == null) {
        totals[file.path] = FileTotals(file, file, file.insertions ?: 0, file.deletions ?: 0, 1, if (file.binary) 1 else 0)
      } else {
        existing.last = file
        existing.insertions += file.insertions ?: 0
        existing.deletions += file.deletions ?: 0
        existing.fileCount++
        if (file.binary) existing.binaryCount++
      }
    }
  }
  val files = totals.map { (path, value) ->
    val binary = value.binaryCount == value.fileCount
    ChangedFile(
      path = path,
      oldPath = value.first.oldPath,
      status = value.last.status,
      insertions = if (binary) null else value.insertions,
      deletions = if (binary) null else value.deletions,
    )
  }.sortedBy { it.path }
  return ChangeSet(mergeBaseSha, files, files.sumOf { it.insertions ?: 0 }, files.sumOf { it.deletions ?: 0 })
}
