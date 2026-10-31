package dev.skillbill.desktop.core.data.chat

import dev.skillbill.desktop.core.common.Diagnostics
import dev.skillbill.desktop.core.common.HostEnvironment
import dev.skillbill.desktop.core.common.di.AppScope
import dev.skillbill.desktop.core.domain.model.ChatEvent
import dev.skillbill.desktop.core.domain.model.ChatFailure
import dev.skillbill.desktop.core.domain.model.ChatToolCall
import dev.skillbill.desktop.core.domain.model.ChatTurnRequest
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import me.tatarka.inject.annotations.Inject
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn

/**
 * Runs each turn as one Cursor `agent --print` stream-json process with `--force`, as executions run. Cursor names its own
 * chat, so later turns resume the chat id the first turn reported, which [ChatStore] keeps across windows.
 */
@Inject
@SingleIn(AppScope::class)
class CursorChatTransport(
  private val host: HostEnvironment,
  private val launcher: ChatProcessLauncher,
  private val store: ChatStore,
  private val diagnostics: Diagnostics,
) : ChatTransport {
  override val agentId = "cursor"

  override fun run(request: ChatTurnRequest): Flow<ChatEvent> {
    val chat = if (request.firstTurn) null else store.agentSession(request.sessionId)
    if (!request.firstTurn && chat == null) diagnostics.record("chat.cursor_chat_missing")
    val prompt = if (chat == null) withScope(scopeSystemPrompt(request.scope, request.mode), request.prompt) else request.prompt
    val command = ChatCommand(
      argv = agentCommand("agent", cursorArguments(request, chat), host.variables["SHELL"]),
      workingDirectory = request.scope.repositoryRoot,
      environment = host.variables,
      stdin = prompt,
    )
    return runChatTurn(launcher, diagnostics, command, CursorStreamDecoder { store.saveAgentSession(request.sessionId, it) })
  }
}

internal fun cursorArguments(request: ChatTurnRequest, chat: String?): List<String> = buildList {
  addAll(listOf("--print", "--output-format", "stream-json", "--force", "--trust", "--approve-mcps"))
  addAll(listOf("--workspace", request.scope.repositoryRoot))
  request.agent.model?.let { model ->
    // Cursor takes effort inside the model name, as the execution launcher passes it.
    val effort = request.agent.effort?.takeIf { "[effort=" !in model }
    addAll(listOf("--model", if (effort != null) "$model[effort=$effort]" else model))
  }
  if (chat != null) addAll(listOf("--resume", chat))
}

/** Decodes Cursor stream-json lines; without partial output each `assistant` line is one whole message. */
internal class CursorStreamDecoder(private val onChat: (String) -> Unit) : ChatLineDecoder {
  private var reported = false
  private var wroteText = false

  override fun decode(line: String): List<ChatEvent> {
    if (line.isBlank()) return emptyList()
    val root = parseStreamLine(line) ?: return listOf(ChatEvent.Failed(ChatFailure.StreamMalformed, line.take(200)))
    if (!reported) {
      root.string("session_id")?.let {
        reported = true
        onChat(it)
      }
    }
    return when (root.string("type")) {
      "assistant" -> assistantText(root)?.let { text ->
        val separated = if (wroteText) "\n\n$text" else text
        wroteText = true
        listOf(ChatEvent.TextDelta(separated))
      }.orEmpty()
      "tool_call" -> toolEvent(root)
      "result" -> if ((root["is_error"] as? JsonPrimitive)?.booleanOrNull == true) {
        listOf(ChatEvent.Failed(ChatFailure.ProcessFailed, root.string("result") ?: root.string("subtype")))
      } else {
        listOf(ChatEvent.Completed)
      }
      "error" -> listOf(ChatEvent.Failed(ChatFailure.ProcessFailed, root.string("message") ?: root.string("error_type")))
      else -> emptyList()
    }
  }

  private fun assistantText(root: JsonObject): String? {
    val message = root.obj("message")
    val content = message?.get("content") as? JsonArray
    val text = content?.mapNotNull { (it as? JsonObject)?.string("text") }?.joinToString("") ?: message?.string("text")
    return text?.takeIf { it.isNotBlank() }
  }

  /** `tool_call` holds one `<name>ToolCall` object with `args`, and `result` once completed. */
  private fun toolEvent(root: JsonObject): List<ChatEvent> {
    val id = root.string("call_id") ?: return emptyList()
    val entry = root.obj("tool_call")?.entries?.firstOrNull() ?: return emptyList()
    val body = entry.value as? JsonObject ?: return emptyList()
    val name = entry.key.removeSuffix("ToolCall")
    val args = body.obj("args")
    val summary = args?.let { it.string("command") ?: it.string("path") ?: it.string("pattern") ?: it.string("query") }
    val call = ChatToolCall(id, name, args?.renderToolContent().orEmpty(), summary)
    return when (root.string("subtype")) {
      "started" -> listOf(ChatEvent.ToolUse(call))
      "completed" -> {
        val result = body.obj("result")
        val failure = result?.obj("error") ?: result?.obj("failure")
        val output = (failure ?: result?.obj("success") ?: result)?.renderToolContent().orEmpty()
        listOf(ChatEvent.ToolUse(call), ChatEvent.ToolResult(id, output, failure != null))
      }
      else -> emptyList()
    }
  }
}
