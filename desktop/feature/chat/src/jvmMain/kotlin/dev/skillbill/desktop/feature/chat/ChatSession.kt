package dev.skillbill.desktop.feature.chat

import dev.skillbill.desktop.core.domain.model.ChatAgent
import dev.skillbill.desktop.core.domain.model.ChatEvent
import dev.skillbill.desktop.core.domain.model.ChatImage
import dev.skillbill.desktop.core.domain.model.ChatHistoryEntry
import dev.skillbill.desktop.core.domain.model.SavedChatProjection
import dev.skillbill.desktop.core.domain.model.NewTaskActionKind
import dev.skillbill.desktop.core.domain.model.ChatHistoryLine
import dev.skillbill.desktop.core.domain.model.ChatHistoryRole
import dev.skillbill.desktop.core.domain.model.ChatToolCall
import dev.skillbill.desktop.core.domain.model.ChatSuggestedAction
import dev.skillbill.desktop.core.domain.model.ChatPermissionMode
import dev.skillbill.desktop.core.domain.model.CursorMcpDependency
import dev.skillbill.desktop.core.domain.model.WorkItem
import kotlinx.coroutines.Job
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.time.Clock

internal data class QueuedChatMessage(
  val text: String,
  val action: ChatSuggestedAction?,
  val pauseAndAct: Boolean,
  val images: List<ChatImage> = emptyList(),
  val cursorMcpDependency: CursorMcpDependency = CursorMcpDependency.ProjectUserOnly,
  val onRejected: (() -> Unit)? = null,
)

private data class ProviderSessionIdentity(val sessionId: String, val agentId: String)

/**
 * One chat about an execution; an execution can have several. [id] names the chat tab. [sessionId] names the agent CLI session and changes when the operator switches agents; [job] parents
 * every turn, so cancelling it ends the turn's process; [turnJob] is the turn that owns [busy] now. [delivered] is the snapshot
 * the agent last received and the base of the next diff.
 */
