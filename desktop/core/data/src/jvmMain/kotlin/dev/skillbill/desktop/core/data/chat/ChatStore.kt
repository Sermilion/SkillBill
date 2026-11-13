package dev.skillbill.desktop.core.data.chat

import dev.skillbill.desktop.core.common.Diagnostics
import dev.skillbill.desktop.core.common.HostEnvironment
import dev.skillbill.desktop.core.common.di.AppScope
import dev.skillbill.desktop.core.domain.model.ChatAgent
import dev.skillbill.desktop.core.domain.model.UNAVAILABLE_AGENT_ID
import dev.skillbill.desktop.core.domain.model.ChatChangesBaseline
import dev.skillbill.desktop.core.domain.model.ChatFailure
import dev.skillbill.desktop.core.domain.model.ChatHistoryEntry
import dev.skillbill.desktop.core.domain.model.ChatHistoryLine
import dev.skillbill.desktop.core.domain.model.ChatHistoryRole
import dev.skillbill.desktop.core.domain.model.ChatNewTaskContext
import dev.skillbill.desktop.core.domain.model.ChatPermissionMode
import dev.skillbill.desktop.core.domain.model.ChatSuggestedAction
import dev.skillbill.desktop.core.domain.model.NewTaskActionKind
import dev.skillbill.desktop.core.domain.model.OperationCardReference
import dev.skillbill.desktop.core.domain.model.OperationCardState
import dev.skillbill.desktop.core.domain.model.VerifyQaChoice
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
import me.tatarka.inject.annotations.Inject
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
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
  const val NEW_TASK_ROOT = "new_task_repository_root"
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
  val newTask: ChatNewTaskContext? = null,
  val summary: String? = null,
  val summarySourceUpdatedAt: Instant? = null,
  val executionCheckoutRoot: String? = null,
)

/**
 * Saved chats, one JSON file per session under the user's home, so a chat and its agent session outlive the window. Every
 * read-modify-write holds one lock; callers run it off the UI thread. A file that cannot be read counts as absent.
 */
@Inject
@SingleIn(AppScope::class)
class ChatStore(host: HostEnvironment, private val diagnostics: Diagnostics, private val clock: Clock) {
  private val directory = File(host.userHome, CHAT_STORE_PATH)
  private val lock = Any()

  internal fun agentSession(sessionId: String): String? = read(sessionId)?.agentSession

  internal fun saveAgentSession(sessionId: String, agentSession: String) =
    update(sessionId) { stored, _ -> stored.copy(agentSession = agentSession) }

  internal fun cursorSessionBinding(sessionId: String): CursorSessionBinding? = read(sessionId)?.let { chat ->
    val profile = chat.cursorProfileIdentity ?: return@let null
    val workingDirectory = chat.cursorWorkingDirectory ?: return@let null
    CursorSessionBinding(profile, workingDirectory)
  }

  internal fun saveCursorSession(sessionId: String, agentSession: String, binding: CursorSessionBinding) =
    update(sessionId) { stored, _ ->
      stored.copy(
        agentSession = agentSession,
        cursorProfileIdentity = binding.profileIdentity,
        cursorWorkingDirectory = binding.workingDirectory,
      )
    }

  internal fun replayHistory(sessionId: String): List<ChatHistoryLine> = read(sessionId)?.lines.orEmpty()

  internal fun newTaskHistory(sessionId: String): List<ChatHistoryLine> = read(sessionId)?.let { chat ->
    val context = chat.newTask ?: return@let emptyList()
    chat.lines.take(context.pendingTurnLineIndex ?: chat.lines.size)
  }.orEmpty()

