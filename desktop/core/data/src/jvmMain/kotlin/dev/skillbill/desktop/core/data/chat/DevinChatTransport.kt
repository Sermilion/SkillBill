package dev.skillbill.desktop.core.data.chat

import dev.skillbill.desktop.core.common.Diagnostics
import dev.skillbill.desktop.core.common.HostEnvironment
import dev.skillbill.desktop.core.common.di.AppScope
import dev.skillbill.desktop.core.data.session.DatabaseSessionManager
import dev.skillbill.desktop.core.domain.model.ChatEvent
import dev.skillbill.desktop.core.domain.model.ChatFailure
import dev.skillbill.desktop.core.domain.model.ChatScope
import dev.skillbill.desktop.core.domain.model.ChatSteeringPolicy
import dev.skillbill.desktop.core.domain.model.ChatSteeringRequest
import dev.skillbill.desktop.core.domain.model.ChatSteeringResult
import dev.skillbill.desktop.core.domain.model.ChatSteeringUpdate
import dev.skillbill.desktop.core.domain.model.ChatStopSettlement
import dev.skillbill.desktop.core.domain.model.ChatTurnRequest
import dev.skillbill.desktop.core.domain.model.hasSameConversationIdentity
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import me.tatarka.inject.annotations.Inject
import skillbill.infrastructure.launcher.mcp.RUNTIME_MCP_SERVER_NAME
import skillbill.infrastructure.launcher.mcp.resolveRuntimeMcpBin
import skillbill.infrastructure.launcher.mcp.runtimeMcpServerEnvironment
import skillbill.infrastructure.launcher.process.cursor.AcpEnvVariable
import skillbill.infrastructure.launcher.process.cursor.AcpProvider
import skillbill.infrastructure.launcher.process.cursor.AcpStdioMcpServer
import skillbill.infrastructure.launcher.process.cursor.CursorAcpImage
import skillbill.infrastructure.launcher.process.cursor.CursorAcpSession
import skillbill.infrastructure.launcher.process.cursor.CursorAcpSteerOutcome
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn
import java.nio.file.Path
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

