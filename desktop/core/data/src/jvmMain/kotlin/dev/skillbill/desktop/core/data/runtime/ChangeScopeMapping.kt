package dev.skillbill.desktop.core.data.runtime

import dev.skillbill.desktop.core.domain.model.ChangeBaseline
import dev.skillbill.desktop.core.domain.model.ChangeSet
import dev.skillbill.desktop.core.domain.model.ChangedFile
import dev.skillbill.desktop.core.domain.model.OutsideCommit
import dev.skillbill.desktop.core.domain.model.ReadProblem
import dev.skillbill.desktop.core.domain.model.ScopeSubtask
import dev.skillbill.desktop.core.domain.model.ScopeSubtaskState
import dev.skillbill.desktop.core.domain.model.SubtaskScopesState
import skillbill.ports.workchanges.model.WorkCommitRange
import skillbill.ports.workchanges.model.WorkGoalScopesResult
import skillbill.ports.workchanges.model.WorkOutsideCommit
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
  startSha = startSha,
  endSha = endSha,
  baselineUntrackedPaths = baselineUntrackedPaths,
)

internal fun WorkGoalScopesResult.toState(): SubtaskScopesState = when (this) {
  is WorkGoalScopesResult.Listed -> SubtaskScopesState.Listed(
    worktreeRoot, targetBranch, rangesSupported, subtasks.map { it.toDomain() }, emptyMap(),
  )
  is WorkGoalScopesResult.NotAGoal -> SubtaskScopesState.NotAGoal
  is WorkGoalScopesResult.Unavailable -> SubtaskScopesState.Unavailable(ReadProblem.Runtime(reason))
}

internal fun dev.skillbill.desktop.core.domain.model.CommitRange.toPort(): WorkCommitRange = WorkCommitRange(fromSha, toSha)

internal fun WorkOutsideCommit.toDomain(): OutsideCommit = OutsideCommit(
  sha = sha,
  parentSha = parentSha,
  subject = subject,
  files = files.map { it.toDomain() },
)

internal fun List<WorkOutsideCommit>.aggregate(mergeBaseSha: String): ChangeSet {
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
    for (file in commit.files.map { it.toDomain() }) {
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
  return ChangeSet(mergeBaseSha, files, sumOf { it.insertions }, sumOf { it.deletions })
}

