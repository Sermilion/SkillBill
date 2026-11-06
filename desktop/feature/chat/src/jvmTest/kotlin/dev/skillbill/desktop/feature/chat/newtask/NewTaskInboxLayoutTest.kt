package dev.skillbill.desktop.feature.chat.newtask

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.v2.runSkikoComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import dev.skillbill.desktop.core.designsystem.SkillBillTheme
import dev.skillbill.desktop.core.domain.model.ChatHistoryEntry
import dev.skillbill.desktop.core.domain.model.ChatHistoryLine
import dev.skillbill.desktop.core.domain.model.ChatHistoryRole
import dev.skillbill.desktop.core.domain.model.ChatNewTaskContext
import dev.skillbill.desktop.core.testing.FakeExecutionChatRepository
import dev.skillbill.desktop.core.testing.FakeWorkControlRepository
import dev.skillbill.desktop.core.testing.workItem
import dev.skillbill.desktop.core.ui.feedback.ToastQueue
import dev.skillbill.desktop.core.ui.input.LocalShellInput
import dev.skillbill.desktop.core.ui.input.ShellInput
import dev.skillbill.desktop.core.ui.navigation.WindowCoroutineScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

@OptIn(ExperimentalTestApi::class)
class NewTaskInboxLayoutTest {
  private val now = Instant.parse("2026-11-06T12:00:00Z")
  private val blocked = run("SKILL-510", "QA unblock guidance and retry", "qa", "The scope of subtask 1 changed while the run was paused", 14)
  private val running = listOf(
    run("SKILL-511", "Multi-chat New task", "implement", "Subtask 2 of 4", 3),
    run("SKILL-508", "Resume chat palette polish", "review", "5 specialists running", 1),
  )
  private val home = NewTaskHome.Returning(listOf(blocked), 2, 9, running)
  private val chats = listOf(
    chat("Introduce multiple chat windows to New task", 0),
    chat("Verdict incomplete: environment_unavailable", 24),
    chat("Background service skillbill-run-47b03a11 stopped", 130),
    chat("Recent chats feel incomplete", 190, image = "Screenshot_20261106_121804.png"),
    chat("Specs panel is empty for the SkillBill project", 245),
    chat("Plan detail misses the subtask list", 262, image = "Screenshot_20261106_081402.png"),
    chat("Flaky monitor test on CI", 1720),
  )

