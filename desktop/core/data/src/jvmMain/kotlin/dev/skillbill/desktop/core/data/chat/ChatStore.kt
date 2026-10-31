package dev.skillbill.desktop.core.data.chat

import dev.skillbill.desktop.core.common.Diagnostics
import dev.skillbill.desktop.core.common.HostEnvironment
import dev.skillbill.desktop.core.common.di.AppScope
import dev.skillbill.desktop.core.domain.model.ChatAgent
import dev.skillbill.desktop.core.domain.model.ChatHistoryEntry
import dev.skillbill.desktop.core.domain.model.ChatHistoryLine
import dev.skillbill.desktop.core.domain.model.ChatHistoryRole
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import me.tatarka.inject.annotations.Inject
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn
import java.io.File
import java.io.IOException
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant

/** Relative to the user's home, beside the settings file. */
internal const val CHAT_STORE_PATH = ".skill-bill/desktop/chats"

private const val CHATS_PER_EXECUTION = 20
private val ORPHAN_AGE = 7.days
private val safeSessionId = Regex("[A-Za-z0-9._-]+")

/** One Junie exchange; Junie cannot resume a session, so each turn replays these. */
internal data class ChatExchange(val operator: String, val agent: String)

private object ChatStoreKeys {
  const val SESSION_ID = "session_id"
  const val EXECUTION_ID = "execution_id"
  const val AGENT_ID = "agent_id"
  const val MODEL = "model"
  const val EFFORT = "effort"
  const val UPDATED_AT = "updated_at_ms"
  const val LINES = "lines"
  const val ROLE = "role"
  const val TEXT = "text"
  const val AGENT_SESSION = "agent_session"
  const val EXCHANGES = "exchanges"
  const val OPERATOR = "operator"
  const val AGENT = "agent"
}

