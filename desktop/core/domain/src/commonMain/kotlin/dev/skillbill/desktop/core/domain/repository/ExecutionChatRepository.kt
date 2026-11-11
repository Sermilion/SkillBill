package dev.skillbill.desktop.core.domain.repository

import dev.skillbill.desktop.core.domain.model.ChatModelsRead
import dev.skillbill.desktop.core.domain.model.ChatAgent
import dev.skillbill.desktop.core.domain.model.ChatEvent
import dev.skillbill.desktop.core.domain.model.ChatHistoryEntry
import dev.skillbill.desktop.core.domain.model.ChatHistoryLine
import dev.skillbill.desktop.core.domain.model.ChatTurnRequest
import dev.skillbill.desktop.core.domain.model.ChatStopSettlement
import dev.skillbill.desktop.core.domain.model.Detail
import dev.skillbill.desktop.core.domain.model.RunOptions
import dev.skillbill.desktop.core.domain.model.SessionMoveResult
import dev.skillbill.desktop.core.domain.model.SavedChatProjection
import dev.skillbill.desktop.core.domain.model.WorkItem
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

/** Chats with the agent of one execution. */
interface ExecutionChatRepository {
  /** The execution's agent and its reasoning model, or null when no agent is known. */
  suspend fun resolveAgent(item: WorkItem, detail: Detail?, runOptions: RunOptions?): ChatAgent?

  /** [agentId] with its reasoning model from the machine config, or the CLI's own default when the config sets none. */
  suspend fun agentFor(agentId: String): ChatAgent

  /** Reads the selected profile's available models without sending a chat prompt. */
  suspend fun models(agent: ChatAgent, repositoryRoot: String): ChatModelsRead

  /** The agent ids a chat can run on, in a stable order. */
  fun agents(): List<String>

  fun supports(agentId: String): Boolean

  /** A cold flow that runs one turn per collection and never throws to its collector; cancelling the collection ends the turn. */
  fun send(request: ChatTurnRequest): Flow<ChatEvent>

  /**
   * Replies the agent of [sessionId] starts on its own between turns, such as when a background command it started finishes.
   * Each reply streams like a turn and ends with [ChatEvent.Completed] or [ChatEvent.Failed]. Agents that run one process per
   * turn never emit. The flow never completes; the collector cancels it.
   */
  fun unprompted(sessionId: String): Flow<ChatEvent>

  /**
   * Stops the agent process kept for [sessionId] between turns, with every background command it started. The next turn of
   * that session resumes it in a new process. Hiding a chat must not call this; closing it does.
   */
  fun end(sessionId: String)

  /** Stops the active turn and waits for its terminal settlement when the selected transport supports it. */
  suspend fun stopTurn(sessionId: String): ChatStopSettlement

  /**
   * What `/resume` offers: native sessions in [repositoryRoot], readable app chats of [executionId], and separate recovery
   * records for unreadable or unsafe app saves. Native sessions and app chats exclude [currentSessionId]. Recovery records stay
   * visible without becoming resumable chats.
   */
  suspend fun savedChats(executionId: String, repositoryRoot: String, currentSessionId: String): SavedChatProjection

  /** The latest lines of [entry] to show again; it also records what the agent needs so the next turn continues that session. */
  suspend fun openSavedChat(entry: ChatHistoryEntry): List<ChatHistoryLine>

  /**
   * Moves the Claude session files of [fromRoot] to [toRoot] and rewrites the cwd of its Codex sessions to [toRoot], in every
   * agent profile, so those chats resume from [toRoot]. Idempotent: a repeat moves nothing. A session that cannot be moved is
   * counted in [SessionMoveResult.failed], never thrown.
   */
  suspend fun moveSessions(fromRoot: String, toRoot: String): SessionMoveResult

  /** Saves [entry] over any earlier save of its session. */
  suspend fun saveChat(entry: ChatHistoryEntry)

  /** Saves a new-task draft and reports whether its durable record was written. */
  suspend fun saveNewTaskDraft(entry: ChatHistoryEntry): Boolean {
    saveChat(entry)
    return true
  }

  /** Every repository's saved new-task drafts, newest first, at most [limit], plus recovery records outside that limit. */
  suspend fun savedNewTaskDrafts(limit: Int? = null): SavedChatProjection

  /** Deletes the saved chat of [sessionId] with its resume state. */
  suspend fun deleteSavedChat(sessionId: String)

  /** Asks for a background summary of the saved chat of [sessionId]; returns at once, never throws, and is free when nothing changed. */
  fun requestSummary(sessionId: String) {}

  /** The session id of each summary written; a hot flow without replay. */
  val summaryUpdates: Flow<String> get() = emptyFlow()
}
