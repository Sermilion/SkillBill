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
import kotlin.time.Clock
import skillbill.ports.idestatus.model.WorkChangeBaseline
import skillbill.ports.workchanges.WorkChangesPort
import skillbill.ports.workchanges.WorkTreeSnapshotPort
import skillbill.ports.workchanges.model.WorkChangeListResult
import skillbill.ports.workchanges.model.WorkCommitTimelineResult
import skillbill.ports.workchanges.model.WorkTreeSnapshotResult

internal class ChatChangeReads(
  private val snapshot: WorkTreeSnapshotPort,
  private val changes: WorkChangesPort,
  private val clock: Clock,
) {
  fun capture(root: String): ChatBaselineCapture = when (val result = snapshot.capture(root)) {
    is WorkTreeSnapshotResult.Captured -> ChatBaselineCapture.Captured(
      ChatChangesBaseline(result.headSha, result.untrackedPaths.toList(), result.stashSha, clock.now()),
    )
    is WorkTreeSnapshotResult.NotARepository -> ChatBaselineCapture.Unavailable("Not a repository: ${result.path}")
    is WorkTreeSnapshotResult.NoCommit -> ChatBaselineCapture.Unavailable(result.reason)
    is WorkTreeSnapshotResult.Failed -> ChatBaselineCapture.Unavailable(result.message)
  }

  fun changedPaths(root: String, since: ChatChangesBaseline): ChatChangedPaths =
    when (val result = changes.changes(WorkChangeBaseline.RunStart(root, since.stashSha ?: since.headSha, since.untrackedPaths))) {
      is WorkChangeListResult.Listed -> ChatChangedPaths.Listed(result.files.flatMap { listOfNotNull(it.path, it.oldPath) }.toSet())
      is WorkChangeListResult.BaseUnavailable -> ChatChangedPaths.Unavailable(result.reason)
      is WorkChangeListResult.NotARepository -> ChatChangedPaths.Unavailable("Not a repository: ${result.worktreeRoot}")
      is WorkChangeListResult.Failed -> ChatChangedPaths.Unavailable(result.message)
    }

  fun read(root: String, baseline: ChatChangesBaseline, paths: Set<String>?): ChatChangesRead {
    val timeline = when (val result = changes.commitTimeline(root, baseline.headSha, emptyList())) {
      is WorkCommitTimelineResult.Listed -> result
      is WorkCommitTimelineResult.BaseUnavailable -> return ChatChangesRead.Unavailable(result.reason)
      is WorkCommitTimelineResult.NotARepository -> return ChatChangesRead.Unavailable("Not a repository: ${result.worktreeRoot}")
      is WorkCommitTimelineResult.Failed -> return ChatChangesRead.Unavailable(result.message)
    }
    val files = when (val result = changes.changes(
      WorkChangeBaseline.RunStart(root, baseline.stashSha ?: baseline.headSha, baseline.untrackedPaths),
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
        timeline.commits.size, current.files.map { it.toDomain() }.attributedTo(paths).isNotEmpty(), timeline.headSha, timeline.toChatTimeline()),
    )
  }
}
