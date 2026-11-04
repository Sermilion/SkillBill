package dev.skillbill.desktop.feature.chat

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.Clipboard
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextInputSelection
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.test.withKeyDown
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
import dev.skillbill.desktop.feature.chat.newtask.NewTaskDraft
import dev.skillbill.desktop.feature.chat.newtask.testDatabaseSessionController
import dev.skillbill.desktop.feature.chat.newtask.testNewTaskDraft
import kotlinx.coroutines.test.runTest
import java.awt.image.BufferedImage
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock

@OptIn(ExperimentalTestApi::class)
class ChatComposerTest {
  @Test
  fun `reopening a busy chat focuses its draft and Enter queues without waiting for agent preparation`() = runTest {
    val workspace = ChatWorkspace(
      FakeExecutionChatRepository(), FakeWorkDetailRepository(), FakeWorkControlRepository(), testDatabaseSessionController(),
      WindowCoroutineScope(backgroundScope), Clock.System, ShellPanes(), SelectionHolder(),
      testNewTaskDraft(),
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

  @Test
  fun `stop shows only while stoppable and send now label when draft is empty with a queue`() = runTest {
    val workspace = ChatWorkspace(
      FakeExecutionChatRepository(), FakeWorkDetailRepository(), FakeWorkControlRepository(), testDatabaseSessionController(),
      WindowCoroutineScope(backgroundScope), Clock.System, ShellPanes(), SelectionHolder(),
      testNewTaskDraft(),
    )
    val item = workItem("SKILL-469")
    workspace.open(item)
    val session = workspace.session(item.id)!!
    session.agentState.value = ChatAgent("claude", null, null)
    session.agentSupportedState.value = true
    session.stoppableState.value = false
    runComposeUiTest {
      setContent {
        SkillBillTheme(reducedMotion = true) {
          CompositionLocalProvider(LocalShellInput provides ShellInput()) {
            ChatComposer(session, workspace)
          }
        }
      }
      onNodeWithText("Stop", substring = false).assertDoesNotExist()
      runOnIdle {
        session.busyState.value = true
        session.stoppableState.value = true
      }
      onNodeWithText("Stop", substring = false).assertExists()
      onNodeWithText("Queue", substring = false).assertExists()
      runOnIdle {
        session.queuedState.value = listOf(QueuedChatMessage("queued", null, pauseAndAct = false))
        workspace.drafts[item.id] = ""
      }
      onNodeWithText("Send now", substring = false).assertExists()
      runOnIdle { session.stoppableState.value = false }
      onNodeWithText("Stop", substring = false).assertDoesNotExist()
    }
  }

  @Test
  fun `pasting an image adds a numbered chip, removing it empties the row, and sending clears pending images`() = runTest {
    val workspace = ChatWorkspace(
      FakeExecutionChatRepository(supported = setOf("claude")), FakeWorkDetailRepository(), FakeWorkControlRepository(), testDatabaseSessionController(),
      WindowCoroutineScope(backgroundScope), Clock.System, ShellPanes(), SelectionHolder(),
      testNewTaskDraft(),
    )
    val item = workItem("SKILL-450")
    workspace.open(item)
    val session = workspace.session(item.id)!!
    session.agentState.value = ChatAgent("claude", null, null)
    session.agentSupportedState.value = true
    var pasted: BufferedImage? = BufferedImage(10, 10, BufferedImage.TYPE_INT_ARGB)
    runComposeUiTest {
      setContent {
        SkillBillTheme(reducedMotion = true) {
          // The no-image paste falls through to text paste; an empty fake keeps it off the host clipboard.
          CompositionLocalProvider(LocalShellInput provides ShellInput(), LocalClipboard provides EmptyClipboard) {
            // Encoding on the test's own dispatcher lets waitForIdle observe the chip with no real-time polling.
            ChatComposer(session, workspace, clipboard = { pasted }, encodeContext = EmptyCoroutineContext)
          }
        }
      }
      val field = onNode(hasSetTextAction())
      val paste = Key.V
      val modifier = if (pasteUsesMeta) Key.MetaLeft else Key.CtrlLeft
      field.performKeyInput { withKeyDown(modifier) { pressKey(paste) } }
      onNodeWithText("[Image #1]").assertExists()
      onNodeWithContentDescription("Remove image 1").performClick()
      onNodeWithText("[Image #1]").assertDoesNotExist()

      field.performKeyInput { withKeyDown(modifier) { pressKey(paste) } }
      onNodeWithText("[Image #1]").assertExists()
      field.performKeyInput { pressKey(Key.Enter) }
      runOnIdle {
        assertNull(workspace.pastedImages[item.id])
        val sent = session.queued.value.map { it.images } + session.transcript.value.filterIsInstance<ChatMessage.Operator>().map { it.images }
        assertEquals(1, sent.single().size)
      }

      pasted = null
      field.performTextInput("typed")
      field.performKeyInput { withKeyDown(modifier) { pressKey(paste) } }
      onNodeWithText("[Image #1]").assertDoesNotExist()
      runOnIdle { assertEquals("typed", workspace.drafts[item.id]) }
    }
  }
}

private object EmptyClipboard : Clipboard {
  override val nativeClipboard = java.awt.datatransfer.Clipboard("empty")

  override suspend fun getClipEntry(): ClipEntry? = null

  override suspend fun setClipEntry(clipEntry: ClipEntry?) = Unit
}