@Inject
@SingleIn(AppScope::class)
class DevinChatTransport(
  private val host: HostEnvironment,
  private val store: ChatStore,
  private val diagnostics: Diagnostics,
  private val liveLauncher: LiveChatProcessLauncher,
  private val approvalAuthority: ChatApprovalAuthority?,
  private val sessions: DatabaseSessionManager,
) : ChatTransport {
  override val agentId = "devin"
  override val approvalCapable = true
  override val steeringPolicy = ChatSteeringPolicy.LiveWhenAvailable
  private val turns = ConcurrentHashMap<String, DevinTurnOwner>()

  private class ActiveTurn(val generation: Long, val protocol: CursorAcpSession)

  private class DeliveryRecord(var active: ActiveTurn?) {
    var result: ChatSteeringResult? = null
    var authoritative = false
  }

  private val deliveryLock = Any()
  private val generations = ConcurrentHashMap<String, AtomicLong>()
  private val activeSteering = ConcurrentHashMap<String, ActiveTurn>()
  private val deliveries = mutableMapOf<Pair<String, String>, DeliveryRecord>()
  private val deliveryUpdates = MutableStateFlow<Map<Pair<String, String>, ChatSteeringUpdate>>(emptyMap())

  override suspend fun steer(request: ChatSteeringRequest): ChatSteeringResult = withContext(Dispatchers.IO) {
    val active = activeSteering[request.sessionId]
      ?: return@withContext ChatSteeringResult.NoActiveTurn(request.deliveryId, "Devin has no active turn")
    val key = request.sessionId to request.deliveryId
    val record = DeliveryRecord(active)
    if (synchronized(deliveryLock) { deliveries.putIfAbsent(key, record) != null }) {
      return@withContext ChatSteeringResult.Rejected(request.deliveryId, "This delivery is already tracked")
    }
    val outcome = active.protocol.steer(
      request.deliveryId,
      request.text,
      request.images.map { CursorAcpImage(Base64.getEncoder().encodeToString(it.bytes), it.mediaType) },
    )
    publish(record, key, when (outcome) {
      CursorAcpSteerOutcome.Pending -> ChatSteeringResult.DeliveryUncertain(
        request.deliveryId, "Devin may have received this follow-up; acknowledgement is pending")
      CursorAcpSteerOutcome.WriteFailed -> ChatSteeringResult.NoActiveTurn(
        request.deliveryId, "Devin's turn ended before the follow-up was written")
      CursorAcpSteerOutcome.Inactive -> ChatSteeringResult.NoActiveTurn(
        request.deliveryId, "Devin has no prompt in flight")
    })
  }

  override fun steeringUpdates(sessionId: String): Flow<ChatSteeringUpdate> = flow {
    deliveryUpdates.collect { snapshot ->
      snapshot.values.filter { it.sessionId == sessionId }.forEach { emit(it) }
    }
  }

  override fun settleSteering(sessionId: String, deliveryId: String): ChatSteeringResult {
    val key = sessionId to deliveryId
    val record = synchronized(deliveryLock) { deliveries[key] }
      ?: return ChatSteeringResult.NoActiveTurn(deliveryId, "No pending Devin delivery remains")
    return synchronized(deliveryLock) {
      publishLocked(record, key, record.result ?: ChatSteeringResult.DeliveryUncertain(
        deliveryId, "Devin may have received this follow-up; acknowledgement is pending"))
    }
  }

  override fun retireSteering(sessionId: String, deliveryId: String) {
    val key = sessionId to deliveryId
    val record = synchronized(deliveryLock) {
      deliveries.remove(key)?.also { deliveryUpdates.update { snapshot -> snapshot - key } }
    } ?: return
    record.active?.protocol?.dropSteer(deliveryId)
  }

  private fun steeringResponse(sessionId: String, deliveryId: String, accepted: Boolean, detail: String?) {
    val key = sessionId to deliveryId
    val record = synchronized(deliveryLock) { deliveries[key] }
    if (record == null) {
      diagnostics.record("chat.devin_steering_stale_response")
      return
    }
    publish(record, key, if (accepted) ChatSteeringResult.Accepted(deliveryId) else ChatSteeringResult.Rejected(
      deliveryId, detail ?: "Devin did not accept the follow-up"))
  }

  private fun publish(record: DeliveryRecord, key: Pair<String, String>, result: ChatSteeringResult): ChatSteeringResult =
    synchronized(deliveryLock) { publishLocked(record, key, result) }

  private fun publishLocked(record: DeliveryRecord, key: Pair<String, String>, result: ChatSteeringResult): ChatSteeringResult {
    if (deliveries[key] !== record) {
      diagnostics.record("chat.devin_stale_steering_update")
      return result
    }
    val authoritative = result !is ChatSteeringResult.DeliveryUncertain
    if (record.authoritative || (!authoritative && record.result != null)) return record.result ?: result
    record.result = result
    record.authoritative = authoritative
    deliveryUpdates.update { it + (key to ChatSteeringUpdate(key.first, result)) }
    return result
  }

  override fun run(request: ChatTurnRequest): Flow<ChatEvent> = flow {
    val owner = DevinTurnOwner()
    if (turns.putIfAbsent(request.sessionId, owner) != null) {
      emit(ChatEvent.Failed(ChatFailure.ProcessFailed, "A Devin turn is already active for this chat."))
      return@flow
    }
    val generation = generations.computeIfAbsent(request.sessionId) { AtomicLong() }.incrementAndGet()
    try {
      val authority = approvalAuthority
      if (authority == null) {
        emit(owner.record(ChatEvent.Failed(ChatFailure.ApprovalUnsupported,
          "Devin chat requires a live operator approval channel; the chat was not started.")))
        return@flow
      }
      if (request.agent.profile != null) {
        diagnostics.record("chat.devin_profile_refused")
        emit(owner.record(ChatEvent.Failed(ChatFailure.AgentUnsupported,
          "Devin uses credentials from the default CLI environment. Remove the selected profile and retry; the chat was not started.")))
        return@flow
      }
      if (request.agent.effort != null) {
        diagnostics.record("chat.devin_effort_refused")
        emit(owner.record(ChatEvent.Failed(ChatFailure.AgentUnsupported,
          "Devin requires a complete model UID. Remove the separate effort setting; the chat was not started.")))
        return@flow
      }
      val cwd = canonicalDirectory(request.scope.repositoryRoot)
      if (cwd == null) {
        emit(owner.record(ChatEvent.Failed(ChatFailure.ProcessFailed,
          "The Devin working directory cannot be resolved; no prompt was sent.")))
        return@flow
      }
      val history = request.savedHistory
      if (!historyMatches(request, history, cwd)) {
        diagnostics.record("chat.devin_saved_identity_refused")
        emit(owner.record(ChatEvent.Failed(ChatFailure.ProcessFailed,
          "The Devin conversation identity or checkout does not match its saved record. Preserve the history and start a separate new chat.")))
        return@flow
      }
      val saved = when (val state = store.devinSavedState(request.sessionId)) {
        DevinSavedState.Missing -> {
          if (!request.firstTurn || history == null) {
            diagnostics.record("chat.devin_resume_refused")
            emit(owner.record(ChatEvent.Failed(ChatFailure.ProcessFailed,
              "The Devin conversation has no saved provider handle. Preserve the history and start a separate new chat.")))
            return@flow
          }
          null
        }
        is DevinSavedState.Unusable -> {
          diagnostics.record("chat.devin_saved_record_unusable")
          emit(owner.record(ChatEvent.Failed(ChatFailure.ProcessFailed,
            "The saved Devin record cannot be read safely. Its original data remains at ${state.path}. Preserve or repair it before starting another chat.")))
          return@flow
        }
        is DevinSavedState.Readable -> {
          val savedRoot = state.checkoutRoot
          val identityMatches = state.agent?.agentId == "devin" && state.agent.profile == request.agent.profile &&
            state.executionId == history?.executionId && state.newTask.hasSameConversationIdentity(history?.newTask) &&
            savedRoot != null && canonicalDirectory(savedRoot) == cwd &&
            (state.entry == null || savedIdentityMatches(state.entry, history, cwd, request))
          if ((state.agent != null || state.executionId != null || state.newTask != null || state.handle != null) && !identityMatches) {
            diagnostics.record("chat.devin_saved_identity_refused")
            emit(owner.record(ChatEvent.Failed(ChatFailure.ProcessFailed,
              "The saved Devin conversation belongs to a different provider identity or checkout. Its history was not changed.")))
            return@flow
          }
          if (state.handle.isNullOrBlank()) {
            val eligibleDraft = state.entry?.let { it.newTask != null || it.executionId != null } == true
            if (!request.firstTurn || !eligibleDraft) {
              emit(owner.record(ChatEvent.Failed(ChatFailure.ProcessFailed,
                "The Devin conversation has no acquired provider handle. Preserve the history and start a separate new chat.")))
              return@flow
            }
          }
          state.handle
        }
      }
      if (!request.firstTurn && saved == null) {
        emit(owner.record(ChatEvent.Failed(ChatFailure.ProcessFailed,
          "The Devin conversation has no safe provider handle to load. Its history was not changed.")))
        return@flow
      }
      val component = sessions.sessions.value
      val runtimeMcp = resolveRuntimeMcpBin(host.variables, Path.of(host.userHome))
      val databasePath = component?.let { runCatching { it.runtime.databaseSession.resolveDbPath() }.getOrNull() }
      if (component == null || runtimeMcp == null || databasePath == null || !sessions.isCurrent(component)) {
        diagnostics.record("chat.devin_runtime_context_refused")
        emit(owner.record(ChatEvent.Failed(ChatFailure.ProcessFailed,
          "The active runtime MCP context is unavailable or changed; Devin was not started.")))
        return@flow
      }
      val mcpServers = listOf(AcpStdioMcpServer(
        RUNTIME_MCP_SERVER_NAME,
        runtimeMcp.toString(),
        emptyList(),
        runtimeMcpServerEnvironment(databasePath).map { (name, value) -> AcpEnvVariable(name, value) },
      ))
      if (owner.stopRequested.isCompleted || !sessions.isCurrent(component)) {
        emit(owner.record(ChatEvent.Failed(ChatFailure.ProcessFailed,
          "The Devin turn was stopped or its runtime context changed before launch.")))
        return@flow
      }
      val environment = host.variables + mapOf("HOME" to (host.variables["HOME"] ?: host.userHome))
      val prompt = if (saved == null || request.scope is ChatScope.NewTask) {
        withScope(scopeSystemPrompt(request.scope), request.prompt)
      } else request.prompt
      cursorAcpTurn(
        liveLauncher, authority, store, diagnostics, host.variables["SHELL"], environment,
        request, saved, null, prompt, AcpProvider.Devin, mcpServers = mcpServers,
        expectedDatabaseSession = component, stopRequested = owner.stopRequested,
        onDisposition = { event -> owner.record(event) }, workingDirectory = cwd,
        registerActive = { protocol ->
          synchronized(deliveryLock) {
            if (generations[request.sessionId]?.get() == generation) {
              activeSteering[request.sessionId] = ActiveTurn(generation, protocol)
            }
          }
        },
        clearActive = { protocol ->
          synchronized(deliveryLock) {
            activeSteering.computeIfPresent(request.sessionId) { _, current ->
              if (current.protocol === protocol) null else current
            }
            deliveries.values.filter { it.active?.protocol === protocol }.forEach { it.active = null }
          }
        },
        onSteeringResponse = { deliveryId, accepted, detail ->
          steeringResponse(request.sessionId, deliveryId, accepted, detail)
        },
      ).collect { event ->
        owner.record(event)
        emit(event)
      }
    } catch (cancelled: CancellationException) {
      owner.record(ChatEvent.Failed(ChatFailure.ProcessFailed, "The Devin turn was cancelled."))
      throw cancelled
    } catch (failure: Exception) {
      diagnostics.record("chat.devin_turn_failed")
      emit(owner.record(ChatEvent.Failed(ChatFailure.ProcessFailed,
        failure.message?.let(::redactDevinDetail) ?: "The Devin turn failed before completion.")))
    } finally {
      owner.finish()
      turns.remove(request.sessionId, owner)
    }
  }

  override fun end(sessionId: String) {
    synchronized(deliveryLock) { activeSteering.remove(sessionId) }
    turns[sessionId]?.requestStop("The Devin chat was ended.")
    deleteChatImages(sessionId)
  }

  override suspend fun stopAndAwait(sessionId: String): ChatStopSettlement =
    turns[sessionId]?.stopAndAwait() ?: ChatStopSettlement.AlreadyFinished
}

