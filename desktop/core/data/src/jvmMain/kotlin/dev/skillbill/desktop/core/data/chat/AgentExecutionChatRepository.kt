package dev.skillbill.desktop.core.data.chat

import dev.skillbill.desktop.core.common.Diagnostics
import dev.skillbill.desktop.core.common.di.AppScope
import dev.skillbill.desktop.core.domain.model.ChatAgent
import dev.skillbill.desktop.core.domain.model.ChatEvent
import dev.skillbill.desktop.core.domain.model.ChatFailure
import dev.skillbill.desktop.core.domain.model.ChatHistoryEntry
import dev.skillbill.desktop.core.domain.model.ChatHistoryLine
import dev.skillbill.desktop.core.domain.model.ChatTurnRequest
import dev.skillbill.desktop.core.domain.model.ChatStopSettlement
import dev.skillbill.desktop.core.domain.model.ChatSteeringPolicy
import dev.skillbill.desktop.core.domain.model.ChatSteeringRequest
import dev.skillbill.desktop.core.domain.model.ChatSteeringResult
import dev.skillbill.desktop.core.domain.model.ChatSteeringUpdate
import dev.skillbill.desktop.core.domain.model.ChatTitleUpdate
import dev.skillbill.desktop.core.domain.model.Detail
import dev.skillbill.desktop.core.domain.model.ChatModelsRead
import dev.skillbill.desktop.core.domain.model.RunOptions
import dev.skillbill.desktop.core.domain.model.SessionMoveResult
import dev.skillbill.desktop.core.domain.model.SavedChatProjection
import dev.skillbill.desktop.core.domain.model.WorkItem
import dev.skillbill.desktop.core.domain.repository.ChatModelCache
import dev.skillbill.desktop.core.domain.repository.ExecutionChatRepository
import dev.skillbill.desktop.core.domain.repository.RuntimeConfigRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import me.tatarka.inject.annotations.Inject
import software.amazon.lastmile.kotlin.inject.anvil.ContributesBinding
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn
import java.util.concurrent.ConcurrentHashMap

