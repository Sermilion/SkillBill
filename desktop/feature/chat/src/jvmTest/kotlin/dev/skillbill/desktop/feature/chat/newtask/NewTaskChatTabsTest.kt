package dev.skillbill.desktop.feature.chat.newtask

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import dev.skillbill.desktop.core.designsystem.SkillBillTheme
import dev.skillbill.desktop.core.ui.input.LocalShellInput
import dev.skillbill.desktop.core.ui.input.ShellInput
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.TestScope
import kotlin.test.Test
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
            Box(Modifier.width(400.dp)) { NewTaskChatTabs(items, chats.selected.value, chats::select, chats::requestClose, chats::newChat) }
          }
        }
      }
      waitForIdle()

      onNodeWithContentDescription("All 20 open chats").assertExists()
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
  fun `overflow control is absent when tabs fit`() = runComposeUiTest {
    val scope = TestScope()
    try {
      val chats = scope.testNewTaskChats()
      val drafts = listOf(chats.selected.value, chats.newChat(), chats.newChat())
      val items = drafts.mapIndexed { index, draft -> OpenChatItem(draft, "Chat $index", "repo-$index", "Claude", NewTaskChatStatus.Idle) }
      setContent {
        SkillBillTheme(reducedMotion = true) {
          CompositionLocalProvider(LocalShellInput provides ShellInput()) {
            Box(Modifier.width(1200.dp)) { NewTaskChatTabs(items, chats.selected.value, chats::select, chats::requestClose, chats::newChat) }
          }
        }
      }
      waitForIdle()
      onNodeWithContentDescription("All 3 open chats").assertDoesNotExist()
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
