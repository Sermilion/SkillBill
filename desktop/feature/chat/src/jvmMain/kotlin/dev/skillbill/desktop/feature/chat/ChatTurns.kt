package dev.skillbill.desktop.feature.chat

import dev.skillbill.desktop.core.domain.model.ChatAgent
import dev.skillbill.desktop.core.domain.model.ChatEvent
import dev.skillbill.desktop.core.domain.model.ChatHistoryEntry
import dev.skillbill.desktop.core.domain.model.ChatHistoryRole
import dev.skillbill.desktop.core.domain.model.ChatImage
import dev.skillbill.desktop.core.domain.model.ChatPermissionMode
import dev.skillbill.desktop.core.domain.model.CursorMcpDependency
import dev.skillbill.desktop.core.domain.model.ChatScope
import dev.skillbill.desktop.core.domain.model.ChatTurnRequest
import dev.skillbill.desktop.core.domain.model.ControlOutcome
import dev.skillbill.desktop.core.domain.model.Detail
import dev.skillbill.desktop.core.domain.model.DetailSections
import dev.skillbill.desktop.core.domain.model.DetailState
import dev.skillbill.desktop.core.domain.model.Lifecycle
import dev.skillbill.desktop.core.domain.model.MainCheckoutOutcome
import dev.skillbill.desktop.core.domain.model.ReadProblem
import dev.skillbill.desktop.core.domain.model.RunOptions
import dev.skillbill.desktop.core.domain.model.SessionMoveResult
import dev.skillbill.desktop.core.domain.model.WorkItem
import dev.skillbill.desktop.core.domain.model.GoalWorktreeState
import dev.skillbill.desktop.core.domain.repository.ExecutionChatRepository
import dev.skillbill.desktop.core.domain.repository.WorkDetailRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.sync.withLock
import kotlin.time.Clock
import java.io.File
import java.util.UUID

internal const val GONE_NOTICE = "This execution is no longer in the open database."
internal const val UNREADABLE_NOTICE = "Execution detail could not be read; the agent sees the summary only."
internal const val NO_AGENT_NOTICE = "No agent is recorded for this execution and no default agent is configured."
internal const val ACT_PREFIX =
  "The operator paused this execution so you can act on it. When this turn ends the app resumes it; " +
    "do not resume, relaunch or continue it yourself. Wait for every command you start to finish before you end this turn."

internal const val ALREADY_RUNNING_NOTICE = "The execution is already running again; the app did not relaunch it."
internal const val REMOVED_WORKTREE_CHAT_NOTICE = "This goal's worktree was removed. New turns run in"
internal const val SESSIONS_MOVED_SENTENCE = "Earlier Claude and Codex chats were moved there and can be resumed; Cursor and Junie chats start fresh."
internal const val NOTHING_TO_MOVE_SENTENCE = "Cursor and Junie chats start fresh."
internal const val MOVE_FAILED_SENTENCE = "Some earlier chats could not be moved and start fresh."
internal const val MAIN_CHECKOUT_NOT_SWITCHED_NOTICE = "The main checkout was not switched to the goal branch"

/** Agents whose sessions the move carries to the repository root; every other agent's chat restarts there. */
private val CONTINUABLE_AGENTS = setOf("claude", "codex")

/** The removed-worktree notice; a null [result] means the move threw. */
internal fun removedWorktreeNotice(root: String, result: SessionMoveResult?): String {
  val sentence = when {
    result == null || result.failed > 0 -> MOVE_FAILED_SENTENCE
    result.claudeMoved + result.codexMoved > 0 -> SESSIONS_MOVED_SENTENCE
    else -> NOTHING_TO_MOVE_SENTENCE
  }
  return "$REMOVED_WORKTREE_CHAT_NOTICE $root. $sentence"
}

/** Coordinates optional pause actions with execution state; provider chat turns always use Act. */
internal fun coordinationModeFor(header: WorkItem): ChatPermissionMode =
  if (header.running || header.lifecycle == Lifecycle.Active) ChatPermissionMode.ReadOnly else ChatPermissionMode.Act

