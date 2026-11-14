package dev.skillbill.desktop.core.data.chat

import dev.skillbill.desktop.core.common.Diagnostics
import dev.skillbill.desktop.core.common.HostEnvironment
import dev.skillbill.desktop.core.common.di.AppScope
import dev.skillbill.desktop.core.domain.model.ChatAgent
import dev.skillbill.desktop.core.domain.model.UNAVAILABLE_AGENT_ID
import dev.skillbill.desktop.core.domain.model.ChatChangesBaseline
import dev.skillbill.desktop.core.domain.model.ChatFailure
import dev.skillbill.desktop.core.domain.model.ChatHistoryEntry
import dev.skillbill.desktop.core.domain.model.ChatHistoryRead
import dev.skillbill.desktop.core.domain.model.ChatHistoryLine
import dev.skillbill.desktop.core.domain.model.ChatHistoryRole
import dev.skillbill.desktop.core.domain.model.ChatNewTaskContext
import dev.skillbill.desktop.core.domain.model.hasSameConversationIdentity
import dev.skillbill.desktop.core.domain.model.ChatStoreRead
import dev.skillbill.desktop.core.domain.model.ChatStoreReadFailure
import dev.skillbill.desktop.core.domain.model.ChatRecoveryEntry
import dev.skillbill.desktop.core.domain.model.ChatRecoveryReason
import dev.skillbill.desktop.core.domain.model.ChatSessionAcquisition
import dev.skillbill.desktop.core.domain.model.SavedChatProjection
import dev.skillbill.desktop.core.domain.model.ChatPermissionMode
import dev.skillbill.desktop.core.domain.model.ChatSuggestedAction
import dev.skillbill.desktop.core.domain.model.NewTaskActionKind
import dev.skillbill.desktop.core.domain.model.OperationCardReference
import dev.skillbill.desktop.core.domain.model.OperationCardState
import dev.skillbill.desktop.core.domain.model.VerifyQaChoice
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.jsonObject
import me.tatarka.inject.annotations.Inject
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.util.logging.Level
import java.util.logging.Logger
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant
import dev.skillbill.desktop.core.domain.model.VerifyReviewModeChoice
import dev.skillbill.desktop.core.domain.model.VerifyStartChoices
import skillbill.install.model.SupportedAgent

/** Relative to the user's home, beside the settings file. */
internal const val CHAT_STORE_PATH = ".skill-bill/desktop/chats"

private const val CHATS_PER_EXECUTION = 20
private val ORPHAN_AGE = 7.days
private val safeSessionId = Regex("[A-Za-z0-9._-]+")
private val chatStoreLogger = Logger.getLogger("dev.skillbill.desktop.chat.store")

internal data class OpenCodeSessionBinding(
  val profileIdentity: String,
  val configIdentity: String,
  val dataRoot: String,
  val workingDirectory: String,
)

internal sealed interface OpenCodeSavedState {
  data object Missing : OpenCodeSavedState
  data class Readable(val agentId: String?, val handle: String?, val binding: OpenCodeSessionBinding?) : OpenCodeSavedState
  data class Unusable(val path: String) : OpenCodeSavedState
}

internal sealed interface DevinSavedState {
  data object Missing : DevinSavedState
  data class Readable(
    val entry: ChatHistoryEntry?,
    val handle: String?,
    val executionId: String?,
    val agent: ChatAgent?,
    val checkoutRoot: String?,
    val newTask: ChatNewTaskContext?,
  ) : DevinSavedState
  data class Unusable(val path: String) : DevinSavedState
}

private object ChatStoreKeys {
  const val SESSION_ID = "session_id"
  const val EXECUTION_ID = "execution_id"
  const val AGENT_ID = "agent_id"
  const val MODEL = "model"
  const val EFFORT = "effort"
  const val PROFILE = "profile"
  const val UPDATED_AT = "updated_at_ms"
  const val LINES = "lines"
  const val ROLE = "role"
  const val TEXT = "text"
  const val AGENT_SESSION = "agent_session"
  const val CURSOR_PROFILE_IDENTITY = "cursor_profile_identity"
  const val CURSOR_WORKING_DIRECTORY = "cursor_working_directory"
  const val OPENCODE_PROFILE_IDENTITY = "opencode_profile_identity"
  const val OPENCODE_CONFIG_IDENTITY = "opencode_config_identity"
  const val OPENCODE_DATA_ROOT = "opencode_data_root"
  const val OPENCODE_WORKING_DIRECTORY = "opencode_working_directory"
  const val EXCHANGES = "exchanges"
  const val NEW_TASK_ROOT = "new_task_repository_root"
  const val NEW_TASK_CONTEXT_VERSION = "new_task_context_version"
  const val NEW_TASK_WORKING_DIRECTORY = "new_task_working_directory"
  const val NEW_TASK_SELECTED_REPOSITORY_ROOT = "new_task_selected_repository_root"
  const val NEW_TASK_BASE_BRANCH = "new_task_base_branch"
  const val NEW_TASK_SESSION_ESTABLISHED = "new_task_session_established"
  const val NEW_TASK_PENDING_TURN_LINE_INDEX = "new_task_pending_turn_line_index"
  const val PROJECT_ID = "project_id"
  const val WORKTREES = "worktrees"
  const val NEW_TASK_CHANGES_BASELINE = "new_task_changes_baseline"
  const val NEW_TASK_CHANGES_CAPTURE_ATTEMPTED = "new_task_changes_capture_attempted"
  const val NEW_TASK_CHANGES_ATTRIBUTED_PATHS = "new_task_changes_attributed_paths"
  const val HEAD_SHA = "head_sha"
  const val UNTRACKED_PATHS = "untracked_paths"
  const val STASH_SHA = "stash_sha"
  const val TAKEN_AT = "taken_at"
  const val FAILURE = "failure"
  const val ACTIONS = "actions"
  const val SELECTED_ACTION = "selected_action"
  const val LABEL = "label"
  const val INSTRUCTION = "instruction"
  const val MODE = "mode"
  const val START = "start"
  const val TITLE = "title"
  const val TARGET = "target"
  const val BRANCH = "branch"
  const val QA = "qa"
  const val REVIEW_MODE = "review_mode"
  const val APPROVAL = "approval"
  const val OPERATION_CARD = "operation_card"
  const val TOKEN = "token"
  const val LAUNCH_ID = "launch_id"
  const val OPERATION_ID = "operation_id"
  const val STATE = "state"
  const val DRAFT_CHOICES = "draft_choices"
  const val REFUSAL = "refusal"
  const val SUMMARY = "summary"
  const val SUMMARY_SOURCE_UPDATED_AT = "summary_source_updated_at_ms"
  const val EXECUTION_CHECKOUT_ROOT = "execution_checkout_root"
}

/** What one session file holds; the workspace writes the conversation fields and the transports write the resume fields. */
private data class StoredChat(
  val sessionId: String,
  val executionId: String? = null,
  val agent: ChatAgent? = null,
  val updatedAt: Instant? = null,
  val lines: List<ChatHistoryLine> = emptyList(),
  val agentSession: String? = null,
  val cursorProfileIdentity: String? = null,
  val cursorWorkingDirectory: String? = null,
  val openCodeBinding: OpenCodeSessionBinding? = null,
  val invalidOpenCodeBinding: Boolean = false,
  val newTask: ChatNewTaskContext? = null,
  val summary: String? = null,
  val summarySourceUpdatedAt: Instant? = null,
  val executionCheckoutRoot: String? = null,
  val source: JsonObject = JsonObject(emptyMap()),
)

