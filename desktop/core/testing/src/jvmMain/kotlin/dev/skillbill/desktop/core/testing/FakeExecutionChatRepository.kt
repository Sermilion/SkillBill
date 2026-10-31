package dev.skillbill.desktop.core.testing

import dev.skillbill.desktop.core.domain.model.ChatAgent
import dev.skillbill.desktop.core.domain.model.ChatEvent
import dev.skillbill.desktop.core.domain.model.ChatTurnRequest
import dev.skillbill.desktop.core.domain.model.Detail
import dev.skillbill.desktop.core.domain.model.RunOptions
import dev.skillbill.desktop.core.domain.model.WorkItem
import dev.skillbill.desktop.core.domain.repository.ExecutionChatRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import java.util.Collections

/** Records every turn and answers it with [events]; [agent] is what every resolve returns. */
class FakeExecutionChatRepository(
  var agent: ChatAgent? = ChatAgent("claude", null, null),
  val supported: Set<String> = setOf("claude"),
) : ExecutionChatRepository {
  val requests: MutableList<ChatTurnRequest> = Collections.synchronizedList(mutableListOf())
  val resolveCalls: MutableList<Triple<WorkItem, Detail?, RunOptions?>> = Collections.synchronizedList(mutableListOf())
  var events: (ChatTurnRequest) -> Flow<ChatEvent> = { flowOf(ChatEvent.Completed) }

  override suspend fun resolveAgent(item: WorkItem, detail: Detail?, runOptions: RunOptions?): ChatAgent? {
    resolveCalls += Triple(item, detail, runOptions)
    return agent
  }

  override fun supports(agentId: String): Boolean = agentId in supported

  override fun send(request: ChatTurnRequest): Flow<ChatEvent> {
    requests += request
    return events(request)
  }
}
