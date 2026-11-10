package dev.skillbill.desktop.core.data.repository

import dev.skillbill.desktop.core.data.runtime.toChatTimeline
import dev.skillbill.desktop.core.data.runtime.toDomain
import dev.skillbill.desktop.core.domain.model.ChatBaselineCapture
import dev.skillbill.desktop.core.domain.model.ChatChangedPaths
import dev.skillbill.desktop.core.domain.model.ChatChanges
import dev.skillbill.desktop.core.domain.model.ChatChangesBaseline
import dev.skillbill.desktop.core.domain.model.ChatChangesRead
import dev.skillbill.desktop.core.domain.model.ChangeScopeTimeline
import dev.skillbill.desktop.core.domain.model.ChangedFile
import dev.skillbill.desktop.core.domain.model.attributedTo
import dev.skillbill.desktop.core.domain.model.ChatProjectWorktrees
import dev.skillbill.desktop.core.domain.model.ChatWorktreeChanges
import dev.skillbill.desktop.core.domain.model.chatWorktreePath
import dev.skillbill.desktop.core.domain.model.forChatWorktree
import kotlin.time.Clock
import skillbill.ports.idestatus.model.WorkChangeBaseline
import skillbill.ports.workchanges.WorkChangesPort
import skillbill.ports.workchanges.WorkTreeSnapshotPort
import skillbill.ports.workchanges.model.WorkChangeListResult
import skillbill.ports.workchanges.model.WorkCommitTimelineResult
import skillbill.ports.workchanges.model.WorkTreeComparisonResult
import skillbill.ports.workchanges.model.WorkTreeProjectResult
import skillbill.ports.workchanges.model.WorkTreeSnapshotResult

