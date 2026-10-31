package dev.skillbill.desktop.core.data.chat

import dev.skillbill.desktop.core.domain.model.ChatHistoryLine
import dev.skillbill.desktop.core.domain.model.ChatHistoryRole
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.longOrNull
import java.io.File
import java.io.RandomAccessFile
import kotlin.time.Instant

internal const val LISTED_SESSIONS = 25
internal const val RESUMED_LINES = 10

private const val CODEX_SCAN_LIMIT = 400
private const val CODEX_TITLE_LINES = 60
private const val TITLE_TAIL_BYTES = 65_536L
private const val LINE_LIMIT = 4_000

/** One session in an agent CLI's own store under [profile]; [file] holds its messages when the app can read them. */
internal data class AgentSession(
  val agentId: String,
  val id: String,
  val profile: File,
  val updatedAt: Instant,
  val title: String?,
  val file: File?,
)

/**
 * Every config directory of one agent that holds [store]: the default `~/.<name>`, profile siblings named `~/.<name>-*`, and
 * any directory the environment or settings name. Links count once.
 */
internal fun agentProfiles(userHome: File, name: String, store: String, named: List<String?>): List<File> {
  val siblings = userHome.listFiles { file -> file.isDirectory && file.name.startsWith(".$name-") }.orEmpty().sortedBy(File::getName)
  val candidates = listOf(File(userHome, ".$name")) + siblings + named.filterNot { it.isNullOrBlank() }.map { File(it!!) }
  return candidates.filter { File(it, store).isDirectory }.distinctBy { it.canonicalPath }
}

/** Claude names a project directory after the working directory with every other character replaced by `-`. */
internal fun claudeProjectName(root: String): String = root.replace(Regex("[^A-Za-z0-9]"), "-")

internal fun claudeSessions(profile: File, root: String): List<AgentSession> {
  val files = File(profile, "projects/${claudeProjectName(root)}").listFiles { file -> file.isFile && file.name.endsWith(".jsonl") }
  return files.orEmpty().sortedByDescending(File::lastModified).take(LISTED_SESSIONS).map { file ->
    AgentSession("claude", file.name.removeSuffix(".jsonl"), profile, Instant.fromEpochMilliseconds(file.lastModified()), claudeTitle(file), file)
  }
}

/** Claude rewrites its `ai-title` and `last-prompt` lines as a session goes, so the file's tail holds the latest. */
private fun claudeTitle(file: File): String? {
  val lines = tail(file).lines().asReversed().mapNotNull(::parseStreamLine)
  return lines.firstNotNullOfOrNull { it.string("customTitle") ?: it.string("aiTitle") }
    ?: lines.firstNotNullOfOrNull { it.string("lastPrompt") }
}

internal fun claudeLines(file: File): List<ChatHistoryLine> {
  val lines = LatestLines()
  file.useLines { stream ->
    for (raw in stream) {
      val root = parseStreamLine(raw) ?: continue
      if (root.flag("isSidechain") || root.flag("isMeta")) continue
      val content = root.obj("message")?.get("content")
      when (root.string("type")) {
        "user" -> operatorText(content)?.let { lines.add(ChatHistoryRole.Operator, it) }
        "assistant" -> textBlocks(content)?.let { lines.add(ChatHistoryRole.Agent, it) }
      }
    }
  }
  return lines.latest()
}

/** Codex shards sessions by day, newest last in each directory; the first line of each names its working directory. */
internal fun codexSessions(profile: File, root: String): List<AgentSession> {
  val names = codexThreadNames(profile)
  val found = mutableListOf<AgentSession>()
  for (file in newestFirst(File(profile, "sessions")).take(CODEX_SCAN_LIMIT)) {
    val meta = file.bufferedReader(Charsets.UTF_8).use { it.readLine() }?.let(::parseStreamLine)?.obj("payload") ?: continue
    if (meta.string("cwd") != root) continue
    val id = meta.string("id") ?: continue
    found += AgentSession("codex", id, profile, Instant.fromEpochMilliseconds(file.lastModified()), names[id] ?: codexFirstPrompt(file), file)
    if (found.size == LISTED_SESSIONS) break
  }
  return found
}

private fun codexThreadNames(home: File): Map<String, String> {
  val index = File(home, "session_index.jsonl").takeIf(File::isFile) ?: return emptyMap()
  return index.useLines { lines ->
    lines.mapNotNull(::parseStreamLine).mapNotNull { line -> line.string("id")?.let { id -> line.string("thread_name")?.let { id to it } } }.toMap()
  }
}

