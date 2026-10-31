package dev.skillbill.desktop.core.domain.repository

import dev.skillbill.desktop.core.domain.model.ChatAgent
import dev.skillbill.desktop.core.domain.model.ChatEvent
import dev.skillbill.desktop.core.domain.model.ChatHistoryEntry
import dev.skillbill.desktop.core.domain.model.ChatTurnRequest
import dev.skillbill.desktop.core.domain.model.Detail
import dev.skillbill.desktop.core.domain.model.RunOptions
import dev.skillbill.desktop.core.domain.model.WorkItem
import kotlinx.coroutines.flow.Flow

/** Chats with the agent of one execution. */
interface ExecutionChatRepository {
  /** The execution's agent and its reasoning model, or null when no agent is known. */
  suspend fun resolveAgent(item: WorkItem, detail: Detail?, runOptions: RunOptions?): ChatAgent?

  /** [agentId] with its reasoning model from the machine config, or the CLI's own default when the config sets none. */
  suspend fun agentFor(agentId: String): ChatAgent

  /** The agent ids a chat can run on, in a stable order. */
  fun agents(): List<String>

  fun supports(agentId: String): Boolean

  /** A cold flow that runs one turn per collection and never throws to its collector; cancelling the collection ends the turn. */
  fun send(request: ChatTurnRequest): Flow<ChatEvent>

  /** The saved chats of [executionId] that hold an operator message, newest first; an unreadable store gives none. */
  suspend fun savedChats(executionId: String): List<ChatHistoryEntry>

  /** Saves [entry] over any earlier save of its session. */
  suspend fun saveChat(entry: ChatHistoryEntry)
}
