package dev.skillbill.desktop.feature.chat

import dev.skillbill.desktop.core.domain.model.ChatAgent
import dev.skillbill.desktop.core.domain.model.ChatHistoryEntry
import dev.skillbill.desktop.core.domain.model.ChatNewTaskContext
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant

class ChatResumePaletteTest {
  private val zone = ZoneId.of("Europe/Berlin")

  private fun at(text: String): Instant =
    Instant.fromEpochMilliseconds(LocalDateTime.parse(text).atZone(zone).toInstant().toEpochMilli())

  private fun entry(id: String, title: String = "chat $id", root: String? = null) =
    ChatHistoryEntry(id, null, ChatAgent("codex", null, null), epoch, title, emptyList(), root?.let { ChatNewTaskContext(it, null) })

  // A title that starts with screenshot paths must show the words the operator typed, or the file name when there are none.
  @Test
  fun `resumeTitle moves leading screenshot paths out of the title`() {
    assertEquals(ResumeTitle("fix it", listOf("a.png")), resumeTitle(entry("1", "/x/a.png fix it")))
    assertEquals(ResumeTitle("a.png", listOf("a.png")), resumeTitle(entry("2", "/x/a.png")))
    assertEquals(ResumeTitle("two lines", listOf("a.png", "b.JPG")), resumeTitle(entry("3", "/x/a.png  /y/b.JPG two\nlines")))
    assertEquals(ResumeTitle("see /x/a.png", emptyList()), resumeTitle(entry("4", "see /x/a.png")))
    assertEquals(ResumeTitle("codex session", emptyList()), resumeTitle(entry("5", "  ")))
  }

  // Groups follow the operator's calendar, not 24-hour spans or UTC.
  @Test
  fun `resumeGroup counts calendar days in the given zone`() {
    val now = at("2026-11-06T00:10")
    assertEquals(ResumeGroup.Yesterday, resumeGroup(at("2026-11-05T23:30"), now, zone))
    assertEquals(ResumeGroup.ThisWeek, resumeGroup(at("2026-10-31T12:00"), now, zone))
    assertEquals(ResumeGroup.Older, resumeGroup(at("2026-10-30T12:00"), now, zone))
  }

  // The cap applies only to the unfiltered list, and the filter must look at branches as well as titles.
  @Test
  fun `resumeListing caps a blank query, lifts the cap, and filters by branch`() {
    val entries = (1..42).map { entry("$it") }
    val branchOf = { it: ChatHistoryEntry -> if (it.sessionId == "40") "feat/billing-export" else null }
    val capped = resumeListing(entries, " ", showAll = false, branchOf)
    assertEquals(30 to 42, capped.shown.size to capped.total)
    assertEquals("30 of 42 chats", resumeCount(capped))
    assertEquals("42 chats", resumeCount(resumeListing(entries, "", showAll = true, branchOf)))
    val byBranch = resumeListing(entries, "BILLING", showAll = false, branchOf)
    assertEquals(listOf("40"), byBranch.shown.map { it.sessionId })
    assertEquals("1 chat", resumeCount(byBranch))
  }

  // A row shows its summary, so a filter that cannot find words in it hides the chat the operator is looking at.
  @Test
  fun `resumeListing matches a word found only in the summary`() {
    val summarized = entry("1", "/x/a.png fix it").copy(summary = "Adds retry to the upload worker")
    val entries = listOf(summarized, entry("2", "fix it too"))
    assertEquals(listOf("1"), resumeListing(entries, "upload", showAll = false) { null }.shown.map { it.sessionId })
  }

  // New Task's palette must show only the picked repository's drafts.
  @Test
  fun `resumableDrafts keeps only the picked repository`() {
    val drafts = listOf(entry("a", root = "/work/app"), entry("b", root = "/work/api"), entry("c", root = "/work/app"), entry("run"))
    assertEquals(listOf("a", "c"), resumableDrafts(drafts, "/work/app").map { it.sessionId })
    assertEquals(emptyList(), resumableDrafts(drafts, null))
  }
}