/** What one session file holds; the workspace writes the conversation fields and the transports write the resume fields. */
private data class StoredChat(
  val sessionId: String,
  val executionId: String? = null,
  val agent: ChatAgent? = null,
  val updatedAt: Instant? = null,
  val lines: List<ChatHistoryLine> = emptyList(),
  val agentSession: String? = null,
  val exchanges: List<ChatExchange> = emptyList(),
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

  internal fun saveAgentSession(sessionId: String, agentSession: String) = update(sessionId) { it.copy(agentSession = agentSession) }

  internal fun exchanges(sessionId: String): List<ChatExchange> = read(sessionId)?.exchanges.orEmpty()

  internal fun saveExchanges(sessionId: String, exchanges: List<ChatExchange>) = update(sessionId) { it.copy(exchanges = exchanges) }

  internal fun saveChat(entry: ChatHistoryEntry) {
    update(entry.sessionId) {
      it.copy(executionId = entry.executionId, agent = entry.agent, updatedAt = entry.updatedAt, lines = entry.lines)
    }
    synchronized(lock) {
      chatsOf(entry.executionId).drop(CHATS_PER_EXECUTION).forEach { fileFor(it.sessionId)?.delete() }
    }
  }

  internal fun savedChats(executionId: String): List<ChatHistoryEntry> = synchronized(lock) {
    sweepOrphans()
    chatsOf(executionId).filter { stored -> stored.lines.any { it.role == ChatHistoryRole.Operator } }.mapNotNull { stored ->
      val agent = stored.agent ?: return@mapNotNull null
      ChatHistoryEntry(stored.sessionId, executionId, agent, stored.updatedAt ?: Instant.DISTANT_PAST, stored.lines)
    }
  }

  private fun chatsOf(executionId: String): List<StoredChat> =
    allFiles().mapNotNull(::readFile).filter { it.executionId == executionId }.sortedByDescending { it.updatedAt ?: Instant.DISTANT_PAST }

  private fun sweepOrphans() {
    val cutoff = (clock.now() - ORPHAN_AGE).toEpochMilliseconds()
    allFiles().filter { it.lastModified() < cutoff && readFile(it)?.executionId == null }.forEach { it.delete() }
  }

  private fun allFiles(): List<File> = directory.listFiles { file -> file.isFile && file.name.endsWith(".json") }?.toList().orEmpty()

  private fun read(sessionId: String): StoredChat? = synchronized(lock) { fileFor(sessionId)?.let(::readFile) }

  private fun update(sessionId: String, change: (StoredChat) -> StoredChat) = synchronized(lock) {
    val file = fileFor(sessionId) ?: return@synchronized
    val updated = change(readFile(file) ?: StoredChat(sessionId))
    try {
      directory.mkdirs()
      val temporary = File(directory, "${file.name}.tmp")
      temporary.writeText(encode(updated).toString(), Charsets.UTF_8)
      if (!temporary.renameTo(file)) {
        file.delete()
        temporary.renameTo(file)
      }
    } catch (_: IOException) {
      diagnostics.record("chat.store_write_failed")
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
    return decode(root, file.name.removeSuffix(".json"))
  }
}

private fun encode(chat: StoredChat): JsonObject = buildJsonObject {
  put(ChatStoreKeys.SESSION_ID, chat.sessionId)
  chat.executionId?.let { put(ChatStoreKeys.EXECUTION_ID, it) }
  chat.agent?.let { agent ->
    put(ChatStoreKeys.AGENT_ID, agent.agentId)
    agent.model?.let { put(ChatStoreKeys.MODEL, it) }
    agent.effort?.let { put(ChatStoreKeys.EFFORT, it) }
  }
  chat.updatedAt?.let { put(ChatStoreKeys.UPDATED_AT, it.toEpochMilliseconds()) }
  put(
    ChatStoreKeys.LINES,
    buildJsonArray {
      chat.lines.forEach { line ->
        add(buildJsonObject {
          put(ChatStoreKeys.ROLE, line.role.name)
          put(ChatStoreKeys.TEXT, line.text)
        })
      }
    },
  )
  chat.agentSession?.let { put(ChatStoreKeys.AGENT_SESSION, it) }
  put(
    ChatStoreKeys.EXCHANGES,
    buildJsonArray {
      chat.exchanges.forEach { exchange ->
        add(buildJsonObject {
          put(ChatStoreKeys.OPERATOR, exchange.operator)
          put(ChatStoreKeys.AGENT, exchange.agent)
        })
      }
    },
  )
}

private fun decode(root: JsonObject, fallbackSessionId: String): StoredChat {
  val agentId = root.string(ChatStoreKeys.AGENT_ID)
  return StoredChat(
    sessionId = root.string(ChatStoreKeys.SESSION_ID) ?: fallbackSessionId,
    executionId = root.string(ChatStoreKeys.EXECUTION_ID),
    agent = agentId?.let { ChatAgent(it, root.string(ChatStoreKeys.MODEL), root.string(ChatStoreKeys.EFFORT)) },
    updatedAt = (root[ChatStoreKeys.UPDATED_AT] as? JsonPrimitive)?.longOrNull?.let(Instant::fromEpochMilliseconds),
    lines = root.objects(ChatStoreKeys.LINES).mapNotNull { line ->
      val role = ChatHistoryRole.entries.firstOrNull { it.name == line.string(ChatStoreKeys.ROLE) } ?: return@mapNotNull null
      line.string(ChatStoreKeys.TEXT)?.let { ChatHistoryLine(role, it) }
    },
    agentSession = root.string(ChatStoreKeys.AGENT_SESSION),
    exchanges = root.objects(ChatStoreKeys.EXCHANGES).mapNotNull { exchange ->
      val operator = exchange.string(ChatStoreKeys.OPERATOR) ?: return@mapNotNull null
      exchange.string(ChatStoreKeys.AGENT)?.let { ChatExchange(operator, it) }
    },
  )
}

private fun JsonObject.objects(key: String): List<JsonObject> = (get(key) as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
