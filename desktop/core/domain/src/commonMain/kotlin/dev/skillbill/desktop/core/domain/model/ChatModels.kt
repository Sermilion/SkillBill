package dev.skillbill.desktop.core.domain.model

/** The agent a chat turn runs on; a null [model] or [effort] leaves the agent CLI's own default. */
data class ChatAgent(val agentId: String, val model: String?, val effort: String?)

/** [ReadOnly] while the execution runs; [Act] once the operator paused it to act. */
enum class ChatPermissionMode { ReadOnly, Act }

/** The one execution a chat is about; [repositoryRoot] is the worktree the agent runs in. */
data class ChatScope(
  val executionId: String,
  val kind: WorkKind?,
  val issueKey: String?,
  val title: String,
  val repositoryRoot: String,
)

/** One operator message; [firstTurn] opens [sessionId], later turns resume it. */
data class ChatTurnRequest(
  val sessionId: String,
  val firstTurn: Boolean,
  val agent: ChatAgent,
  val scope: ChatScope,
  val mode: ChatPermissionMode,
  val prompt: String,
)

data class ChatSuggestedAction(val label: String, val instruction: String, val mode: ChatPermissionMode)

data class ChatToolCall(val id: String, val name: String, val input: String, val summary: String?)

/** What one turn streams back; a turn ends with [Completed] or [Failed]. */
sealed interface ChatEvent {
  data class TextDelta(val text: String) : ChatEvent

  data class ToolUse(val call: ChatToolCall) : ChatEvent

  data class ToolResult(val callId: String, val output: String, val isError: Boolean) : ChatEvent

  data class SuggestedActions(val actions: List<ChatSuggestedAction>) : ChatEvent

  data object Completed : ChatEvent

  data class Failed(val failure: ChatFailure, val detail: String?) : ChatEvent
}

/** Why a turn ended without an answer. */
enum class ChatFailure { AgentUnsupported, BinaryMissing, ProcessFailed, StreamMalformed }
