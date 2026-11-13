package dev.skillbill.desktop.core.data.chat

import dev.skillbill.desktop.core.common.Diagnostics
import dev.skillbill.desktop.core.common.HostEnvironment
import dev.skillbill.desktop.core.domain.model.ChatEvent
import dev.skillbill.desktop.core.domain.model.ChatFailure
import dev.skillbill.desktop.core.domain.model.ChatScope
import dev.skillbill.desktop.core.domain.model.ChatStoreRead
import dev.skillbill.desktop.core.domain.model.ChatTurnRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onEach
import skillbill.infrastructure.launcher.process.cursor.AcpProvider
import java.util.concurrent.ConcurrentHashMap

/** Bounds the earlier turns each new ACP session is sent. */
private const val PROMPT_LIMIT = 120_000

/**
 * Runs each turn as one ACP process whose permission requests reach approval cards. Each turn opens a new ACP session
 * and sends the earlier turns of this chat, kept in [ChatStore], with the new message.
 */
abstract class FreshAcpChatTransport(
  private val host: HostEnvironment,
  private val store: ChatStore,
  private val diagnostics: Diagnostics,
  private val liveLauncher: LiveChatProcessLauncher,
  private val approvalAuthority: ChatApprovalAuthority?,
  private val provider: AcpProvider,
) : ChatTransport {
  override val agentId = provider.id
  override val approvalCapable = true
  private val turns = ConcurrentHashMap<String, Job>()

  override fun run(request: ChatTurnRequest): Flow<ChatEvent> = flow {
    val live = liveLauncher
    val authority = approvalAuthority
    if (authority == null) {
      emit(ChatEvent.Failed(ChatFailure.ApprovalUnsupported,
        "${provider.name} ACP requires an operator approval channel; the chat was not started."))
      return@flow
    }
    val historyRead = if (request.scope is ChatScope.NewTask) {
      store.newTaskHistoryRead(request.sessionId)
    } else if (request.firstTurn) ChatStoreRead.Value(emptyList()) else store.exchangesRead(request.sessionId)
    if (historyRead is ChatStoreRead.Failed) {
      val failure = historyRead.failure
      emit(ChatEvent.Failed(ChatFailure.ProcessFailed,
        "The saved chat history could not be read at ${failure.sourcePath}: ${failure.reason}. ${failure.recovery}"))
      return@flow
    }
    val history = (historyRead as? ChatStoreRead.Value)?.value.orEmpty()
    val prompt = replayPrompt(scopeSystemPrompt(request.scope), history, request.prompt)
    val environment = host.variables + mapOf("HOME" to (host.variables["HOME"] ?: host.userHome))
    val reply = StringBuilder()
    val job = currentCoroutineContext()[Job]
    if (job != null) turns[request.sessionId] = job
    try {
      emitAll(
        cursorAcpTurn(live, authority, store, diagnostics, host.variables["SHELL"], environment, request,
          saved = null, binding = null, prompt = prompt, provider = provider).onEach { event ->
          when (event) {
            is ChatEvent.TextDelta -> reply.append(event.text)
            ChatEvent.Completed ->
              store.saveExchanges(request.sessionId, history + ChatExchange(request.prompt, reply.toString().trim()))
            else -> Unit
          }
        }.onCompletion { cause ->
          if (cause is CancellationException && reply.isNotBlank() && request.scope !is ChatScope.NewTask) {
            store.saveExchanges(request.sessionId, history + ChatExchange(request.prompt, reply.toString().trim()))
          }
        },
      )
    } finally {
      if (job != null) turns.remove(request.sessionId, job)
    }
  }

  override fun end(sessionId: String) { turns.remove(sessionId)?.cancel(); deleteChatImages(sessionId) }
}

/** The scope, then as many of the latest exchanges as fit, then the new message. */
internal fun replayPrompt(scope: String, history: List<ChatExchange>, prompt: String): String {
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
