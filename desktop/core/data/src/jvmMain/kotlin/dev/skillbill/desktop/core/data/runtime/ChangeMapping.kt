package dev.skillbill.desktop.core.data.runtime

import dev.skillbill.desktop.core.domain.model.ChangeBaseline
import dev.skillbill.desktop.core.domain.model.ChangeStatus
import dev.skillbill.desktop.core.domain.model.ChangedFile
import dev.skillbill.desktop.core.domain.model.ChangeScopeTimeline
import dev.skillbill.desktop.core.domain.model.ScopeCommit
import skillbill.ports.idestatus.model.WorkChangeBaseline
import skillbill.ports.workchanges.model.WorkChangeStatus
import skillbill.ports.workchanges.model.WorkChangedFile
import skillbill.ports.workchanges.model.WorkCommitTimelineResult

internal fun WorkChangeBaseline.toDomain(): ChangeBaseline = when (this) {
  is WorkChangeBaseline.SubtaskStart -> ChangeBaseline.SubtaskStart(worktreeRoot, baseSha, excludedUntrackedPaths)
  is WorkChangeBaseline.RunStart -> ChangeBaseline.RunStart(worktreeRoot, baseSha, excludedUntrackedPaths)
  is WorkChangeBaseline.TargetMergeBase -> ChangeBaseline.TargetMergeBase(worktreeRoot, targetBranch)
  is WorkChangeBaseline.CommittedBranchRange -> ChangeBaseline.CommittedBranchRange(worktreeRoot, targetBranch, headBranch)
}

internal fun ChangeBaseline.toPort(): WorkChangeBaseline = when (this) {
  is ChangeBaseline.SubtaskStart -> WorkChangeBaseline.SubtaskStart(worktreeRoot, baseSha, excludedUntrackedPaths)
  is ChangeBaseline.RunStart -> WorkChangeBaseline.RunStart(worktreeRoot, baseSha, excludedUntrackedPaths)
  is ChangeBaseline.TargetMergeBase -> WorkChangeBaseline.TargetMergeBase(worktreeRoot, targetBranch)
  is ChangeBaseline.CommittedBranchRange -> WorkChangeBaseline.CommittedBranchRange(worktreeRoot, targetBranch, headBranch)
  is ChangeBaseline.CommittedRevisionRange -> error("Committed revision ranges require committed Git reads.")
}

internal fun WorkChangedFile.toDomain(): ChangedFile = ChangedFile(path, oldPath, status.toDomain(), insertions, deletions)

internal fun ChangedFile.toPort(): WorkChangedFile = WorkChangedFile(path, oldPath, status.toPort(), insertions, deletions)

internal fun WorkCommitTimelineResult.Listed.toChatTimeline(): ChangeScopeTimeline = ChangeScopeTimeline(
  mergeBaseSha, headSha, commits.map { ScopeCommit(it.sha, it.parentSha, it.subject, emptyList()) },
)

private fun WorkChangeStatus.toDomain(): ChangeStatus = when (this) {
  WorkChangeStatus.ADDED -> ChangeStatus.Added
  WorkChangeStatus.MODIFIED -> ChangeStatus.Modified
  WorkChangeStatus.DELETED -> ChangeStatus.Deleted
  WorkChangeStatus.RENAMED -> ChangeStatus.Renamed
  WorkChangeStatus.UNTRACKED -> ChangeStatus.Untracked
}

private fun ChangeStatus.toPort(): WorkChangeStatus = when (this) {
  ChangeStatus.Added -> WorkChangeStatus.ADDED
  ChangeStatus.Modified -> WorkChangeStatus.MODIFIED
  ChangeStatus.Deleted -> WorkChangeStatus.DELETED
  ChangeStatus.Renamed -> WorkChangeStatus.RENAMED
  ChangeStatus.Untracked -> WorkChangeStatus.UNTRACKED
}