private fun codexFirstPrompt(file: File): String? = file.useLines { lines ->
  lines.take(CODEX_TITLE_LINES).mapNotNull(::codexMessage).firstOrNull { it.role == ChatHistoryRole.Operator }?.text
}

internal fun codexLines(file: File): List<ChatHistoryLine> {
  val lines = LatestLines()
  file.useLines { stream -> stream.mapNotNull(::codexMessage).forEach { lines.add(it.role, it.text) } }
  return lines.latest()
}

private fun codexMessage(raw: String): ChatHistoryLine? {
  val root = parseStreamLine(raw)?.takeIf { it.string("type") == "response_item" } ?: return null
  val payload = root.obj("payload")?.takeIf { it.string("type") == "message" } ?: return null
  return when (payload.string("role")) {
    "user" -> operatorText(payload["content"])?.let { ChatHistoryLine(ChatHistoryRole.Operator, it) }
    "assistant" -> textBlocks(payload["content"])?.let { ChatHistoryLine(ChatHistoryRole.Agent, it) }
    else -> null
  }
}

/** Cursor keeps one directory per chat with a `meta.json`; its messages sit in a SQLite store the app does not read. */
internal fun cursorSessions(profile: File, root: String): List<AgentSession> {
  val chats = File(profile, "chats").listFiles(File::isDirectory).orEmpty().flatMap { it.listFiles(File::isDirectory).orEmpty().toList() }
  return chats.mapNotNull { chat ->
    val meta = File(chat, "meta.json").takeIf(File::isFile)?.readText(Charsets.UTF_8)?.let(::parseStreamLine) ?: return@mapNotNull null
    if (meta.string("cwd") != root || (meta["hasConversation"] as? JsonPrimitive)?.booleanOrNull == false) return@mapNotNull null
    val updated = (meta["updatedAtMs"] as? JsonPrimitive)?.longOrNull ?: chat.lastModified()
    AgentSession("cursor", chat.name, profile, Instant.fromEpochMilliseconds(updated), null, null)
  }.sortedByDescending { it.updatedAt }.take(LISTED_SESSIONS)
}

/** Keeps the last [RESUMED_LINES] lines, joining an agent reply split over several stored messages. */
private class LatestLines {
  private val lines = ArrayDeque<ChatHistoryLine>()

  fun add(role: ChatHistoryRole, text: String) {
    val last = lines.lastOrNull()
    if (role == ChatHistoryRole.Agent && last?.role == ChatHistoryRole.Agent) {
      lines.removeLast()
      lines.addLast(last.copy(text = "${last.text}\n\n$text".take(LINE_LIMIT)))
      return
    }
    lines.addLast(ChatHistoryLine(role, text.take(LINE_LIMIT)))
    if (lines.size > RESUMED_LINES) lines.removeFirst()
  }

  fun latest(): List<ChatHistoryLine> = lines.toList()
}

/** What the operator typed: injected context such as `<system-reminder>` blocks, AGENTS.md and tool results is left out. */
private fun operatorText(content: Any?): String? {
  val text = when (content) {
    is JsonPrimitive -> content.takeIf { it.isString }?.content
    is JsonArray -> content.mapNotNull { block ->
      (block as? JsonObject)?.takeIf { it.string("type") == "text" || it.string("type") == "input_text" }?.string("text")?.takeUnless(::injected)
    }.joinToString("\n\n")
    else -> null
  }
  return text?.trim()?.takeUnless { it.isEmpty() || injected(it) }
}

private fun injected(text: String): Boolean = text.trimStart().let { it.startsWith("<") || it.startsWith("# AGENTS.md") }

private fun textBlocks(content: Any?): String? = (content as? JsonArray)?.mapNotNull { block ->
  (block as? JsonObject)?.takeIf { it.string("type") == "text" || it.string("type") == "output_text" }?.string("text")
}?.joinToString("\n\n")?.trim()?.takeIf { it.isNotEmpty() }

private fun JsonObject.flag(key: String): Boolean = (get(key) as? JsonPrimitive)?.booleanOrNull == true

private fun tail(file: File): String = RandomAccessFile(file, "r").use { access ->
  val start = (access.length() - TITLE_TAIL_BYTES).coerceAtLeast(0)
  access.seek(start)
  val bytes = ByteArray((access.length() - start).toInt())
  access.readFully(bytes)
  String(bytes, Charsets.UTF_8)
}

private fun newestFirst(directory: File): Sequence<File> = sequence {
  val children = directory.listFiles().orEmpty().sortedByDescending(File::getName)
  for (child in children) {
    if (child.isDirectory) yieldAll(newestFirst(child)) else if (child.name.endsWith(".jsonl")) yield(child)
  }
}
