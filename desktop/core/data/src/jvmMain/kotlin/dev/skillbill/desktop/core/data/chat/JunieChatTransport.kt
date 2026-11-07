package dev.skillbill.desktop.core.data.chat

import dev.skillbill.desktop.core.common.Diagnostics
import dev.skillbill.desktop.core.common.HostEnvironment
import dev.skillbill.desktop.core.common.di.AppScope
import dev.skillbill.desktop.core.domain.model.ChatEvent
import dev.skillbill.desktop.core.domain.model.ChatScope
import dev.skillbill.desktop.core.domain.model.ChatTurnRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onEach
import dev.skillbill.desktop.core.domain.model.ChatFailure
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import skillbill.infrastructure.launcher.process.cursor.AcpProvider
import java.util.concurrent.ConcurrentHashMap
import me.tatarka.inject.annotations.Inject
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn

/** Bounds the earlier turns each new ACP session is sent. */
private const val PROMPT_LIMIT = 120_000

/**
 * Runs each turn as one `junie --acp` process whose permission requests reach approval cards. Each turn opens a new ACP
 * session and sends the earlier turns of this chat, kept in [ChatStore], with the new message.
 */
@Inject
@SingleIn(AppScope::class)
class JunieChatTransport(
  private val host: HostEnvironment,
  private val launcher: ChatProcessLauncher,
  private val store: ChatStore,
  private val diagnostics: Diagnostics,
  private val liveLauncher: LiveChatProcessLauncher? = null,
  private val approvalAuthority: ChatApprovalAuthority? = null,
) : ChatTransport {
  override val agentId = "junie"
  override val approvalCapable = true
  private val turns = ConcurrentHashMap<String, Job>()

  override fun run(request: ChatTurnRequest): Flow<ChatEvent> = flow {
    val live = liveLauncher
    val authority = approvalAuthority
    if (live == null || authority == null) {
      emit(ChatEvent.Failed(ChatFailure.ApprovalUnsupported,
        "Junie ACP requires a live process and operator approval channel; the chat was not started."))
      return@flow
    }
    val history = if (request.scope is ChatScope.NewTask) {
      store.newTaskHistory(request.sessionId)
    } else if (request.firstTurn) emptyList() else store.exchanges(request.sessionId)
    val prompt = juniePrompt(scopeSystemPrompt(request.scope), history, request.prompt)
    val environment = host.variables + mapOf("HOME" to (host.variables["HOME"] ?: host.userHome))
    val reply = StringBuilder()
    val job = currentCoroutineContext()[Job]
    if (job != null) turns[request.sessionId] = job
    try {
      emitAll(
        cursorAcpTurn(live, launcher, authority, store, diagnostics, host.variables["SHELL"], environment, request,
          saved = null, binding = null, prompt = prompt, provider = AcpProvider.Junie).onEach { event ->
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
