package dev.skillbill.desktop.core.data.chat

import dev.skillbill.desktop.core.common.Diagnostics
import dev.skillbill.desktop.core.common.HostEnvironment
import dev.skillbill.desktop.core.common.di.AppScope
import dev.skillbill.desktop.core.data.session.DatabaseSessionManager
import dev.skillbill.desktop.core.domain.model.ChatEvent
import dev.skillbill.desktop.core.domain.model.ChatFailure
import dev.skillbill.desktop.core.domain.model.ChatStopSettlement
import dev.skillbill.desktop.core.domain.model.ChatScope
import dev.skillbill.desktop.core.domain.model.ChatTurnRequest
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.collect
import me.tatarka.inject.annotations.Inject
import skillbill.contracts.system.AgentHomeEnvironmentKeys
import skillbill.contracts.system.OpenCodeLaunchIdentity
import skillbill.infrastructure.launcher.mcp.RUNTIME_MCP_SERVER_NAME
import skillbill.infrastructure.launcher.mcp.resolveRuntimeMcpBin
import skillbill.infrastructure.launcher.mcp.runtimeMcpServerEnvironment
import skillbill.infrastructure.launcher.process.cursor.AcpProvider
import skillbill.infrastructure.launcher.process.cursor.AcpEnvVariable
import skillbill.infrastructure.launcher.process.cursor.AcpStdioMcpServer
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference

