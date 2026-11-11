package dev.skillbill.desktop.core.data.chat

import dev.skillbill.desktop.core.common.Diagnostics
import dev.skillbill.desktop.core.common.HostEnvironment
import dev.skillbill.desktop.core.common.di.AppScope
import dev.skillbill.desktop.core.data.usage.codexEnvironment
import dev.skillbill.desktop.core.domain.model.ChatEvent
import dev.skillbill.desktop.core.domain.model.ChatFailure
import dev.skillbill.desktop.core.domain.model.ChatScope
import dev.skillbill.desktop.core.domain.model.ChatSteeringPolicy
import dev.skillbill.desktop.core.domain.model.ChatSteeringRequest
import dev.skillbill.desktop.core.domain.model.ChatSteeringResult
import dev.skillbill.desktop.core.domain.model.ChatSteeringUpdate
import dev.skillbill.desktop.core.domain.model.ChatTurnRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import me.tatarka.inject.annotations.Inject
import skillbill.contracts.system.AgentHomeEnvironmentKeys
import skillbill.infrastructure.launcher.process.codex.CodexAppServerProtocol
import skillbill.infrastructure.launcher.process.codex.CodexSchemaAdmission
import skillbill.infrastructure.launcher.process.codex.CodexSteeringAdmission
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

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
  override val steeringPolicy = ChatSteeringPolicy.LiveWhenAvailable
  private val activeTurns = ConcurrentHashMap<String, Job>()
  private val generations = ConcurrentHashMap<String, AtomicLong>()
  private data class ActiveTurn(
    val generation: Long,
    val threadId: String,
    val turnId: String,
    val rpc: CodexAppServerRpc,
    val images: CodexTurnImages,
  )
  private val activeSteering = ConcurrentHashMap<String, ActiveTurn>()
  private class DeliveryRecord(var active: ActiveTurn?) {
    var writable = true
    var registered = false
    var result: ChatSteeringResult? = null
    var authoritative = false
  }
  private val deliveryLock = Any()
  private val deliveries = mutableMapOf<Pair<String, String>, DeliveryRecord>()
  private val deliveryUpdates = MutableStateFlow<Map<Pair<String, String>, ChatSteeringUpdate>>(emptyMap())

  override suspend fun steer(request: ChatSteeringRequest): ChatSteeringResult = withContext(Dispatchers.IO) {
    val capability = CodexSchemaAdmission.inspectPinnedSteering()
    currentCoroutineContext().ensureActive()
    if (capability is CodexSteeringAdmission.Unavailable) {
      diagnostics.record("chat.codex_steering_unavailable")
      return@withContext ChatSteeringResult.Unsupported(request.deliveryId, capability.diagnostic)
    }
    val active = activeSteering[request.sessionId]
      ?: return@withContext ChatSteeringResult.NoActiveTurn(request.deliveryId, "Codex has no registered active turn")
    val key = request.sessionId to request.deliveryId
    val record = DeliveryRecord(active)
    if (synchronized(deliveryLock) { deliveries.putIfAbsent(key, record) != null }) {
      return@withContext ChatSteeringResult.Rejected(request.deliveryId, "This delivery is already tracked")
    }
    val imagePaths = try {
      active.images.write(request.images)
    } catch (cancelled: CancellationException) {
      throw cancelled
    } catch (error: Exception) {
      diagnostics.record("chat.codex_steering_image_failed")
      val result = ChatSteeringResult.NoActiveTurn(request.deliveryId, "Could not prepare follow-up images: ${error.message}")
      publish(record, key, result)
      return@withContext result
    }
    currentCoroutineContext().ensureActive()
    val inputs = buildList {
      if (request.text.isNotBlank()) add(CodexAppServerProtocol.textInput(request.text))
      imagePaths.forEach { add(CodexAppServerProtocol.localImageInput(it)) }
    }
    active.rpc.steer(
      request.deliveryId,
      active.threadId,
      active.turnId,
      inputs,
      acquireWrite = { acquire ->
        synchronized(deliveryLock) {
          deliveries[key] === record && record.writable &&
            activeSteering[request.sessionId] === active &&
            active.generation == generations[request.sessionId]?.get() && acquire()
        }
      },
      onRegistered = { registered ->
        synchronized(deliveryLock) {
          if (deliveries[key] === record && record.writable && registered()) {
            record.registered = true
            true
          } else false
        }
      },
      onUpdate = { result -> publish(record, key, result) },
    ).let { returned -> publish(record, key, returned) }
  }

  override fun steeringUpdates(sessionId: String) = flow {
    deliveryUpdates.collect { snapshot -> snapshot.values.filter { it.sessionId == sessionId }.forEach { emit(it) } }
  }

  override fun settleSteering(sessionId: String, deliveryId: String): ChatSteeringResult {
    val key = sessionId to deliveryId
    val record = synchronized(deliveryLock) { deliveries[key] }
      ?: return ChatSteeringResult.NoActiveTurn(deliveryId, "No pending Codex delivery remains")
    val active = synchronized(deliveryLock) {
      record.writable = false
      record.active.takeIf { record.registered }
    }
    active?.rpc?.settleSteering(deliveryId)
    return synchronized(deliveryLock) {
      record.result ?: run {
        val result = ChatSteeringResult.NoActiveTurn(deliveryId, "Codex turn ended before the follow-up was registered")
        publishLocked(record, key, result)
        result
      }
    }
  }

  override fun retireSteering(sessionId: String, deliveryId: String) {
    val key = sessionId to deliveryId
    synchronized(deliveryLock) {
      deliveries.remove(key)?.let { record ->
        record.writable = false
        deliveryUpdates.update { it - key }
      }
    }
  }

  private fun publish(record: DeliveryRecord, key: Pair<String, String>, result: ChatSteeringResult): ChatSteeringResult =
    synchronized(deliveryLock) { publishLocked(record, key, result) }

  private fun publishLocked(record: DeliveryRecord, key: Pair<String, String>, result: ChatSteeringResult): ChatSteeringResult {
    if (deliveries[key] !== record) {
      diagnostics.record("chat.codex_stale_steering_update")
      return result
    }
    val authoritative = result !is ChatSteeringResult.DeliveryUncertain
    if (record.authoritative || (!authoritative && record.result != null)) return record.result ?: result
    record.result = result
    record.authoritative = authoritative
    deliveryUpdates.update { it + (key to ChatSteeringUpdate(key.first, result)) }
    if (result is ChatSteeringResult.NoActiveTurn || result is ChatSteeringResult.Rejected || result is ChatSteeringResult.Unsupported) {
      record.writable = false
    }
    return result
  }

  private fun releaseRpc(rpc: CodexAppServerRpc) = synchronized(deliveryLock) {
    deliveries.values.filter { it.active?.rpc === rpc }.forEach {
      it.active = null
    }
  }

  override fun run(request: ChatTurnRequest): Flow<ChatEvent> = flow {
    val generation = synchronized(deliveryLock) {
      generations.computeIfAbsent(request.sessionId) { AtomicLong() }.incrementAndGet()
    }
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
      val turnImages = try {
        CodexTurnImages.create(request.sessionId)
      } catch (error: java.io.IOException) {
        emit(ChatEvent.Failed(ChatFailure.ProcessFailed, "Could not save a pasted image: ${error.message}"))
        return@flow
      }
      val imagePaths = try {
        turnImages.write(request.images)
      } catch (error: Exception) {
        diagnostics.record("chat.codex_initial_image_failed")
        runCatching { turnImages.close() }.onFailure { diagnostics.record("chat.codex_image_cleanup_failed") }
        emit(ChatEvent.Failed(ChatFailure.ProcessFailed, "Could not save a pasted image: ${error.message}"))
        return@flow
      }
      try {
        val prompt = if (thread == null || request.scope is ChatScope.NewTask) {
          withScope(scopeSystemPrompt(request.scope), request.prompt)
        } else {
          request.prompt
        }
        val command = ChatCommand(
          argv = agentCommand("codex", listOf("app-server"), host.variables["SHELL"]),
          workingDirectory = request.scope.repositoryRoot,
          environment = codexEnvironment(host.variables, request.agent.profile),
          stdin = "",
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
            registerActive = { rpc, threadId, turnId ->
              synchronized(deliveryLock) {
                if (generations[request.sessionId]?.get() == generation) {
                  activeSteering[request.sessionId] = ActiveTurn(generation, threadId, turnId, rpc, turnImages)
                }
              }
            },
            clearActive = { rpc ->
              synchronized(deliveryLock) {
                activeSteering.computeIfPresent(request.sessionId) { _, current -> if (current.rpc === rpc) null else current }
              }
            },
            releaseRpc = ::releaseRpc,
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
        runCatching { turnImages.close() }.onFailure { diagnostics.record("chat.codex_image_cleanup_failed") }
      }
    } finally {
      if (collection != null) activeTurns.remove(request.sessionId, collection)
    }
  }

  override fun end(sessionId: String) {
    val steering = synchronized(deliveryLock) { activeSteering.remove(sessionId) }
    steering?.rpc?.closeAndSettleSteering()
    val active = activeTurns[sessionId]
    if (active != null) active.cancel()
  }

  override suspend fun awaitEnd(sessionId: String) {
    activeTurns[sessionId]?.join()
  }
}
