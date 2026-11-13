package dev.skillbill.desktop.core.designsystem.detail

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.runComposeUiTest
import dev.skillbill.desktop.core.designsystem.SkillBillTheme
import kotlin.test.Test
import kotlin.test.assertEquals

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

  @Test
  fun `folding keeps pill focus and keyboard navigation opens the already selected target`() = runComposeUiTest {
    val selected = mutableStateOf<String?>("review")
    val navigated = mutableStateOf("")
    val tabs = listOf(
      PhaseTab("review", "review", PipelineStepState.Done, true),
      PhaseTab("validate", "validate", PipelineStepState.Done, true),
    )
    setContent {
      SkillBillTheme(reducedMotion = true) {
        PhaseTabs(
          tabs,
          selected.value,
          onActivate = { selected.value = if (selected.value == it) null else it },
          onNavigate = { navigated.value = it; selected.value = it },
        )
      }
    }
    onNodeWithText("review").assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Expanded"))
    onNodeWithText("review").performClick()
    assertEquals(null, selected.value)
    onNodeWithText("review").assertIsFocused()
    onNodeWithText("review").assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Collapsed"))
    onNodeWithText("review").performKeyInput { pressKey(Key.DirectionRight) }
    assertEquals("validate", navigated.value)
    assertEquals("validate", selected.value)
    onNodeWithText("validate").assertIsFocused()
    navigated.value = ""
    onNodeWithText("validate").performKeyInput { pressKey(Key.MoveEnd) }
    assertEquals("validate", navigated.value)
    assertEquals("validate", selected.value)
  }

  @Test
  fun `home and end on the only openable tab stay on that tab`() {
    val tabs = listOf(
      PhaseTab("review", "review", PipelineStepState.Done, true),
      PhaseTab("validate", "validate", PipelineStepState.Pending, false),
    )
    assertEquals("review", phaseTabMove(tabs, "review", Key.MoveHome))
    assertEquals("review", phaseTabMove(tabs, "review", Key.MoveEnd))
  }

  @Test
  fun `tab stop follows focused then selected then the first newly openable pill`() {
    val closed = PhaseTab("preplan", "preplan", PipelineStepState.Done, false)
    val open = PhaseTab("preplan", "preplan", PipelineStepState.Done, true)
    val plan = PhaseTab("plan", "plan", PipelineStepState.Pending, false)
    val review = PhaseTab("review", "review", PipelineStepState.Done, true)
    assertEquals(null, phaseTabStop(listOf(closed, plan), null, null))
    assertEquals("preplan", phaseTabStop(listOf(open, plan), null, null))
    assertEquals("preplan", phaseTabStop(listOf(open, review), "preplan", "review"))
    assertEquals("review", phaseTabStop(listOf(open, review), "missing", "review"))
  }

  @Test
  fun `a pill that becomes openable under the same ids takes the tab stop`() = runComposeUiTest {
    val openable = mutableStateOf(false)
    setContent {
      SkillBillTheme(reducedMotion = true) {
        PhaseTabs(
          listOf(PhaseTab("preplan", "preplan", PipelineStepState.Done, openable.value), PhaseTab("plan", "plan", PipelineStepState.Pending, false)),
          selectedId = null,
          onActivate = {},
        )
      }
    }
    runOnIdle { openable.value = true }
    onNodeWithText("preplan").performSemanticsAction(SemanticsActions.RequestFocus)
    onNodeWithText("preplan").assertIsFocused()
  }
}
