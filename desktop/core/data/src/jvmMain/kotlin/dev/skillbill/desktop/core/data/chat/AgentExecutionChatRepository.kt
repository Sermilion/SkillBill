package dev.skillbill.desktop.core.data.chat

import dev.skillbill.desktop.core.common.Diagnostics
import dev.skillbill.desktop.core.common.di.AppScope
import dev.skillbill.desktop.core.domain.model.ChatAgent
import dev.skillbill.desktop.core.domain.model.ChatEvent
import dev.skillbill.desktop.core.domain.model.ChatFailure
import dev.skillbill.desktop.core.domain.model.ChatHistoryEntry
import dev.skillbill.desktop.core.domain.model.ChatHistoryLine
import dev.skillbill.desktop.core.domain.model.ChatTurnRequest
import dev.skillbill.desktop.core.domain.model.ChatSteeringPolicy
import dev.skillbill.desktop.core.domain.model.ChatSteeringRequest
import dev.skillbill.desktop.core.domain.model.ChatSteeringResult
import dev.skillbill.desktop.core.domain.model.ChatSteeringUpdate
import dev.skillbill.desktop.core.domain.model.Detail
import dev.skillbill.desktop.core.domain.model.RunOptions
import dev.skillbill.desktop.core.domain.model.SessionMoveResult
import dev.skillbill.desktop.core.domain.model.WorkItem
import dev.skillbill.desktop.core.domain.repository.ExecutionChatRepository
import dev.skillbill.desktop.core.domain.repository.RuntimeConfigRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.withContext
import me.tatarka.inject.annotations.Inject
import software.amazon.lastmile.kotlin.inject.anvil.ContributesBinding
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn

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
) : ExecutionChatRepository {
  private val resolver = ChatAgentResolver(runtimeConfig::machineConfig, diagnostics)

  override suspend fun resolveAgent(item: WorkItem, detail: Detail?, runOptions: RunOptions?): ChatAgent? =
    resolver.resolve(item, detail, runOptions)

  override suspend fun agentFor(agentId: String): ChatAgent = resolver.forAgent(agentId)

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
      emitAll(transport.run(request))
    }
  }.flowOn(Dispatchers.IO)

  override fun unprompted(sessionId: String): Flow<ChatEvent> = transports.map { it.unprompted(sessionId) }.merge().flowOn(Dispatchers.IO)

  override fun end(sessionId: String) = transports.forEach { it.end(sessionId) }

  override suspend fun savedChats(executionId: String, repositoryRoot: String, currentSessionId: String): List<ChatHistoryEntry> =
    withContext(Dispatchers.IO) { catalog.list(executionId, repositoryRoot, currentSessionId, resolver::forAgent) }

  override suspend fun openSavedChat(entry: ChatHistoryEntry): List<ChatHistoryLine> = withContext(Dispatchers.IO) { catalog.open(entry) }

  override suspend fun moveSessions(fromRoot: String, toRoot: String): SessionMoveResult =
    withContext(Dispatchers.IO) { catalog.move(fromRoot, toRoot) }

  override suspend fun saveChat(entry: ChatHistoryEntry) {
    withContext(Dispatchers.IO) { store.saveChat(entry) }
  }

  override suspend fun saveNewTaskDraft(entry: ChatHistoryEntry): Boolean = withContext(Dispatchers.IO) { store.saveChat(entry) }

  override suspend fun savedNewTaskDrafts(limit: Int?): List<ChatHistoryEntry> =
    withContext(Dispatchers.IO) { store.newTaskDrafts(limit) }

  override suspend fun deleteSavedChat(sessionId: String) = withContext(Dispatchers.IO) { store.delete(sessionId) }

  override fun requestSummary(sessionId: String) = summarizer.request(sessionId)

  override val summaryUpdates: Flow<String> get() = summarizer.updates
}
