package dev.skillbill.desktop.feature.chat.newtask

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasAnySibling
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import dev.skillbill.desktop.core.designsystem.SkillBillTheme
import dev.skillbill.desktop.core.ui.input.LocalShellInput
import dev.skillbill.desktop.core.ui.input.ShellInput
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.TestScope
import kotlin.test.Test
import kotlin.test.assertSame

@OptIn(ExperimentalTestApi::class)
class NewTaskChatListTest {
  @Test
  fun `side list counts statuses and selects a row`() = runComposeUiTest {
    val scope = TestScope()
    try {
      val chats = scope.testNewTaskChats()
      val drafts = listOf(chats.selected.value, chats.newChat(), chats.newChat(), chats.newChat())
      chats.select(drafts[0])
      val items = listOf(
        OpenChatItem(drafts[0], "Running chat", "repo-a", "Claude", NewTaskChatStatus.Replying),
        OpenChatItem(drafts[1], "Unread one", "repo-b", "Claude", NewTaskChatStatus.NewReply),
        OpenChatItem(drafts[2], "Unread two", "repo-c", "Claude", NewTaskChatStatus.NewReply),
        OpenChatItem(drafts[3], "Idle", "repo-d", "Claude", NewTaskChatStatus.Idle),
      )
      setContent {
        SkillBillTheme(reducedMotion = true) {
          CompositionLocalProvider(LocalShellInput provides ShellInput()) {
            NewTaskChatList(items, drafts[0], chats::select, chats::newChat, chats::requestClose)
          }
        }
      }
      waitForIdle()

      onNodeWithText("Replying").assertExists().assert(hasAnySibling(hasText("1")))
      onNodeWithText("New replies").assertExists().assert(hasAnySibling(hasText("2")))
      assertSame(drafts[0], chats.selected.value)
      onNodeWithText("Idle").performClick()

      assertSame(drafts[3], chats.selected.value)
    } finally {
      scope.backgroundScope.cancel()
      scope.cancel()
    }
  }
}
