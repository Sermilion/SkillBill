package dev.skillbill.desktop.core.data.chat

import dev.skillbill.desktop.core.common.Diagnostics
import dev.skillbill.desktop.core.common.HostEnvironment
import dev.skillbill.desktop.core.common.di.AppScope
import dev.skillbill.desktop.core.domain.model.ChatEvent
import dev.skillbill.desktop.core.domain.model.ChatTurnRequest
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.onEach
import me.tatarka.inject.annotations.Inject
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn

/** The prompt is one argv entry, and Linux caps a single argument at 128 KiB. */
private const val PROMPT_LIMIT = 120_000

/**
 * Runs each turn as one `junie` text-output process, as executions run. Junie's text output carries no session id, so each
 * turn sends the earlier turns of this chat, kept in [ChatStore], with the new message instead of resuming a session.
 */
@Inject
@SingleIn(AppScope::class)
class JunieChatTransport(
  private val host: HostEnvironment,
  private val launcher: ChatProcessLauncher,
  private val store: ChatStore,
  private val diagnostics: Diagnostics,
) : ChatTransport {
  override val agentId = "junie"

  override fun run(request: ChatTurnRequest): Flow<ChatEvent> {
    val history = if (request.firstTurn) emptyList() else store.exchanges(request.sessionId)
    val prompt = juniePrompt(scopeSystemPrompt(request.scope, request.mode), history, request.prompt)
    val command = ChatCommand(
      argv = agentCommand("junie", junieArguments(request, prompt), host.variables["SHELL"]),
      workingDirectory = request.scope.repositoryRoot,
      environment = host.variables,
      stdin = "",
    )
    val reply = StringBuilder()
    return runChatTurn(launcher, diagnostics, command, JunieTextDecoder).onEach { event ->
      when (event) {
        is ChatEvent.TextDelta -> reply.append(event.text)
        ChatEvent.Completed -> store.saveExchanges(request.sessionId, history + ChatExchange(request.prompt, reply.toString().trim()))
        else -> Unit
      }
    }
  }
}

internal fun junieArguments(request: ChatTurnRequest, prompt: String): List<String> = buildList {
  addAll(listOf("--project", request.scope.repositoryRoot, "--output-format", "text", "--skip-update-check"))
  request.agent.model?.let { addAll(listOf("--model", it)) }
  // Junie rejects --effort without a model.
  if (request.agent.model != null) request.agent.effort?.let { addAll(listOf("--effort", it)) }
  add(prompt)
}

/** The scope, then as many of the latest exchanges as fit, then the new message. */
internal fun juniePrompt(scope: String, history: List<ChatExchange>, prompt: String): String {
  val fixed = withScope(scope, prompt)
  var budget = PROMPT_LIMIT - fixed.length
  val kept = history.asReversed().takeWhile { exchange ->
    budget -= exchange.operator.length + exchange.agent.length + 32
    budget >= 0
  }.asReversed()
  if (kept.isEmpty()) return fixed
  val transcript = kept.joinToString("\n\n") { "Operator:\n${it.operator}\n\nYou:\n${it.agent}" }
  return withScope(scope, "Earlier in this chat:\n\n$transcript\n\n---\n\nThe operator's new message:\n\n$prompt")
}

/** Text output streams the reply line by line; exit 0 ends the turn. */
internal object JunieTextDecoder : ChatLineDecoder {
  override val json = false

  override fun decode(line: String): List<ChatEvent> = listOf(ChatEvent.TextDelta(line + "\n"))

  override fun exited(code: Int, stderrTail: String): List<ChatEvent> =
    if (code == 0) listOf(ChatEvent.Completed) else listOf(exitFailure(code, stderrTail))
}
