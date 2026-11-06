package dev.skillbill.desktop.feature.chat.newtask

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.v2.runSkikoComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import dev.skillbill.desktop.core.designsystem.SkillBillTheme
import dev.skillbill.desktop.core.domain.model.ChatHistoryEntry
import dev.skillbill.desktop.core.domain.model.ChatHistoryLine
import dev.skillbill.desktop.core.domain.model.ChatHistoryRole
import dev.skillbill.desktop.core.domain.model.ChatNewTaskContext
import dev.skillbill.desktop.core.testing.FakeExecutionChatRepository
import dev.skillbill.desktop.core.testing.workItem
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Instant

@OptIn(ExperimentalTestApi::class)
class NewTaskInboxLayoutTest {
  private val now = Instant.parse("2026-11-06T12:00:00Z")
  private val blocked = workItem("SKILL-510", reason = "QA unblock guidance and retry").let { it.copy(title = "QA unblock guidance and retry", stepLabel = "qa", control = it.control.copy(updatedAt = now)) }
  private val chats = listOf(
    chat("Introduce multiple chat windows to New task", "/work/app"),
    chat("Add CSV export to the history screen", "/work/readian-web"),
    chat("Recent chats feel incomplete", "/work/other-tool"),
  )

  @Test
  fun `section cards keep row height and align badge and time columns at narrow width`() = runSkikoComposeUiTest(
    size = Size(800f, 700f),
    density = Density(1.5f),
  ) {
    var width by androidx.compose.runtime.mutableStateOf(720.dp)
    setContent {
      SkillBillTheme(reducedMotion = true) {
        Box(Modifier.width(width + 48.dp).background(SkillBillTheme.colors.bgPanel).padding(24.dp).testTag("Home sections")) {
          androidx.compose.foundation.layout.Column {
            RecentChatsSection(chats, "/work/app", { now }, {}, {}, {})
            NeedsYouSection(NewTaskHome.Returning(listOf(blocked), 2, 9), { now }, {}, {})
          }
        }
      }
    }
    waitForIdle()

    val chatRows = chats.map { onNodeWithText(chatTitle(it.title).title).fetchSemanticsNode().boundsInRoot }
    val runRow = onNodeWithText(blocked.title).fetchSemanticsNode().boundsInRoot
    (chatRows + runRow).forEach { assertEquals(51f, it.height, .1f) }
    val badgeEnds = listOf("readian-web", "other-tool").map { onNodeWithText(it, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot.right }
    assertEquals(badgeEnds.first(), badgeEnds.last(), .1f)
    val timeEnds = onAllNodesWithText("now", useUnmergedTree = true).fetchSemanticsNodes().map { it.boundsInRoot.right }
    assertEquals(4, timeEnds.size)
    timeEnds.forEach { assertEquals(timeEnds.first(), it, .1f) }
    onNodeWithTag("Home sections").saveCapture("home-sections.png")

    runOnIdle { width = 480.dp }
    waitForIdle()
    val repo = onNodeWithText("readian-web", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
    onAllNodesWithText("now", useUnmergedTree = true).fetchSemanticsNodes().forEach { assertTrue(repo.right < it.boundsInRoot.left) }
    onNodeWithTag("Home sections").saveCapture("home-sections-narrow.png")
  }

  private fun SemanticsNodeInteraction.saveCapture(name: String) {
    val directory = System.getenv("SKILL512_EVIDENCE_DIR")?.let(::File) ?: return
    directory.mkdirs()
    ImageIO.write(captureToImage().toAwtImage(), "png", File(directory, name))
  }

  private fun chat(title: String, root: String): ChatHistoryEntry = ChatHistoryEntry(
    sessionId = title,
    executionId = null,
    agent = requireNotNull(FakeExecutionChatRepository().agent),
    updatedAt = now,
    title = title,
    lines = listOf(ChatHistoryLine(ChatHistoryRole.Operator, title)),
    newTask = ChatNewTaskContext(root, "main"),
  )
}
