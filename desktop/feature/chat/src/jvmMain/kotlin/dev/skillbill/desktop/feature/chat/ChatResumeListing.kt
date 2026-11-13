package dev.skillbill.desktop.feature.chat

import dev.skillbill.desktop.core.domain.model.ChatHistoryEntry
import dev.skillbill.desktop.core.domain.model.SavedChatProjection
import java.io.File
import java.io.IOException
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import kotlin.time.Instant

/** The most chats the resume palette lists before the operator asks for all of them. */
internal const val RESUME_LIMIT = 30

private val LEADING_IMAGE = Regex("""^\s*(/\S+\.(png|jpe?g|gif|webp))\s*""", RegexOption.IGNORE_CASE)
private val WHITESPACE = Regex("\\s+")

/** The saved New Task drafts of the repository at [repositoryRoot], in input order; none when no repository is picked. */
internal fun resumableDrafts(saved: List<ChatHistoryEntry>, repositoryRoot: String?): List<ChatHistoryEntry> {
  if (repositoryRoot == null) return emptyList()
  val canonicalRoots = mutableMapOf<String, String>()
  fun resolved(root: String): String = canonicalRoots.getOrPut(root) { canonicalRepositoryRoot(root) }
  val selectedRoot = resolved(repositoryRoot)
  return saved.filter { entry -> entry.newTask?.repositoryRoot?.let { resolved(it) == selectedRoot } == true }
}

internal fun resumableDrafts(saved: SavedChatProjection, repositoryRoot: String?): SavedChatProjection {
  val entries = resumableDrafts(saved.entries, repositoryRoot)
  val recoveries = saved.recoveries.filter { recovery ->
    val root = recovery.repositoryRoot
    if (root == null || repositoryRoot == null) root == null
    else canonicalRepositoryRoot(root) == canonicalRepositoryRoot(repositoryRoot)
  }
  return SavedChatProjection(entries, recoveries)
}

private fun canonicalRepositoryRoot(root: String): String = try {
  File(root).canonicalPath
} catch (error: IOException) {
  System.getLogger("skillbill.chat.resume").log(
    System.Logger.Level.WARNING,
    "Could not resolve saved-chat repository path; using its absolute normalized path: $root",
    error,
  )
  File(root).absoluteFile.normalize().path
}

/** The screenshot paths at the start of [text], in order, and the text after them. A path later in the text stays in it. */
internal fun splitLeadingImages(text: String): Pair<List<String>, String> {
  val paths = mutableListOf<String>()
  var rest = text
  while (true) {
    val match = LEADING_IMAGE.find(rest) ?: break
    paths += match.groupValues[1]
    rest = rest.substring(match.range.last + 1)
  }
  return paths to rest
}

/** A saved chat's one-line title and the file names of the screenshots its first message started with. */
internal data class ResumeTitle(val text: String, val images: List<String>)

/** The title without leading screenshot paths; an image-only title falls back to the first file name, then to the agent. */
internal fun resumeTitle(entry: ChatHistoryEntry): ResumeTitle {
  val (paths, rest) = splitLeadingImages(entry.title)
  val images = paths.map { it.substringAfterLast('/') }
  val text = rest.replace(WHITESPACE, " ").trim()
    .ifEmpty { images.firstOrNull() ?: "${entry.agent.agentId} session" }
  return ResumeTitle(text, images)
}

/** The day group a saved chat's row sits under. */
internal enum class ResumeGroup(val label: String) {
  Today("Today"),
  Yesterday("Yesterday"),
  ThisWeek("This week"),
  Older("Older"),
}

/** Groups by calendar days between [updatedAt] and [now] in [zone]; a date after today counts as Today. */
internal fun resumeGroup(updatedAt: Instant, now: Instant, zone: ZoneId = ZoneId.systemDefault()): ResumeGroup {
  fun date(instant: Instant) = java.time.Instant.ofEpochMilli(instant.toEpochMilliseconds()).atZone(zone).toLocalDate()
  val days = ChronoUnit.DAYS.between(date(updatedAt), date(now))
  return when {
    days <= 0 -> ResumeGroup.Today
    days == 1L -> ResumeGroup.Yesterday
    days <= 6 -> ResumeGroup.ThisWeek
    else -> ResumeGroup.Older
  }
}

/** The chats the palette shows and how many matched the filter. */
internal data class ResumeListing(val shown: List<ChatHistoryEntry>, val total: Int)

/**
 * Filters [entries] by [query] on summary, title, branch and agent, keeping input order. A blank query shows the first
 * [RESUME_LIMIT] unless [showAll]; a non-blank query shows every match.
 */
internal fun resumeListing(entries: List<ChatHistoryEntry>, query: String, showAll: Boolean, branchOf: (ChatHistoryEntry) -> String?): ResumeListing {
  val q = query.trim()
  if (q.isEmpty()) return ResumeListing(if (showAll) entries else entries.take(RESUME_LIMIT), entries.size)
  val matches = entries.filter { entry ->
    listOfNotNull(entry.summary, resumeTitle(entry).text, entry.title, branchOf(entry), agentName(entry.agent), entry.agent.agentId)
      .any { it.contains(q, ignoreCase = true) }
  }
  return ResumeListing(matches, matches.size)
}

/** The palette footer's count, such as "30 of 42 chats", "1 chat" or "0 chats". */
internal fun resumeCount(listing: ResumeListing): String = when {
  listing.shown.size < listing.total -> "${listing.shown.size} of ${listing.total} chats"
  listing.total == 1 -> "1 chat"
  else -> "${listing.total} chats"
}
