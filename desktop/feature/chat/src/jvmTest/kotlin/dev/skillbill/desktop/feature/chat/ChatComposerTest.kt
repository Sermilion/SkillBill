package dev.skillbill.desktop.feature.chat

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.click
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextInputSelection
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.text.TextRange
import dev.skillbill.desktop.core.designsystem.SkillBillTheme
import dev.skillbill.desktop.core.domain.model.ChatAgent
import dev.skillbill.desktop.core.domain.model.ChatEvent
import dev.skillbill.desktop.core.domain.model.ChatToolCall
import dev.skillbill.desktop.core.testing.FakeExecutionChatRepository
import dev.skillbill.desktop.core.testing.FakeWorkControlRepository
import dev.skillbill.desktop.core.testing.FakeWorkDetailRepository
import dev.skillbill.desktop.core.testing.workItem
import dev.skillbill.desktop.core.ui.input.LocalShellInput
import dev.skillbill.desktop.core.ui.input.ShellInput
import dev.skillbill.desktop.core.ui.navigation.WindowCoroutineScope
import dev.skillbill.desktop.core.ui.selection.SelectionHolder
import dev.skillbill.desktop.core.ui.shell.ShellPanes
import dev.skillbill.desktop.core.ui.shell.FloatingPane
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Clock

@OptIn(ExperimentalTestApi::class)
class ChatComposerTest {
  @Test
  fun `reopening a busy chat focuses its draft and Enter queues without waiting for agent preparation`() = runTest {
    val workspace = ChatWorkspace(
      FakeExecutionChatRepository(), FakeWorkDetailRepository(), FakeWorkControlRepository(),
      WindowCoroutineScope(backgroundScope), Clock.System, ShellPanes(), SelectionHolder(),
    )
    val item = workItem("SKILL-1")
    workspace.open(item)
    val session = workspace.session(item.id)!!
    val open = mutableStateOf(true)
    val input = ShellInput()
    lateinit var focusManager: FocusManager
    runComposeUiTest {
      setContent {
        SkillBillTheme(reducedMotion = true) {
          CompositionLocalProvider(LocalShellInput provides input) {
            focusManager = LocalFocusManager.current
            FloatingPane(open.value, "Chat", onDismiss = { open.value = false }) {
              ChatComposer(session, workspace)
            }
          }
        }
      }
      val field = onNode(hasSetTextAction())
      onNodeWithText("Preparing chat...").assertExists()
      val bounds = field.fetchSemanticsNode().boundsInRoot
      for (x in listOf(bounds.left + 2f, bounds.center.x, bounds.right - 2f)) {
        runOnIdle { focusManager.clearFocus(force = true) }
        onRoot().performMouseInput { click(Offset(x, bounds.center.y)) }
        field.assertIsFocused()
      }
      field.assertIsEnabled().assertIsFocused().performTextInput("first question")
      runOnIdle {
        assertTrue(input.textInputFocused)
        open.value = false
      }
      waitForIdle()
      runOnIdle { open.value = true }
      field.assertIsFocused().assertTextEquals("first question").performTextInputSelection(TextRange("first question".length))
      field.performTextInput(" again")
      field.performKeyInput { keyDown(Key.Enter); keyUp(Key.Enter) }
      field.assertIsFocused().performTextInput("next draft")
      onNodeWithText("1 message queued").assertExists()
      runOnIdle {
        assertEquals(listOf("first question again"), session.queued.value.map { it.text })
        assertEquals("next draft", workspace.drafts[item.id])
      }
      onNodeWithText("Queue", substring = false).performClick()
      field.assertIsFocused().performTextInput("keep typing")
      onNodeWithText("2 messages queued").assertExists()
      runOnIdle { assertEquals("keep typing", workspace.drafts[item.id]) }
      runOnIdle { session.agentState.value = ChatAgent("codex", null, null) }
      onNodeWithText("Thinking...").assertExists()
      runOnIdle { session.appendDelta("Answer") }
      onNodeWithText("Responding...").assertExists()
      val edit = ChatToolCall("edit", "edit", "View.kt", null)
      runOnIdle { session.appendTool(edit) }
      onNodeWithText("Editing files...").assertExists()
      runOnIdle { session.recordToolResult(ChatEvent.ToolResult(edit.id, "done", false)) }
      onNodeWithText("Working on changes...").assertExists()
      runOnIdle {
        session.endStreaming()
        session.busyState.value = false
      }
      onNodeWithText("Ready").assertExists()
      onNodeWithText("Thinking...").assertDoesNotExist()
    }
  }
}
