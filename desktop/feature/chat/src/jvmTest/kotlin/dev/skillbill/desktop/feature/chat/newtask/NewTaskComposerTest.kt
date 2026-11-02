package dev.skillbill.desktop.feature.chat.newtask

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.runComposeUiTest
import dev.skillbill.desktop.core.designsystem.SkillBillTheme
import dev.skillbill.desktop.core.ui.input.LocalShellInput
import dev.skillbill.desktop.core.ui.input.ShellInput
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

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
          NewTaskComposer(NewTaskUiState(), NewTaskDraft()) {}
        }
      }
    }

    onNode(hasSetTextAction()).assertIsFocused()
    assertTrue(input.textInputFocused)

    runOnIdle { focusManager.clearFocus(force = true) }
    waitForIdle()
    assertFalse(input.textInputFocused)
  }
}