private fun historyMatches(request: ChatTurnRequest, history: dev.skillbill.desktop.core.domain.model.ChatHistoryEntry?, cwd: String): Boolean {
  history ?: return false
  if (history.sessionId != request.sessionId || history.agent.agentId != "devin" || history.agent.profile != request.agent.profile) return false
  val root = history.executionCheckoutRoot ?: history.newTask?.workingDirectory
  if (root == null || canonicalDirectory(root) != cwd) return false
  return when (val scope = request.scope) {
    is ChatScope.Execution -> history.executionId == scope.executionId
    is ChatScope.NewTask -> history.executionId == null && history.newTask?.let {
      it.selectedRepositoryRoot == scope.selectedRepositoryRoot && canonicalDirectory(it.workingDirectory) == cwd
    } == true
  }
}

private fun savedIdentityMatches(
  saved: dev.skillbill.desktop.core.domain.model.ChatHistoryEntry,
  history: dev.skillbill.desktop.core.domain.model.ChatHistoryEntry?,
  cwd: String,
  request: ChatTurnRequest,
): Boolean {
  if (saved.agent.agentId != "devin" || saved.agent.profile != request.agent.profile ||
    saved.agent.effort != request.agent.effort
  ) return false
  val savedRoot = saved.executionCheckoutRoot ?: saved.newTask?.workingDirectory
  if (savedRoot == null || canonicalDirectory(savedRoot) != cwd) return false
  if (history == null) return !request.firstTurn
  return saved.executionId == history.executionId && saved.newTask.hasSameConversationIdentity(history.newTask) &&
    saved.agent.agentId == history.agent.agentId && saved.agent.profile == history.agent.profile
}

