package dev.skillbill.desktop.core.data.repository

import dev.skillbill.desktop.core.common.Diagnostics
import dev.skillbill.desktop.core.common.di.AppScope
import dev.skillbill.desktop.core.data.session.DatabaseSessionManager
import dev.skillbill.desktop.core.domain.model.ChatBaselineCapture
import dev.skillbill.desktop.core.domain.model.ChatChangedPaths
import dev.skillbill.desktop.core.domain.model.ChatChangesBaseline
import dev.skillbill.desktop.core.domain.model.ChatChangesRead
import dev.skillbill.desktop.core.domain.model.ChatProjectWorktrees
import dev.skillbill.desktop.core.domain.repository.ChatChangesRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.withLock
import kotlin.time.Clock
import me.tatarka.inject.annotations.Inject
import software.amazon.lastmile.kotlin.inject.anvil.ContributesBinding
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn

@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class RuntimeChatChangesRepository(
  private val sessions: DatabaseSessionManager,
  private val diagnostics: Diagnostics,
  private val gitReadLock: GitReadLock,
  private val clock: Clock,
) : ChatChangesRepository {
  override suspend fun projectWorktrees(repositoryRoot: String): ChatProjectWorktrees = withContext(Dispatchers.IO) {
    gitReadLock.mutex.withLock {
      val runtime = sessions.sessions.value?.runtime
      val result = if (runtime == null) ChatProjectWorktrees.Unavailable("No database session is open.")
      else ChatChangeReads(runtime.workTreeSnapshotPort, runtime.workChangesPort, clock).projectWorktrees(repositoryRoot)
      if (result is ChatProjectWorktrees.Unavailable) diagnostics.record("chat.project_worktrees_unavailable")
      result
    }
  }

  override suspend fun captureBaseline(repositoryRoot: String): ChatBaselineCapture = withContext(Dispatchers.IO) {
    gitReadLock.mutex.withLock {
      val runtime = sessions.sessions.value?.runtime
      val result = if (runtime == null) ChatBaselineCapture.Unavailable("No database session is open.")
      else ChatChangeReads(runtime.workTreeSnapshotPort, runtime.workChangesPort, clock).capture(repositoryRoot)
      if (result is ChatBaselineCapture.Unavailable) diagnostics.record(BASELINE_UNAVAILABLE)
      if (result is ChatBaselineCapture.Captured) result.unavailableWorktrees.forEach { _ -> diagnostics.record("chat.worktree_baseline_unavailable") }
      result
    }
  }

  override suspend fun read(repositoryRoot: String, baseline: ChatChangesBaseline, paths: Set<String>?): ChatChangesRead = withContext(Dispatchers.IO) {
    gitReadLock.mutex.withLock {
      val runtime = sessions.sessions.value?.runtime
      val result = if (runtime == null) ChatChangesRead.Unavailable("No database session is open.")
      else ChatChangeReads(runtime.workTreeSnapshotPort, runtime.workChangesPort, clock).read(repositoryRoot, baseline, paths)
      if (result is ChatChangesRead.Unavailable) diagnostics.record(CHANGES_UNAVAILABLE)
      result
    }
  }

  override suspend fun changedPaths(repositoryRoot: String, since: ChatChangesBaseline): ChatChangedPaths = withContext(Dispatchers.IO) {
    gitReadLock.mutex.withLock {
      val runtime = sessions.sessions.value?.runtime
      val result = if (runtime == null) ChatChangedPaths.Unavailable("No database session is open.")
      else ChatChangeReads(runtime.workTreeSnapshotPort, runtime.workChangesPort, clock).changedPaths(repositoryRoot, since)
      if (result is ChatChangedPaths.Unavailable) diagnostics.record(TURN_CHANGES_UNAVAILABLE)
      result
    }
  }

  private companion object {
    const val BASELINE_UNAVAILABLE = "chat.changes_baseline_unavailable"
    const val CHANGES_UNAVAILABLE = "chat.changes_unavailable"
    const val TURN_CHANGES_UNAVAILABLE = "chat.turn_changes_unavailable"
  }
}
