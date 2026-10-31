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
import me.tatarka.inject.annotations.Inject
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn

/**
 * Runs each turn as one `codex exec --json` process with approvals and the sandbox bypassed, as executions run. Codex names
 * its own thread, so later turns resume the thread the first turn reported, which [ChatStore] keeps across windows.
 */
@Inject
@SingleIn(AppScope::class)
class CodexChatTransport(
  private val host: HostEnvironment,
  private val launcher: ChatProcessLauncher,
  private val store: ChatStore,
  private val diagnostics: Diagnostics,
) : ChatTransport {
  override val agentId = "codex"

  override fun run(request: ChatTurnRequest): Flow<ChatEvent> {
    val thread = if (request.firstTurn) null else store.agentSession(request.sessionId)
    if (!request.firstTurn && thread == null) diagnostics.record("chat.codex_thread_missing")
    val prompt = if (thread == null) withScope(scopeSystemPrompt(request.scope, request.mode), request.prompt) else request.prompt
    val command = ChatCommand(
      argv = agentCommand("codex", codexArguments(request, thread), host.variables["SHELL"]),
      workingDirectory = request.scope.repositoryRoot,
      environment = host.variables,
      stdin = prompt,
    )
    return runChatTurn(launcher, diagnostics, command, CodexStreamDecoder { store.saveAgentSession(request.sessionId, it) })
  }
}

/** Options go before `resume`, which reads the prompt from stdin through `-`. */
internal fun codexArguments(request: ChatTurnRequest, thread: String?): List<String> = buildList {
  addAll(listOf("exec", "--json", "--dangerously-bypass-approvals-and-sandbox", "--skip-git-repo-check"))
  addAll(listOf("--config", "shell_environment_policy.inherit=all"))
  addAll(listOf("--cd", request.scope.repositoryRoot))
  request.agent.model?.let { addAll(listOf("--model", it)) }
  request.agent.effort?.let { addAll(listOf("--config", "model_reasoning_effort=$it")) }
  if (thread != null) addAll(listOf("resume", thread, "-"))
}

/**
 * Decodes `codex exec --json` lines. `error` lines can be retries the turn recovers from, so only `turn.failed` or an exit
 * without `turn.completed` fails the turn, with the last error as its detail.
 */
internal class CodexStreamDecoder(private val onThread: (String) -> Unit) : ChatLineDecoder {
  private var lastError: String? = null
  private var wroteText = false
  private val started = mutableSetOf<String>()

  override fun decode(line: String): List<ChatEvent> {
    if (line.isBlank()) return emptyList()
    val root = parseStreamLine(line) ?: return listOf(ChatEvent.Failed(ChatFailure.StreamMalformed, line.take(200)))
    return when (root.string("type")) {
      "thread.started" -> {
        root.string("thread_id")?.let(onThread)
        emptyList()
      }
      "item.started" -> root.obj("item")?.let(::toolCall)?.let { call ->
        started += call.id
        listOf(ChatEvent.ToolUse(call))
      }.orEmpty()
      "item.completed" -> root.obj("item")?.let(::completed).orEmpty()
      "turn.completed" -> listOf(ChatEvent.Completed)
      "turn.failed" -> listOf(ChatEvent.Failed(ChatFailure.ProcessFailed, root.obj("error")?.string("message") ?: lastError))
      "error" -> {
        lastError = root.string("message")
        emptyList()
      }
      else -> emptyList()
    }
  }

  override fun exited(code: Int, stderrTail: String): List<ChatEvent> =
    listOf(exitFailure(code, lastError ?: stderrTail))

  private fun completed(item: JsonObject): List<ChatEvent> {
    if (item.string("type") == "agent_message") {
      val text = item.string("text")?.takeIf { it.isNotBlank() } ?: return emptyList()
      val separated = if (wroteText) "\n\n$text" else text
      wroteText = true
      return listOf(ChatEvent.TextDelta(separated))
    }
    val call = toolCall(item) ?: return emptyList()
    return buildList {
      if (call.id !in started) add(ChatEvent.ToolUse(call))
      add(ChatEvent.ToolResult(call.id, toolOutput(item), toolFailed(item)))
    }
  }

  private fun toolCall(item: JsonObject): ChatToolCall? {
    val id = item.string("id") ?: return null
    return when (val type = item.string("type")) {
      null, "agent_message", "reasoning", "todo_list" -> null
      "command_execution" -> item.string("command").orEmpty().let { ChatToolCall(id, "shell", it, it) }
      "file_change" -> {
        val changes = (item["changes"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
        val lines = changes.map { "${it.string("kind") ?: "update"} ${it.string("path").orEmpty()}" }
        ChatToolCall(id, "edit", lines.joinToString("\n"), changes.firstOrNull()?.string("path"))
      }
      "mcp_tool_call" -> ChatToolCall(
        id,
        "mcp__${item.string("server")}__${item.string("tool")}",
        item["arguments"]?.renderToolContent().orEmpty(),
        item.string("tool"),
      )
      "web_search" -> ChatToolCall(id, "websearch", item.string("query").orEmpty(), item.string("query"))
      else -> ChatToolCall(id, type, item.renderToolContent(), null)
    }
  }

  private fun toolOutput(item: JsonObject): String =
    item.string("aggregated_output")
      ?: item.obj("error")?.string("message")
      ?: item["result"]?.renderToolContent()
      ?: item.string("status").orEmpty()

  private fun toolFailed(item: JsonObject): Boolean =
    item.string("status") == "failed" || (item.int("exit_code") ?: 0) != 0 || item.obj("error") != null
}
