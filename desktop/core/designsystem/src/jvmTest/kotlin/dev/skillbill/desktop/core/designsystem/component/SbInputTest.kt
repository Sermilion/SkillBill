package dev.skillbill.desktop.core.designsystem.component

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextInputSelection
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.test.withKeyDown
import androidx.compose.ui.text.TextRange
import dev.skillbill.desktop.core.designsystem.SkillBillTheme
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class SbInputTest {
  @Test
  fun `read only stays enabled and multiline caller receives submit shortcut`() = runComposeUiTest {
    var readOnlyChanged: String? = null
    var multilineValue by mutableStateOf("abc")
    var callerReceivedShortcut = false
    setContent {
      SkillBillTheme(reducedMotion = true) {
        Column {
          SbInput("abc", { readOnlyChanged = it }, Modifier.testTag("readOnly"), readOnly = true)
          SbInput(
            multilineValue,
            { multilineValue = it },
            Modifier.testTag("multiline").onPreviewKeyEvent { event ->
              if (event.key == Key.Enter && event.isCtrlPressed) {
                callerReceivedShortcut = true
                true
              } else false
            },
            multiline = true,
            onSubmit = null,
          )
        }
      }
    }

    val readOnly = onNodeWithTag("readOnly").performClick().assertIsEnabled().assertIsFocused()
    readOnly.performTextInputSelection(TextRange(1))
    readOnly.performKeyInput { pressKey(Key.Backspace) }
    runOnIdle { assertEquals(null, readOnlyChanged) }
    readOnly.assertTextEquals("abc")

    val multiline = onNodeWithTag("multiline").performClick().assertIsFocused()
    multiline.performTextInputSelection(TextRange(3))
    multiline.performKeyInput { pressKey(Key.Enter) }
    runOnIdle {
      assertEquals("abc\n", multilineValue)
      assertEquals(false, callerReceivedShortcut)
    }
    multiline.assertTextEquals("abc\n")
    multiline.performKeyInput { withKeyDown(Key.CtrlLeft) { pressKey(Key.Enter) } }
    runOnIdle { assertEquals(true, callerReceivedShortcut) }
    multiline.assertTextEquals("abc\n")
  }
}
