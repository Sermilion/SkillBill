package dev.skillbill.desktop.feature.chat.newtask

import dev.skillbill.desktop.core.domain.model.ChatAgent
import dev.skillbill.desktop.core.domain.model.ChatHistoryEntry
import dev.skillbill.desktop.core.domain.model.ChatNewTaskContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant

class NewTaskInboxTest {
  private val now = Instant.parse("2026-11-06T10:00:00Z")

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

  @Test
  fun `nearest scroll target moves only rows outside the viewport`() {
    assertEquals(34, nearestScrollTarget(start = 34, end = 68, scroll = 50, viewport = 100))
    assertEquals(84, nearestScrollTarget(start = 150, end = 184, scroll = 50, viewport = 100))
    assertEquals(null, nearestScrollTarget(start = 70, end = 104, scroll = 50, viewport = 100))
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
