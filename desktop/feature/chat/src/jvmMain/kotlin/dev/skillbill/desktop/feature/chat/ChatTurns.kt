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
import dev.skillbill.desktop.core.domain.model.WorkKind
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
private const val REMOVED_EXECUTION_CHAT_NOTICE = "worktree was removed when it finished. New turns run in"
internal const val SESSIONS_MOVED_SENTENCE = "Earlier Claude and Codex chats were moved there and can be resumed; Cursor chats start fresh."
internal const val NOTHING_TO_MOVE_SENTENCE = "Cursor chats start fresh."
internal const val MOVE_FAILED_SENTENCE = "Some earlier chats could not be moved and start fresh."
internal const val MAIN_CHECKOUT_NOT_SWITCHED_NOTICE = "The main checkout was not switched to the goal branch"

private val REMOVAL_RESULT_SENTENCES = setOf(SESSIONS_MOVED_SENTENCE, NOTHING_TO_MOVE_SENTENCE, MOVE_FAILED_SENTENCE)

internal fun isSerializedRemovalNotice(text: String): Boolean {
  val goalPrefix = REMOVED_WORKTREE_CHAT_NOTICE
  val executionMarker = "'s $REMOVED_EXECUTION_CHAT_NOTICE"
  val prefixLength = when {
    text.startsWith(goalPrefix) -> goalPrefix.length
    text.startsWith("This ") -> {
      val markerStart = text.lastIndexOf(executionMarker)
      if (markerStart <= "This ".length || text.substring("This ".length, markerStart).isBlank()) return false
      markerStart + executionMarker.length
    }
    else -> return false
  }
  if (text.getOrNull(prefixLength) != ' ') return false
  val body = text.substring(prefixLength + 1)
  return REMOVAL_RESULT_SENTENCES.any { sentence ->
    val ending = ". $sentence"
    body.endsWith(ending) && body.dropLast(ending.length).isNotBlank()
  } || LEGACY_REMOVAL_RESULT.matches(body)
}

internal fun isSerializedMainCheckoutNotice(text: String): Boolean {
  if (!text.startsWith(MAIN_CHECKOUT_NOT_SWITCHED_NOTICE)) return false
  val suffix = text.removePrefix(MAIN_CHECKOUT_NOT_SWITCHED_NOTICE)
  return suffix == "." || (suffix.startsWith(": ") && suffix.drop(2).isNotBlank() && suffix.endsWith("."))
}

/** Agents whose sessions the move can carry to the selected root; every other agent's chat restarts there. */
internal val CONTINUABLE_AGENTS = setOf("claude", "codex")

private val LEGACY_REMOVAL_RESULT = Regex(
  "^.*\\S\\. (?:Earlier Claude and Codex chats were moved there and can be resumed; )?Cursor and [A-Za-z]+ chats start fresh\\.$",
)

internal data class WorktreeMoveOutcome(val result: SessionMoveResult?) {
  val continuesProviderSession: Boolean get() = result?.failed == 0
  fun canContinue(agentId: String): Boolean = agentId in CONTINUABLE_AGENTS && continuesProviderSession
}

/** The removed-worktree notice; a null [result] means the move threw. */
internal fun removedWorktreeNotice(root: String, result: SessionMoveResult?): String =
  removedWorktreeNotice(REMOVED_WORKTREE_CHAT_NOTICE, root, result)

internal fun removedWorktreeNotice(item: WorkItem, root: String, result: SessionMoveResult?): String {
  val prefix = if (item.kind == WorkKind.Goal || item.goalWorktree != null) {
    REMOVED_WORKTREE_CHAT_NOTICE
  } else {
    val label = item.runKindLabel?.takeIf(String::isNotBlank)
      ?: when (item.kind) {
        WorkKind.Phase -> "phase"
        WorkKind.Operation -> "operation"
        else -> "execution"
      }
    "This $label's $REMOVED_EXECUTION_CHAT_NOTICE"
  }
  return removedWorktreeNotice(prefix, root, result)
}

