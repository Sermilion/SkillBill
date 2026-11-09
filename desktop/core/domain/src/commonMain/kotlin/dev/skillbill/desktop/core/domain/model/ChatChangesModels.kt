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
)

sealed interface ChatBaselineCapture {
  data class Captured(val baseline: ChatChangesBaseline) : ChatBaselineCapture
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
