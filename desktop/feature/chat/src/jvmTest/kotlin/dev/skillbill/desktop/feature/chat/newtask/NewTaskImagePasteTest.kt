package dev.skillbill.desktop.feature.chat.newtask

import dev.skillbill.desktop.core.testing.RecordingDiagnostics
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.test.withKeyDown
import dev.skillbill.desktop.core.designsystem.SkillBillTheme
import dev.skillbill.desktop.core.testing.FakeExecutionChatRepository
import dev.skillbill.desktop.core.testing.FakeWorkControlRepository
import dev.skillbill.desktop.core.ui.feedback.ToastQueue
import dev.skillbill.desktop.core.ui.input.LocalShellInput
import dev.skillbill.desktop.core.ui.input.ShellInput
import dev.skillbill.desktop.core.ui.navigation.WindowCoroutineScope
import dev.skillbill.desktop.feature.chat.ChatMessage
import dev.skillbill.desktop.feature.chat.pasteUsesMeta
import dev.skillbill.desktop.feature.chat.TextPasteClipboard
import java.awt.image.BufferedImage
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

@OptIn(ExperimentalTestApi::class, ExperimentalCoroutinesApi::class)
class NewTaskImagePasteTest {
  @Test
  fun `pasted screenshots can be previewed removed and sent without text to the new task agent`() = runTest {
    val repository = FakeExecutionChatRepository()
    val draft = NewTaskDraft(
      repository, FakeWorkControlRepository(), testDatabaseSessionController(), ToastQueue(),
      WindowCoroutineScope(backgroundScope), Clock.System, diagnostics = RecordingDiagnostics(),
    ).apply {
      chatDispatcher = StandardTestDispatcher(testScheduler)
      this.repository = NewTaskRepository("/work/app", "app")
      agentId = "claude"
      selectedAgent = repository.agent
    }
    var pasted: BufferedImage? = BufferedImage(10, 10, BufferedImage.TYPE_INT_ARGB)
    runComposeUiTest {
      setContent {
        SkillBillTheme(reducedMotion = true) {
          CompositionLocalProvider(LocalShellInput provides ShellInput(), LocalClipboard provides TextPasteClipboard("copied text")) {
            NewTaskComposer(
              NewTaskUiState(agent = repository.agent), draft,
              clipboard = { pasted }, encodeContext = EmptyCoroutineContext,
            ) { action ->
              when (action) {
                NewTaskAction.Send -> draft.send()
                is NewTaskAction.TextChanged -> draft.text = action.text
                else -> Unit
              }
            }
          }
        }
      }
      val field = onNode(hasSetTextAction())
      val modifier = if (pasteUsesMeta) Key.MetaLeft else Key.CtrlLeft
      field.performKeyInput { withKeyDown(modifier) { pressKey(Key.V) } }
      onNodeWithText("[Image #1]").performClick()
      onNodeWithContentDescription("[Image #1]").assertExists()
      field.performKeyInput { pressKey(Key.Escape) }
      onNodeWithContentDescription("Remove image 1").performClick()
      field.assertIsFocused()
      onNodeWithText("[Image #1]").assertDoesNotExist()
      field.performKeyInput { withKeyDown(modifier) { pressKey(Key.V) } }
      onNodeWithText("Send", substring = false).assertIsEnabled()
      field.performKeyInput { pressKey(Key.Enter) }
      runOnIdle {
        assertTrue(draft.images.isEmpty())
        assertEquals(1, draft.chat.value!!.thread.transcript.value.filterIsInstance<ChatMessage.Operator>().single().images.size)
        pasted = null
        testScheduler.advanceUntilIdle()
      }
      field.performClick()
      field.assertIsFocused()
      field.performKeyInput { withKeyDown(modifier) { pressKey(Key.V) } }
      runOnIdle { assertEquals("copied text", draft.text) }
    }
    runCurrent()
    val request = repository.requests.single()
    assertEquals("", request.prompt)
    assertEquals("image/png", request.images.single().mediaType)
    assertEquals("[Image #1]", repository.saved.values.single().lines.first().text)
    draft.close()
  }
}
