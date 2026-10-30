package dev.skillbill.desktop.feature.detail

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import dev.skillbill.desktop.core.designsystem.SkillBillTheme
import dev.skillbill.desktop.core.domain.model.GoalAssessmentFinding
import dev.skillbill.desktop.core.domain.model.GoalAssessmentFindingState
import dev.skillbill.desktop.core.domain.model.GoalAssessmentResult
import dev.skillbill.desktop.core.domain.model.GoalAssessmentState
import dev.skillbill.desktop.core.domain.model.GoalAssessmentVerification
import dev.skillbill.desktop.core.domain.model.GoalQaFinding
import dev.skillbill.desktop.core.domain.model.GoalQaResult
import dev.skillbill.desktop.core.domain.model.WorkSubtask
import dev.skillbill.desktop.core.testing.workItem
import dev.skillbill.desktop.core.ui.input.LocalShellInput
import dev.skillbill.desktop.core.ui.input.ShellInput
import kotlin.test.assertEquals
import kotlin.test.Test
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class DetailPeekTest {
  @Test
  fun `goal finish findings open in the peek and keep their ordering when reopened`() = runComposeUiTest {
    val detail = emptyDetail.copy(
      subtasks = (1..12).map { WorkSubtask(it, "Build the view $it", "completed", null, null, emptyList()) },
      goalFinishPipeline = listOf("goal_assessment", "qa"),
      goalAssessment = GoalAssessmentResult(
        GoalAssessmentState.Completed, 3, null, false,
        listOf(
          GoalAssessmentFinding("GA-1", "minor", "View.kt", 10, "An action has no label", GoalAssessmentVerification.Confirmed, GoalAssessmentFindingState.Open, ""),
          GoalAssessmentFinding("GA-2", "major", "View.kt", 20, "A completed run loses its findings", GoalAssessmentVerification.Confirmed, GoalAssessmentFindingState.Open, ""),
        ),
      ),
      goalQa = GoalQaResult("findings", null, 0, epoch, listOf(GoalQaFinding("QA-1", "The drawer covers its close button", null, false)), "Verdict: skipped\n\n## Coverage\n\n- No supported target\n- No application exercised", "unsupported_target"),
    )
    val state = mutableStateOf(DetailUiState(
      workItem("goal"), SectionsUi.Loaded(detail, null, emptyMap(), emptySet()), false, epoch,
    ))
    val open = mutableStateOf(false)
    val input = ShellInput()
    setContent {
      SkillBillTheme(reducedMotion = true) {
        CompositionLocalProvider(LocalShellInput provides input) {
          DetailScreen(state.value, DetailScreenHost(open.value, "Ctrl I", { open.value = !open.value })) { action ->
            if (action is DetailAction.SelectSubtask) {
              state.value = state.value.copy(subtaskChoice = SubtaskChoice(action.key))
              open.value = true
            }
          }
        }
      }
    }

    onNodeWithText("Goal assessment").assertDoesNotExist()
    val subtask = onNode(hasText("Build the view 2") and hasClickAction()).performScrollTo()
    val bounds = subtask.fetchSemanticsNode().boundsInRoot
    onRoot().performMouseInput { moveTo(bounds.center); press() }
    waitForIdle()
    onRoot().performMouseInput { release() }
    waitForIdle()
    runOnIdle {
      assertEquals(SubtaskKey.Subtask(2), state.value.subtaskChoice.key)
      assertTrue(open.value)
    }
    assertEquals(bounds.top, subtask.fetchSemanticsNode().boundsInRoot.top)
    onNodeWithContentDescription("Close details").performClick()
    onNodeWithText("Goal finish").performScrollTo().performClick()
    onNodeWithText("Goal assessment").assertExists()
    onNodeWithText("Priority").performClick()
    onNodeWithText("Priority").assertIsSelected()
    onNodeWithText("QA").performClick()
    onNodeWithText("Coverage").assertExists()
    onNodeWithText("## Coverage").assertDoesNotExist()
    onNodeWithText("Assessment").performClick()
    onNodeWithText("Priority").assertIsSelected()
    onNodeWithContentDescription("Close details").performClick()
    onNodeWithText("Goal assessment").assertDoesNotExist()
    onNodeWithText("Goal finish").performScrollTo().performClick()
    onNodeWithText("Priority").assertIsSelected()
    runOnIdle { assertTrue(input.dismissTopLayer()) }
    onNodeWithText("Goal assessment").assertDoesNotExist()
    onNodeWithText("Build the view 1").performScrollTo().performClick()
    onNodeWithText("Goal assessment").assertDoesNotExist()
    onNodeWithContentDescription("Close details").assertExists()
  }
}
