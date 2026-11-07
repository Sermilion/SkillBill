package dev.skillbill.desktop.feature.chat.newtask

import dev.skillbill.desktop.feature.chat.ChatMessage
import dev.skillbill.desktop.feature.chat.savedText

/** Characters of earlier conversation a rebound session may receive. */
internal const val NEW_TASK_REPLAY_BUDGET = 24_000

private const val OMITTED_LINE = "Older messages were left out."
private const val MESSAGE_SEPARATOR = "\n\n"

/** The conversation a rebound chat hands to its new agent session with the next message. */
internal data class NewTaskReplay(
  val agentId: String,
  val repositoryRoot: String,
  val baseBranch: String?,
  val messages: List<ChatMessage>,
)

/** One replayed message; [prefix] names who said [text]. */
private class ReplayLine(label: String, val text: String) {
  val prefix = "$label: "
  val rendered: String get() = prefix + text
}

/** The preamble for a session now bound to [repositoryRoot] and [baseBranch], or null when nothing is worth replaying. */
internal fun newTaskReplayPreamble(replay: NewTaskReplay, repositoryRoot: String, baseBranch: String?): String? {
  val lines = replay.messages.mapNotNull { it.replayLine() }
  if (lines.isEmpty()) return null
  val kept = ArrayDeque<String>()
  var size = 0
  for (line in lines.asReversed()) {
    val rendered = line.rendered
    val added = rendered.length + if (kept.isEmpty()) 0 else MESSAGE_SEPARATOR.length
    if (size + added > NEW_TASK_REPLAY_BUDGET) break
    kept.addFirst(rendered)
    size += added
  }
  val truncated = kept.isEmpty()
  if (truncated) {
    val newest = lines.last()
    kept.addFirst(newest.prefix + newest.text.takeLast(NEW_TASK_REPLAY_BUDGET - newest.prefix.length))
  }
  val body = kept.joinToString(MESSAGE_SEPARATOR)
  val conversation = if (truncated || kept.size < lines.size) "$OMITTED_LINE$MESSAGE_SEPARATOR$body" else body
  return buildString {
    append("Earlier in this chat you were talking with ${replay.agentId}. Here is that conversation:")
    if (replay.repositoryRoot != repositoryRoot) append("\nThe repository changed from ${replay.repositoryRoot} to $repositoryRoot.")
    val oldBranch = replay.baseBranch.branchName()
    val newBranch = baseBranch.branchName()
    if (oldBranch != newBranch) append("\nThe base branch changed from $oldBranch to $newBranch.")
    append(MESSAGE_SEPARATOR).append(conversation).append(MESSAGE_SEPARATOR).append("Continue from here.")
  }
}

private fun ChatMessage.replayLine(): ReplayLine? {
  val line = when (this) {
    is ChatMessage.Operator -> ReplayLine("Operator", savedText())
    is ChatMessage.Agent -> if (streaming) null else ReplayLine("Agent", text)
    is ChatMessage.Notice, is ChatMessage.Error, is ChatMessage.StateLine -> null
  }
  return line?.takeIf { it.text.isNotBlank() }
}

private fun String?.branchName(): String = if (isNullOrBlank()) "the default branch" else this