  internal fun saveChat(entry: ChatHistoryEntry): Boolean {
    val saved = update(entry.sessionId) { stored, exists ->
      stored.copy(
        executionId = entry.executionId,
        agent = entry.agent,
        updatedAt = if (exists && stored.lines == entry.lines) stored.updatedAt else entry.updatedAt,
        lines = entry.lines,
        newTask = entry.newTask,
        executionCheckoutRoot = entry.executionCheckoutRoot ?: stored.executionCheckoutRoot,
      )
    }
    val executionId = entry.executionId ?: return saved
    synchronized(lock) {
      chatsOf(executionId).drop(CHATS_PER_EXECUTION).forEach { fileFor(it.sessionId)?.delete() }
    }
    return saved
  }

  internal fun savedChats(executionId: String): List<ChatHistoryEntry> = synchronized(lock) {
    sweepOrphans()
    chatsOf(executionId).mapNotNull(StoredChat::toEntry)
  }

  internal fun newTaskDrafts(limit: Int? = null): List<ChatHistoryEntry> = synchronized(lock) {
    val drafts = allFiles().mapNotNull(::readFile).filter { it.newTask != null }
      .sortedByDescending { it.updatedAt ?: Instant.DISTANT_PAST }.mapNotNull(StoredChat::toEntry)
    if (limit == null) drafts else drafts.take(limit)
  }

  internal fun delete(sessionId: String) {
    synchronized(lock) { fileFor(sessionId)?.delete() }
  }

  /** The app's save of the chat whose own session or agent session is [sessionId], when it holds an operator message. */
  internal fun savedFor(sessionId: String): ChatHistoryEntry? = synchronized(lock) {
    (fileFor(sessionId)?.let(::readFile)?.toEntry())
      ?: allFiles().mapNotNull(::readFile).firstOrNull { it.agentSession == sessionId }?.toEntry()
  }

  /** Writes [summary] onto an existing record only, keeping `updated_at`, so a chat deleted during the call stays deleted. */
  internal fun saveSummary(sessionId: String, summary: String, sourceUpdatedAt: Instant): Boolean = synchronized(lock) {
    val file = fileFor(sessionId) ?: return@synchronized false
    val stored = readFile(file) ?: return@synchronized false
    write(file, stored.copy(summary = summary, summarySourceUpdatedAt = sourceUpdatedAt))
  }

  /** The chat of [sessionId], found as [savedFor] finds it, when its summary is missing or older than its last save. */
  internal fun staleForSummary(sessionId: String): ChatHistoryEntry? = synchronized(lock) {
    val stored = fileFor(sessionId)?.let(::readFile)?.takeIf { it.toEntry() != null }
      ?: allFiles().mapNotNull(::readFile).firstOrNull { it.agentSession == sessionId }
    stored?.takeIf { it.needsSummary() }?.toEntry()
  }

  /** Every chat that needs a summary, newest first, at most [limit]. */
  internal fun staleSummaries(limit: Int): List<ChatHistoryEntry> = synchronized(lock) {
    allFiles().mapNotNull(::readFile).filter { it.needsSummary() }
      .sortedByDescending { it.updatedAt ?: Instant.DISTANT_PAST }.take(limit).mapNotNull(StoredChat::toEntry)
  }

  private fun chatsOf(executionId: String): List<StoredChat> =
    allFiles().mapNotNull(::readFile).filter { it.executionId == executionId }.sortedByDescending { it.updatedAt ?: Instant.DISTANT_PAST }

  private fun sweepOrphans() {
    val cutoff = (clock.now() - ORPHAN_AGE).toEpochMilliseconds()
    allFiles().filter { file ->
      file.lastModified() < cutoff && readFile(file).let { it?.executionId == null && it?.newTask == null }
    }.forEach { it.delete() }
  }

  private fun allFiles(): List<File> = directory.listFiles { file -> file.isFile && file.name.endsWith(".json") }?.toList().orEmpty()

  private fun read(sessionId: String): StoredChat? = synchronized(lock) { fileFor(sessionId)?.let(::readFile) }

  private fun update(sessionId: String, change: (StoredChat, Boolean) -> StoredChat): Boolean = synchronized(lock) {
    val file = fileFor(sessionId) ?: return@synchronized false
    val existing = readFile(file)
    write(file, change(existing ?: StoredChat(sessionId), existing != null))
  }