/**
 * Saved chats, one JSON file per session under the user's home, so a chat and its agent session outlive the window. Every
 * read-modify-write holds one lock; callers run it off the UI thread. Invalid files are retained and excluded from updates
 * and orphan sweeping.
 */
@Inject
@SingleIn(AppScope::class)
class ChatStore(host: HostEnvironment, private val diagnostics: Diagnostics, private val clock: Clock) {
  private val directory = File(host.userHome, CHAT_STORE_PATH)
  private val lock = Any()
  private var scanFailure: ChatStoreReadFailure? = null

  internal fun agentSession(sessionId: String): String? = (agentSessionRead(sessionId) as? ChatStoreRead.Value)?.value

  internal fun agentSessionRead(sessionId: String): ChatStoreRead<String?> = read(sessionId).map { it.agentSession }

  internal fun saveAgentSession(sessionId: String, agentSession: String) =
    update(sessionId) { stored, _ -> stored.copy(agentSession = agentSession) }

  internal fun cursorSessionBinding(sessionId: String): CursorSessionBinding? =
    (cursorSessionBindingRead(sessionId) as? ChatStoreRead.Value)?.value

  internal fun cursorSessionBindingRead(sessionId: String): ChatStoreRead<CursorSessionBinding?> = read(sessionId).map { chat ->
    val profile = chat.cursorProfileIdentity
    val workingDirectory = chat.cursorWorkingDirectory
    if (profile == null || workingDirectory == null) null else CursorSessionBinding(profile, workingDirectory)
  }

  internal fun saveCursorSession(sessionId: String, agentSession: String, binding: CursorSessionBinding) =
    update(sessionId) { stored, _ ->
      stored.copy(
        agentSession = agentSession,
        cursorProfileIdentity = binding.profileIdentity,
        cursorWorkingDirectory = binding.workingDirectory,
      )
    }

  internal fun openCodeBinding(sessionId: String): OpenCodeSessionBinding? =
    (read(sessionId) as? ChatStoreRead.Value)?.value?.openCodeBinding

  internal fun openCodeSavedState(sessionId: String): OpenCodeSavedState = synchronized(lock) {
    val file = fileFor(sessionId) ?: return@synchronized OpenCodeSavedState.Missing
    if (!file.isFile) return@synchronized OpenCodeSavedState.Missing
    when (val result = readFile(file)) {
      is ChatStoreRead.Failed, ChatStoreRead.Missing -> OpenCodeSavedState.Unusable(file.absolutePath)
      is ChatStoreRead.Value -> {
        val stored = result.value
        if (stored.invalidOpenCodeBinding || (stored.agentSession != null && stored.agent?.agentId != "opencode")) {
          OpenCodeSavedState.Unusable(file.absolutePath)
        } else {
          OpenCodeSavedState.Readable(stored.agent?.agentId, stored.agentSession, stored.openCodeBinding)
        }
      }
    }
  }

  internal fun devinSavedState(sessionId: String): DevinSavedState = synchronized(lock) {
    val file = fileFor(sessionId) ?: return@synchronized DevinSavedState.Unusable(File(directory, "$sessionId.json").absolutePath)
    when (val result = readFile(file)) {
      ChatStoreRead.Missing -> DevinSavedState.Missing
      is ChatStoreRead.Failed -> DevinSavedState.Unusable(file.absolutePath)
      is ChatStoreRead.Value -> {
        val stored = result.value
        if (stored.needsRecovery() || (stored.agentSession != null && stored.agent?.agentId != "devin")) {
          DevinSavedState.Unusable(file.absolutePath)
        } else DevinSavedState.Readable(
          stored.toEntry(), stored.agentSession, stored.executionId, stored.agent,
          stored.executionCheckoutRoot ?: stored.newTask?.workingDirectory, stored.newTask,
        )
      }
    }
  }

  internal fun saveDevinSession(sessionId: String, handle: String, history: ChatHistoryEntry): Boolean = synchronized(lock) {
    if (handle.isBlank() || history.sessionId != sessionId || history.agent.agentId != "devin" ||
      !history.hasDevinOwnership()
    ) return@synchronized false
    val file = fileFor(sessionId) ?: return@synchronized false
    val current = when (val result = readFile(file)) {
      ChatStoreRead.Missing -> null
      is ChatStoreRead.Failed -> return@synchronized false
      is ChatStoreRead.Value -> result.value.also { stored ->
        val priorEntry = stored.toEntry()
        if (stored.needsRecovery() || (stored.agentSession != null && stored.agentSession != handle) ||
          (stored.agentSession != null && stored.agent?.agentId != "devin") ||
          (stored.agent != null && (stored.agent.agentId != "devin" || stored.agent.profile != history.agent.profile)) ||
          (stored.executionId != null && stored.executionId != history.executionId) ||
          (stored.newTask != null && !stored.newTask.hasSameConversationIdentity(history.newTask)) ||
          ((stored.executionCheckoutRoot ?: stored.newTask?.workingDirectory) != null &&
            !sameDirectory(stored.executionCheckoutRoot ?: stored.newTask?.workingDirectory,
              history.executionCheckoutRoot ?: history.newTask?.workingDirectory)) ||
          (priorEntry != null && !sameDevinIdentity(priorEntry, history))
        ) return@synchronized false
      }
    }
    val stored = current ?: StoredChat(sessionId)
    write(file, stored.copy(
      executionId = history.executionId ?: stored.executionId,
      agent = history.agent,
      updatedAt = history.updatedAt,
      lines = history.lines,
      agentSession = handle,
      newTask = history.newTask ?: stored.newTask,
      summary = history.summary ?: stored.summary,
      executionCheckoutRoot = if (history.newTask != null) null else stored.executionCheckoutRoot ?: history.executionCheckoutRoot,
    ))
  }

  internal fun savedEntry(sessionId: String): ChatHistoryEntry? =
    (savedForRead(sessionId) as? ChatStoreRead.Value)?.value

  internal fun acquisition(sessionId: String): ChatSessionAcquisition = synchronized(lock) {
    val file = fileFor(sessionId) ?: return@synchronized ChatSessionAcquisition.Recovery(null, "The saved chat identifier is invalid. Keep the original record unchanged.")
    when (val result = readFile(file)) {
      ChatStoreRead.Missing -> ChatSessionAcquisition.Missing
      is ChatStoreRead.Failed -> ChatSessionAcquisition.Recovery(null,
        "The saved chat could not be read. Keep the original file at ${file.absolutePath} unchanged and restore it before retrying.")
      is ChatStoreRead.Value -> {
        val stored = result.value
        val entry = stored.toEntry() ?: stored.toDraftEntry()
        if (stored.needsRecovery()) {
          ChatSessionAcquisition.Recovery(entry,
            "The saved provider identity is incomplete. Keep the original file at ${file.absolutePath} unchanged, or start a separate new chat.")
        } else when {
          stored.agent?.agentId == "devin" && !stored.agentSession.isNullOrBlank() ->
            ChatSessionAcquisition.Acquired(entry ?: return@synchronized ChatSessionAcquisition.Recovery(null,
              "The Devin handle has no projectable conversation identity. Keep the original file at ${file.absolutePath} unchanged."))
          stored.agent?.agentId == "opencode" && !stored.agentSession.isNullOrBlank() ->
            ChatSessionAcquisition.Acquired(entry ?: return@synchronized ChatSessionAcquisition.Recovery(null,
              "The OpenCode handle has no projectable conversation identity. Keep the original file at ${file.absolutePath} unchanged."))
          stored.newTask != null && !stored.newTask.sessionEstablished ->
            ChatSessionAcquisition.Draft(entry ?: return@synchronized ChatSessionAcquisition.Recovery(null,
              "The saved draft has incomplete identity. Keep the original file at ${file.absolutePath} unchanged."))
          stored.agent?.agentId in setOf("devin", "opencode") ->
            ChatSessionAcquisition.Recovery(entry,
              "The saved ${stored.agent?.agentId} conversation has no safe native handle. Keep the original file at ${file.absolutePath} unchanged, or start a separate new chat.")
          entry != null -> ChatSessionAcquisition.Draft(entry)
          else -> ChatSessionAcquisition.Recovery(null,
            "The saved conversation is missing resume metadata. Keep the original file at ${file.absolutePath} unchanged.")
        }
      }
    }
  }