/** The directory an execution's agent runs in: its checkout while that directory exists, else the repository root. */
internal fun WorkItem.workingRoot(): String =
  if (checkoutRoot.isNotBlank() && File(checkoutRoot).isDirectory) checkoutRoot else repositoryRoot

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
  suspend fun accept(session: ChatSession, state: DetailState?): ExecutionRead? {
    val sections = state?.sections
    if (sections is DetailSections.Gone) {
      markGone(session)
      return null
    }
    val header = state?.header ?: session.item
    session.item = header
    followRoot(session)
    session.coordinationModeState.value = coordinationModeFor(header)
    if (sections is DetailSections.Loaded) return ExecutionRead(header, sections.detail, sections.runOptions)
    session.append(ChatMessage.Notice(UNREADABLE_NOTICE))
    return ExecutionRead(header, null, null)
  }

  /** Follows a removed worktree, then ends the agent session when the started root is not the one the next turn runs in. */
  suspend fun followRoot(session: ChatSession) {
    followWorktree(session)
    if (session.item.goalWorktree != null && session.started && session.startedRoot != session.item.workingRoot()) {
      if (session.agentState.value?.agentId == "opencode") {
        if (!session.openCodeCheckoutChangedNoticed) {
          chat.end(session.sessionId)
          session.openCodeCheckoutChangedNoticed = true
          session.append(ChatMessage.Notice("This OpenCode conversation is bound to its saved checkout. Start a new chat to continue from the current checkout."))
        }
      } else {
        chat.end(session.sessionId)
        session.restart(UUID.randomUUID().toString())
      }
    }
  }

  /**
   * Once per session, when the worktree is gone: ends the agent process, moves the goal's Claude and Codex sessions to the repository
   * root and posts the notice. A started Claude or Codex session whose move had no failure keeps its id and resumes in the root.
   */
  suspend fun followWorktree(session: ChatSession) {
    if (worktreeGone(session.item) && !session.worktreeRemovedNoticed) moveSessions(session)
    followMainCheckout(session)
  }

  private suspend fun moveSessions(session: ChatSession) {
    session.worktreeMoveLock.withLock {
      val worktree = session.item.goalWorktree
      if (worktree != null && !session.worktreeRemovedNoticed) {
        val root = session.item.repositoryRoot
        chat.end(session.sessionId)
        val result = try {
          chat.moveSessions(worktree.path, root)
        } catch (cancelled: CancellationException) {
          throw cancelled
        } catch (_: Exception) {
          null
        }
        session.worktreeRemovedNoticed = true
        if (session.agentState.value?.agentId in CONTINUABLE_AGENTS && session.started && result?.failed == 0) session.startedRoot = root
        if (session.agentState.value?.agentId == "opencode") session.openCodeCheckoutChangedNoticed = true
        val notice = if (session.agentState.value?.agentId == "opencode") {
          "This goal's worktree was removed. The saved OpenCode conversation stays bound to that checkout. Start a new chat to work in $root."
        } else removedWorktreeNotice(root, result)
        session.append(ChatMessage.Notice(notice))
      }
    }
  }

  /** The worktree is removed, or the runtime still lists it but its directory is gone. */
  private fun worktreeGone(item: WorkItem): Boolean =
    item.goalWorktree?.state == GoalWorktreeState.Removed || (item.goalWorktree != null && item.workingRoot() != item.checkoutRoot)

  /** Tells the operator once per session that the main checkout stayed where it was after the worktree was removed. */
  private fun followMainCheckout(session: ChatSession) {
    val worktree = session.item.goalWorktree ?: return
    val notSwitched = worktree.mainCheckout == MainCheckoutOutcome.SkippedUncommittedChanges || worktree.mainCheckout == MainCheckoutOutcome.Failed
    if (worktree.state != GoalWorktreeState.Removed || !notSwitched || session.mainCheckoutNoticed) return
    session.mainCheckoutNoticed = true
    session.append(ChatMessage.Notice(MAIN_CHECKOUT_NOT_SWITCHED_NOTICE + (worktree.mainCheckoutDetail?.let { ": $it" }.orEmpty()) + "."))
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
  suspend fun turn(
    session: ChatSession,
    read: ExecutionRead,
    text: String,
    prefix: String? = null,
    images: List<ChatImage> = emptyList(),
    cursorMcpDependency: CursorMcpDependency = CursorMcpDependency.ProjectUserOnly,
  ): Boolean {
    val agent = session.agentState.value ?: resolve(session, read) ?: run {
      session.append(ChatMessage.Notice(NO_AGENT_NOTICE))
      return false
    }
    val current = snapshot(read.header, read.detail)
    val delivered = session.delivered
    val context = if (session.started && delivered != null) diff(delivered, current).render() else current.render()
    val prompt = listOfNotNull(prefix, context, text.takeIf { it.isNotBlank() }).joinToString("\n\n")
    return stream(session, agent, read.header, prompt, current, images, cursorMcpDependency)
  }

  /** Resolves the session's agent without a turn, so a chat that skips the opening turn can still be sent to. */
  suspend fun prepare(session: ChatSession, read: ExecutionRead) {
    if (session.agentState.value == null && resolve(session, read) == null) session.append(ChatMessage.Notice(NO_AGENT_NOTICE))
  }

  private suspend fun resolve(session: ChatSession, read: ExecutionRead): ChatAgent? =
    chat.resolveAgent(read.header, read.detail, read.runOptions)?.also {
      session.agentState.value = it
      session.agentSupportedState.value = chat.supports(it.agentId)
      session.thread.showCapability(it)
    }

  // A provider session and snapshot count as delivered only after a non-failure update. Refused setup leaves saved history intact.
  private suspend fun stream(
    session: ChatSession,
    agent: ChatAgent,
    header: WorkItem,
    prompt: String,
    sent: ExecutionSnapshot,
    images: List<ChatImage>,
    cursorMcpDependency: CursorMcpDependency,
  ): Boolean {
    val root = header.workingRoot()
    val scope = ChatScope.Execution(header.id, header.kind, header.issueKey, header.title, root)
    val savedHistory = if (agent.agentId == "opencode") savedHistory(session, agent) else null
    val request = ChatTurnRequest(
      session.sessionId,
      !session.started,
      agent,
      scope,
      ChatPermissionMode.Act,
      prompt,
      images,
      cursorMcpDependency,
      savedHistory,
    )
    var delivered = false
    try {
      chat.send(request).collect { event ->
        session.thread.dropUnprompted = false
        if (event !is ChatEvent.Failed && !delivered) {
          if (event !is ChatEvent.Notice) {
            delivered = true
            session.delivered = sent
            session.started = true
            session.startedRoot = root
          }
        }
        session.thread.show(agent, event)
      }
    } finally {
      session.endStreaming()
    }
    if (session.started && delivered) save(session, agent)
    return delivered
  }

  /**
   * Shows one event of a reply the agent started on its own, such as after a background command finished. The reply holds the
   * composer while it streams unless an operator turn already does, and is saved once it ends.
   */
  suspend fun unprompted(session: ChatSession, event: ChatEvent) {
    if (session.thread.dropUnprompted) return
    if (event is ChatEvent.ApprovalRequested || event is ChatEvent.Notice) {
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
      !session.started && agent?.agentId != "opencode" -> session.restart(UUID.randomUUID().toString())
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

  internal suspend fun save(session: ChatSession, agent: ChatAgent) {
    chat.saveChat(savedHistory(session, agent))
  }

  private fun savedHistory(session: ChatSession, agent: ChatAgent): ChatHistoryEntry {
    val lines = session.historyLines()
    val title = lines.firstOrNull { it.role == ChatHistoryRole.Operator }?.text.orEmpty()
    return ChatHistoryEntry(session.sessionId, session.item.id, agent, clock.now(), title, lines)
  }
}
