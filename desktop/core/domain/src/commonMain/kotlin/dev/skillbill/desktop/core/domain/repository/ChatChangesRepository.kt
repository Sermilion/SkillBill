package dev.skillbill.desktop.core.domain.repository

import dev.skillbill.desktop.core.domain.model.ChatBaselineCapture
import dev.skillbill.desktop.core.domain.model.ChatChangedPaths
import dev.skillbill.desktop.core.domain.model.ChatChangesBaseline
import dev.skillbill.desktop.core.domain.model.ChatChangesRead
import dev.skillbill.desktop.core.domain.model.ChatProjectWorktrees

interface ChatChangesRepository {
  suspend fun readCurrentChanges(repositoryRoot: String): ChatChangesRead

  suspend fun projectWorktrees(repositoryRoot: String): ChatProjectWorktrees

  suspend fun captureBaseline(repositoryRoot: String): ChatBaselineCapture

  suspend fun read(repositoryRoot: String, baseline: ChatChangesBaseline, paths: Set<String>?): ChatChangesRead

  suspend fun changedPaths(repositoryRoot: String, since: ChatChangesBaseline): ChatChangedPaths
}