@Inject
@SingleIn(AppScope::class)
class OpenCodeChatTransport(
  private val host: HostEnvironment,
  private val store: ChatStore,
  private val diagnostics: Diagnostics,
  private val liveLauncher: LiveChatProcessLauncher,
  private val approvalAuthority: ChatApprovalAuthority? = null,
  private val sessions: DatabaseSessionManager,
) : ChatTransport {
  override val agentId = "opencode"
  override val approvalCapable = true
  private val turns = ConcurrentHashMap<String, OpenCodeTurnOwner>()

  override fun run(request: ChatTurnRequest): Flow<ChatEvent> = flow {
    val owner = OpenCodeTurnOwner(request.sessionId, UUID.randomUUID().toString())
    if (turns.putIfAbsent(request.sessionId, owner) != null) {
      emit(ChatEvent.Failed(ChatFailure.ProcessFailed, "An OpenCode turn is already active for this chat."))
      return@flow
    }
    try {
      if (request.images.isNotEmpty()) {
        owner.record(ChatEvent.Failed(ChatFailure.ProcessFailed,
          "OpenCode image support is not confirmed for the selected model; no prompt was sent."))
        emit(requireNotNull(owner.disposition()))
        return@flow
      }
      val authority = approvalAuthority
      if (authority == null) {
        owner.record(ChatEvent.Failed(ChatFailure.ApprovalUnsupported,
          "OpenCode chat requires an operator approval channel; the chat was not started."))
        emit(requireNotNull(owner.disposition()))
        return@flow
      }
      val cwd = try {
        Path.of(request.scope.repositoryRoot).toRealPath().toString()
      } catch (_: Exception) {
        val failure = ChatEvent.Failed(ChatFailure.ProcessFailed, "The OpenCode checkout cannot be resolved; no prompt was sent.")
        owner.record(failure)
        emit(failure)
        return@flow
      }
      val environment = host.variables + mapOf("HOME" to (host.variables["HOME"] ?: host.userHome)) +
        request.agent.profile?.let { mapOf(AgentHomeEnvironmentKeys.OPENCODE_CONFIG_DIR to it) }.orEmpty()
      val identity = try {
        OpenCodeLaunchIdentity.capture(environment, Path.of(host.userHome), Path.of(cwd), request.agent.profile)
      } catch (_: Exception) {
        val failure = ChatEvent.Failed(ChatFailure.ProcessFailed, "The selected OpenCode profile cannot be resolved; no prompt was sent.")
        owner.record(failure)
        emit(failure)
        return@flow
      }
      val binding = OpenCodeSessionBinding(
        identity.effectiveConfigDirectory,
        identity.toString(),
        identity.effectiveDataHome,
        cwd,
      )
      val savedState = store.openCodeSavedState(request.sessionId)
      val saved = when (savedState) {
        OpenCodeSavedState.Missing -> null
        is OpenCodeSavedState.Unusable -> {
          diagnostics.record("chat.opencode_resume_refused")
          val failure = ChatEvent.Failed(ChatFailure.ProcessFailed,
            "The saved OpenCode record cannot be read safely. Its original data remains at ${savedState.path}. Start an explicit new chat after preserving or repairing it.")
          owner.record(failure)
          emit(failure)
          return@flow
        }
        is OpenCodeSavedState.Readable -> {
          if (savedState.handle != null && (savedState.handle.isBlank() || savedState.agentId != "opencode" || savedState.binding != binding)) {
            diagnostics.record("chat.opencode_resume_refused")
            val failure = ChatEvent.Failed(ChatFailure.ProcessFailed,
              "The saved OpenCode conversation no longer matches its provider, profile, data directory or checkout. Start an explicit new chat; history was not replaced.")
            owner.record(failure)
            emit(failure)
            return@flow
          }
          if (savedState.handle == null && (!request.firstTurn || savedState.binding != null || savedState.agentId?.let { it != "opencode" } == true)) {
            diagnostics.record("chat.opencode_resume_refused")
            val failure = ChatEvent.Failed(ChatFailure.ProcessFailed,
              "The saved OpenCode conversation has no usable provider handle. Start an explicit new chat; history was not replaced.")
            owner.record(failure)
            emit(failure)
            return@flow
          }
          savedState.handle
        }
      }
      if (!request.firstTurn && saved == null) {
        diagnostics.record("chat.opencode_resume_refused")
        val failure = ChatEvent.Failed(ChatFailure.ProcessFailed,
          "The saved OpenCode conversation has no matching provider binding. Start an explicit new chat; history was not replaced.")
        owner.record(failure)
        emit(failure)
        return@flow
      }
      val component = sessions.sessions.value
      val runtimeMcp = resolveRuntimeMcpBin(host.variables, Path.of(host.userHome))
      val databasePath = component?.let { runCatching { it.runtime.databaseSession.resolveDbPath() }.getOrNull() }
      if (component == null || runtimeMcp == null || databasePath == null || !sessions.isCurrent(component)) {
        val failure = ChatEvent.Failed(ChatFailure.ProcessFailed,
          "The active runtime MCP context is unavailable; OpenCode was not started.")
        owner.record(failure)
        emit(failure)
        return@flow
      }
      val mcpServers = listOf(AcpStdioMcpServer(
        RUNTIME_MCP_SERVER_NAME,
        runtimeMcp.toString(),
        emptyList(),
        runtimeMcpServerEnvironment(databasePath).map { (name, value) -> AcpEnvVariable(name, value) },
      ))
      if (owner.stopRequested.isCompleted || !sessions.isCurrent(component)) {
        val failure = ChatEvent.Failed(ChatFailure.ProcessFailed,
          "The OpenCode turn was stopped or its runtime context changed before the prompt was sent.")
        owner.record(failure)
        emit(failure)
        return@flow
      }
      val prompt = if (saved == null || request.scope is ChatScope.NewTask) {
        withScope(scopeSystemPrompt(request.scope), request.prompt)
      } else request.prompt
      if (owner.stopRequested.isCompleted) {
        val failure = ChatEvent.Failed(ChatFailure.ProcessFailed, "The OpenCode turn was stopped before its prompt was sent.")
        owner.record(failure)
        emit(failure)
        return@flow
      }
      cursorAcpTurn(liveLauncher, authority, store, diagnostics, host.variables["SHELL"],
        environment, request, saved, null, prompt, AcpProvider.OpenCode, binding, mcpServers, component,
        owner.stopRequested, owner::record).collect { event ->
        owner.record(event)
        emit(event)
      }
    } catch (cancelled: CancellationException) {
      owner.record(ChatEvent.Failed(ChatFailure.ProcessFailed, "The OpenCode turn was cancelled."))
      throw cancelled
    } finally {
      owner.finish()
      turns.remove(request.sessionId, owner)
    }
  }

  override fun end(sessionId: String) {
    turns[sessionId]?.requestStop("The OpenCode chat was ended.")
    deleteChatImages(sessionId)
  }

  override suspend fun stopAndAwait(sessionId: String): ChatStopSettlement =
    turns[sessionId]?.stopAndAwait() ?: ChatStopSettlement.AlreadyFinished
}

private class OpenCodeTurnOwner(val sessionId: String, val generation: String) {
  val stopRequested = CompletableDeferred<String>()
  private val lock = Any()
  private val terminal = AtomicReference<ChatEvent?>(null)
  private val settled = CompletableDeferred<Unit>()

  fun requestStop(reason: String) {
    synchronized(lock) {
      if (terminal.get() == null) stopRequested.complete(reason)
    }
  }

  suspend fun stopAndAwait(): ChatStopSettlement {
    val alreadyFinished = synchronized(lock) {
      if (terminal.get() != null) true else {
        stopRequested.complete("The OpenCode turn was stopped.")
        false
      }
    }
    if (alreadyFinished) return ChatStopSettlement.AlreadyFinished
    settled.await()
    return ChatStopSettlement.Settled
  }

  fun record(event: ChatEvent) {
    if (event is ChatEvent.Completed || event is ChatEvent.Failed) synchronized(lock) {
      terminal.compareAndSet(null, event)
    }
  }

  fun disposition(): ChatEvent? = terminal.get()

  fun finish() {
    synchronized(lock) {
      if (terminal.get() == null) terminal.compareAndSet(null,
        ChatEvent.Failed(ChatFailure.ProcessFailed, "The OpenCode turn ended without terminal settlement."))
    }
    settled.complete(Unit)
  }
}