class ChatSession internal constructor(
  initial: WorkItem,
  sessionId: String,
  internal val job: Job,
  clock: Clock = Clock.System,
  val id: String = initial.id,
) {
  private val headerState = MutableStateFlow(initial)

  /** The execution as of the latest read; it starts as the row the chat opened from. */
  val header: StateFlow<WorkItem> = headerState.asStateFlow()

  internal var item: WorkItem
    get() = headerState.value
    set(value) {
      headerState.value = value
    }

  private val sessionIdState = MutableStateFlow(sessionId)

  /** The agent session the chat continues; it changes on `/clear`, `/resume` and an agent switch. */
  internal val sessionIds: StateFlow<String> = sessionIdState.asStateFlow()

  val sessionId: String get() = sessionIdState.value

  internal var delivered: ExecutionSnapshot? = null
  internal var started: Boolean = false
  internal var startedRoot: String? = null
  internal var openCodeCheckoutChangedNoticed: Boolean = false
  internal var worktreeRemovedNoticed: Boolean = false
  internal var worktreeMoveOutcome: WorktreeMoveOutcome? = null
  internal var worktreeRemovalNotice: ChatMessage.Notice? = null
  internal var mainCheckoutNotice: ChatMessage.Notice? = null
  private val providerSessionRoots = mutableMapOf<ProviderSessionIdentity, String>()

  /**
   * Held while the removed checkout's sessions move, so this session's refresh, resume listing and turn move them and post the
   * notice once. Other windows hold their own sessions; the catalog serializes their moves.
   */
  internal val worktreeMoveLock = Mutex()
  internal var mainCheckoutNoticed: Boolean = false
  internal var turnJob: Job? = null
  internal var resumeJob: Job? = null
  internal var stoppedTurn: Job? = null
  internal var pauseWatch: Job? = null
  internal var unpromptedObserver: Job? = null
  internal var approvalObserver: Job? = null
  internal val transcriptSaveLock = Mutex()
  private val acceptedPersistences = mutableSetOf<Job>()

  internal fun trackAcceptedPersistence(job: Job) = synchronized(this) {
    acceptedPersistences += job
    job.invokeOnCompletion { synchronized(this) { acceptedPersistences -= job } }
  }

  internal fun hasPendingAcceptedPersistence(): Boolean = synchronized(this) { acceptedPersistences.any { !it.isCompleted } }

  internal suspend fun awaitAcceptedPersistence() {
    while (true) {
      val pending = synchronized(this) { acceptedPersistences.toList() }
      if (pending.isEmpty()) return
      pending.forEach { it.join() }
    }
  }

  /** The next turn opens [newSessionId] and sends the full snapshot, as a first turn does. */
  internal fun restart(newSessionId: String) {
    sessionIdState.value = newSessionId
    delivered = null
    started = false
    startedRoot = null
  }

  /** The next turn continues the saved agent session [savedSessionId] and sends the full snapshot, since what it last saw is unknown. */
  internal fun resumeFrom(savedSessionId: String, root: String, agent: ChatAgent) {
    sessionIdState.value = savedSessionId
    delivered = null
    recordStarted(agent, root)
  }

  internal fun recordStarted(agent: ChatAgent, root: String) {
    started = true
    startedRoot = root
    providerSessionRoots[ProviderSessionIdentity(sessionId, agent.agentId)] = root
  }

  internal fun hasProviderSessionAt(entry: ChatHistoryEntry, root: String): Boolean =
    providerSessionRoots[ProviderSessionIdentity(entry.appSessionId ?: entry.sessionId, entry.agent.agentId)] == root

  internal fun canContinue(entry: ChatHistoryEntry, root: String): Boolean {
    if (hasProviderSessionAt(entry, root) || entry.executionCheckoutRoot == root) return true
    val moveOutcome = worktreeMoveOutcome
    if (moveOutcome != null) return moveOutcome.canContinue(entry.agent.agentId)
    if (entry.executionCheckoutRoot != null) return false
    return entry.agent.agentId != "cursor" && entry.agent.agentId != "junie"
  }

  internal var steering: ChatSteeringDelivery? = null
  internal var recoveryOnly = false
  internal var databaseSessionId: Long? = null
  internal val thread = ChatThread(clock)

  val transcript: StateFlow<List<ChatMessage>> get() = thread.transcript

  internal val agentState get() = thread.agentState
  val agent: StateFlow<ChatAgent?> get() = thread.agent

  internal val agentSupportedState get() = thread.agentSupportedState
  val agentSupported: StateFlow<Boolean> get() = thread.agentSupported

  internal val busyState get() = thread.busyState
  val busy: StateFlow<Boolean> get() = thread.busy

  internal val stoppableState get() = thread.stoppableState
  val stoppable: StateFlow<Boolean> get() = thread.stoppable

  internal val queuedState get() = thread.queuedState
  internal val queued: StateFlow<List<QueuedChatMessage>> get() = thread.queued

  internal val coordinationModeState = MutableStateFlow(coordinationModeFor(item))
  val coordinationMode: StateFlow<ChatPermissionMode> = coordinationModeState.asStateFlow()

  /**
   * True only while the approval feed holds this window's operator registration for the chat's repository. Every card
   * control is disabled while it is false, and a restored or stored card never turns it on.
   */
  internal val approvalLiveState = MutableStateFlow(false)
  val approvalLive: StateFlow<Boolean> = approvalLiveState.asStateFlow()

  internal val goneState = MutableStateFlow(false)
  val gone: StateFlow<Boolean> = goneState.asStateFlow()

  /** The saved chats `/resume` offers; null while the picker is closed. */
  internal val resumeChoicesState = MutableStateFlow<SavedChatProjection?>(null)
  val resumeChoices: StateFlow<SavedChatProjection?> = resumeChoicesState.asStateFlow()

  /** The start waiting for an issue prefix; null when no start needs one. */
  internal val prefixChoiceState = MutableStateFlow<PendingPrefixChoice?>(null)
  val prefixChoice: StateFlow<PendingPrefixChoice?> = prefixChoiceState.asStateFlow()

  internal fun append(message: ChatMessage) = thread.append(message)

  internal fun replaceTranscript(transcript: List<ChatMessage>) = thread.replaceTranscript(transcript)

  internal fun resumeTranscript(stateCard: ChatMessage, resumeNotice: ChatMessage, lines: List<ChatHistoryLine>): List<ChatMessage> {
    val retainedRemoval = worktreeRemovalNotice
    val retainedMainCheckout = mainCheckoutNotice
    val history = lines.filterNot { line ->
      line.role == ChatHistoryRole.Notice && line.approval == null && line.operationCard == null &&
        ((retainedRemoval != null && isSerializedRemovalNotice(line.text)) ||
          (retainedMainCheckout != null && isSerializedMainCheckoutNotice(line.text)))
    }.map(::restored)
    return listOf(stateCard, resumeNotice) + listOfNotNull(retainedRemoval, retainedMainCheckout) + history
  }

  internal fun historyLines(): List<ChatHistoryLine> = thread.historyLines()

  internal fun appendDelta(text: String) = thread.appendDelta(text)

  internal fun appendTool(call: ChatToolCall) = thread.appendTool(call)

  internal fun recordToolResult(result: ChatEvent.ToolResult) = thread.recordToolResult(result)

  internal fun suggestActions(actions: List<ChatSuggestedAction>) = thread.suggestActions(actions)

  internal fun endStreaming() = thread.endStreaming()
}

/** A goal or plan start the runtime sent back for an issue prefix; choosing one retries [action]. */
data class PendingPrefixChoice(val action: ChatSuggestedAction, val start: NewTaskActionKind, val choices: List<String>)
