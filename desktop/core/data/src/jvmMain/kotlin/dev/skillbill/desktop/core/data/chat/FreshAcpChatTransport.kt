package dev.skillbill.desktop.core.data.chat

import dev.skillbill.desktop.core.common.Diagnostics
import dev.skillbill.desktop.core.common.HostEnvironment
import dev.skillbill.desktop.core.domain.model.ChatEvent
import dev.skillbill.desktop.core.domain.model.ChatFailure
import dev.skillbill.desktop.core.domain.model.ChatHistoryLine
import dev.skillbill.desktop.core.domain.model.ChatHistoryRole
import dev.skillbill.desktop.core.domain.model.ChatScope
import dev.skillbill.desktop.core.domain.model.ChatTurnRequest
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import skillbill.contracts.system.AgentHomeEnvironmentKeys
import skillbill.infrastructure.launcher.process.cursor.AcpProvider
import java.util.concurrent.ConcurrentHashMap

/** Bounds the earlier turns each new ACP session is sent. */
private const val PROMPT_LIMIT = 120_000

/**
 * Runs each turn as one ACP process whose permission requests reach approval cards. Each turn opens a new ACP session
 * and sends earlier conversation lines already saved in [ChatStore] with the new message.
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
    val history = if (request.scope is ChatScope.NewTask) {
      store.newTaskHistory(request.sessionId)
    } else if (request.firstTurn) emptyList() else store.replayHistory(request.sessionId)
    val prompt = replayPrompt(scopeSystemPrompt(request.scope), history, request.prompt)
    val environment = host.variables + mapOf("HOME" to (host.variables["HOME"] ?: host.userHome))
    val job = currentCoroutineContext()[Job]
    if (job != null) turns[request.sessionId] = job
    try {
      emitAll(acpTurn(
        live,
        authority,
        store,
        diagnostics,
        launchFactory = { dependency ->
          acpTurnLaunch(provider, host.variables["SHELL"], environment, request, null, prompt, dependency)
        },
        provider = provider,
        profileIdentity = environment[AgentHomeEnvironmentKeys.CURSOR_CONFIG_DIR]
          .takeIf { provider == AcpProvider.Cursor } ?: environment["HOME"].orEmpty(),
        request = request,
        saved = null,
        binding = null,
      ))
    } finally {
      if (job != null) turns.remove(request.sessionId, job)
    }
  }

  override fun end(sessionId: String) { turns.remove(sessionId)?.cancel(); deleteChatImages(sessionId) }
}

/** The scope, then as much of the latest conversation history as fits, then the new message. */
internal fun replayPrompt(scope: String, history: List<ChatHistoryLine>, prompt: String): String {
  val fixed = withScope(scope, prompt)
  var budget = PROMPT_LIMIT - fixed.length
  val conversation = history.filter { it.role == ChatHistoryRole.Operator || it.role == ChatHistoryRole.Agent }
  val kept = conversation.asReversed().takeWhile { line ->
    budget -= line.text.length + 16
    budget >= 0
  }.asReversed()
  if (kept.isEmpty()) return fixed
  val transcript = kept.joinToString("\n\n") {
    val speaker = if (it.role == ChatHistoryRole.Operator) "Operator" else "You"
    "$speaker:\n${it.text}"
  }
  return withScope(scope, "Earlier in this chat:\n\n$transcript\n\n---\n\nThe operator's new message:\n\n$prompt")
}
