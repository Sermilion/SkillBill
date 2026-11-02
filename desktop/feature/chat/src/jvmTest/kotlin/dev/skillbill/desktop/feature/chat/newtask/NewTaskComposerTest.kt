package dev.skillbill.desktop.feature.chat.newtask

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runComposeUiTest
import dev.skillbill.desktop.core.designsystem.SkillBillTheme
import dev.skillbill.desktop.core.domain.model.ChatEvent
import dev.skillbill.desktop.core.domain.model.ChatToolCall
import dev.skillbill.desktop.core.testing.FakeExecutionChatRepository
import dev.skillbill.desktop.core.testing.FakeWorkControlRepository
import dev.skillbill.desktop.core.ui.feedback.ToastQueue
import dev.skillbill.desktop.core.ui.input.LocalShellInput
import dev.skillbill.desktop.core.ui.input.ShellInput
import dev.skillbill.desktop.core.ui.navigation.WindowCoroutineScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.flow.takeWhile
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Clock

@OptIn(ExperimentalTestApi::class)
class NewTaskComposerTest {
  @Test
  fun `opening focuses the input and clearing focus releases the single-key shortcuts`() = runComposeUiTest {
    val input = ShellInput()
    lateinit var focusManager: FocusManager
    setContent {
      SkillBillTheme(reducedMotion = true) {
        CompositionLocalProvider(LocalShellInput provides input) {
          focusManager = LocalFocusManager.current
          NewTaskComposer(NewTaskUiState(), testNewTaskDraft()) {}
        }
      }
    }

    onNode(hasSetTextAction()).assertIsFocused()
    assertTrue(input.textInputFocused)

    runOnIdle { focusManager.clearFocus(force = true) }
    waitForIdle()
    assertFalse(input.textInputFocused)
  }

  @Test
  fun `the chat shows the same activity states as a run chat and the home screen shows none`() = runComposeUiTest {
    val chat = FakeExecutionChatRepository()
    val events = Channel<ChatEvent>(Channel.UNLIMITED)
    chat.events = { events.consumeAsFlow().takeWhile { it != ChatEvent.Completed } }
    val draft = NewTaskDraft(
      chat, FakeWorkControlRepository(), testDatabaseSessionController(), ToastQueue(),
      WindowCoroutineScope(CoroutineScope(Dispatchers.Unconfined)), Clock.System,
    ).apply {
      chatDispatcher = Dispatchers.Unconfined
      repository = NewTaskRepository(
        createTempDirectory("new-task-repo").also { it.toFile().deleteOnExit() }.toString(),
        "repo",
      )
      agentId = "claude"
      selectedAgent = chat.agent
      text = "Add a button"
    }
    setContent {
      SkillBillTheme(reducedMotion = true) {
        CompositionLocalProvider(LocalShellInput provides ShellInput()) {
          NewTaskComposer(NewTaskUiState(), draft) {}
        }
      }
    }
    fun send(event: ChatEvent) {
      events.trySend(event)
      waitForIdle()
    }

    onNodeWithText("Ready").assertDoesNotExist()

    runOnIdle { draft.send() }
    waitForIdle()
    onNodeWithText("Thinking...").assertExists()

    val read = ChatToolCall("read", "Read", "View.kt", "Read the view")
    send(ChatEvent.ToolUse(read))
    onNodeWithText("Reading files...").assertExists()

    send(ChatEvent.ToolResult(read.id, "contents", false))
    send(ChatEvent.TextDelta("Done."))
    onNodeWithText("Responding...").assertExists()

    send(ChatEvent.Completed)
    waitUntil { draft.chat.value?.thread?.busy?.value == false }
    onNodeWithText("Ready").assertExists()
  }
}
