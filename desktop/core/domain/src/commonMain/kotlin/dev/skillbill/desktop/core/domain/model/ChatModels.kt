package dev.skillbill.desktop.core.domain.model

import kotlin.time.Instant

const val UNAVAILABLE_AGENT_ID = "unavailable"

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

/** What a chat is about. [repositoryRoot] is the non-null provider and approval directory; New task selection is separate. */
sealed interface ChatScope {
  /** The effective non-null directory for provider commands and approval ownership. */
  val repositoryRoot: String

  data class Execution(
    val executionId: String,
    val kind: WorkKind?,
    val issueKey: String?,
    val title: String,
    override val repositoryRoot: String,
    val verifySourceBranch: String? = null,
  ) : ChatScope

  data class NewTask(
    val workingDirectory: String,
    val selectedRepositoryRoot: String?,
    val baseBranch: String?,
    val userHome: String = workingDirectory,
  ) : ChatScope {
    override val repositoryRoot: String get() = workingDirectory

    constructor(repositoryRoot: String, baseBranch: String?) : this(repositoryRoot, repositoryRoot, baseBranch)
  }
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

data class ChatSteeringRequest(
  val sessionId: String,
  val agentId: String,
  val deliveryId: String,
  val text: String,
  val images: List<ChatImage> = emptyList(),
)

sealed interface ChatSteeringResult {
  val deliveryId: String
  data class Accepted(override val deliveryId: String) : ChatSteeringResult
  data class Unsupported(override val deliveryId: String, val reason: String) : ChatSteeringResult
  data class NoActiveTurn(override val deliveryId: String, val reason: String) : ChatSteeringResult
  data class Rejected(override val deliveryId: String, val reason: String) : ChatSteeringResult
  data class DeliveryUncertain(override val deliveryId: String, val reason: String) : ChatSteeringResult
}

data class ChatSteeringUpdate(val sessionId: String, val result: ChatSteeringResult)

enum class ChatSteeringPolicy { LegacySendNow, LiveWhenAvailable }

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
  val savedHistory: ChatHistoryEntry? = null,
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

  /** A repository path proposed by a completed New task reply; callers must validate it before selection. */
  data class RepositoryCandidate(val path: String) : ChatEvent

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

enum class ChatStopSettlement { Settled, AlreadyFinished, CollectorCancellationRequired }

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
  val workingDirectory: String,
  val selectedRepositoryRoot: String?,
  val baseBranch: String?,
  val sessionEstablished: Boolean = true,
  val pendingTurnLineIndex: Int? = null,
  val changesBaseline: ChatChangesBaseline? = null,
  val changesCaptureAttempted: Boolean = false,
  val changesAttributedPaths: Set<String>? = null,
) {
  /** Compatibility directory accessor used by provider and approval consumers. */
  val repositoryRoot: String get() = workingDirectory

  constructor(
    repositoryRoot: String,
    baseBranch: String?,
    sessionEstablished: Boolean = true,
    pendingTurnLineIndex: Int? = null,
    changesBaseline: ChatChangesBaseline? = null,
    changesCaptureAttempted: Boolean = false,
    changesAttributedPaths: Set<String>? = null,
    selectedRepositoryRoot: String? = repositoryRoot,
  ) : this(
    repositoryRoot,
    selectedRepositoryRoot,
    baseBranch,
    sessionEstablished,
    pendingTurnLineIndex,
    changesBaseline,
    changesCaptureAttempted,
    changesAttributedPaths,
  )
}

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

/** A saved app-chat file that needs operator recovery instead of provider continuation. */
enum class ChatRecoveryReason(val description: String) {
  UnreadableFile("The saved chat file could not be read as valid chat data."),
  InvalidOpenCodeBinding("The saved OpenCode session binding is incomplete or invalid."),
  OpenCodeHandleUnbound("The saved OpenCode provider handle has no complete identity binding."),
  MissingResumeMetadata("The saved chat is missing the operator message or agent details needed to resume safely."),
}

data class ChatRecoveryEntry(
  val sessionId: String,
  val originalDataPath: String,
  val reason: ChatRecoveryReason,
  val executionId: String? = null,
  val repositoryRoot: String? = null,
  val title: String? = null,
  val updatedAt: Instant? = null,
  val agentId: String? = null,
  val lines: List<ChatHistoryLine> = emptyList(),
)

/** Readable chats and records that need diagnosis stay distinct in saved-chat listings. */
data class SavedChatProjection(
  val entries: List<ChatHistoryEntry> = emptyList(),
  val recoveries: List<ChatRecoveryEntry> = emptyList(),
)
