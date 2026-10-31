package dev.skillbill.desktop.core.domain.repository

import dev.skillbill.desktop.core.domain.model.ChatAgent
import dev.skillbill.desktop.core.domain.model.ChatEvent
import dev.skillbill.desktop.core.domain.model.ChatTurnRequest
import dev.skillbill.desktop.core.domain.model.Detail
import dev.skillbill.desktop.core.domain.model.RunOptions
import dev.skillbill.desktop.core.domain.model.WorkItem
import kotlinx.coroutines.flow.Flow

/** Chats with the agent of one execution. */
interface ExecutionChatRepository {
  /** The execution's agent and its reasoning model, or null when no agent is known. */
  suspend fun resolveAgent(item: WorkItem, detail: Detail?, runOptions: RunOptions?): ChatAgent?

  fun supports(agentId: String): Boolean

  /** A cold flow that runs one turn per collection and never throws to its collector; cancelling the collection ends the turn. */
  fun send(request: ChatTurnRequest): Flow<ChatEvent>
}
