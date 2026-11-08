package dev.skillbill.desktop.feature.chat.newtask

import dev.skillbill.desktop.core.domain.model.ChangeStatus
import dev.skillbill.desktop.feature.chat.ChatMessage
import kotlin.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

enum class ChangeLetterTone { Done, Muted, Failed, Planning }
data class ChangeLetter(val letter: String, val label: String, val tone: ChangeLetterTone)
data class ChangePathParts(val name: String, val folder: String?)

internal fun changeLetter(status: ChangeStatus) = when (status) {
  ChangeStatus.Added, ChangeStatus.Untracked -> ChangeLetter("A", "Added", ChangeLetterTone.Done)
  ChangeStatus.Modified -> ChangeLetter("M", "Modified", ChangeLetterTone.Muted)
  ChangeStatus.Deleted -> ChangeLetter("D", "Deleted", ChangeLetterTone.Failed)
  ChangeStatus.Renamed -> ChangeLetter("R", "Renamed", ChangeLetterTone.Planning)
}
internal fun changePathParts(path: String): ChangePathParts {
  val parts = path.split('/')
  val dirs = parts.dropLast(1)
  val folder = when { dirs.isEmpty() -> null; dirs.size <= 3 -> dirs.joinToString("/"); else -> listOf(dirs.first(), "…", dirs[dirs.lastIndex - 1], dirs.last()).joinToString("/") }
  return ChangePathParts(parts.last(), folder)
}
internal fun chatChangesResumedDate(takenAt: Instant, zone: ZoneId = ZoneId.systemDefault()) = DateTimeFormatter.ofPattern("MMM d", Locale.ENGLISH).withZone(zone).format(java.time.Instant.ofEpochSecond(takenAt.epochSeconds, takenAt.nanosecondsOfSecond.toLong()))
internal fun fileCount(n: Int) = "$n ${if (n == 1) "file" else "files"}"
internal fun moreFiles(n: Int) = "$n more ${if (n == 1) "file" else "files"} in the diff"
internal fun chatChangesMade(commits: Int, uncommitted: Boolean): String = listOfNotNull(if (commits > 0) "$commits ${if (commits == 1) "commit" else "commits"}" else null, if (uncommitted) "uncommitted edits" else null).joinToString(" and ")
internal fun chatChangesFooter(since: String, made: String) = "Since $since" + if (made.isNotEmpty()) " · $made" else ""
internal fun chatChangesWindowTitle(transcript: List<ChatMessage>): String =
  transcript.filterIsInstance<ChatMessage.Operator>().firstOrNull()?.text?.lineSequence()?.firstOrNull()?.trim()?.takeIf(String::isNotBlank) ?: "New task"
