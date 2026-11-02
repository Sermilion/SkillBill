package dev.skillbill.desktop.core.designsystem.detail

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import dev.skillbill.desktop.core.designsystem.SkillBillTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class TerminalPaneChromeTest {
  @Test
  fun `long chat titles share the strip equally as more tabs open and keep controls visible`() = runComposeUiTest {
    val tabs = mutableStateOf(listOf(
      TerminalTabChrome("first", "Code review on branch feat/SKILL-455-" + "long-branch-name".repeat(12)),
      TerminalTabChrome("second", "Short"),
    ))
    var selected: String? = null
    var closed: String? = null
    setContent {
      SkillBillTheme(reducedMotion = true) {
        Box(Modifier.width(600.dp)) {
          TerminalPaneChrome(
            height = 300.dp, tabs = tabs.value, activeId = "first", renamingId = null,
            onSelectTab = { selected = it }, onStartRename = null, onRename = { _, _ -> },
            onCancelRename = {}, renameFieldGate = { _, _ -> Modifier }, onCloseTab = { closed = it },
            onNewTab = {}, onResizeBy = null, onClose = {}, noun = "chat", equalWidthTabs = true,
          ) {}
        }
      }
    }
    for (count in listOf(2, 4)) {
      if (count == 4) runOnIdle {
        tabs.value += listOf(TerminalTabChrome("third", "Another long title".repeat(10)), TerminalTabChrome("fourth", "Fourth"))
      }
      val nodes = onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.Selected)).fetchSemanticsNodes()
      assertEquals(count, nodes.size)
      val bounds = nodes.map { it.boundsInRoot }
      val newTab = onNodeWithContentDescription("New chat tab").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
      bounds.forEach {
        assertTrue(it.width > 0)
        assertEquals(bounds.first().width, it.width, 1f)
        assertTrue(it.right <= newTab.left)
      }
      val last = tabs.value.last()
      onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.Selected))[count - 1].performClick()
      runOnIdle { assertEquals(last.id, selected) }
      onNodeWithContentDescription("Close ${last.title} tab").assertIsDisplayed().performClick()
      runOnIdle { assertEquals(last.id, closed) }
      onNodeWithContentDescription("Close chat").assertIsDisplayed()
    }
  }
}
