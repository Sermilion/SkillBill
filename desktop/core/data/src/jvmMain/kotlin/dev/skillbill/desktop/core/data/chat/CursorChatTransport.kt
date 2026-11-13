package dev.skillbill.desktop.core.data.chat

import dev.skillbill.desktop.core.common.Diagnostics
import dev.skillbill.desktop.core.common.HostEnvironment
import dev.skillbill.desktop.core.common.di.AppScope
import dev.skillbill.desktop.core.data.usage.cursorEnvironment
import dev.skillbill.desktop.core.domain.model.ChatEvent
import dev.skillbill.desktop.core.domain.model.ChatFailure
import dev.skillbill.desktop.core.domain.model.ChatScope
import dev.skillbill.desktop.core.domain.model.ChatTurnRequest
import dev.skillbill.desktop.core.domain.model.ChatStoreRead
import dev.skillbill.desktop.core.domain.model.CursorMcpDependency
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import java.util.concurrent.ConcurrentHashMap
import skillbill.infrastructure.launcher.process.cursor.CursorAcpMcpDependency
import skillbill.infrastructure.launcher.process.cursor.cursorAcpDependencyRefusal
import java.nio.file.Path
import me.tatarka.inject.annotations.Inject
import skillbill.contracts.system.AgentHomeEnvironmentKeys
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn


@Inject
@SingleIn(AppScope::class)
class CursorChatTransport(
  private val host: HostEnvironment,
  private val store: ChatStore,
  private val diagnostics: Diagnostics,
  private val liveLauncher: LiveChatProcessLauncher,
  private val approvalAuthority: ChatApprovalAuthority? = null,
) : ChatTransport {
  override val agentId = "cursor"
  override val approvalCapable = true
  private val turns = ConcurrentHashMap<String, Job>()

  override fun run(request: ChatTurnRequest): Flow<ChatEvent> = flow {
    val dependency = when (request.cursorMcpDependency) {
      CursorMcpDependency.ProjectUserOnly -> CursorAcpMcpDependency.ProjectUserOnly
      CursorMcpDependency.TeamDashboardRequired -> CursorAcpMcpDependency.TeamDashboardRequired
    }
    cursorAcpDependencyRefusal(dependency)?.let { reason ->
      diagnostics.record("chat.cursor_mcp_dependency_refused")
      emit(ChatEvent.Failed(ChatFailure.AgentUnsupported, reason))
      return@flow
    }
    val live = liveLauncher
    val authority = approvalAuthority
    if (authority == null) {
      emit(ChatEvent.Failed(ChatFailure.ApprovalUnsupported,
        "Cursor ACP requires an operator approval channel; the chat was not started."))
      return@flow
    }
    val savedRead = store.agentSessionRead(request.sessionId)
    if (savedRead is ChatStoreRead.Failed) {
      val failure = savedRead.failure
      emit(ChatEvent.Failed(ChatFailure.ProcessFailed,
        "The saved Cursor chat could not be read at ${failure.sourcePath}: ${failure.reason}. ${failure.recovery}"))
      return@flow
    }
    val saved = (savedRead as? ChatStoreRead.Value)?.value
    if (saved?.isBlank() == true || (!request.firstTurn && saved == null)) {
      emit(ChatEvent.Failed(ChatFailure.ProcessFailed,
        "The saved Cursor session is unavailable. Start an explicit new chat; history was not replaced."))
      return@flow
    }
    val environment = cursorEnvironment(host.variables, request.agent.profile) +
      mapOf("HOME" to (host.variables["HOME"] ?: host.userHome))
    val binding = CursorSessionBinding(
      environment[AgentHomeEnvironmentKeys.CURSOR_CONFIG_DIR] ?: environment["HOME"].orEmpty(),
      Path.of(request.scope.repositoryRoot).toAbsolutePath().normalize().toString(),

    )
    val bindingRead = store.cursorSessionBindingRead(request.sessionId)
    if (bindingRead is ChatStoreRead.Failed) {
      val failure = bindingRead.failure
      emit(ChatEvent.Failed(ChatFailure.ProcessFailed,
        "The saved Cursor binding could not be read at ${failure.sourcePath}: ${failure.reason}. ${failure.recovery}"))
      return@flow
    }
    if (saved != null && (bindingRead as? ChatStoreRead.Value)?.value != binding) {
      emit(ChatEvent.Failed(ChatFailure.ProcessFailed,
        "The saved Cursor session belongs to a different or unknown profile or checkout. " +
          "Start an explicit new chat; history was not replaced."))
      return@flow
    }
    val prompt = if (saved == null || request.scope is ChatScope.NewTask) {
      withScope(scopeSystemPrompt(request.scope), request.prompt)
    } else request.prompt
    val job = currentCoroutineContext()[Job]
    if (job != null) turns[request.sessionId] = job
    try {
      emitAll(cursorAcpTurn(live, authority, store, diagnostics, host.variables["SHELL"],
        environment, request, saved, binding, prompt))
    } finally {
      if (job != null) turns.remove(request.sessionId, job)
    }
  }

  override fun end(sessionId: String) { turns.remove(sessionId)?.cancel(); deleteChatImages(sessionId) }
}

internal data class CursorSessionBinding(val profileIdentity: String, val workingDirectory: String)
