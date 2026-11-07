package dev.skillbill.desktop.core.testing

import dev.skillbill.desktop.core.domain.model.ChatAgent
import dev.skillbill.desktop.core.domain.model.ChatEvent
import dev.skillbill.desktop.core.domain.model.ChatHistoryEntry
import dev.skillbill.desktop.core.domain.model.ChatHistoryLine
import dev.skillbill.desktop.core.domain.model.ChatHistoryRole
import dev.skillbill.desktop.core.domain.model.ChatTurnRequest
import dev.skillbill.desktop.core.domain.model.Detail
import dev.skillbill.desktop.core.domain.model.RunOptions
import dev.skillbill.desktop.core.domain.model.SessionMoveResult
import dev.skillbill.desktop.core.domain.model.WorkItem
import dev.skillbill.desktop.core.domain.repository.ExecutionChatRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
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

  /** What [agentFor] answers; by default the agent with the CLI's model. */
  var agentFor: (String) -> ChatAgent = { ChatAgent(it, null, null) }
  val agentForCalls: MutableList<String> = Collections.synchronizedList(mutableListOf())

  override suspend fun agentFor(agentId: String): ChatAgent {
    agentForCalls += agentId
    return agentFor.invoke(agentId)
  }

  override fun agents(): List<String> = supported.sorted()

  override fun supports(agentId: String): Boolean = agentId in supported

  override fun send(request: ChatTurnRequest): Flow<ChatEvent> {
    requests += request
    return events(request)
  }

  /** Emit a session id and an event to stream a reply the agent starts on its own. */
  val unpromptedEvents: MutableSharedFlow<Pair<String, ChatEvent>> = MutableSharedFlow(extraBufferCapacity = 64)

  override fun unprompted(sessionId: String): Flow<ChatEvent> =
    unpromptedEvents.filter { it.first == sessionId }.map { it.second }

  /** Session ids whose agent process the workspace ended, in order. */
  val ended: MutableList<String> = Collections.synchronizedList(mutableListOf())

  override fun end(sessionId: String) {
    ended += sessionId
  }

  /** Every `moveSessions` call as (fromRoot, toRoot); [moveResult] answers it. */
  val moveCalls: MutableList<Pair<String, String>> = Collections.synchronizedList(mutableListOf())
  var moveResult: suspend (String, String) -> SessionMoveResult = { _, _ -> SessionMoveResult(0, 0, 0) }

  override suspend fun moveSessions(fromRoot: String, toRoot: String): SessionMoveResult {
    moveCalls += fromRoot to toRoot
    return moveResult(fromRoot, toRoot)
  }

  /** Saved chats by session id, as the store keeps them. */
  val saved: MutableMap<String, ChatHistoryEntry> = Collections.synchronizedMap(linkedMapOf())

  override suspend fun savedChats(executionId: String, repositoryRoot: String, currentSessionId: String): List<ChatHistoryEntry> =
    saved.values.filter { entry ->
      entry.sessionId != currentSessionId && entry.executionId == executionId && entry.lines.any { it.role == ChatHistoryRole.Operator }
    }.sortedByDescending { it.updatedAt }

  override suspend fun openSavedChat(entry: ChatHistoryEntry): List<ChatHistoryLine> = entry.lines

  override suspend fun saveChat(entry: ChatHistoryEntry) {
    saved[entry.sessionId] = entry
  }

  override suspend fun savedNewTaskDrafts(limit: Int?): List<ChatHistoryEntry> {
    val drafts = saved.values.filter { entry ->
      entry.newTask != null && entry.lines.any { it.role == ChatHistoryRole.Operator }
    }.sortedByDescending { it.updatedAt }
    return if (limit == null) drafts else drafts.take(limit)
  }

  override suspend fun deleteSavedChat(sessionId: String) {
    saved.remove(sessionId)
  }

  /** Session ids passed to [requestSummary], in order. */
  val summaryRequests: MutableList<String> = Collections.synchronizedList(mutableListOf())

  override fun requestSummary(sessionId: String) {
    summaryRequests += sessionId
  }

  /** Emit a session id to report a summary written. */
  val summaryUpdateEvents: MutableSharedFlow<String> = MutableSharedFlow(extraBufferCapacity = 16)

  override val summaryUpdates: Flow<String> get() = summaryUpdateEvents
}
