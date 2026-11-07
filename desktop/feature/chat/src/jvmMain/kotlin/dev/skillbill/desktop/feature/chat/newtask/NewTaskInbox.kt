package dev.skillbill.desktop.feature.chat.newtask

import dev.skillbill.desktop.core.domain.model.ChatHistoryEntry
import java.io.File
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

internal data class ChatTitle(val title: String, val imageName: String?)

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