private fun removedWorktreeNotice(prefix: String, root: String, result: SessionMoveResult?): String {
  val sentence = when {
    result == null || result.failed > 0 -> MOVE_FAILED_SENTENCE
    result.claudeMoved + result.codexMoved > 0 -> SESSIONS_MOVED_SENTENCE
    else -> NOTHING_TO_MOVE_SENTENCE
  }
  return "$prefix $root. $sentence"
}

/** Coordinates optional pause actions with execution state; provider chat turns always use Act. */
internal fun coordinationModeFor(header: WorkItem): ChatPermissionMode =
  if (header.running || header.lifecycle == Lifecycle.Active) ChatPermissionMode.ReadOnly else ChatPermissionMode.Act

/** The directory an execution's agent runs in: its checkout while present, else its captured primary root or legacy root. */
internal fun WorkItem.workingRoot(): String =
  if (goalWorktree?.state != GoalWorktreeState.Removed && checkoutRoot.isNotBlank() && File(checkoutRoot).isDirectory) checkoutRoot
  else primaryRepositoryRoot?.takeIf(String::isNotBlank) ?: repositoryRoot

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
    session.awaitAcceptedPersistence()
    val sections = state?.sections
    if (sections is DetailSections.Gone) {
      markGone(session)
      return null
    }
    val header = state?.header ?: session.item
    session.item = header
    if (session.thread.hasDeliveryClaim() || session.thread.delivery.value != null) return null
    followRoot(session)
    session.coordinationModeState.value = coordinationModeFor(header)
    if (sections is DetailSections.Loaded) return ExecutionRead(header, sections.detail, sections.runOptions)
    session.append(ChatMessage.Notice(UNREADABLE_NOTICE))
    return ExecutionRead(header, null, null)
  }

  /** Follows a removed checkout, then ends the agent session when its root differs from the next turn's root. */
  suspend fun followRoot(session: ChatSession) {
    session.worktreeMoveLock.withLock {
      val replacing = relocationSource(session.item)?.let { it != session.item.workingRoot() } == true ||
        session.started && session.startedRoot != session.item.workingRoot()
      if (replacing && session.thread.hasDeliveryClaim()) {
        session.steering?.detach { chat.end(session.sessionId) } ?: chat.end(session.sessionId)
        if (session.thread.deferDestructiveAction("change the chat checkout")) {
          session.recoveryOnly = true
          return
        }
      }
      followWorktreeWhileLocked(session)
      if (session.started && session.startedRoot != session.item.workingRoot()) {
        chat.end(session.sessionId)
        session.thread.clearBackgroundCommands()
        session.restart(UUID.randomUUID().toString())
      }
    }
  }

  /**
   * Once per session, when the execution checkout is gone: moves stored sessions to the selected root and posts its notice. A started
   * Claude or Codex session whose move had no failure keeps its id and resumes in that root.
   */
  suspend fun followWorktree(session: ChatSession) {
    session.worktreeMoveLock.withLock {
      val replacing = relocationSource(session.item)?.let { it != session.item.workingRoot() } == true ||
        session.started && session.startedRoot != session.item.workingRoot()
      if (replacing && session.thread.hasDeliveryClaim()) {
        session.steering?.detach { chat.end(session.sessionId) } ?: chat.end(session.sessionId)
        if (session.thread.deferDestructiveAction("change the chat checkout")) {
          session.recoveryOnly = true
          return
        }
      }
      followWorktreeWhileLocked(session)
    }
  }

  internal suspend fun followWorktreeWhileLocked(session: ChatSession) {
    if (session.thread.hasDeliveryClaim() || session.thread.delivery.value != null) return
    val item = session.item
    if (!session.worktreeRemovedNoticed) {
      val source = relocationSource(item)
      val root = item.workingRoot()
      if (source != null && source != root) moveSessionsLocked(session, source, root)
    }
    followMainCheckout(session)
  }

  private suspend fun moveSessionsLocked(session: ChatSession, source: String, root: String) {
    if (session.worktreeRemovedNoticed) return
    val currentSource = relocationSource(session.item) ?: return
    val currentRoot = session.item.workingRoot()
    if (currentSource != source || currentRoot != root || currentSource == currentRoot) return
    chat.end(session.sessionId)
    session.thread.clearBackgroundCommands()
    val result = try {
      chat.moveSessions(source, root)
    } catch (cancelled: CancellationException) {
      throw cancelled
    } catch (_: Exception) {
      null
    }
    session.worktreeMoveOutcome = WorktreeMoveOutcome(result)
    session.worktreeRemovedNoticed = true
    val agent = session.agentState.value
    if (agent != null && agent.agentId in CONTINUABLE_AGENTS && session.started && result?.failed == 0) session.recordStarted(agent, root)
    val notice = ChatMessage.Notice(removedWorktreeNotice(session.item, root, result))
    session.worktreeRemovalNotice = notice
    session.append(notice)
  }

  private fun relocationSource(item: WorkItem): String? {
    val goalWorktree = item.goalWorktree
    if (goalWorktree != null) {
      val missing = goalWorktree.state == GoalWorktreeState.Removed || item.workingRoot() != item.checkoutRoot
      return goalWorktree.path.takeIf { missing }
    }
    val primaryRoot = item.primaryRepositoryRoot?.takeIf(String::isNotBlank) ?: return null
    return item.repositoryRoot.takeIf {
      it != primaryRoot && !File(item.repositoryRoot).isDirectory
    }
  }

  /** Tells the operator once per session that the main checkout stayed where it was after the worktree was removed. */
  private fun followMainCheckout(session: ChatSession) {
    val worktree = session.item.goalWorktree ?: return
    val notSwitched = worktree.mainCheckout == MainCheckoutOutcome.SkippedUncommittedChanges || worktree.mainCheckout == MainCheckoutOutcome.Failed
    if (worktree.state != GoalWorktreeState.Removed || !notSwitched || session.mainCheckoutNoticed) return
    session.mainCheckoutNoticed = true
    val notice = ChatMessage.Notice(MAIN_CHECKOUT_NOT_SWITCHED_NOTICE + (worktree.mainCheckoutDetail?.let { ": $it" }.orEmpty()) + ".")
    session.mainCheckoutNotice = notice
    session.append(notice)
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

  /** Sends [text] with the full snapshot for fresh provider sessions, otherwise the diff since the last delivered turn. */
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
    val context = if (session.started && delivered != null && agent.agentId != "devin") {
      diff(delivered, current).render()
    } else {
      current.render()
    }
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
      session.thread.showUnavailableAgentNotice()
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
    val request = ChatTurnRequest(session.sessionId, !session.started, agent, scope, ChatPermissionMode.Act, prompt, images, cursorMcpDependency)
    var delivered = false
    try {
      chat.send(request).collect { event ->
        session.thread.dropUnprompted = false
        if (event !is ChatEvent.Failed && !delivered) {
          if (event !is ChatEvent.Notice) {
            delivered = true
            session.delivered = sent
            session.recordStarted(agent, root)
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
    if (event is ChatEvent.BackgroundTaskStarted || event is ChatEvent.BackgroundTaskEnded) {
      // A background command is not a reply, and it still runs or ends after the operator stopped one.
      session.thread.show(session.agentState.value, event)
      return
    }
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

  internal fun acceptedTranscript(session: ChatSession): ChatHistoryEntry? {
    val agent = session.agentState.value ?: return null
    val lines = session.historyLines()
    val title = lines.firstOrNull { it.role == ChatHistoryRole.Operator }?.text.orEmpty()
    return ChatHistoryEntry(session.sessionId, session.item.id, agent, clock.now(), title, lines, executionCheckoutRoot = session.startedRoot)
  }

  internal suspend fun saveAccepted(session: ChatSession, entry: ChatHistoryEntry) {
    session.transcriptSaveLock.withLock { chat.saveChat(entry) }
  }

  internal suspend fun save(session: ChatSession, agent: ChatAgent) {
    val lines = session.historyLines()
    val title = lines.firstOrNull { it.role == ChatHistoryRole.Operator }?.text.orEmpty()
    chat.saveChat(
      ChatHistoryEntry(
        session.sessionId, session.item.id, agent, clock.now(), title, lines,
        executionCheckoutRoot = session.startedRoot,
      ),
    )
  }
}