  internal fun saveOpenCodeSession(
    sessionId: String,
    agentSession: String,
    binding: OpenCodeSessionBinding,
    history: ChatHistoryEntry? = null,
  ): Boolean = synchronized(lock) {
    val current = (readFile(fileFor(sessionId) ?: return@synchronized false) as? ChatStoreRead.Value)?.value
    if (current?.needsRecovery() == true || (current?.agentSession != null &&
      (current.agentSession != agentSession || current.openCodeBinding != binding || current.agent?.agentId != "opencode"))) return@synchronized false
    update(sessionId) { stored, _ ->
      stored.copy(
        executionId = history?.executionId ?: stored.executionId,
        agent = history?.agent ?: stored.agent?.copy(agentId = "opencode") ?: ChatAgent("opencode", null, null),
        updatedAt = history?.updatedAt ?: stored.updatedAt,
        lines = history?.lines ?: stored.lines,
        agentSession = agentSession,
        openCodeBinding = binding,
        newTask = history?.newTask ?: stored.newTask,
        summary = history?.summary ?: stored.summary,
      )
    }
  }

  internal fun replayHistoryRead(sessionId: String): ChatStoreRead<List<ChatHistoryLine>> = read(sessionId).map { chat ->
    chat.lines
  }.orElse(emptyList())

  internal fun newTaskHistoryRead(sessionId: String): ChatStoreRead<List<ChatHistoryLine>> = read(sessionId).map { chat ->
    val context = chat.newTask
    if (context == null) emptyList() else chat.lines.take(context.pendingTurnLineIndex ?: chat.lines.size)
  }.orElse(emptyList())

  internal fun saveChat(entry: ChatHistoryEntry): Boolean = synchronized(lock) {
    val existing = (read(entry.sessionId) as? ChatStoreRead.Value)?.value
    if (existing != null && !existing.needsRecovery() && !existing.agentSession.isNullOrBlank() &&
      existing.agent?.agentId in setOf("devin", "opencode")) {
      val original = existing.executionCheckoutRoot ?: existing.newTask?.workingDirectory ?: existing.openCodeBinding?.workingDirectory
      val proposed = entry.executionCheckoutRoot ?: entry.newTask?.workingDirectory
      if (original == null || proposed == null || !sameDirectory(original, proposed)) return false
    }
    val saved = update(entry.sessionId) { stored, exists ->
      stored.copy(
        executionId = entry.executionId,
        agent = entry.agent,
        updatedAt = if (exists && stored.lines == entry.lines) stored.updatedAt else entry.updatedAt,
        lines = entry.lines,
        newTask = entry.newTask,
        executionCheckoutRoot = stored.executionCheckoutRoot ?: entry.executionCheckoutRoot,
      )
    }
    if (!saved) return@synchronized false
    val executionId = entry.executionId ?: return@synchronized saved
    chatsOf(executionId).filterNot(StoredChat::needsRecovery).drop(CHATS_PER_EXECUTION).forEach { fileFor(it.sessionId)?.delete() }
    saved
  }

  internal fun savedChats(executionId: String): SavedChatProjection = savedProjection(executionId = executionId)

  internal fun savedChatsRead(executionId: String): ChatHistoryRead = synchronized(lock) {
    sweepOrphans()
    val reads = allFiles().map(::readFile)
    ChatHistoryRead(
      reads.mapNotNull { (it as? ChatStoreRead.Value)?.value }
        .filter { it.executionId == executionId }.sortedByDescending { it.updatedAt ?: Instant.DISTANT_PAST }
        .mapNotNull(StoredChat::toEntry),
      reads.mapNotNull { (it as? ChatStoreRead.Failed)?.failure } + listOfNotNull(scanFailure),
    )
  }

  internal fun newTaskDrafts(limit: Int? = null): SavedChatProjection = savedProjection(limit = limit, draftsOnly = true)

  internal fun newTaskDraftsRead(limit: Int? = null): ChatHistoryRead = synchronized(lock) {
    val reads = allFiles().map(::readFile)
    val drafts = reads.mapNotNull { (it as? ChatStoreRead.Value)?.value }.filter { it.newTask != null }
      .sortedByDescending { it.updatedAt ?: Instant.DISTANT_PAST }.mapNotNull(StoredChat::toEntry)
    ChatHistoryRead(if (limit == null) drafts else drafts.take(limit), reads.mapNotNull { (it as? ChatStoreRead.Failed)?.failure } + listOfNotNull(scanFailure))
  }

  private fun savedProjection(executionId: String? = null, limit: Int? = null, draftsOnly: Boolean = false): SavedChatProjection = synchronized(lock) {
    if (!draftsOnly) sweepOrphans()
    val reads = allFiles().map { file -> file to readFile(file) }
    val entries = reads.mapNotNull { (_, result) ->
      val stored = (result as? ChatStoreRead.Value)?.value ?: return@mapNotNull null
      if (stored.needsRecovery() || (executionId != null && stored.executionId != executionId) || (draftsOnly && stored.newTask == null)) {
        return@mapNotNull null
      }
      stored.toEntry()
    }.sortedByDescending { it.updatedAt }
    val recoveries = reads.mapNotNull { (file, result) ->
      val stored = (result as? ChatStoreRead.Value)?.value
      if (stored?.isResumeOnly() == true) return@mapNotNull null
      val relevant = when {
        result is ChatStoreRead.Failed -> true
        stored == null -> false
        executionId != null && stored.executionId != null -> stored.executionId == executionId
        executionId != null && stored.newTask != null -> false
        draftsOnly -> stored.executionId == null && (stored.newTask != null || stored.needsRecovery() || stored.toEntry() == null)
        else -> stored.needsRecovery() || stored.toEntry() == null
      }
      if (!relevant || (stored != null && !stored.needsRecovery() && stored.toEntry() != null)) return@mapNotNull null
      stored?.let { recoveryFor(file, it) } ?: recoveryFor(file, null)
    }
    SavedChatProjection(if (limit == null) entries else entries.take(limit), recoveries)
  }

  internal fun delete(sessionId: String) {
    synchronized(lock) { fileFor(sessionId)?.delete() }
  }

  /** The app's save of the chat whose own session or agent session is [sessionId], when it holds an operator message. */
  internal fun savedFor(sessionId: String): ChatHistoryEntry? = (savedForRead(sessionId) as? ChatStoreRead.Value)?.value

