package dev.skillbill.desktop.core.domain.model

import kotlin.time.Instant

/**
 * The agent a chat turn runs on; a null [model] or [effort] leaves the agent CLI's own default. [profile] is the agent's config
 * directory, such as `~/.codex-api`, when a resumed session came from it; null runs the agent as the app is configured to.
 */
data class ChatAgent(
  val agentId: String,
  val model: String?,
  val effort: String?,
  val profile: String? = null,
  val approvalUnavailableReason: String? = null,
)

/** Provider permission scope; operator chats start in [Act], independently of execution lifecycle. */
enum class ChatPermissionMode { ReadOnly, Act }

enum class CursorMcpDependency { ProjectUserOnly, TeamDashboardRequired }

/** What a chat is about: one [Execution], or [NewTask] work with no execution yet. [repositoryRoot] is where the agent runs. */
sealed interface ChatScope {
  val repositoryRoot: String

  data class Execution(
    val executionId: String,
    val kind: WorkKind?,
    val issueKey: String?,
    val title: String,
    override val repositoryRoot: String,
  ) : ChatScope

  data class NewTask(override val repositoryRoot: String, val baseBranch: String?) : ChatScope
}

/**
 * One image pasted into a chat message. [mediaType] is `"image/png"` or `"image/jpeg"`. An image's number in a message is its
 * 1-based position in the list. [bytes] is not copied, so callers must not change it after construction.
 */
class ChatImage(val bytes: ByteArray, val mediaType: String) {
  override fun equals(other: Any?): Boolean =
    other is ChatImage && mediaType == other.mediaType && bytes.contentEquals(other.bytes)

  override fun hashCode(): Int = 31 * mediaType.hashCode() + bytes.contentHashCode()

  override fun toString(): String = "ChatImage($mediaType, ${bytes.size} bytes)"
}

/**
 * One operator message; [firstTurn] opens [sessionId], later turns resume it. [images] live for the session only and are
 * never saved.
 */
data class ChatTurnRequest(
  val sessionId: String,
  val firstTurn: Boolean,
  val agent: ChatAgent,
  val scope: ChatScope,
  val mode: ChatPermissionMode,
  val prompt: String,
  val images: List<ChatImage> = emptyList(),
  val cursorMcpDependency: CursorMcpDependency = CursorMcpDependency.ProjectUserOnly,
)

/** The run a new-task action starts when clicked; [wire] is its value in the action block and on the start chips. */
enum class NewTaskActionKind(val wire: String) {
  Goal("goal"),
  Plan("plan"),
  Review("review"),
  Validation("validation"),
  Qa("qa"),
  Pr("pr"),
  Monitor("monitor"),
  Verify("verify"),
  PrReviewFix("pr-review-fix"),
}

/**
 * A null [start] sends [instruction] as the next turn; otherwise clicking the action starts that run. [title] names a started
 * goal or plan. [branch] is the git branch a started run works on; null runs it on the chat's checkout as it is. [verify]
 * carries a verify start's QA and review mode.
 */
data class ChatSuggestedAction(
  val label: String,
  val instruction: String,
  val mode: ChatPermissionMode,
  val start: NewTaskActionKind? = null,
  val title: String? = null,
  val target: String? = null,
  val branch: String? = null,
  val verify: VerifyStartChoices = VerifyStartChoices(),
)

data class ChatToolCall(val id: String, val name: String, val input: String, val summary: String?, val workingDirectory: String? = null)

/** What one turn streams back; a turn ends with [Completed] or [Failed]. */
sealed interface ChatEvent {
  data object MessageStarted : ChatEvent

  data class TextDelta(val text: String) : ChatEvent

  data class Notice(val text: String) : ChatEvent

  data class ToolUse(val call: ChatToolCall) : ChatEvent

  data class ToolResult(val callId: String, val output: String, val isError: Boolean) : ChatEvent

  data class SuggestedActions(val actions: List<ChatSuggestedAction>) : ChatEvent

  /** A permission request of this chat's process, or its settled outcome; the same card may arrive again as it changes. */
  data class ApprovalRequested(val card: ApprovalCard) : ChatEvent

  /**
   * The shell command of tool call [callId] keeps running in the background as [taskId] after its call returned. It is not
   * reply text: it may arrive between turns, and it never opens or ends a reply.
   */
  data class BackgroundTaskStarted(val taskId: String, val callId: String) : ChatEvent

  /** The background command [taskId] finished, failed or was stopped, including when its agent process ended. */
  data class BackgroundTaskEnded(val taskId: String) : ChatEvent

  data object Completed : ChatEvent

  data class Failed(val failure: ChatFailure, val detail: String?) : ChatEvent
}

/** Why a turn ended without an answer. */
enum class ChatFailure { AgentUnsupported, ApprovalUnsupported, BinaryMissing, ProcessFailed, StreamMalformed }

enum class ChatHistoryRole { Operator, Agent, Notice, Error, Approval, OperationCard }

/** What a saved chat keeps of a parked-operation card: only the identity, so a restored card is read from the store again. */
data class OperationCardReference(
  val token: String,
  val launchId: String,
  val operationId: String,
  val state: OperationCardState,
  val draftChoices: Map<String, String>? = null,
  val refusal: String? = null,
)

/**
 * One saved line. An [ChatHistoryRole.Approval] line carries the complete structured [approval] record and its outcome;
 * [text] is only a one-line label. A restored card is a display copy and never authority. An [ChatHistoryRole.OperationCard]
 * line carries only its [operationCard] reference.
 */
data class ChatHistoryLine(
  val role: ChatHistoryRole,
  val text: String,
  val failure: ChatFailure? = null,
  val actions: List<ChatSuggestedAction> = emptyList(),
  val selectedAction: ChatSuggestedAction? = null,
  val approval: ApprovalCard? = null,
  val operationCard: OperationCardReference? = null,
)

/** Where a saved new-task draft runs; it marks the save as a draft, which references no execution. */
data class ChatNewTaskContext(
  val repositoryRoot: String,
  val baseBranch: String?,
  val sessionEstablished: Boolean = true,
  val pendingTurnLineIndex: Int? = null,
  val changesBaseline: ChatChangesBaseline? = null,
  val changesCaptureAttempted: Boolean = false,
  val changesAttributedPaths: Set<String>? = null,
)

data class ChatChangesBaseline(
  val headSha: String,
  val untrackedPaths: List<String>,
  val stashSha: String?,
  val takenAt: Instant,
  val projectId: String? = null,
  val worktrees: Map<String, ChatChangesBaseline> = emptyMap(),
)

/**
 * A chat `/resume` can continue: [sessionId] names the agent session to resume. [executionId] is set on chats the app saved
 * and null on sessions read from an agent CLI's own store. [lines] are the latest messages when already known; a listed
 * agent session leaves them empty until it is opened.
 */
data class ChatHistoryEntry(
  val sessionId: String,
  val executionId: String?,
  val agent: ChatAgent,
  val updatedAt: Instant,
  val title: String,
  val lines: List<ChatHistoryLine>,
  val newTask: ChatNewTaskContext? = null,
  val summary: String? = null,
  /** The app session linked by a native listing; transient metadata, absent on stored app history and older callers. */
  val appSessionId: String? = null,
  /** The checkout where this provider conversation was established, when known. */
  val executionCheckoutRoot: String? = null,
)
