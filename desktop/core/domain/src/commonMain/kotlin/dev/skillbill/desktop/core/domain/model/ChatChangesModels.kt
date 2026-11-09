package dev.skillbill.desktop.core.domain.model

data class ChatChanges(
  val files: List<ChangedFile>,
  val insertions: Int,
  val deletions: Int,
  val commitCount: Int,
  val uncommitted: Boolean,
  val headSha: String,
  val timeline: ChangeScopeTimeline,
  val comparisonBaseSha: String? = null,
  val worktrees: List<ChatWorktreeChanges> = emptyList(),
)

sealed interface ChatBaselineCapture {
  data class Captured(val baseline: ChatChangesBaseline, val unavailableWorktrees: Map<String, String> = emptyMap()) : ChatBaselineCapture
  data class Unavailable(val reason: String) : ChatBaselineCapture
}

sealed interface ChatChangesRead {
  data class Listed(val changes: ChatChanges) : ChatChangesRead
  data class Unavailable(val reason: String) : ChatChangesRead
}

sealed interface ChatChangedPaths {
  data class Listed(val paths: Set<String>) : ChatChangedPaths
  data class Unavailable(val reason: String) : ChatChangedPaths
}

fun List<ChangedFile>.attributedTo(paths: Set<String>?): List<ChangedFile> =
  if (paths == null) this else filter { it.path in paths || it.oldPath in paths }

data class ChatWorktreeChanges(
  val repositoryRoot: String,
  val baseline: ChatChangesBaseline,
  val paths: Set<String>?,
  val changes: ChatChanges,
)

sealed interface ChatProjectWorktrees {
  data class Listed(val projectId: String, val roots: List<String>) : ChatProjectWorktrees
  data class Unavailable(val reason: String) : ChatProjectWorktrees
}

fun chatWorktreePath(root: String, path: String): String = root.replace('\\', '/').trimEnd('/') + "/" + path

fun Set<String>?.forChatWorktree(root: String, includeRelative: Boolean): Set<String>? = this?.mapNotNull { path ->
  val prefix = chatWorktreePath(root, "")
  when {
    path.startsWith(prefix) -> path.removePrefix(prefix)
    includeRelative && !path.startsWith("/") && !path.contains(":/") -> path
    else -> null
  }
}?.toSet()
