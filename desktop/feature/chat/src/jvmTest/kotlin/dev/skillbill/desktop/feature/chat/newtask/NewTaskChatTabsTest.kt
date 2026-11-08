package dev.skillbill.desktop.feature.chat.newtask

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.rightClick
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import dev.skillbill.desktop.core.designsystem.SkillBillTheme
import dev.skillbill.desktop.core.designsystem.navigation.LocalSidebarToggle
import dev.skillbill.desktop.core.ui.input.LocalShellInput
import dev.skillbill.desktop.core.ui.input.ShellInput
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.TestScope
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class NewTaskChatTabsTest {
  @Test
  fun `keyboard opens overflow and repository filtering selects a chat`() = runComposeUiTest {
    val scope = TestScope()
    try {
      val chats = scope.testNewTaskChats()
      val drafts = buildList {
        add(chats.selected.value)
        repeat(19) { add(chats.newChat()) }
      }
      drafts.forEachIndexed { index, draft -> draft.repository = NewTaskRepository("/repo-$index", "repo-$index") }
      val items = drafts.map { OpenChatItem(it, "New chat", it.repository?.name, "Claude", NewTaskChatStatus.Idle) }
      setContent {
        SkillBillTheme(reducedMotion = true) {
          CompositionLocalProvider(LocalShellInput provides ShellInput()) {
            Box(Modifier.width(400.dp)) { NewTaskChatTabs(items, chats.selected.value, chats::select, chats::requestClose, chats::discard, chats::newChat) }
          }
        }
      }
      waitForIdle()

      val chevron = onNodeWithContentDescription("All 20 open chats").getBoundsInRoot()
      assertEquals(392f, chevron.right.value, 0.5f)
      assertTrue(onNodeWithContentDescription("New chat").getBoundsInRoot().right < chevron.left)
      onNodeWithContentDescription("New chat").performSemanticsAction(SemanticsActions.RequestFocus) { it() }
      onNodeWithContentDescription("New chat").performKeyInput {
        pressKey(Key.Tab)
        pressKey(Key.Enter)
      }
      onNodeWithText("Filter open chats").assertExists()
      onNode(hasSetTextAction()).performTextInput("REPO-17")
      onNodeWithText("repo-17").performClick()
      waitForIdle()

      assertTrue(chats.selected.value === drafts[17])
      onNodeWithText("Filter open chats").assertDoesNotExist()
      onNodeWithContentDescription("All 20 open chats").assertIsFocused()
    } finally {
      scope.backgroundScope.cancel()
      scope.cancel()
    }
  }

  @Test
  fun `right-click and Shift+F10 open a tab menu that offers Discard only once the chat has a conversation`() = runComposeUiTest {
    val scope = TestScope()
    try {
      val chats = scope.testNewTaskChats()
      val drafts = listOf(chats.selected.value, chats.newChat())
      val items = listOf(
        OpenChatItem(drafts[0], "Fix footer", "repo-a", "Claude", NewTaskChatStatus.Idle, hasConversation = true),
        OpenChatItem(drafts[1], "Empty chat", "repo-b", "Claude", NewTaskChatStatus.Idle),
      )
      val discarded = mutableListOf<NewTaskDraft>()
      var toggles = 0
      setContent {
        SkillBillTheme(reducedMotion = true) {
          CompositionLocalProvider(LocalShellInput provides ShellInput(), LocalSidebarToggle provides { toggles++ }) {
            Box(Modifier.width(1200.dp)) { NewTaskChatTabs(items, chats.selected.value, chats::select, chats::requestClose, { discarded += it }, chats::newChat) }
          }
        }
      }
      waitForIdle()

      onNodeWithContentDescription("Toggle sidebar").performClick()
      assertEquals(1, toggles)

      onNodeWithText("Fix footer").performSemanticsAction(SemanticsActions.RequestFocus) { it() }
      onNodeWithText("Fix footer").performKeyInput {
        keyDown(Key.ShiftLeft)
        pressKey(Key.F10)
        keyUp(Key.ShiftLeft)
      }
      onNodeWithText("Discard").performClick()
      waitForIdle()
      assertEquals(listOf(drafts[0]), discarded)
      onNodeWithText("Discard").assertDoesNotExist()

      onNodeWithText("Empty chat").performMouseInput { rightClick() }
      onNodeWithText("Close").assertExists()
      onNodeWithText("Discard").assertDoesNotExist()
    } finally {
      scope.backgroundScope.cancel()
      scope.cancel()
    }
  }

  @Test
  fun `overflow control is absent when tabs fit`() = runComposeUiTest {
    val scope = TestScope()
    try {
      val chats = scope.testNewTaskChats()
      val drafts = listOf(chats.selected.value, chats.newChat(), chats.newChat())
      val items = drafts.mapIndexed { index, draft -> OpenChatItem(draft, "Chat $index", "repo-$index", "Claude", NewTaskChatStatus.Idle) }
      setContent {
        SkillBillTheme(reducedMotion = true) {
          CompositionLocalProvider(LocalShellInput provides ShellInput()) {
            Box(Modifier.width(1200.dp)) { NewTaskChatTabs(items, chats.selected.value, chats::select, chats::requestClose, chats::discard, chats::newChat) }
          }
        }
      }
      waitForIdle()
      onNodeWithContentDescription("All 3 open chats").assertDoesNotExist()
      val lastTab = onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab))[2].getBoundsInRoot()
      val plus = onNodeWithContentDescription("New chat").getBoundsInRoot()
      assertEquals(lastTab.right.value + 2f, plus.left.value, 0.5f, "New chat sits one gap after the last tab")
      // Three 196dp tabs after the 8dp padding end at 600dp, so "+" spans 602..626dp; a weight(1f) tab row would push it to the strip's right edge.
      assertTrue(plus.right.value < 700f, "New chat must not be pushed to the far right")
    } finally {
      scope.backgroundScope.cancel()
      scope.cancel()
    }
  }

  @Test
  fun `close control ends 4dp inside a short tab when selected or hovered`() = runComposeUiTest {
    val scope = TestScope()
    try {
      val chats = scope.testNewTaskChats()
      val first = chats.selected.value
      val drafts = listOf(first, chats.newChat())
      chats.select(first)
      val items = drafts.mapIndexed { index, draft -> OpenChatItem(draft, "Chat $index", "repo-$index", "Claude", NewTaskChatStatus.Idle) }
      setContent {
        SkillBillTheme(reducedMotion = true) {
          CompositionLocalProvider(LocalShellInput provides ShellInput()) {
            Box(Modifier.width(1200.dp)) { NewTaskChatTabs(items, chats.selected.value, chats::select, chats::requestClose, chats::discard, chats::newChat) }
          }
        }
      }
      waitForIdle()
      val tabs = onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab))
      val selected = tabs[0].getBoundsInRoot()
      val close = onNodeWithContentDescription("Close Chat 0", useUnmergedTree = true).getBoundsInRoot()
      assertEquals(20f, close.right.value - close.left.value, 0.5f)
      assertEquals(20f, close.bottom.value - close.top.value, 0.5f)
      assertEquals(selected.right.value - 4f, close.right.value, 0.5f, "Close trailing edge is the tab's 4dp end padding")
      onNodeWithContentDescription("Close Chat 1", useUnmergedTree = true).assertDoesNotExist()
      val hoveredTab = tabs[1]
      hoveredTab.performMouseInput { enter(center) }
      waitForIdle()
      val hovered = hoveredTab.getBoundsInRoot()
      val hoveredClose = onNodeWithContentDescription("Close Chat 1", useUnmergedTree = true).getBoundsInRoot()
      assertEquals(20f, hoveredClose.right.value - hoveredClose.left.value, 0.5f)
      assertEquals(20f, hoveredClose.bottom.value - hoveredClose.top.value, 0.5f)
      assertEquals(hovered.right.value - 4f, hoveredClose.right.value, 0.5f, "Hovered close trailing edge is the tab's 4dp end padding")
    } finally {
      scope.backgroundScope.cancel()
      scope.cancel()
    }
  }

  @Test
  fun `hidden activity follows tabs outside the viewport`() {
    val metrics = TabStripMetrics(tabWidth = 112f, overflow = true)
    val statuses = List(20) { if (it == 18) NewTaskChatStatus.Replying else NewTaskChatStatus.Idle }
    assertTrue(hiddenActivity(statuses, metrics, gap = 2f, scroll = 0, viewport = 400))
    assertFalse(hiddenActivity(statuses, metrics, gap = 2f, scroll = 18 * 114, viewport = 400))
  }
}