  internal fun savedForRead(sessionId: String): ChatStoreRead<ChatHistoryEntry?> = synchronized(lock) {
    val directFile = fileFor(sessionId)
    if (directFile == null) return@synchronized ChatStoreRead.Failed(failureFor(sessionId, "invalid session identifier"))
    when (val direct = readFile(directFile)) {
      is ChatStoreRead.Failed -> return@synchronized direct
      is ChatStoreRead.Value -> {
        val entry = direct.value.toEntry()
        if (entry != null) return@synchronized ChatStoreRead.Value(entry)
      }
      ChatStoreRead.Missing -> Unit
    }
    val reads = allFiles().map(::readFile)
    val match = reads.filterIsInstance<ChatStoreRead.Value<StoredChat>>().firstOrNull { it.value.agentSession == sessionId }
    when {
      match != null -> ChatStoreRead.Value(match.value.toEntry())
      reads.any { it is ChatStoreRead.Failed } -> reads.first { it is ChatStoreRead.Failed } as ChatStoreRead.Failed
      scanFailure != null -> ChatStoreRead.Failed(scanFailure!!)
      else -> ChatStoreRead.Value(null)
    }
  }

  /** Writes [summary] onto an existing record only, keeping `updated_at`, so a chat deleted during the call stays deleted. */
  internal fun saveSummary(sessionId: String, summary: String, sourceUpdatedAt: Instant): Boolean = synchronized(lock) {
    val file = fileFor(sessionId) ?: return@synchronized false
    val stored = (readFile(file) as? ChatStoreRead.Value)?.value ?: return@synchronized false
    if (stored.needsRecovery()) return@synchronized false
    write(file, stored.copy(summary = summary, summarySourceUpdatedAt = sourceUpdatedAt))
  }

  /** The chat of [sessionId], found as [savedFor] finds it, when its summary is missing or older than its last save. */
  internal fun staleForSummary(sessionId: String): ChatHistoryEntry? =
    (staleForSummaryRead(sessionId) as? ChatStoreRead.Value)?.value

  internal fun staleForSummaryRead(sessionId: String): ChatStoreRead<ChatHistoryEntry?> = synchronized(lock) {
    val direct = fileFor(sessionId)?.let(::readFile) ?: ChatStoreRead.Failed(failureFor(sessionId, "invalid session identifier"))
    val stored = when (direct) {
      is ChatStoreRead.Failed -> return@synchronized direct
      ChatStoreRead.Missing -> {
        val reads = allFiles().map(::readFile)
        when {
          reads.any { it is ChatStoreRead.Failed } -> return@synchronized reads.first { it is ChatStoreRead.Failed } as ChatStoreRead.Failed
          scanFailure != null -> return@synchronized ChatStoreRead.Failed(scanFailure!!)
          else -> reads.filterIsInstance<ChatStoreRead.Value<StoredChat>>().firstOrNull { it.value.agentSession == sessionId }?.value
            ?: return@synchronized ChatStoreRead.Value(null)
        }
      }
      is ChatStoreRead.Value -> direct.value
    }
    ChatStoreRead.Value(stored.takeIf { it.needsSummary() }?.toEntry())
  }

  /** Every chat that needs a summary, newest first, at most [limit]. */
  internal fun staleSummaries(limit: Int): List<ChatHistoryEntry> = staleSummariesRead(limit).entries

  internal fun staleSummariesRead(limit: Int): ChatHistoryRead = synchronized(lock) {
    val reads = allFiles().map(::readFile)
    ChatHistoryRead(
      reads.mapNotNull { (it as? ChatStoreRead.Value)?.value }.filter { it.needsSummary() }
        .sortedByDescending { it.updatedAt ?: Instant.DISTANT_PAST }.take(limit).mapNotNull(StoredChat::toEntry),
      reads.mapNotNull { (it as? ChatStoreRead.Failed)?.failure } + listOfNotNull(scanFailure),
    )
  }

  private fun chatsOf(executionId: String): List<StoredChat> =
    allFiles().map(::readFile).mapNotNull { (it as? ChatStoreRead.Value)?.value }
      .filter { it.executionId == executionId }.sortedByDescending { it.updatedAt ?: Instant.DISTANT_PAST }

  private fun sweepOrphans() {
    val cutoff = (clock.now() - ORPHAN_AGE).toEpochMilliseconds()
    allFiles().filter { file ->
      file.lastModified() < cutoff && (readFile(file) as? ChatStoreRead.Value)?.value?.let {
        !it.needsRecovery() && !it.isResumeOnly() && it.toEntry() != null && it.executionId == null && it.newTask == null
      } == true
    }.forEach { it.delete() }
  }

  private fun allFiles(): List<File> {
    val files = try {
      directory.listFiles { file -> file.name.endsWith(".json") }
    } catch (_: SecurityException) {
      null
    }
    if (files != null) {
      scanFailure = null
      return files.toList()
    }
    val directoryMissing = try {
      Files.notExists(directory.toPath())
    } catch (_: SecurityException) {
      false
    }
    if (directoryMissing) {
      scanFailure = null
      return emptyList()
    }
    diagnostics.record("chat.store_scan_failed")
    scanFailure = ChatStoreReadFailure(
      "chat-store", directory.absolutePath, "the chat store directory could not be listed",
      "Keep the directory contents unchanged and restore read access before retrying.",
    )
    chatStoreLogger.log(Level.WARNING, "chat.store_scan_failed ${directory.absolutePath.escapeLog()}")
    return emptyList()
  }

  private fun recoveryFor(file: File, stored: StoredChat?): ChatRecoveryEntry = ChatRecoveryEntry(
    sessionId = file.name.removeSuffix(".json"),
    originalDataPath = file.absolutePath,
    reason = when {
      stored == null -> ChatRecoveryReason.UnreadableFile
      stored.invalidOpenCodeBinding -> ChatRecoveryReason.InvalidOpenCodeBinding
      stored.needsRecovery() && stored.agent?.agentId == "opencode" -> ChatRecoveryReason.OpenCodeHandleUnbound
      else -> ChatRecoveryReason.MissingResumeMetadata
    },
    executionId = stored?.executionId,
    repositoryRoot = stored?.newTask?.repositoryRoot,
    title = stored?.lines?.firstOrNull { it.role == ChatHistoryRole.Operator }?.text,
    updatedAt = stored?.updatedAt,
    agentId = stored?.agent?.agentId,
    lines = stored?.lines.orEmpty(),
  )

  private fun read(sessionId: String): ChatStoreRead<StoredChat> = synchronized(lock) {
    val file = fileFor(sessionId) ?: return@synchronized ChatStoreRead.Failed(failureFor(sessionId, "invalid session identifier"))
    readFile(file)
  }

  private fun update(sessionId: String, change: (StoredChat, Boolean) -> StoredChat): Boolean = synchronized(lock) {
    val file = fileFor(sessionId) ?: return@synchronized false
    val result = readFile(file)
    val existing = when (result) {
      is ChatStoreRead.Failed -> return@synchronized false
      ChatStoreRead.Missing -> null
      is ChatStoreRead.Value -> result.value.also { if (it.needsRecovery()) return@synchronized false }
    }
    write(file, change(existing ?: StoredChat(sessionId), existing != null))
  }

  private fun write(file: File, stored: StoredChat): Boolean {
    return replaceJson(file, encode(stored), "chat.store_write_failed") == null
  }

