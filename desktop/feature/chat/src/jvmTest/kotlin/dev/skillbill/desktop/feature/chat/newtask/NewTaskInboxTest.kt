package dev.skillbill.desktop.feature.chat.newtask

import dev.skillbill.desktop.core.domain.model.BoardSectionKind
import dev.skillbill.desktop.core.domain.model.ChatAgent
import dev.skillbill.desktop.core.domain.model.ChatHistoryEntry
import dev.skillbill.desktop.core.domain.model.ChatNewTaskContext
import dev.skillbill.desktop.core.testing.loadedBoard
import dev.skillbill.desktop.core.testing.workItem
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant

class NewTaskInboxTest {
  private val now = Instant.parse("2026-11-06T10:00:00Z")

  @Test
  fun `inbox orders runs before date groups and groups chats by local calendar date`() {
    val blocked = workItem("blocked")
    val running = workItem("running")
    val today = chat("today", "Today draft", "2026-11-06T09:00:00Z")
    val future = chat("future", "Future draft", "2026-11-07T09:00:00Z")
    val yesterday = chat("yesterday", "Yesterday draft", "2026-11-05T23:55:00Z")
    val yesterdayMorning = chat("yesterday-morning", "Yesterday morning", "2026-11-05T01:00:00Z")
    val earlier = chat("earlier", "Earlier draft", "2026-10-30T12:00:00Z")

    assertEquals(
      listOf(
        InboxItem.Run(blocked, blocked = true),
        InboxItem.Run(running, blocked = false),
        InboxItem.Group(InboxDay.Today), InboxItem.Chat(today, chatTitle(today.title), null), InboxItem.Chat(future, chatTitle(future.title), null),
        InboxItem.Group(InboxDay.Yesterday), InboxItem.Chat(yesterday, chatTitle(yesterday.title), null), InboxItem.Chat(yesterdayMorning, chatTitle(yesterdayMorning.title), null),
        InboxItem.Group(InboxDay.Earlier), InboxItem.Chat(earlier, chatTitle(earlier.title), null),
      ),
      inboxItems(listOf(blocked), listOf(running), listOf(today, future, yesterday, yesterdayMorning, earlier), null, now, ZoneOffset.UTC),
    )
    assertEquals(
      listOf(InboxItem.Group(InboxDay.Today), InboxItem.Chat(today, chatTitle(today.title), null)),
      inboxItems(emptyList(), emptyList(), listOf(today), null, now, ZoneOffset.UTC),
    )
    assertEquals(
      listOf(InboxItem.Run(blocked, blocked = true)),
      inboxItems(listOf(blocked), listOf(running), listOf(today), null, now, ZoneOffset.UTC, InboxFilter.NeedsYou),
    )
    assertEquals(
      listOf(
        InboxItem.Group(InboxDay.Today), InboxItem.Chat(today, chatTitle(today.title), null), InboxItem.Chat(future, chatTitle(future.title), null),
        InboxItem.Group(InboxDay.Yesterday), InboxItem.Chat(yesterday, chatTitle(yesterday.title), null), InboxItem.Chat(yesterdayMorning, chatTitle(yesterdayMorning.title), null),
        InboxItem.Group(InboxDay.Earlier), InboxItem.Chat(earlier, chatTitle(earlier.title), null),
      ),
      inboxItems(listOf(blocked), listOf(running), listOf(today, future, yesterday, yesterdayMorning, earlier), null, now, ZoneOffset.UTC, InboxFilter.Chats),
    )
  }

  @Test
  fun `returning home excludes needs you and finished items from running rows`() {
    val needsYou = workItem("needs-you")
    val running = workItem("running")
    val finished = workItem("finished", finishedAt = now)
    val home = newTaskHome(
      loadedBoard(
        mapOf(
          BoardSectionKind.NeedsYou to listOf(needsYou),
          BoardSectionKind.Goals to listOf(running),
          BoardSectionKind.Finished to listOf(finished),
        ),
      ),
    ) as NewTaskHome.Returning

    assertEquals(listOf(running), home.runningItems)
  }

  @Test
  fun `chat title picks plain prompt text and finds the first image path`() {
    val cases = listOf(
      "`/repo/src/Main.kt`\nFix the header\nsecond line" to ChatTitle("Fix the header", null),
      "/repo/src/Main.kt\nREADME.md" to ChatTitle("Main.kt", null),
      "Look at ./screenshots/shot.PNG please" to ChatTitle("Look at ./screenshots/shot.PNG please", "shot.PNG"),
      "https://example.test/path" to ChatTitle("https://example.test/path", null),
      " \n\t" to ChatTitle("Untitled chat", null),
    )

    cases.forEach { (text, expected) -> assertEquals(expected, chatTitle(text), text) }
  }

  @Test
  fun `repository label appears only when the draft differs from the selected root`() {
    val entry = chat("same", "Draft", "2026-11-06T09:00:00Z", "/work/app/../app")
    val noContext = entry.copy(newTask = null)

    assertEquals(null, chatRepoLabel(entry, "/work/app"))
    assertEquals("app", chatRepoLabel(entry, "/work/lib"))
    assertEquals("app", chatRepoLabel(entry, null))
    assertEquals(null, chatRepoLabel(noContext, null))
  }

  @Test
  fun `short time floors values at each unit boundary and treats future times as now`() {
    val cases = listOf(
      59L to "now",
      24L * 60 to "24m",
      2L * 60 * 60 to "2h",
      25L * 60 * 60 to "1d",
      3L * 24 * 60 * 60 to "3d",
      -60L to "now",
    )

    cases.forEach { (secondsAgo, expected) ->
      val at = Instant.fromEpochMilliseconds(now.toEpochMilliseconds() - secondsAgo * 1_000)
      assertEquals(expected, shortAgo(at, now))
    }
  }

  private fun chat(session: String, title: String, updatedAt: String, root: String? = null) = ChatHistoryEntry(
    sessionId = session,
    executionId = null,
    agent = ChatAgent("claude", null, null),
    updatedAt = Instant.parse(updatedAt),
    title = title,
    lines = emptyList(),
    newTask = root?.let { ChatNewTaskContext(it, "main") },
  )
}
