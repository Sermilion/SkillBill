package dev.skillbill.desktop.feature.chat

import dev.skillbill.desktop.core.domain.model.ChatAgent
import dev.skillbill.desktop.core.domain.model.ChatEvent
import dev.skillbill.desktop.core.domain.model.ChatHistoryEntry
import dev.skillbill.desktop.core.domain.model.ChatHistoryRole
import dev.skillbill.desktop.core.domain.model.ChatImage
import dev.skillbill.desktop.core.domain.model.ChatPermissionMode
import dev.skillbill.desktop.core.domain.model.ChatScope
import dev.skillbill.desktop.core.domain.model.ChatTurnRequest
import dev.skillbill.desktop.core.domain.model.ControlOutcome
import dev.skillbill.desktop.core.domain.model.Detail
import dev.skillbill.desktop.core.domain.model.DetailSections
import dev.skillbill.desktop.core.domain.model.DetailState
import dev.skillbill.desktop.core.domain.model.Lifecycle
import dev.skillbill.desktop.core.domain.model.ReadProblem
import dev.skillbill.desktop.core.domain.model.RunOptions
import dev.skillbill.desktop.core.domain.model.WorkItem
import dev.skillbill.desktop.core.domain.model.GoalWorktreeState
import dev.skillbill.desktop.core.domain.repository.ExecutionChatRepository
import dev.skillbill.desktop.core.domain.repository.WorkDetailRepository
import kotlinx.coroutines.flow.firstOrNull
import kotlin.time.Clock
import java.util.UUID

internal const val GONE_NOTICE = "This execution is no longer in the open database."
internal const val UNREADABLE_NOTICE = "Execution detail could not be read; the agent sees the summary only."
internal const val NO_AGENT_NOTICE = "No agent is recorded for this execution and no default agent is configured."
internal const val ACT_PREFIX =
  "The operator paused this execution so you can act on it. When this turn ends the app resumes it; " +
    "do not resume, relaunch or continue it yourself. Wait for every command you start to finish before you end this turn."

internal const val ALREADY_RUNNING_NOTICE = "The execution is already running again; the app did not relaunch it."
internal const val REMOVED_WORKTREE_CHAT_NOTICE = "This goal's worktree was removed, so its earlier agent sessions cannot continue. New turns run in"

/** The one mode rule: a running or active execution is only read; anything else may be acted on. */
internal fun modeFor(header: WorkItem): ChatPermissionMode =
  if (header.running || header.lifecycle == Lifecycle.Active) ChatPermissionMode.ReadOnly else ChatPermissionMode.Act

/** One fresh read of the execution: [detail] and [runOptions] are null when the detail could not be read. */
internal class ExecutionRead(val header: WorkItem, val detail: Detail?, val runOptions: RunOptions?)

internal fun ControlOutcome.problemDetail(): String? = when (this) {
  is ControlOutcome.Refused -> detail ?: rejection.name
  is ControlOutcome.Failed -> when (val problem = problem) {
    is ReadProblem.Runtime -> problem.message
    is ReadProblem.Storage -> problem.failure.name
  }
  else -> null
}