/** Sends each turn to the transport registered for the turn's agent, off the UI thread; saved chats live in [store] and [catalog]. */
@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class AgentExecutionChatRepository(
  private val transports: Set<ChatTransport>,
  runtimeConfig: RuntimeConfigRepository,
  private val store: ChatStore,
  private val catalog: AgentSessionCatalog,
  private val diagnostics: Diagnostics,
  private val summarizer: ChatSummarizer,
  private val modelCatalog: ChatModelCatalog,
  private val modelCache: ChatModelCache,
) : ExecutionChatRepository {
  private val resolver = ChatAgentResolver(runtimeConfig::machineConfig, diagnostics)
  private val activeTurns = ConcurrentHashMap<String, ActiveTurn>()

  override suspend fun resolveAgent(item: WorkItem, detail: Detail?, runOptions: RunOptions?): ChatAgent? =
    resolver.resolve(item, detail, runOptions)

  override suspend fun agentFor(agentId: String): ChatAgent = resolver.forAgent(agentId)

  override fun models(agent: ChatAgent, repositoryRoot: String): Flow<ChatModelsRead> = flow {
    val cached = modelCache.read(agent.agentId, agent.profile)
    if (cached != null) emit(ChatModelsRead.Cached(cached))
    when (val fresh = modelCatalog.read(agent, repositoryRoot)) {
      is ChatModelsRead.Loaded -> if (fresh.models != cached) {
        modelCache.write(agent.agentId, agent.profile, fresh.models)
        emit(fresh)
      }
      is ChatModelsRead.Failed -> emit(cached?.let { ChatModelsRead.Cached(it, fresh.message) } ?: fresh)
      is ChatModelsRead.Cached -> emit(fresh)
    }
  }.flowOn(Dispatchers.IO)

  override fun agents(): List<String> = transports.map { it.agentId }.sorted()

  override fun supports(agentId: String): Boolean = transports.any { it.agentId == agentId }

  override fun steeringPolicy(agentId: String): ChatSteeringPolicy =
    transports.firstOrNull { it.agentId == agentId }?.steeringPolicy ?: ChatSteeringPolicy.LegacySendNow

  override suspend fun steer(request: ChatSteeringRequest): ChatSteeringResult {
    val transport = transports.firstOrNull { it.agentId == request.agentId }
    if (transport == null) {
      diagnostics.record("chat.steering_unsupported")
      return ChatSteeringResult.Unsupported(request.deliveryId, "Live delivery is unavailable")
    }
    val result = transport.steer(request)
    when (result) {
      is ChatSteeringResult.Accepted -> Unit
      is ChatSteeringResult.DeliveryUncertain -> diagnostics.record("chat.steering_uncertain")
      else -> diagnostics.record("chat.steering_fallback")
    }
    return result
  }

  override fun steeringUpdates(agentId: String, sessionId: String): Flow<ChatSteeringUpdate> =
    transports.firstOrNull { it.agentId == agentId }?.steeringUpdates(sessionId) ?: kotlinx.coroutines.flow.emptyFlow()

  override fun settleSteering(agentId: String, sessionId: String, deliveryId: String): ChatSteeringResult {
    diagnostics.record("chat.steering_settled_for_teardown")
    return transports.firstOrNull { it.agentId == agentId }?.settleSteering(sessionId, deliveryId)
      ?: ChatSteeringResult.NoActiveTurn(deliveryId, "The chat transport is unavailable")
  }

  override fun retireSteering(agentId: String, sessionId: String, deliveryId: String) {
    transports.firstOrNull { it.agentId == agentId }?.retireSteering(sessionId, deliveryId)
  }

  override fun send(request: ChatTurnRequest): Flow<ChatEvent> = flow {
    val transport = transports.firstOrNull { it.agentId == request.agent.agentId }
    if (transport == null) {
      diagnostics.record("chat.agent_unsupported")
      emit(ChatEvent.Failed(ChatFailure.AgentUnsupported, request.agent.agentId))
    } else {
      val active = ActiveTurn(transport)
      if (activeTurns.putIfAbsent(request.sessionId, active) != null) {
        emit(ChatEvent.Failed(ChatFailure.ProcessFailed, "A chat turn is already active for this session."))
      } else {
        try {
          emitAll(transport.run(request))
        } finally {
          active.completion.complete(Unit)
          activeTurns.remove(request.sessionId, active)
        }
      }
    }
  }.flowOn(Dispatchers.IO)

  override fun unprompted(sessionId: String): Flow<ChatEvent> = transports.map { it.unprompted(sessionId) }.merge().flowOn(Dispatchers.IO)

  override fun end(sessionId: String) {
    activeTurns[sessionId]?.transport?.end(sessionId) ?: transports.forEach { it.end(sessionId) }
  }

  override suspend fun awaitEnd(sessionId: String) {
    activeTurns[sessionId]?.transport?.awaitEnd(sessionId) ?: transports.forEach { it.awaitEnd(sessionId) }
  }

  override suspend fun stopTurn(sessionId: String): ChatStopSettlement {
    val active = activeTurns[sessionId] ?: return ChatStopSettlement.AlreadyFinished
    val result = try {
      active.transport.stopAndAwait(sessionId).also { settled ->
        if (settled != ChatStopSettlement.CollectorCancellationRequired) active.completion.await()
      }
    } catch (cancelled: CancellationException) {
      withContext(NonCancellable) {
        withTimeoutOrNull(STOP_SETTLEMENT_TIMEOUT_MS) { active.completion.await() }
        diagnostics.record("chat.stop_cancelled_nondelivery")
      }
      throw cancelled
    } catch (failure: Exception) {
      diagnostics.record("chat.stop_settlement_failed")
      active.transport.end(sessionId)
      ChatStopSettlement.CollectorCancellationRequired
    }
    return result
  }

  override suspend fun savedChats(executionId: String, repositoryRoot: String, currentSessionId: String): SavedChatProjection =
    withContext(Dispatchers.IO) { catalog.list(executionId, repositoryRoot, currentSessionId, resolver::forAgent) }

  override suspend fun openSavedChat(entry: ChatHistoryEntry): List<ChatHistoryLine> = withContext(Dispatchers.IO) { catalog.open(entry) }

  override suspend fun acquisition(sessionId: String) = withContext(Dispatchers.IO) { store.acquisition(sessionId) }

  override suspend fun moveSessions(fromRoot: String, toRoot: String): SessionMoveResult =
    withContext(Dispatchers.IO) { catalog.move(fromRoot, toRoot) }

  override suspend fun saveChat(entry: ChatHistoryEntry) {
    withContext(Dispatchers.IO) { store.saveChat(entry) }
  }

  override suspend fun saveNewTaskDraft(entry: ChatHistoryEntry): Boolean = withContext(Dispatchers.IO) { store.saveChat(entry) }

  override suspend fun savedNewTaskDrafts(limit: Int?): SavedChatProjection =
    withContext(Dispatchers.IO) { store.newTaskDrafts(limit) }

  override suspend fun deleteSavedChat(sessionId: String) = withContext(Dispatchers.IO) { store.delete(sessionId) }

  override fun requestSummary(sessionId: String) = summarizer.request(sessionId)

  override val summaryUpdates: Flow<String> get() = summarizer.updates

  override fun requestTitle(sessionId: String) = summarizer.requestTitle(sessionId)

  override val titleUpdates: Flow<ChatTitleUpdate> get() = summarizer.titleUpdates
}

private class ActiveTurn(val transport: ChatTransport) {
  val completion = CompletableDeferred<Unit>()
}

private const val STOP_SETTLEMENT_TIMEOUT_MS = 25_000L