  private fun write(file: File, stored: StoredChat): Boolean {
    val temporary = File(directory, "${file.name}.tmp")
    return try {
      directory.mkdirs()
      temporary.writeText(encode(stored).toString(), Charsets.UTF_8)
      Files.move(temporary.toPath(), file.toPath(), REPLACE_EXISTING)
      true
    } catch (_: IOException) {
      diagnostics.record("chat.store_write_failed")
      temporary.delete()
      false
    }
  }

  private fun fileFor(sessionId: String): File? {
    if (!safeSessionId.matches(sessionId)) {
      diagnostics.record("chat.store_session_id_rejected")
      return null
    }
    return File(directory, "$sessionId.json")
  }

  private fun readFile(file: File): StoredChat? {
    if (!file.isFile) return null
    val root = try {
      parseStreamLine(file.readText(Charsets.UTF_8))
    } catch (_: IOException) {
      null
    }
    if (root == null) {
      diagnostics.record("chat.store_unreadable")
      return null
    }
    return decode(root, file.name.removeSuffix(".json"), diagnostics)
  }
}

private fun StoredChat.toEntry(): ChatHistoryEntry? {
  val operator = lines.firstOrNull { it.role == ChatHistoryRole.Operator } ?: return null
  return ChatHistoryEntry(
    sessionId, executionId, agent ?: return null, updatedAt ?: Instant.DISTANT_PAST, operator.text, lines, newTask, summary,
    executionCheckoutRoot = executionCheckoutRoot ?: cursorWorkingDirectory,
  )
}

/** Staleness rule: a listable chat whose summary source differs from its `updated_at`. */
private fun StoredChat.needsSummary(): Boolean =
  toEntry() != null && summarySourceUpdatedAt?.toEpochMilliseconds() != updatedAt?.toEpochMilliseconds()

private fun encode(chat: StoredChat): JsonObject = buildJsonObject {
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
  chat.newTask?.let { newTask ->
    put(ChatStoreKeys.NEW_TASK_ROOT, newTask.repositoryRoot)
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
    newTask = root.string(ChatStoreKeys.NEW_TASK_ROOT)?.let {
      ChatNewTaskContext(
        it,
        root.string(ChatStoreKeys.NEW_TASK_BASE_BRANCH),
        (root[ChatStoreKeys.NEW_TASK_SESSION_ESTABLISHED] as? JsonPrimitive)?.content?.toBooleanStrictOrNull() ?: true,
        (root[ChatStoreKeys.NEW_TASK_PENDING_TURN_LINE_INDEX] as? JsonPrimitive)?.intOrNull,
        decodeChangesBaseline(root[ChatStoreKeys.NEW_TASK_CHANGES_BASELINE], diagnostics),
        (root[ChatStoreKeys.NEW_TASK_CHANGES_CAPTURE_ATTEMPTED] as? JsonPrimitive)?.content?.toBooleanStrictOrNull()
          ?: ((root[ChatStoreKeys.NEW_TASK_SESSION_ESTABLISHED] as? JsonPrimitive)?.content?.toBooleanStrictOrNull() != false
            || root[ChatStoreKeys.NEW_TASK_CHANGES_BASELINE] != null),
        decodeAttributedPaths(root[ChatStoreKeys.NEW_TASK_CHANGES_ATTRIBUTED_PATHS], diagnostics),
      )
    },
    summary = root.string(ChatStoreKeys.SUMMARY)?.takeIf { it.isNotBlank() },
    summarySourceUpdatedAt = (root[ChatStoreKeys.SUMMARY_SOURCE_UPDATED_AT] as? JsonPrimitive)?.longOrNull?.let(Instant::fromEpochMilliseconds),
    executionCheckoutRoot = root.string(ChatStoreKeys.EXECUTION_CHECKOUT_ROOT),
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
