package dev.skillbill.desktop.core.data.chat

import dev.skillbill.desktop.core.common.Diagnostics
import dev.skillbill.desktop.core.common.HostEnvironment
import dev.skillbill.desktop.core.common.di.AppScope
import dev.skillbill.desktop.core.domain.model.ChatEvent
import dev.skillbill.desktop.core.domain.model.ChatFailure
import dev.skillbill.desktop.core.domain.model.ChatScope
import dev.skillbill.desktop.core.domain.model.ChatToolCall
import dev.skillbill.desktop.core.domain.model.ChatTurnRequest
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import java.util.concurrent.ConcurrentHashMap
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import me.tatarka.inject.annotations.Inject
import skillbill.contracts.system.AgentHomeEnvironmentKeys
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn

@Inject
@SingleIn(AppScope::class)
class CursorChatTransport(
  private val host: HostEnvironment,
  private val launcher: ChatProcessLauncher,
  private val store: ChatStore,
  private val diagnostics: Diagnostics,
  private val liveLauncher: LiveChatProcessLauncher? = null,
  private val approvalAuthority: ChatApprovalAuthority? = null,
) : ChatTransport {
  override val agentId = "cursor"
  override val approvalCapable = true
  private val turns = ConcurrentHashMap<String, Job>()

  override fun run(request: ChatTurnRequest): Flow<ChatEvent> = flow {
    val live = liveLauncher
    val authority = approvalAuthority
    if (live == null || authority == null) {
      emit(ChatEvent.Failed(ChatFailure.ApprovalUnsupported,
        "Cursor ACP requires a live process and operator approval channel; the chat was not started."))
      return@flow
    }
    val saved = store.agentSession(request.sessionId)
    if (saved?.isBlank() == true || (!request.firstTurn && saved == null)) {
      emit(ChatEvent.Failed(ChatFailure.ProcessFailed,
        "The saved Cursor session is unavailable. Start an explicit new chat; history was not replaced."))
      return@flow
    }
    val environment = host.variables + mapOf("HOME" to (host.variables["HOME"] ?: host.userHome)) + request.agent.profile
      ?.let { mapOf(AgentHomeEnvironmentKeys.CURSOR_CONFIG_DIR to it) }.orEmpty()
    val prompt = if (saved == null || request.scope is ChatScope.NewTask) {
      withScope(scopeSystemPrompt(request.scope, request.mode), request.prompt)
    } else request.prompt
    val job = currentCoroutineContext()[Job]
    if (job != null) turns[request.sessionId] = job
    try {
      emitAll(cursorAcpTurn(live, launcher, authority, store, diagnostics, host.variables["SHELL"],
        environment, request, saved, prompt))
    } finally {
      if (job != null) turns.remove(request.sessionId, job)
    }
  }

  override fun end(sessionId: String) { turns.remove(sessionId)?.cancel(); deleteChatImages(sessionId) }
}

internal fun cursorArguments(request: ChatTurnRequest, chat: String?): List<String> = listOf("acp")

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