/** Reads, snapshots and streams the turns of [ChatWorkspace]; every call runs inside a session job. */
internal class ChatTurns(
  private val chat: ExecutionChatRepository,
  private val details: WorkDetailRepository,
  private val clock: Clock,
) {
  /** The first settled read; a flow that ends without one counts as unreadable. */
  suspend fun freshRead(item: WorkItem): DetailState? = details.observe(item).firstOrNull { it.sections !is DetailSections.Pending }

  /** Turns [state] into a read, or null after marking the session gone. Every read refreshes the session's header and mode. */
  fun accept(session: ChatSession, state: DetailState?): ExecutionRead? {
    val sections = state?.sections
    if (sections is DetailSections.Gone) {
      markGone(session)
      return null
    }
    val header = state?.header ?: session.item
    session.item = header
    followWorktree(session)
    if (header.goalWorktree != null && session.started && session.startedRoot != header.checkoutRoot) {
      chat.end(session.sessionId)
      session.restart(UUID.randomUUID().toString())
    }
    session.modeState.value = modeFor(header)
    if (sections is DetailSections.Loaded) return ExecutionRead(header, sections.detail, sections.runOptions)
    session.append(ChatMessage.Notice(UNREADABLE_NOTICE))
    return ExecutionRead(header, null, null)
  }

  fun followWorktree(session: ChatSession) {
    if (session.item.goalWorktree?.state != GoalWorktreeState.Removed || session.worktreeRemovedNoticed) return
    session.worktreeRemovedNoticed = true
    session.append(ChatMessage.Notice("$REMOVED_WORKTREE_CHAT_NOTICE ${session.item.repositoryRoot}."))
  }

  fun markGone(session: ChatSession) {
    if (session.goneState.value) return
    session.goneState.value = true
    if (session.queuedState.value.isNotEmpty()) {
      session.queuedState.value = emptyList()
      session.append(ChatMessage.Notice("Queued messages were not sent because this execution is no longer in the open database."))
    }
    session.append(ChatMessage.Notice(GONE_NOTICE))
  }

  /** Sends [text] with the full snapshot on a first turn and the diff since the last delivered one after that. */
  suspend fun turn(session: ChatSession, read: ExecutionRead, mode: ChatPermissionMode, text: String, prefix: String? = null, images: List<ChatImage> = emptyList()) {
    val agent = session.agentState.value ?: resolve(session, read) ?: run {
      session.append(ChatMessage.Notice(NO_AGENT_NOTICE))
      return
    }
    val current = snapshot(read.header, read.detail)
    val delivered = session.delivered
    val context = if (session.started && delivered != null) diff(delivered, current).render() else current.render()
    val prompt = listOfNotNull(prefix, context, text.takeIf { it.isNotBlank() }).joinToString("\n\n")
    stream(session, agent, read.header, mode, prompt, current, images)
  }

  /** Resolves the session's agent without a turn, so a chat that skips the opening turn can still be sent to. */
  suspend fun prepare(session: ChatSession, read: ExecutionRead) {
    if (session.agentState.value == null && resolve(session, read) == null) session.append(ChatMessage.Notice(NO_AGENT_NOTICE))
  }

  private suspend fun resolve(session: ChatSession, read: ExecutionRead): ChatAgent? =
    chat.resolveAgent(read.header, read.detail, read.runOptions)?.also {
      session.agentState.value = it
      session.agentSupportedState.value = chat.supports(it.agentId)
    }

  // The snapshot counts as delivered, and the CLI session as created, only once the turn produced output. A turn that ends
  // without cancellation saves the chat, so a later window can resume it.
  private suspend fun stream(
    session: ChatSession,
    agent: ChatAgent,
    header: WorkItem,
    mode: ChatPermissionMode,
    prompt: String,
    sent: ExecutionSnapshot,
    images: List<ChatImage>,
  ) {
    val scope = ChatScope.Execution(header.id, header.kind, header.issueKey, header.title, header.checkoutRoot)
    val request = ChatTurnRequest(session.sessionId, !session.started, agent, scope, mode, prompt, images)
    var delivered = false
    try {
      chat.send(request).collect { event ->
        session.thread.dropUnprompted = false
        if (event !is ChatEvent.Failed && !delivered) {
          delivered = true
          session.delivered = sent
          session.started = true
          session.startedRoot = header.checkoutRoot
        }
        session.thread.show(agent, event)
      }
    } finally {
      session.endStreaming()
    }
    if (session.started) save(session, agent)
  }

  /**
   * Shows one event of a reply the agent started on its own, such as after a background command finished. The reply holds the
   * composer while it streams unless an operator turn already does, and is saved once it ends.
   */
  suspend fun unprompted(session: ChatSession, event: ChatEvent) {
    if (session.thread.dropUnprompted) return
    if (event is ChatEvent.ApprovalRequested) {
      // A card is not a reply: it neither holds the composer nor ends with a completion, so it never starts an own turn.
      session.thread.show(session.agentState.value, event)
      return
    }
    val ownTurn = session.turnJob == null
    if (ownTurn) {
      session.busyState.value = true
      session.stoppableState.value = true
    }
    val agent = session.agentState.value
    session.thread.show(agent, event)
    if (event !is ChatEvent.Completed && event !is ChatEvent.Failed) return
    if (ownTurn) {
      session.busyState.value = false
      session.stoppableState.value = false
    }
    if (agent != null && session.started) save(session, agent)
  }

  /** Closes a turn the operator stopped: a session the agent never created is not saved, and the next turn opens a new one. */
  suspend fun finishStopped(session: ChatSession) {
    session.endStreaming()
    session.append(ChatMessage.Notice(STOPPED_NOTICE))
    val agent = session.agentState.value
    when {
      !session.started -> session.restart(UUID.randomUUID().toString())
      agent != null -> save(session, agent)
    }
  }

  /** Saves the chat after an approval outcome changed outside a turn, so the saved copy shows the final outcome. */
  suspend fun approvalChanged(session: ChatSession) {
    val agent = session.agentState.value
    if (agent != null && session.started) save(session, agent)
  }

  /** Saves the chat after an operation card changed outside a turn; false when the chat has no agent to save under. */
  internal suspend fun operationCardChanged(session: ChatSession): Boolean {
    val agent = session.agentState.value ?: return false
    save(session, agent)
    return true
  }

  private suspend fun save(session: ChatSession, agent: ChatAgent) {
    val lines = session.historyLines()
    val title = lines.firstOrNull { it.role == ChatHistoryRole.Operator }?.text.orEmpty()
    chat.saveChat(ChatHistoryEntry(session.sessionId, session.item.id, agent, clock.now(), title, lines))
  }
}
