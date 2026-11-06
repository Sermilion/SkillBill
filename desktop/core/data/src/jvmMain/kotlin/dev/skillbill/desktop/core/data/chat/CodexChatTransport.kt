package dev.skillbill.desktop.core.data.chat

import dev.skillbill.desktop.core.common.Diagnostics
import dev.skillbill.desktop.core.common.HostEnvironment
import dev.skillbill.desktop.core.common.di.AppScope
import dev.skillbill.desktop.core.domain.model.ChatEvent
import dev.skillbill.desktop.core.domain.model.ChatFailure
import dev.skillbill.desktop.core.domain.model.ChatScope
import dev.skillbill.desktop.core.domain.model.ChatTurnRequest
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import me.tatarka.inject.annotations.Inject
import skillbill.contracts.system.AgentHomeEnvironmentKeys
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn
import java.util.concurrent.ConcurrentHashMap

/** Runs each turn in Codex's provider-managed sandbox and approval configuration. */
@Inject
@SingleIn(AppScope::class)
class CodexChatTransport(
  private val host: HostEnvironment,
  private val launcher: LiveChatProcessLauncher,
  private val store: ChatStore,
  private val diagnostics: Diagnostics,
  private val approvalAuthority: ChatApprovalAuthority? = null,
) : ChatTransport {
  override val agentId = "codex"
  override val approvalCapable = true
  private val activeTurns = ConcurrentHashMap<String, Job>()

  override fun run(request: ChatTurnRequest): Flow<ChatEvent> = flow {
    if (approvalAuthority == null) {
      diagnostics.record("chat.codex_approval_unavailable")
      emit(ChatEvent.Failed(ChatFailure.ApprovalUnsupported,
        "Codex requires a live operator approval channel; the chat was not started."))
      return@flow
    }
    val collection = currentCoroutineContext()[Job]
    if (collection != null) activeTurns[request.sessionId] = collection
    try {
      val savedThread = store.agentSession(request.sessionId)
      if (savedThread != null && savedThread.isBlank()) {
        diagnostics.record("chat.codex_thread_invalid")
        emit(
          ChatEvent.Failed(
            ChatFailure.ProcessFailed,
            "This Codex chat has an invalid saved provider thread. Start an explicit new chat to continue.",
          ),
        )
        return@flow
      }
      val thread = savedThread ?: if (request.firstTurn) null else {
        diagnostics.record("chat.codex_thread_missing")
        emitAll(flowOf(
          ChatEvent.Failed(
            ChatFailure.ProcessFailed,
            "This Codex chat has no saved provider thread. Start an explicit new chat to continue.",
          ),
        ))
        return@flow
      }
      val imagePaths = try {
        writeChatImages(request.sessionId, request.images)
      } catch (error: java.io.IOException) {
        deleteChatImages(request.sessionId)
        emit(ChatEvent.Failed(ChatFailure.ProcessFailed, "Could not save a pasted image: ${error.message}"))
        return@flow
      }
      try {
        val prompt = if (thread == null || request.scope is ChatScope.NewTask) {
          withScope(scopeSystemPrompt(request.scope, request.mode), request.prompt)
        } else {
          request.prompt
        }
        val command = ChatCommand(
          argv = agentCommand("codex", listOf("app-server"), host.variables["SHELL"]),
          workingDirectory = request.scope.repositoryRoot,
          environment =
            host.variables + request.agent.profile?.let { mapOf(AgentHomeEnvironmentKeys.CODEX_HOME to it) }.orEmpty(),
          stdin = "",
          probeInvocation = agentInvocation("codex", host.variables["SHELL"]),
        )
        emitAll(
          codexAppServerTurn(
            launcher = launcher,
            diagnostics = diagnostics,
            command = command,
            request = request,
            savedThread = thread,
            prompt = prompt,
            imagePaths = imagePaths,
            saveThread = { store.saveAgentSession(request.sessionId, it) },
            openApproval = { writeLine, stop, onCard ->
              approvalAuthority?.openCodex(
                sessionId = request.sessionId,
                repositoryRoot = request.scope.repositoryRoot,
                mode = request.mode,
                profileIdentity = command.environment[AgentHomeEnvironmentKeys.CODEX_HOME] ?: host.userHome,
                writeLine = writeLine,
                stopProcess = stop,
                onCard = onCard,
              ) ?: CodexChatApprovalOpening.Refused(
                "Operator approval is unavailable because the Codex approval authority is not configured.",
              )
            },
          ),
        )
      } finally {
        deleteChatImages(request.sessionId)
      }
    } finally {
      if (collection != null) activeTurns.remove(request.sessionId, collection)
    }
  }

  override fun end(sessionId: String) {
    val active = activeTurns[sessionId]
    if (active == null) deleteChatImages(sessionId) else active.cancel()
  }
}
