package dev.skillbill.desktop.core.testing

import dev.skillbill.desktop.core.domain.model.ChatBaselineCapture
import dev.skillbill.desktop.core.domain.model.ChatChangedPaths
import dev.skillbill.desktop.core.domain.model.ChatChangesBaseline
import dev.skillbill.desktop.core.domain.model.ChatChangesRead
import dev.skillbill.desktop.core.domain.model.ChatProjectWorktrees
import dev.skillbill.desktop.core.domain.repository.ChatChangesRepository

class FakeChatChangesRepository(
  var captureResult: ChatBaselineCapture = ChatBaselineCapture.Unavailable("Not configured."),
  var readResult: ChatChangesRead = ChatChangesRead.Unavailable("Not configured."),
  var changedPathsResult: ChatChangedPaths = ChatChangedPaths.Listed(emptySet()),
) : ChatChangesRepository {
  val openingReadCalls = mutableListOf<Pair<String, ChatChangesBaseline>>()
  override suspend fun readSinceOpening(repositoryRoot: String, baseline: ChatChangesBaseline): ChatChangesRead {
    openingReadCalls += repositoryRoot to baseline
    return readResult
  }

  var projectResult: ChatProjectWorktrees = ChatProjectWorktrees.Listed("project", emptyList())
  override suspend fun projectWorktrees(repositoryRoot: String): ChatProjectWorktrees = projectResult

  val captureCalls = mutableListOf<String>()
  val readCalls = mutableListOf<Pair<String, ChatChangesBaseline>>()
  val readPaths = mutableListOf<Set<String>?>()
  val changedPathsCalls = mutableListOf<ChatChangesBaseline>()

  override suspend fun captureBaseline(repositoryRoot: String): ChatBaselineCapture {
    captureCalls += repositoryRoot
    return captureResult
  }

  override suspend fun read(repositoryRoot: String, baseline: ChatChangesBaseline, paths: Set<String>?): ChatChangesRead {
    readCalls += repositoryRoot to baseline
    readPaths += paths
    return readResult
  }

  override suspend fun changedPaths(repositoryRoot: String, since: ChatChangesBaseline): ChatChangedPaths {
    changedPathsCalls += since
    return changedPathsResult
  }
}