internal class ChatChangeReads(
  private val snapshot: WorkTreeSnapshotPort,
  private val changes: WorkChangesPort,
  private val clock: Clock,
) {
  fun readCurrentChanges(root: String): ChatChangesRead {
    val files = when (val result = changes.uncommittedChanges(root)) {
      is WorkChangeListResult.Listed -> result
      is WorkChangeListResult.BaseUnavailable -> return ChatChangesRead.Unavailable(result.reason)
      is WorkChangeListResult.NotARepository -> return ChatChangesRead.Unavailable("Not a repository: ${result.worktreeRoot}")
      is WorkChangeListResult.Failed -> return ChatChangesRead.Unavailable(result.message)
    }
    val mapped = files.files.map { it.toDomain() }.sortedBy(ChangedFile::path)
    return ChatChangesRead.Listed(ChatChanges(mapped, mapped.sumOf { it.insertions ?: 0 }, mapped.sumOf { it.deletions ?: 0 },
      0, mapped.isNotEmpty(), files.baseSha, ChangeScopeTimeline(files.baseSha, files.baseSha, emptyList()), "HEAD"))
  }

  fun projectWorktrees(root: String): ChatProjectWorktrees = when (val result = snapshot.projectWorktrees(root)) {
    is WorkTreeProjectResult.Listed -> ChatProjectWorktrees.Listed(result.projectId, result.worktreeRoots)
    is WorkTreeProjectResult.Unavailable -> ChatProjectWorktrees.Unavailable(result.reason)
  }

  fun capture(root: String): ChatBaselineCapture {
    val project = when (val result = snapshot.projectWorktrees(root)) {
      is WorkTreeProjectResult.Listed -> result
      is WorkTreeProjectResult.Unavailable -> return ChatBaselineCapture.Unavailable(result.reason)
    }
    val baselines = linkedMapOf<String, ChatChangesBaseline>()
    val unavailable = linkedMapOf<String, String>()
    for (worktree in project.worktreeRoots) {
      when (val result = captureWorktree(worktree)) {
        is ChatBaselineCapture.Captured -> baselines[worktree] = result.baseline
        is ChatBaselineCapture.Unavailable -> if (worktree == project.checkoutRoot) return result else unavailable[worktree] = result.reason
      }
    }
    val selected = baselines.getValue(project.checkoutRoot)
    return ChatBaselineCapture.Captured(selected.copy(projectId = project.projectId, worktrees = baselines), unavailable)
  }

  private fun captureWorktree(root: String): ChatBaselineCapture = when (val result = snapshot.capture(root)) {
    is WorkTreeSnapshotResult.Captured -> ChatBaselineCapture.Captured(
      ChatChangesBaseline(result.headSha, result.untrackedPaths.toList(), result.stashSha, clock.now()),
    )
    is WorkTreeSnapshotResult.NotARepository -> ChatBaselineCapture.Unavailable("Not a repository: ${result.path}")
    is WorkTreeSnapshotResult.NoCommit -> ChatBaselineCapture.Unavailable(result.reason)
    is WorkTreeSnapshotResult.Failed -> ChatBaselineCapture.Unavailable(result.message)
  }

  fun changedPaths(root: String, since: ChatChangesBaseline): ChatChangedPaths {
    if (since.worktrees.isEmpty()) return changedWorktreePaths(root, since)
    val paths = linkedSetOf<String>()
    for ((worktree, baseline) in since.worktrees) {
      when (val result = changedWorktreePaths(worktree, baseline)) {
        is ChatChangedPaths.Listed -> paths += result.paths.map { if (worktree == root) it else chatWorktreePath(worktree, it) }
        is ChatChangedPaths.Unavailable -> return result
      }
    }
    return ChatChangedPaths.Listed(paths)
  }

  private fun changedWorktreePaths(root: String, since: ChatChangesBaseline): ChatChangedPaths {
    val comparison = when (val result = snapshot.comparisonBaseline(root, since.headSha, since.stashSha)) {
      is WorkTreeComparisonResult.Resolved -> result
      is WorkTreeComparisonResult.Unavailable -> return ChatChangedPaths.Unavailable(result.reason)
    }
    return when (val result = changes.changes(WorkChangeBaseline.RunStart(root, comparison.baseSha, since.untrackedPaths))) {
      is WorkChangeListResult.Listed -> ChatChangedPaths.Listed(result.files.flatMap { listOfNotNull(it.path, it.oldPath) }.toSet())
      is WorkChangeListResult.BaseUnavailable -> ChatChangedPaths.Unavailable(result.reason)
      is WorkChangeListResult.NotARepository -> ChatChangedPaths.Unavailable("Not a repository: ${result.worktreeRoot}")
      is WorkChangeListResult.Failed -> ChatChangedPaths.Unavailable(result.message)
    }
  }

  fun read(root: String, baseline: ChatChangesBaseline, paths: Set<String>?): ChatChangesRead {
    if (baseline.worktrees.isEmpty()) return readWorktree(root, baseline, paths)
    when (val project = projectWorktrees(root)) {
      is ChatProjectWorktrees.Unavailable -> return ChatChangesRead.Unavailable(project.reason)
      is ChatProjectWorktrees.Listed -> if (project.projectId != baseline.projectId) return ChatChangesRead.Unavailable("The checkout now belongs to a different project.")
    }
    val worktrees = mutableListOf<ChatWorktreeChanges>()
    for ((worktree, start) in baseline.worktrees) {
      val attributed = paths.forChatWorktree(worktree, includeRelative = worktree == root)
      if (attributed?.isEmpty() == true || paths == null && worktree != root) continue
      when (val result = readWorktree(worktree, start, attributed)) {
        is ChatChangesRead.Listed -> if (result.changes.files.isNotEmpty()) worktrees += ChatWorktreeChanges(worktree, start, attributed, result.changes)
        is ChatChangesRead.Unavailable -> return result
      }
    }
    val first = worktrees.firstOrNull()?.changes ?: return ChatChangesRead.Listed(
      ChatChanges(emptyList(), 0, 0, 0, false, baseline.headSha, ChangeScopeTimeline(baseline.headSha, baseline.headSha, emptyList())),
    )
    val files = worktrees.flatMap { it.changes.files }
    return ChatChangesRead.Listed(first.copy(
      files = files,
      insertions = files.sumOf { it.insertions ?: 0 },
      deletions = files.sumOf { it.deletions ?: 0 },
      commitCount = worktrees.flatMap { it.changes.timeline.commits }.distinctBy { it.sha }.size,
      uncommitted = worktrees.any { it.changes.uncommitted },
      worktrees = worktrees,
    ))
  }

  private fun readWorktree(root: String, baseline: ChatChangesBaseline, paths: Set<String>?): ChatChangesRead {
    val comparison = when (val result = snapshot.comparisonBaseline(root, baseline.headSha, baseline.stashSha)) {
      is WorkTreeComparisonResult.Resolved -> result
      is WorkTreeComparisonResult.Unavailable -> return ChatChangesRead.Unavailable(result.reason)
    }
    val originalTimeline = when (val result = changes.commitTimeline(root, baseline.headSha, emptyList())) {
      is WorkCommitTimelineResult.Listed -> result
      is WorkCommitTimelineResult.BaseUnavailable -> return ChatChangesRead.Unavailable(result.reason)
      is WorkCommitTimelineResult.NotARepository -> return ChatChangesRead.Unavailable("Not a repository: ${result.worktreeRoot}")
      is WorkCommitTimelineResult.Failed -> return ChatChangesRead.Unavailable(result.message)
    }
    val timeline = if (comparison.upstreamSha == null) originalTimeline else {
      val upstreamTimeline = when (val result = changes.commitTimeline(root, comparison.upstreamSha, emptyList())) {
        is WorkCommitTimelineResult.Listed -> result
        is WorkCommitTimelineResult.BaseUnavailable -> return ChatChangesRead.Unavailable(result.reason)
        is WorkCommitTimelineResult.NotARepository -> return ChatChangesRead.Unavailable("Not a repository: ${result.worktreeRoot}")
        is WorkCommitTimelineResult.Failed -> return ChatChangesRead.Unavailable(result.message)
      }
      val localShas = upstreamTimeline.commits.mapTo(hashSetOf()) { it.sha }
      upstreamTimeline.copy(commits = originalTimeline.commits.filter { it.sha in localShas })
    }
    val files = when (val result = changes.changes(
      WorkChangeBaseline.RunStart(root, comparison.baseSha, baseline.untrackedPaths),
    )) {
      is WorkChangeListResult.Listed -> result
      is WorkChangeListResult.BaseUnavailable -> return ChatChangesRead.Unavailable(result.reason)
      is WorkChangeListResult.NotARepository -> return ChatChangesRead.Unavailable("Not a repository: ${result.worktreeRoot}")
      is WorkChangeListResult.Failed -> return ChatChangesRead.Unavailable(result.message)
    }
    val current = when (val result = changes.changes(
      WorkChangeBaseline.RunStart(root, timeline.headSha, baseline.untrackedPaths),
    )) {
      is WorkChangeListResult.Listed -> result
      is WorkChangeListResult.BaseUnavailable -> return ChatChangesRead.Unavailable(result.reason)
      is WorkChangeListResult.NotARepository -> return ChatChangesRead.Unavailable("Not a repository: ${result.worktreeRoot}")
      is WorkChangeListResult.Failed -> return ChatChangesRead.Unavailable(result.message)
    }
    val mapped = files.files.map { it.toDomain() }.attributedTo(paths).sortedBy(ChangedFile::path)
    return ChatChangesRead.Listed(
      ChatChanges(mapped, mapped.sumOf { it.insertions ?: 0 }, mapped.sumOf { it.deletions ?: 0 },
        timeline.commits.size, current.files.map { it.toDomain() }.attributedTo(paths).isNotEmpty(), timeline.headSha, timeline.toChatTimeline(), comparison.baseSha),
    )
  }
}