  private fun replaceJson(file: File, value: JsonObject, diagnostic: String): String? {
    val temporary = File(directory, "${file.name}.tmp")
    return try {
      directory.mkdirs()
      temporary.writeText(value.toString(), Charsets.UTF_8)
      Files.move(temporary.toPath(), file.toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
      null
    } catch (failure: IOException) {
      diagnostics.record(diagnostic)
      val reason = failure.message ?: failure::class.simpleName ?: "write failed"
      chatStoreLogger.log(Level.WARNING, "$diagnostic ${reason.escapeLog()} ${file.absolutePath.escapeLog()}; temporary ${temporary.absolutePath.escapeLog()}")
      reason
    } catch (failure: SecurityException) {
      diagnostics.record(diagnostic)
      val reason = failure.message ?: failure::class.simpleName ?: "write denied"
      chatStoreLogger.log(Level.WARNING, "$diagnostic ${reason.escapeLog()} ${file.absolutePath.escapeLog()}; temporary ${temporary.absolutePath.escapeLog()}")
      reason
    }
  }

  private fun fileFor(sessionId: String): File? {
    if (!safeSessionId.matches(sessionId)) {
      diagnostics.record("chat.store_session_id_rejected")
      return null
    }
    return File(directory, "$sessionId.json")
  }

  private fun readFile(file: File): ChatStoreRead<StoredChat> {
    try {
      val path = file.toPath()
      if (Files.notExists(path)) return ChatStoreRead.Missing
      if (!Files.exists(path)) return failed(file, "the source path exists but cannot be read", "chat.store_unreadable")
      if (!Files.isRegularFile(path)) return failed(file, "the source path is not a regular chat file", "chat.store_unreadable")
    } catch (failure: SecurityException) {
      return failed(file, "source path access failed: ${failure.message ?: failure::class.simpleName}", "chat.store_unreadable")
    }
    val source = try {
      Json.parseToJsonElement(file.readText(Charsets.UTF_8)).jsonObject
    } catch (failure: Exception) {
      return failed(file, "unreadable JSON: ${failure::class.simpleName}", "chat.store_unreadable")
    }
    var root = source
    val version = root[ChatStoreKeys.NEW_TASK_CONTEXT_VERSION]
    val workingDirectory = root[ChatStoreKeys.NEW_TASK_WORKING_DIRECTORY]
    val selectedRoot = root[ChatStoreKeys.NEW_TASK_SELECTED_REPOSITORY_ROOT]
    val legacyRoot = root[ChatStoreKeys.NEW_TASK_ROOT]
    val otherDraftMarker = listOf(
      ChatStoreKeys.NEW_TASK_BASE_BRANCH,
      ChatStoreKeys.NEW_TASK_SESSION_ESTABLISHED,
      ChatStoreKeys.NEW_TASK_PENDING_TURN_LINE_INDEX,
      ChatStoreKeys.NEW_TASK_CHANGES_BASELINE,
      ChatStoreKeys.NEW_TASK_CHANGES_CAPTURE_ATTEMPTED,
      ChatStoreKeys.NEW_TASK_CHANGES_ATTRIBUTED_PATHS,
    ).any(root::containsKey)
    if (version == null && workingDirectory == null && selectedRoot == null && legacyRoot != null) {
      val oldRoot = (legacyRoot as? JsonPrimitive)?.takeIf { it.isString }?.content
      if (oldRoot.isNullOrBlank()) return failed(file, "legacy repository root is missing or blank", "chat.store_legacy_context_invalid")
      root = JsonObject(root + mapOf(
        ChatStoreKeys.NEW_TASK_CONTEXT_VERSION to JsonPrimitive(1),
        ChatStoreKeys.NEW_TASK_WORKING_DIRECTORY to JsonPrimitive(oldRoot),
        ChatStoreKeys.NEW_TASK_SELECTED_REPOSITORY_ROOT to JsonPrimitive(oldRoot),
      ))
      replaceJson(file, root, "chat.store_migration_failed")?.let { reason ->
        return failed(file, "legacy context migration failed: $reason; inspect the source and sibling .tmp file", "chat.store_migration_failed")
      }
    } else if (version != null || workingDirectory != null || selectedRoot != null) {
      val parsedVersion = (version as? JsonPrimitive)?.intOrNull
      val directoryValue = (workingDirectory as? JsonPrimitive)?.takeIf { it.isString }?.content
      val selectionValid = selectedRoot == JsonNull || (selectedRoot as? JsonPrimitive)?.let { it.isString && it.content.isNotBlank() } == true
      if (parsedVersion != 1 || directoryValue.isNullOrBlank() || selectedRoot == null || !selectionValid) {
        return failed(file, "unsupported version or malformed explicit new-task context", "chat.store_context_invalid")
      }
    } else if (otherDraftMarker) {
      return failed(file, "legacy new-task markers are incomplete", "chat.store_legacy_context_invalid")
    }
    return try {
      ChatStoreRead.Value(decode(root, file.name.removeSuffix(".json"), diagnostics))
    } catch (failure: Exception) {
      failed(file, "record decoding failed: ${failure::class.simpleName}", "chat.store_decode_failed")
    }
  }

  private fun failed(file: File, reason: String, diagnostic: String): ChatStoreRead.Failed {
    diagnostics.record(diagnostic)
    val failure = ChatStoreReadFailure(file.name.removeSuffix(".json"), file.absolutePath, reason, "Keep this file unchanged and recover it from the source path or its sibling .tmp file.")
    chatStoreLogger.log(Level.WARNING, "$diagnostic ${reason.escapeLog()} ${file.absolutePath.escapeLog()}")
    return ChatStoreRead.Failed(failure)
  }

  private fun failureFor(sessionId: String, reason: String) = ChatStoreReadFailure(sessionId, File(directory, "$sessionId.json").absolutePath, reason, "Check the saved chat file and restore it before retrying.")
}

private fun <T, R> ChatStoreRead<T>.map(transform: (T) -> R): ChatStoreRead<R> = when (this) {
  ChatStoreRead.Missing -> ChatStoreRead.Missing
  is ChatStoreRead.Failed -> this
  is ChatStoreRead.Value -> ChatStoreRead.Value(transform(value))
}

private fun <T> ChatStoreRead<T>.orElse(value: T): ChatStoreRead<T> = when (this) {
  ChatStoreRead.Missing -> ChatStoreRead.Value(value)
  is ChatStoreRead.Failed -> this
  is ChatStoreRead.Value -> this
}

private fun String.escapeLog(): String = replace("\r", "\\r").replace("\n", "\\n")

private fun StoredChat.toEntry(): ChatHistoryEntry? {
  val operator = lines.firstOrNull { it.role == ChatHistoryRole.Operator }
  val boundExecution = executionId != null && newTask == null && agent?.agentId == "opencode" &&
    !agentSession.isNullOrBlank() && openCodeBinding != null
  val boundDevinExecution = executionId != null && newTask == null && agent?.agentId == "devin" &&
    !agentSession.isNullOrBlank() && !executionCheckoutRoot.isNullOrBlank()
  val boundNativeDraft = newTask != null && agent?.agentId in setOf("devin", "opencode") && !agentSession.isNullOrBlank()
  if (operator == null && !boundExecution && !boundDevinExecution && !boundNativeDraft) return null
  val title = operator?.text ?: lines.firstOrNull { it.role == ChatHistoryRole.Agent && it.text.isNotBlank() }?.text
    ?: if (agent?.agentId == "devin") "Devin conversation" else if (agent?.agentId == "opencode") "OpenCode conversation" else "Saved conversation"
  return ChatHistoryEntry(
    sessionId, executionId, agent ?: return null, updatedAt ?: Instant.DISTANT_PAST, title, lines, newTask, summary,
    executionCheckoutRoot = executionCheckoutRoot ?: cursorWorkingDirectory ?: openCodeBinding?.workingDirectory,
  )
}

private fun StoredChat.toDraftEntry(): ChatHistoryEntry? {
  if (newTask == null) return null
  val agent = agent ?: return null
  return ChatHistoryEntry(
    sessionId, executionId, agent, updatedAt ?: Instant.DISTANT_PAST,
    lines.firstOrNull { it.role == ChatHistoryRole.Operator }?.text ?: "New task draft",
    lines, newTask, summary, executionCheckoutRoot = executionCheckoutRoot ?: newTask.workingDirectory,
  )
}

private fun sameDevinIdentity(left: ChatHistoryEntry, right: ChatHistoryEntry): Boolean =
  left.sessionId == right.sessionId && left.executionId == right.executionId &&
    left.agent.agentId == right.agent.agentId && left.agent.effort == right.agent.effort && left.agent.profile == right.agent.profile &&
    left.newTask.hasSameConversationIdentity(right.newTask) &&
    sameDirectory(left.executionCheckoutRoot ?: left.newTask?.workingDirectory,
      right.executionCheckoutRoot ?: right.newTask?.workingDirectory)

private fun sameDirectory(left: String?, right: String?): Boolean {
  if (left.isNullOrBlank() || right.isNullOrBlank()) return false
  return try {
    File(left).toPath().toRealPath() == File(right).toPath().toRealPath()
  } catch (_: IOException) {
    false
  } catch (_: SecurityException) {
    false
  }
}

/** Staleness rule: a listable chat whose summary source differs from its `updated_at`. */
private fun StoredChat.needsSummary(): Boolean =
  toEntry() != null && summarySourceUpdatedAt?.toEpochMilliseconds() != updatedAt?.toEpochMilliseconds()

private fun StoredChat.needsRecovery(): Boolean = invalidOpenCodeBinding ||
  (agent?.agentId == "opencode" && ((agentSession != null && openCodeBinding == null) || (agentSession.isNullOrBlank() && openCodeBinding != null) ||
    (agentSession.isNullOrBlank() && newTask?.sessionEstablished == true))) ||
  (agent?.agentId == "devin" && when {
    agentSession.isNullOrBlank() -> newTask?.sessionEstablished ?: lines.isNotEmpty()
    executionId != null -> executionCheckoutRoot.isNullOrBlank() || newTask != null
    newTask != null -> newTask.workingDirectory.isBlank() || executionCheckoutRoot != null
    else -> true
  })

private fun ChatHistoryEntry.hasDevinOwnership(): Boolean = when {
  executionId != null && !executionCheckoutRoot.isNullOrBlank() && newTask == null -> true
  executionId == null && newTask?.let { context ->
    context.workingDirectory.isNotBlank() && context.selectedRepositoryRoot?.isNotBlank() != false
  } == true -> true
  else -> false
}

private fun StoredChat.isResumeOnly(): Boolean = executionId == null && newTask == null && lines.isEmpty() &&
  !agentSession.isNullOrBlank() && summary == null && !needsRecovery()

private val REWRITTEN_CHAT_KEYS = setOf(
    ChatStoreKeys.EXECUTION_ID,
    ChatStoreKeys.AGENT_ID,
    ChatStoreKeys.MODEL,
    ChatStoreKeys.EFFORT,
    ChatStoreKeys.PROFILE,
    ChatStoreKeys.UPDATED_AT,
    ChatStoreKeys.AGENT_SESSION,
    ChatStoreKeys.CURSOR_PROFILE_IDENTITY,
    ChatStoreKeys.CURSOR_WORKING_DIRECTORY,
    ChatStoreKeys.OPENCODE_PROFILE_IDENTITY,
    ChatStoreKeys.OPENCODE_CONFIG_IDENTITY,
    ChatStoreKeys.OPENCODE_DATA_ROOT,
    ChatStoreKeys.OPENCODE_WORKING_DIRECTORY,
    ChatStoreKeys.EXCHANGES,
    ChatStoreKeys.SUMMARY,
    ChatStoreKeys.SUMMARY_SOURCE_UPDATED_AT,
    ChatStoreKeys.EXECUTION_CHECKOUT_ROOT,
    ChatStoreKeys.NEW_TASK_ROOT,
    ChatStoreKeys.NEW_TASK_CONTEXT_VERSION,
    ChatStoreKeys.NEW_TASK_WORKING_DIRECTORY,
    ChatStoreKeys.NEW_TASK_SELECTED_REPOSITORY_ROOT,
    ChatStoreKeys.NEW_TASK_BASE_BRANCH,
    ChatStoreKeys.NEW_TASK_SESSION_ESTABLISHED,
    ChatStoreKeys.NEW_TASK_PENDING_TURN_LINE_INDEX,
    ChatStoreKeys.NEW_TASK_CHANGES_BASELINE,
    ChatStoreKeys.NEW_TASK_CHANGES_CAPTURE_ATTEMPTED,
    ChatStoreKeys.NEW_TASK_CHANGES_ATTRIBUTED_PATHS,
)

private fun encode(chat: StoredChat): JsonObject = buildJsonObject {
  chat.source.forEach { (key, value) -> if (key !in REWRITTEN_CHAT_KEYS) put(key, value) }
  put(ChatStoreKeys.SESSION_ID, chat.sessionId)
  chat.executionId?.let { put(ChatStoreKeys.EXECUTION_ID, it) }
  chat.agent?.let { agent ->
    put(ChatStoreKeys.AGENT_ID, agent.agentId)
    agent.model?.let { put(ChatStoreKeys.MODEL, it) }
    agent.effort?.let { put(ChatStoreKeys.EFFORT, it) }
    agent.profile?.let { put(ChatStoreKeys.PROFILE, it) }
  }
  chat.updatedAt?.let { put(ChatStoreKeys.UPDATED_AT, it.toEpochMilliseconds()) }
  put(
    ChatStoreKeys.LINES,
    buildJsonArray {
      chat.lines.forEach { line ->
        add(buildJsonObject {
          put(ChatStoreKeys.ROLE, line.role.name)
          put(ChatStoreKeys.TEXT, line.text)
          line.failure?.let { put(ChatStoreKeys.FAILURE, it.name) }
          if (line.actions.isNotEmpty()) put(ChatStoreKeys.ACTIONS, buildJsonArray { line.actions.forEach { add(it.toJson()) } })
          line.selectedAction?.let { put(ChatStoreKeys.SELECTED_ACTION, it.toJson()) }
          line.approval?.let { put(ChatStoreKeys.APPROVAL, it.toJson()) }
          line.operationCard?.let { put(ChatStoreKeys.OPERATION_CARD, it.toJson()) }
        })
      }
    },
  )
  chat.agentSession?.let { put(ChatStoreKeys.AGENT_SESSION, it) }
  chat.cursorProfileIdentity?.let { put(ChatStoreKeys.CURSOR_PROFILE_IDENTITY, it) }
  chat.cursorWorkingDirectory?.let { put(ChatStoreKeys.CURSOR_WORKING_DIRECTORY, it) }
  chat.openCodeBinding?.let { binding ->
    put(ChatStoreKeys.OPENCODE_PROFILE_IDENTITY, binding.profileIdentity)
    put(ChatStoreKeys.OPENCODE_CONFIG_IDENTITY, binding.configIdentity)
    put(ChatStoreKeys.OPENCODE_DATA_ROOT, binding.dataRoot)
    put(ChatStoreKeys.OPENCODE_WORKING_DIRECTORY, binding.workingDirectory)
  }
  chat.newTask?.let { newTask ->
    put(ChatStoreKeys.NEW_TASK_CONTEXT_VERSION, 1)
    put(ChatStoreKeys.NEW_TASK_WORKING_DIRECTORY, newTask.workingDirectory)
    if (newTask.selectedRepositoryRoot == null) {
      put(ChatStoreKeys.NEW_TASK_SELECTED_REPOSITORY_ROOT, JsonNull)
    } else {
      put(ChatStoreKeys.NEW_TASK_SELECTED_REPOSITORY_ROOT, newTask.selectedRepositoryRoot)
      put(ChatStoreKeys.NEW_TASK_ROOT, newTask.selectedRepositoryRoot)
    }
    newTask.baseBranch?.let { put(ChatStoreKeys.NEW_TASK_BASE_BRANCH, it) }
    put(ChatStoreKeys.NEW_TASK_SESSION_ESTABLISHED, newTask.sessionEstablished)
    put(ChatStoreKeys.NEW_TASK_CHANGES_CAPTURE_ATTEMPTED, newTask.changesCaptureAttempted)
    newTask.pendingTurnLineIndex?.let { put(ChatStoreKeys.NEW_TASK_PENDING_TURN_LINE_INDEX, it) }
    newTask.changesBaseline?.let { baseline ->
      put(ChatStoreKeys.NEW_TASK_CHANGES_BASELINE, baseline.toJsonBaseline())
    }
    newTask.changesAttributedPaths?.let { paths ->
      put(ChatStoreKeys.NEW_TASK_CHANGES_ATTRIBUTED_PATHS, buildJsonArray { paths.sorted().forEach { add(it) } })
    }
  }
  chat.summary?.let { put(ChatStoreKeys.SUMMARY, it) }
  chat.summarySourceUpdatedAt?.let { put(ChatStoreKeys.SUMMARY_SOURCE_UPDATED_AT, it.toEpochMilliseconds()) }
  chat.executionCheckoutRoot?.let { put(ChatStoreKeys.EXECUTION_CHECKOUT_ROOT, it) }
}

private fun decode(root: JsonObject, fallbackSessionId: String, diagnostics: Diagnostics): StoredChat {
  val agentId = root.string(ChatStoreKeys.AGENT_ID)?.let { id ->
    if (id in SupportedAgent.supportedIds || id == UNAVAILABLE_AGENT_ID) id else {
      diagnostics.record("chat.store_unsupported_agent_normalized")
      UNAVAILABLE_AGENT_ID
    }
  }
  val openCodeFields = listOf(
    ChatStoreKeys.OPENCODE_PROFILE_IDENTITY,
    ChatStoreKeys.OPENCODE_CONFIG_IDENTITY,
    ChatStoreKeys.OPENCODE_DATA_ROOT,
    ChatStoreKeys.OPENCODE_WORKING_DIRECTORY,
  )
  val hasOpenCodeBinding = openCodeFields.any(root::containsKey)
  val openCodeValues = openCodeFields.map { root.string(it)?.takeIf(String::isNotBlank) }
  if (hasOpenCodeBinding && openCodeValues.any { it == null }) diagnostics.record("chat.opencode_binding_unreadable")
  return StoredChat(
    sessionId = root.string(ChatStoreKeys.SESSION_ID) ?: fallbackSessionId,
    executionId = root.string(ChatStoreKeys.EXECUTION_ID),
    agent = agentId?.let { ChatAgent(it, root.string(ChatStoreKeys.MODEL), root.string(ChatStoreKeys.EFFORT), root.string(ChatStoreKeys.PROFILE)) },
    updatedAt = (root[ChatStoreKeys.UPDATED_AT] as? JsonPrimitive)?.longOrNull?.let(Instant::fromEpochMilliseconds),
    lines = root.objects(ChatStoreKeys.LINES).mapNotNull { line ->
      val role = ChatHistoryRole.entries.firstOrNull { it.name == line.string(ChatStoreKeys.ROLE) } ?: return@mapNotNull null
      line.string(ChatStoreKeys.TEXT)?.let { text ->
        ChatHistoryLine(
          role,
          text,
          ChatFailure.entries.firstOrNull { it.name == line.string(ChatStoreKeys.FAILURE) },
          line.actions(ChatStoreKeys.ACTIONS, diagnostics),
          line.selectedAction(diagnostics),
          (line[ChatStoreKeys.APPROVAL] as? JsonObject)?.toApprovalCard(diagnostics),
          (line[ChatStoreKeys.OPERATION_CARD] as? JsonObject)?.toOperationCardReference(),
        )
      }
    },
    agentSession = root.string(ChatStoreKeys.AGENT_SESSION),
    cursorProfileIdentity = root.string(ChatStoreKeys.CURSOR_PROFILE_IDENTITY),
    cursorWorkingDirectory = root.string(ChatStoreKeys.CURSOR_WORKING_DIRECTORY),
    openCodeBinding = if (hasOpenCodeBinding && openCodeValues.all { it != null }) {
      OpenCodeSessionBinding(requireNotNull(openCodeValues[0]), requireNotNull(openCodeValues[1]), requireNotNull(openCodeValues[2]), requireNotNull(openCodeValues[3]))
    } else null,
    invalidOpenCodeBinding = hasOpenCodeBinding && openCodeValues.any { it == null },
    newTask = root.string(ChatStoreKeys.NEW_TASK_WORKING_DIRECTORY)?.let { workingDirectory ->
      val selectedRoot = when (root[ChatStoreKeys.NEW_TASK_SELECTED_REPOSITORY_ROOT]) {
        JsonNull -> null
        else -> root.string(ChatStoreKeys.NEW_TASK_SELECTED_REPOSITORY_ROOT)
      }
      ChatNewTaskContext(
        workingDirectory,
        root.string(ChatStoreKeys.NEW_TASK_BASE_BRANCH),
        (root[ChatStoreKeys.NEW_TASK_SESSION_ESTABLISHED] as? JsonPrimitive)?.content?.toBooleanStrictOrNull() ?: true,
        (root[ChatStoreKeys.NEW_TASK_PENDING_TURN_LINE_INDEX] as? JsonPrimitive)?.intOrNull,
        decodeChangesBaseline(root[ChatStoreKeys.NEW_TASK_CHANGES_BASELINE], diagnostics),
        (root[ChatStoreKeys.NEW_TASK_CHANGES_CAPTURE_ATTEMPTED] as? JsonPrimitive)?.content?.toBooleanStrictOrNull()
          ?: ((root[ChatStoreKeys.NEW_TASK_SESSION_ESTABLISHED] as? JsonPrimitive)?.content?.toBooleanStrictOrNull() != false
            || root[ChatStoreKeys.NEW_TASK_CHANGES_BASELINE] != null),
        decodeAttributedPaths(root[ChatStoreKeys.NEW_TASK_CHANGES_ATTRIBUTED_PATHS], diagnostics),
        selectedRoot,
      )
    },
    summary = root.string(ChatStoreKeys.SUMMARY)?.takeIf { it.isNotBlank() },
    summarySourceUpdatedAt = (root[ChatStoreKeys.SUMMARY_SOURCE_UPDATED_AT] as? JsonPrimitive)?.longOrNull?.let(Instant::fromEpochMilliseconds),
    executionCheckoutRoot = root.string(ChatStoreKeys.EXECUTION_CHECKOUT_ROOT),
    source = root,
  )
}

private fun decodeAttributedPaths(element: JsonElement?, diagnostics: Diagnostics): Set<String>? {
  if (element == null || element == JsonNull) return null
  val paths = (element as? JsonArray)
    ?.takeIf { array -> array.all { (it as? JsonPrimitive)?.isString == true } }
    ?.map { (it as JsonPrimitive).content }
  if (paths == null) diagnostics.record("chat.changes_attributed_paths_unreadable")
  return paths?.toSet()
}

private fun ChatChangesBaseline.toJsonBaseline(): JsonObject = buildJsonObject {
  put(ChatStoreKeys.HEAD_SHA, headSha)
  put(ChatStoreKeys.UNTRACKED_PATHS, buildJsonArray { untrackedPaths.forEach { add(it) } })
  stashSha?.let { put(ChatStoreKeys.STASH_SHA, it) }
  put(ChatStoreKeys.TAKEN_AT, takenAt.toEpochMilliseconds())
  projectId?.let { put(ChatStoreKeys.PROJECT_ID, it) }
  if (worktrees.isNotEmpty()) put(ChatStoreKeys.WORKTREES, buildJsonObject {
    worktrees.forEach { (root, baseline) -> put(root, baseline.copy(projectId = null, worktrees = emptyMap()).toJsonBaseline()) }
  })
}

private fun decodeChangesBaseline(element: JsonElement?, diagnostics: Diagnostics, nested: Boolean = false): ChatChangesBaseline? {
  if (element == null || element == JsonNull) return null
  val baseline = element as? JsonObject
  if (baseline == null) {
    diagnostics.record("chat.changes_baseline_unreadable")
    return null
  }
  val headSha = baseline.string(ChatStoreKeys.HEAD_SHA)
  val paths = (baseline[ChatStoreKeys.UNTRACKED_PATHS] as? JsonArray)
    ?.takeIf { array -> array.all { element -> (element as? JsonPrimitive)?.isString == true } }
    ?.map { (it as JsonPrimitive).content }
  val takenAtValue = baseline[ChatStoreKeys.TAKEN_AT] as? JsonPrimitive
  val takenAt = takenAtValue?.takeIf { !it.isString }?.longOrNull
  val stashShaValue = baseline[ChatStoreKeys.STASH_SHA]
  val stashSha = when {
    stashShaValue == null || stashShaValue == JsonNull -> null
    (stashShaValue as? JsonPrimitive)?.isString == true -> (stashShaValue as JsonPrimitive).content
    else -> {
      diagnostics.record("chat.changes_baseline_unreadable")
      return null
    }
  }
  if (headSha == null || paths == null || takenAt == null) {
    diagnostics.record("chat.changes_baseline_unreadable")
    return null
  }
  val projectElement = baseline[ChatStoreKeys.PROJECT_ID]
  val projectId = (projectElement as? JsonPrimitive)?.takeIf { it.isString }?.content
  val treeElement = baseline[ChatStoreKeys.WORKTREES]
  val trees = linkedMapOf<String, ChatChangesBaseline>()
  if (projectElement != null && projectId == null || treeElement != null && (nested || treeElement !is JsonObject)) {
    diagnostics.record("chat.changes_baseline_unreadable")
    return null
  }
  if (treeElement is JsonObject) {
    if (projectId == null) {
      diagnostics.record("chat.changes_baseline_unreadable")
      return null
    }
    for ((root, value) in treeElement) {
      val decoded = decodeChangesBaseline(value, diagnostics, nested = true) ?: return null
      trees[root] = decoded
    }
  }
  return ChatChangesBaseline(headSha, paths, stashSha, Instant.fromEpochMilliseconds(takenAt), projectId, trees)
}

private fun OperationCardReference.toJson(): JsonObject = buildJsonObject {
  put(ChatStoreKeys.TOKEN, token)
  put(ChatStoreKeys.LAUNCH_ID, launchId)
  put(ChatStoreKeys.OPERATION_ID, operationId)
  put(ChatStoreKeys.STATE, state.name)
  draftChoices?.let { choices -> put(ChatStoreKeys.DRAFT_CHOICES, buildJsonObject { choices.forEach { (key, value) -> put(key, value) } }) }
  refusal?.let { put(ChatStoreKeys.REFUSAL, it) }
}

private fun JsonObject.toOperationCardReference(): OperationCardReference? {
  val state = OperationCardState.entries.firstOrNull { it.name == string(ChatStoreKeys.STATE) } ?: return null
  return OperationCardReference(
    token = string(ChatStoreKeys.TOKEN) ?: return null,
    launchId = string(ChatStoreKeys.LAUNCH_ID) ?: return null,
    operationId = string(ChatStoreKeys.OPERATION_ID) ?: return null,
    state = state,
    draftChoices = (get(ChatStoreKeys.DRAFT_CHOICES) as? JsonObject)?.mapValues { it.value.jsonPrimitive.content },
    refusal = string(ChatStoreKeys.REFUSAL),
  )
}

private fun ChatSuggestedAction.toJson(): JsonObject = buildJsonObject {
  put(ChatStoreKeys.LABEL, label)
  put(ChatStoreKeys.INSTRUCTION, instruction)
  put(ChatStoreKeys.MODE, mode.name)
  start?.let { put(ChatStoreKeys.START, it.wire) }
  title?.let { put(ChatStoreKeys.TITLE, it) }
  target?.takeIf { it.isNotBlank() }?.let { put(ChatStoreKeys.TARGET, it) }
  branch?.takeIf { it.isNotBlank() }?.let { put(ChatStoreKeys.BRANCH, it) }
  verify.qa?.let { put(ChatStoreKeys.QA, it.wire) }
  verify.reviewMode?.let { put(ChatStoreKeys.REVIEW_MODE, it.wire) }
}

private fun JsonObject.actions(key: String, diagnostics: Diagnostics): List<ChatSuggestedAction> {
  val value = get(key) ?: return emptyList()
  val array = value as? JsonArray
  if (array == null) {
    diagnostics.record("chat.store_malformed_action")
    return emptyList()
  }
  return array.mapNotNull { value ->
    val action = value as? JsonObject
    if (action == null) diagnostics.record("chat.store_malformed_action")
    action?.toAction(diagnostics)
  }
}

private fun JsonObject.selectedAction(diagnostics: Diagnostics): ChatSuggestedAction? {
  val value = get(ChatStoreKeys.SELECTED_ACTION) ?: return null
  val action = value as? JsonObject
  if (action == null) {
    diagnostics.record("chat.store_malformed_action")
    return null
  }
  return action.toAction(diagnostics)
}

private fun JsonObject.toAction(diagnostics: Diagnostics): ChatSuggestedAction? {
  val label = string(ChatStoreKeys.LABEL)
  val instruction = string(ChatStoreKeys.INSTRUCTION)
  val mode = ChatPermissionMode.entries.firstOrNull { it.name == string(ChatStoreKeys.MODE) }
  val startValue = get(ChatStoreKeys.START)
  val start = if (startValue == null) null else NewTaskActionKind.entries.firstOrNull { it.wire == string(ChatStoreKeys.START) }
  if (label == null || instruction == null || mode == null || (startValue != null && start == null)) {
    diagnostics.record("chat.store_malformed_action")
    return null
  }
  val title = string(ChatStoreKeys.TITLE)
  if (ChatStoreKeys.TITLE in this && title == null) diagnostics.record("chat.store_malformed_action")
  val target = string(ChatStoreKeys.TARGET)?.trim()?.takeIf { it.isNotBlank() }
  val branch = string(ChatStoreKeys.BRANCH)?.trim()?.takeIf { it.isNotBlank() }
  val qa = VerifyQaChoice.entries.firstOrNull { it.wire == string(ChatStoreKeys.QA) }
  val reviewMode = VerifyReviewModeChoice.entries.firstOrNull { it.wire == string(ChatStoreKeys.REVIEW_MODE) }
  if ((ChatStoreKeys.QA in this && qa == null) || (ChatStoreKeys.REVIEW_MODE in this && reviewMode == null)) {
    diagnostics.record("chat.store_malformed_action")
  }
  return ChatSuggestedAction(label, instruction, mode, start, title, target, branch, VerifyStartChoices(qa, reviewMode))
}

private fun JsonObject.objects(key: String): List<JsonObject> = (get(key) as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
