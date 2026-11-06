package dev.skillbill.desktop.feature.chat.newtask

import dev.skillbill.desktop.core.domain.model.ChatHistoryEntry
import dev.skillbill.desktop.core.domain.model.WorkItem
import java.io.File
import java.time.ZoneId
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

internal enum class InboxDay(val label: String) {
  Today("Today"),
  Yesterday("Yesterday"),
  Earlier("Earlier"),
}

internal enum class InboxFilter { All, NeedsYou, Chats }

internal sealed interface InboxItem {
  data class Group(val day: InboxDay) : InboxItem
  data class Run(val item: WorkItem, val blocked: Boolean) : InboxItem
  data class Chat(val entry: ChatHistoryEntry, val title: ChatTitle, val repoLabel: String?) : InboxItem
}

internal data class ChatTitle(val title: String, val imageName: String?)

internal fun inboxItems(
  needsYou: List<WorkItem>,
  running: List<WorkItem>,
  chats: List<ChatHistoryEntry>,
  selectedRoot: String?,
  now: Instant,
  zone: ZoneId = ZoneId.systemDefault(),
  filter: InboxFilter = InboxFilter.All,
): List<InboxItem> = when (filter) {
  InboxFilter.All, InboxFilter.Chats -> buildList {
    if (filter == InboxFilter.All) {
      needsYou.forEach { add(InboxItem.Run(it, blocked = true)) }
      running.forEach { add(InboxItem.Run(it, blocked = false)) }
    }
    val chatsByDay = chats.groupBy { inboxDay(it, now, zone) }
    InboxDay.entries.forEach { day ->
      val entries = chatsByDay[day].orEmpty()
      if (entries.isNotEmpty()) {
        add(InboxItem.Group(day))
        entries.forEach { entry -> add(InboxItem.Chat(entry, chatTitle(entry.title), chatRepoLabel(entry, selectedRoot))) }
      }
    }
  }
  InboxFilter.NeedsYou -> needsYou.map { InboxItem.Run(it, blocked = true) }
}

internal fun shortAgo(at: Instant, now: Instant): String {
  val elapsed = now - at
  return when {
    elapsed < 1.minutes -> "now"
    elapsed < 1.hours -> "${elapsed.inWholeMinutes}m"
    elapsed < 1.days -> "${elapsed.inWholeHours}h"
    else -> "${elapsed.inWholeDays}d"
  }
}

internal fun chatRepoLabel(entry: ChatHistoryEntry, selectedRoot: String?): String? {
  val root = entry.newTask?.repositoryRoot ?: return null
  return if (selectedRoot != null && File(root).normalize().path == File(selectedRoot).normalize().path) null else File(root).name
}

internal fun chatTitle(text: String): ChatTitle {
  val lines = text.lines().map(String::trim).filter(String::isNotEmpty)
  val title = lines.firstOrNull { !isBarePath(it) }
    ?: lines.firstOrNull()?.let { fileName(unquote(it)) }
    ?: "Untitled chat"
  val imageName = lines.asSequence()
    .flatMap { it.split(Regex("\\s+")).asSequence() }
    .firstOrNull { token ->
      isBarePath(token) && fileName(unquote(token)).substringAfterLast('.', "").lowercase() in IMAGE_EXTENSIONS
    }
    ?.let { fileName(unquote(it)) }
  return ChatTitle(title, imageName)
}

private fun inboxDay(entry: ChatHistoryEntry, now: Instant, zone: ZoneId): InboxDay {
  val today = java.time.Instant.ofEpochMilli(now.toEpochMilliseconds()).atZone(zone).toLocalDate()
  val date = java.time.Instant.ofEpochMilli(entry.updatedAt.toEpochMilliseconds()).atZone(zone).toLocalDate()
  return when {
    date >= today -> InboxDay.Today
    date == today.minusDays(1) -> InboxDay.Yesterday
    else -> InboxDay.Earlier
  }
}

private fun unquote(value: String): String = value.removeSurrounding("`")

private fun isBarePath(raw: String): Boolean {
  val value = unquote(raw)
  if (value.isBlank() || value.any(Char::isWhitespace) || "://" in value) return false
  val file = fileName(value)
  return value.startsWith('/') || value.startsWith("~/") || value.startsWith("./") || value.startsWith("../") ||
    value.count { it == '/' } >= 2 || FILE_EXTENSION.matches(file)
}

private fun fileName(path: String): String = path.substringAfterLast('/')

private val FILE_EXTENSION = Regex("^.+\\.[A-Za-z0-9]{1,8}$")
private val IMAGE_EXTENSIONS = setOf("png", "jpg", "jpeg", "gif", "webp", "bmp")