private fun canonicalDirectory(path: String): String? = try {
  Path.of(path).toRealPath().toString()
} catch (_: Exception) {
  null
}

private fun redactDevinDetail(value: String): String = value.takeLast(1_200).replace(
  Regex("(?i)(token|password|secret|api[_-]?key)(\\s*[:=]\\s*)[^\\s,;]+"),
  "\$1\$2[redacted]",
)

private class DevinTurnOwner {
  val stopRequested = CompletableDeferred<String>()
  private val lock = Any()
  private val terminal = AtomicReference<ChatEvent?>(null)
  private val settled = CompletableDeferred<Unit>()

  fun requestStop(reason: String) {
    synchronized(lock) { if (terminal.get() == null) stopRequested.complete(reason) }
  }

  suspend fun stopAndAwait(): ChatStopSettlement {
    val alreadyFinished = synchronized(lock) {
      if (terminal.get() != null) true else {
        stopRequested.complete("The Devin turn was stopped.")
        false
      }
    }
    if (alreadyFinished) return ChatStopSettlement.AlreadyFinished
    settled.await()
    return ChatStopSettlement.Settled
  }

  fun record(event: ChatEvent): ChatEvent {
    if (event is ChatEvent.Completed || event is ChatEvent.Failed) synchronized(lock) { terminal.compareAndSet(null, event) }
    return event
  }

  fun finish() {
    synchronized(lock) {
      if (terminal.get() == null) terminal.compareAndSet(null,
        ChatEvent.Failed(ChatFailure.ProcessFailed, "The Devin turn ended without terminal settlement."))
    }
    settled.complete(Unit)
  }
}
