package dev.skillbill.desktop.core.designsystem.detail

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.runComposeUiTest
import dev.skillbill.desktop.core.designsystem.SkillBillTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class PhaseTabsTest {
  @Test
  fun `key movement skips inert tabs wraps and follows selection with focus`() = runComposeUiTest {
    val selected = mutableStateOf("validate")
    val tabs = listOf(
      PhaseTab("preplan", "preplan", PipelineStepState.Done, true),
      PhaseTab("plan", "plan", PipelineStepState.Done, true),
      PhaseTab("implement", "implement", PipelineStepState.Done, true),
      PhaseTab("review", "review", PipelineStepState.Skipped, true),
      PhaseTab("validate", "validate", PipelineStepState.Waiting, true),
      PhaseTab("write_history", "write_history", PipelineStepState.Pending, false),
      PhaseTab("commit_push", "commit_push", PipelineStepState.Pending, false),
    )
    setContent {
      SkillBillTheme(reducedMotion = true) { PhaseTabs(tabs, selected.value, { selected.value = it }) }
    }
    onNodeWithText("validate").performClick()
    onNodeWithText("validate").performKeyInput { pressKey(Key.MoveHome) }
    assertEquals("preplan", selected.value)
    onNodeWithText("preplan").assertIsFocused()
    onNodeWithText("preplan").performKeyInput { pressKey(Key.DirectionLeft) }
    assertEquals("validate", selected.value)
    onNodeWithText("validate").performKeyInput { pressKey(Key.MoveEnd) }
    assertEquals("validate", selected.value)
    onNodeWithText("validate").performKeyInput { pressKey(Key.DirectionRight) }
    assertEquals("preplan", selected.value)
    onNodeWithText("write_history").assertIsNotEnabled()
  }
}
