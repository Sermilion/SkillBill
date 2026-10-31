package dev.skillbill.desktop.core.data.chat

import dev.skillbill.desktop.core.common.Diagnostics
import dev.skillbill.desktop.core.common.di.AppScope
import dev.skillbill.desktop.core.domain.model.ChatAgent
import dev.skillbill.desktop.core.domain.model.ChatEvent
import dev.skillbill.desktop.core.domain.model.ChatFailure
import dev.skillbill.desktop.core.domain.model.ChatTurnRequest
import dev.skillbill.desktop.core.domain.model.Detail
import dev.skillbill.desktop.core.domain.model.RunOptions
import dev.skillbill.desktop.core.domain.model.WorkItem
import dev.skillbill.desktop.core.domain.repository.ExecutionChatRepository
import dev.skillbill.desktop.core.domain.repository.RuntimeConfigRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import me.tatarka.inject.annotations.Inject
import software.amazon.lastmile.kotlin.inject.anvil.ContributesBinding
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn

/** Sends each turn to the transport registered for the turn's agent. */
@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class AgentExecutionChatRepository(
  private val transports: Set<ChatTransport>,
  runtimeConfig: RuntimeConfigRepository,
  private val diagnostics: Diagnostics,
) : ExecutionChatRepository {
  private val resolver = ChatAgentResolver(runtimeConfig::machineConfig, diagnostics)

  override suspend fun resolveAgent(item: WorkItem, detail: Detail?, runOptions: RunOptions?): ChatAgent? =
    resolver.resolve(item, detail, runOptions)

  override fun supports(agentId: String): Boolean = transports.any { it.agentId == agentId }

  override fun send(request: ChatTurnRequest): Flow<ChatEvent> = flow {
    val transport = transports.firstOrNull { it.agentId == request.agent.agentId }
    if (transport == null) {
      diagnostics.record("chat.agent_unsupported")
      emit(ChatEvent.Failed(ChatFailure.AgentUnsupported, request.agent.agentId))
    } else {
      emitAll(transport.run(request))
    }
  }
}