  @Test
  fun `long titles cannot move badges or time columns and dividers do not increase row height`() = runSkikoComposeUiTest(
    size = Size(1152f, 1000f),
    density = Density(1.5f),
  ) {
    var width by mutableStateOf(720.dp)
    var mixed by mutableStateOf(false)
    val foreign = chat("Add CSV export to the history screen", 1500, root = "/work/readian-web")
    setContent {
      SkillBillTheme(reducedMotion = true) {
        Box(Modifier.width(width + 48.dp).background(SkillBillTheme.colors.bgPanel).padding(24.dp).testTag("Inbox reference")) {
          val shown = if (mixed) chats.dropLast(1) + foreign + chats.last() else chats
          InboxSection(
            needsYou = listOf(blocked), running = running, chats = shown, savedCount = shown.size,
            selectedRoot = "/work/SkillBill", home = home, showAll = false, now = { now },
            onOpenRun = {}, onResume = {}, onDiscard = {}, onShowAll = {}, onShowAllWork = {},
          )
        }
      }
    }
    waitForIdle()

    val rows = (listOf(blocked) + running).map { onNodeWithText(it.title).fetchSemanticsNode().boundsInRoot }
    rows.forEach { assertEquals(51f, it.height, .1f) }
    assertEquals(51f, rows[1].top - rows[0].top, .1f)
    assertEquals(51f, rows[2].top - rows[1].top, .1f)
    val badgeEnds = listOf("qa", "implement", "review").map { onNodeWithText(it, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot.right }
    badgeEnds.forEach { assertEquals(badgeEnds.first(), it, .1f) }
    val timeEnds = listOf("14m", "3m", "1m", "24m", "2h", "1d").map { onNodeWithText(it, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot.right }
    timeEnds.forEach { assertEquals(timeEnds.first(), it, .1f) }
    onNodeWithText("discard chat").assertExists()
    onNodeWithTag("Inbox reference").saveCapture("inbox.png")

    onNodeWithContentDescription("Pick up where you left off").performMouseInput { enter(center) }
    onNodeWithText("2", useUnmergedTree = true).assertExists()
    onAllNodesWithText("9", useUnmergedTree = true).assertCountEquals(2)
    onNodeWithTag("Inbox reference").saveCapture("inbox-hover.png")

    onNodeWithContentDescription("Chats").performClick()
    onNodeWithText(blocked.title).assertDoesNotExist()
    onNodeWithText(chats.last().title).assertExists()
    onNodeWithContentDescription("Needs you").performClick()
    onNodeWithText(blocked.title).assertExists()
    onNodeWithText(chats.first().title).assertDoesNotExist()
    onNodeWithContentDescription("All").performClick()

    runOnIdle { width = 480.dp; mixed = true }
    waitForIdle()
    val repo = onNodeWithText("readian-web", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
    val time = onNodeWithText("14m", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
    assertTrue(repo.right < time.left)
    onAllNodesWithText("1d", useUnmergedTree = true).fetchSemanticsNodes().forEach { assertEquals(time.right, it.boundsInRoot.right, .1f) }
    onNodeWithTag("Inbox reference").saveCapture("inbox-narrow-mixed.png")
  }

  @Test
  fun `returning inbox admits every counted chat instead of hiding the seventh draft`() = runSkikoComposeUiTest(
    size = Size(1600f, 1400f),
    density = Density(1.5f),
  ) {
    val repository = FakeExecutionChatRepository().apply { chats.forEach { saved[it.sessionId] = it } }
    val draft = NewTaskDraft(
      repository, FakeWorkControlRepository(), testDatabaseSessionController(), ToastQueue(),
      WindowCoroutineScope(CoroutineScope(Dispatchers.Unconfined)), Clock.System,
    ).apply { this.repository = NewTaskRepository("/work/SkillBill", "SkillBill") }
    setContent {
      SkillBillTheme(reducedMotion = true) {
        Box(Modifier.background(SkillBillTheme.colors.bgPanel)) {
          CompositionLocalProvider(LocalShellInput provides ShellInput()) {
            NewTaskScreen(NewTaskUiState(home = home), draft, NewTaskScreenHost({ now }, {}, {})) {}
          }
        }
      }
    }
    onNodeWithText(chats.last().title).assertExists()
    onNodeWithText("Show all").assertDoesNotExist()
    onNodeWithContentDescription("Chats").performClick()
    onNodeWithText(chats.last().title).assertExists()
    onNodeWithText(blocked.title).assertDoesNotExist()
    onNodeWithContentDescription("All").performClick()
    onNodeWithContentDescription("Pick up where you left off").performMouseInput { enter(center); exit() }
    onRoot().saveCapture("new-task-populated.png")
  }

  private fun SemanticsNodeInteraction.saveCapture(name: String) {
    val directory = System.getenv("SKILL512_EVIDENCE_DIR")?.let(::File) ?: return
    directory.mkdirs()
    ImageIO.write(captureToImage().toAwtImage(), "png", File(directory, name))
  }

  private fun run(key: String, title: String, stage: String, reason: String, ago: Int) = workItem(key, reason = reason).let {
    it.copy(title = title, stepLabel = stage, control = it.control.copy(updatedAt = now - ago.minutes))
  }

  private fun chat(title: String, ago: Int, image: String? = null, root: String = "/work/SkillBill"): ChatHistoryEntry {
    val text = listOfNotNull(title, image?.let { "/shots/$it" }).joinToString("\n")
    return ChatHistoryEntry(
      sessionId = title, executionId = null, agent = requireNotNull(FakeExecutionChatRepository().agent),
      updatedAt = now - ago.minutes, title = text,
      lines = listOf(ChatHistoryLine(ChatHistoryRole.Operator, text)), newTask = ChatNewTaskContext(root, "main"),
    )
  }
}
