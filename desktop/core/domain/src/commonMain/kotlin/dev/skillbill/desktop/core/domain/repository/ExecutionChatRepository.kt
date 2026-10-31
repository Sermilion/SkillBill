package dev.skillbill.desktop.core.domain.repository

import dev.skillbill.desktop.core.domain.model.ChatAgent
import dev.skillbill.desktop.core.domain.model.ChatEvent
import dev.skillbill.desktop.core.domain.model.ChatHistoryEntry
import dev.skillbill.desktop.core.domain.model.ChatHistoryLine
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

  /**
   * What `/resume` offers, newest first: every agent CLI's own sessions in [repositoryRoot], and the app's saved chats of
   * [executionId] for agents without a session store. The chat now open as [currentSessionId] is left out. A store that cannot
   * be read gives no entries.
   */
  suspend fun savedChats(executionId: String, repositoryRoot: String, currentSessionId: String): List<ChatHistoryEntry>

  /** The latest lines of [entry] to show again; it also records what the agent needs so the next turn continues that session. */
  suspend fun openSavedChat(entry: ChatHistoryEntry): List<ChatHistoryLine>

  /** Saves [entry] over any earlier save of its session. */
  suspend fun saveChat(entry: ChatHistoryEntry)
}
