package dev.skillbill.desktop.feature.chat

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import dev.skillbill.desktop.core.designsystem.SkillBillTheme
import dev.skillbill.desktop.core.domain.model.ChatEvent
import dev.skillbill.desktop.core.domain.model.ChatToolCall
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

@OptIn(ExperimentalTestApi::class)
class ChatToolRowTest {
  private val start = Instant.parse("2026-10-31T12:00:00Z")

  @Test
  fun `a finished turn folds its tools and a row shows input and output only once opened`() = runComposeUiTest {
    val status = ChatToolCall("bash-1", "Bash", "git status --short", "Check branch changes")
    val read = ChatToolCall("read-1", "Read", "README.md", "Read project instructions")
    val finished = listOf(
      ChatToolEntry(status, ChatEvent.ToolResult(status.id, " M app.kt", false), start, start + 2.seconds),
      ChatToolEntry(read, ChatEvent.ToolResult(read.id, "fatal: missing", true), start + 2.seconds, start + 3.seconds),
    )
    val streaming = mutableStateOf(true)
    setContent { SkillBillTheme(reducedMotion = true) { ChatToolGroup(finished, streaming.value) } }

    onNodeWithText("Check branch changes").assertExists()
    runOnIdle { streaming.value = false }
    onNodeWithText("Check branch changes").assertDoesNotExist()
    onNodeWithText("Ran 2 tools").assertExists()
    onNodeWithText("1 failed").assertExists()

    onNodeWithText("Ran 2 tools").performClick()
    onNodeWithText("$ git status --short").assertDoesNotExist()
    onNodeWithText("Check branch changes").performClick()
    onNodeWithText("$ git status --short").assertExists()
    onNodeWithText(" M app.kt").assertExists()
    onNodeWithText("fatal: missing").assertDoesNotExist()
  }

  @Test
  fun `a long group shows its newest five calls until expanded, then folds back from the bottom`() = runComposeUiTest {
    val tools = (1..7).map { n ->
      val call = ChatToolCall("bash-$n", "Bash", "echo $n", "Command $n")
      ChatToolEntry(call, ChatEvent.ToolResult(call.id, "$n", false), start, start + 1.seconds)
    }
    setContent { SkillBillTheme(reducedMotion = true) { ChatToolGroup(tools, streaming = true) } }

    onNodeWithText("Command 2").assertDoesNotExist()
    onNodeWithText("Command 3").assertExists()
    onNodeWithText("Command 7").assertExists()
    onNodeWithText("Show 2 earlier").performClick()
    onNodeWithText("Command 1").assertExists()
    onNodeWithText("Show less").performClick()
    onNodeWithText("Command 1").assertDoesNotExist()
    onNodeWithText("Show 2 earlier").assertExists()
  }

  @Test
  fun `durations read as tenths under ten seconds, then seconds, then minutes`() {
    assertEquals("0.4s", durationLabel(400.milliseconds))
    assertEquals("9.2s", durationLabel(9_249.milliseconds))
    assertEquals("21s", durationLabel(21.seconds))
    assertEquals("1m 48s", durationLabel(108.seconds))
    assertEquals("0.0s", durationLabel((-5).seconds))
  }

  @Test
  fun `only labelled diffs or unlabelled hunks render as diffs, not markdown-style dash lists`() {
    assertEquals(true, isDiff("patch", "anything"))
    assertEquals(true, isDiff(null, "@@ -1,2 +1,2 @@\n-old\n+new"))
    assertEquals(false, isDiff(null, "- first item\n- second item"))
    assertEquals(false, isDiff("kotlin", "@@ -1 +1 @@\n-a\n+b"))
    assertEquals(DiffLineKind.File, diffLineKind("+++ b/app.kt"))
    assertEquals(DiffLineKind.Added, diffLineKind("+val x = 1"))
  }
}
