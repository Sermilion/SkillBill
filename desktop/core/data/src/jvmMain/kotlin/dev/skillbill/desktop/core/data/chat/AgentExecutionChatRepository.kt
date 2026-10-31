package dev.skillbill.desktop.core.data.chat

import dev.skillbill.desktop.core.common.Diagnostics
import dev.skillbill.desktop.core.common.di.AppScope
import dev.skillbill.desktop.core.domain.model.ChatAgent
import dev.skillbill.desktop.core.domain.model.ChatEvent
import dev.skillbill.desktop.core.domain.model.ChatFailure
import dev.skillbill.desktop.core.domain.model.ChatHistoryEntry
import dev.skillbill.desktop.core.domain.model.ChatHistoryLine
import dev.skillbill.desktop.core.domain.model.ChatTurnRequest
import dev.skillbill.desktop.core.domain.model.Detail
import dev.skillbill.desktop.core.domain.model.RunOptions
import dev.skillbill.desktop.core.domain.model.WorkItem
import dev.skillbill.desktop.core.domain.repository.ExecutionChatRepository
import dev.skillbill.desktop.core.domain.repository.RuntimeConfigRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
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
) : ExecutionChatRepository {
  private val resolver = ChatAgentResolver(runtimeConfig::machineConfig, diagnostics)

  override suspend fun resolveAgent(item: WorkItem, detail: Detail?, runOptions: RunOptions?): ChatAgent? =
    resolver.resolve(item, detail, runOptions)

  override suspend fun agentFor(agentId: String): ChatAgent = resolver.forAgent(agentId)

  override fun agents(): List<String> = transports.map { it.agentId }.sorted()

  override fun supports(agentId: String): Boolean = transports.any { it.agentId == agentId }

  override fun send(request: ChatTurnRequest): Flow<ChatEvent> = flow {
    val transport = transports.firstOrNull { it.agentId == request.agent.agentId }
    if (transport == null) {
      diagnostics.record("chat.agent_unsupported")
      emit(ChatEvent.Failed(ChatFailure.AgentUnsupported, request.agent.agentId))
    } else {
      emitAll(transport.run(request))
    }
  }.flowOn(Dispatchers.IO)

  override suspend fun savedChats(executionId: String, repositoryRoot: String, currentSessionId: String): List<ChatHistoryEntry> =
    withContext(Dispatchers.IO) { catalog.list(executionId, repositoryRoot, currentSessionId, resolver::forAgent) }

  override suspend fun openSavedChat(entry: ChatHistoryEntry): List<ChatHistoryLine> = withContext(Dispatchers.IO) { catalog.open(entry) }

  override suspend fun saveChat(entry: ChatHistoryEntry) = withContext(Dispatchers.IO) { store.saveChat(entry) }
}
