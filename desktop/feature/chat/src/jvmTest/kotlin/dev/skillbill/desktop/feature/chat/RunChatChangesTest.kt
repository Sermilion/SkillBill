package dev.skillbill.desktop.feature.chat

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import dev.skillbill.desktop.core.designsystem.SkillBillTheme
import dev.skillbill.desktop.core.domain.model.ChatChanges
import dev.skillbill.desktop.core.domain.model.ChatChangesBaseline
import dev.skillbill.desktop.core.domain.model.ChatChangesRead
import dev.skillbill.desktop.core.domain.model.ChangeScopeTimeline
import dev.skillbill.desktop.core.domain.model.ChangeStatus
import dev.skillbill.desktop.core.domain.model.ChangedFile
import dev.skillbill.desktop.core.testing.FakeChatChangesRepository
import dev.skillbill.desktop.core.ui.input.LocalShellInput
import dev.skillbill.desktop.core.ui.input.ShellInput
import dev.skillbill.desktop.core.ui.changes.ChangesSource
import dev.skillbill.desktop.feature.chat.newtask.ChatChangesState
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.time.Instant

@OptIn(ExperimentalTestApi::class)
class RunChatChangesTest {
  private val baseline = ChatChangesBaseline("head", emptyList(), null, Instant.fromEpochMilliseconds(1))
  private val changed = ChatChanges(listOf(ChangedFile("run.kt", null, ChangeStatus.Modified, 3, 2)),
    3, 2, 0, true, "head", ChangeScopeTimeline("head", "head", emptyList()))

  @Test
  fun `run checkout refresh removes stale changes when git is unavailable and recovers`() = runTest {
    val repository = FakeChatChangesRepository(readResult = ChatChangesRead.Listed(changed))
    val states = mutableListOf<ChatChangesState>()
    val observer = backgroundScope.launch { runChatChanges(repository, "/work/run").collect { states += it } }
    runCurrent()
    assertEquals(3, assertIs<ChatChangesState.Shown>(states.last()).changes.insertions)

    repository.readResult = ChatChangesRead.Unavailable("Checkout removed")
    advanceTimeBy(1000)
    runCurrent()
    assertIs<ChatChangesState.None>(states.last())

    repository.readResult = ChatChangesRead.Listed(changed.copy(headSha = "new-head"))
    advanceTimeBy(1000)
    runCurrent()
    assertEquals("new-head", assertIs<ChatChangesState.Shown>(states.last()).baseline.headSha)

    repository.readResult = ChatChangesRead.Listed(changed.copy(files = emptyList(), insertions = 0, deletions = 0))
    advanceTimeBy(1000)
    runCurrent()
    assertIs<ChatChangesState.None>(states.last())
    assertEquals(setOf("/work/run"), repository.currentReadCalls.toSet())
    assertEquals(emptyList(), repository.captureCalls)
    observer.cancel()
  }

  @Test
  fun `pill opens the run checkout diff and selected file then disappears when clean`() = runComposeUiTest {
    val state = mutableStateOf<ChatChangesState>(ChatChangesState.Shown(changed, baseline, false, null))
    var opened: Pair<ChangesSource.Chat, String?>? = null
    setContent {
      CompositionLocalProvider(LocalShellInput provides remember { ShellInput() }) {
        SkillBillTheme(reducedMotion = true) {
          val focus = remember { FocusRequester() }
          Column {
            Box(Modifier.focusRequester(focus).focusable())
            RunChatChangesPill(state.value, "run-chat", "Run chat", "/work/run", focus) { source, path ->
              opened = source to path
            }
          }
        }
      }
    }
    onNodeWithText("1 file").performClick()
    onNodeWithContentDescription("run.kt").performClick()
    runOnIdle {
      assertEquals("/work/run", opened?.first?.target?.worktreeRoot)
      assertEquals("run.kt", opened?.second)
      assertEquals("chat:run-chat", opened?.first?.id)
      assertEquals(null, opened?.first?.target?.paths)
    }
    onNodeWithText("1 file").performClick()
    onNodeWithText("Open diff").performClick()
    runOnIdle { assertEquals(null, opened?.second) }
    runOnIdle { state.value = ChatChangesState.None }
    onNodeWithText("1 file").assertDoesNotExist()
    onNodeWithText("Open diff").assertDoesNotExist()
  }
}
