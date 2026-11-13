package dev.skillbill.desktop.feature.detail

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import dev.skillbill.desktop.core.designsystem.SkillBillTheme
import dev.skillbill.desktop.core.designsystem.detail.rememberSectionCollapse
import dev.skillbill.desktop.core.domain.model.GoalAssessmentResult
import dev.skillbill.desktop.core.domain.model.GoalAssessmentState
import dev.skillbill.desktop.core.domain.model.QaRunDetails
import dev.skillbill.desktop.core.domain.model.QaRunOccurrence
import dev.skillbill.desktop.core.domain.model.QaRunStage
import dev.skillbill.desktop.core.domain.model.WorkKind
import dev.skillbill.desktop.core.domain.model.WorkSubtask
import dev.skillbill.desktop.core.ui.input.LocalShellInput
import dev.skillbill.desktop.core.ui.input.ShellInput
import dev.skillbill.desktop.core.ui.shell.LocalShellLayout
import dev.skillbill.desktop.core.ui.shell.ShellLayout
import dev.skillbill.desktop.core.testing.workItem
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class GoalFinishCardTest {
  @Test
  fun `changing QA rounds shows that rounds evidence and keeps stages folded until opened`() = runComposeUiTest {
    val history = QaRunDetails(historyAvailability = "recorded", occurrences = listOf(round("first"), round("retest")))
    setContent {
      SkillBillTheme(reducedMotion = true) {
        GoalQaHistory(history, rememberSectionCollapse()) {}
      }
    }
    onNodeWithText("Round 2").assertIsSelected()
    onNodeWithText("retest · completed").assertExists()
    onNodeWithText("retest stage · completed").assertDoesNotExist()
    onNodeWithText("Show").performClick()
    onNodeWithText("retest stage · completed").assertExists()
    onNodeWithText("Round 1").performClick()
    onNodeWithText("first · completed").assertExists()
    onNodeWithText("retest stage · completed").assertDoesNotExist()
    onNodeWithText("first stage · completed").assertDoesNotExist()
    onNodeWithText("Show").performClick()
    onNodeWithText("first stage · completed").assertExists()
  }

  @Test
  fun `unavailable assessment and absent QA render explicit missing results`() = runComposeUiTest {
    val assessment = mutableStateOf(true)
    setContent {
      SkillBillTheme(reducedMotion = true) {
        if (assessment.value) GoalAssessmentCard(
          finishCard("goal_assessment"),
          GoalAssessmentResult(GoalAssessmentState.Completed, 2, null, false, emptyList(), unavailableReason = "unreadable"),
          mutableStateOf(AssessmentOrder.Default),
        ) {}
        else GoalQaCard(workItem("goal"), emptyDetail, finishCard("qa"), DetailUiState(
          workItem("goal"), SectionsUi.Loaded(emptyDetail, null, emptyMap(), emptySet()), false, epoch,
        )) {}
      }
    }
    onNodeWithText("No assessment result is available for this run.").assertExists()
    runOnIdle { assessment.value = false }
    onNodeWithText("No QA result is available for this run.").assertExists()
  }

  @Test
  fun `goals use the wide details pane even before Goal finish`() {
    assertTrue(detailsPaneWide(workItem("goal")))
    assertFalse(detailsPaneWide(workItem("phase", kind = WorkKind.Phase)))
  }

  @Test
  fun `Goal finish keeps the details overlay available in drawer mode`() = runComposeUiTest {
    val layout = mutableStateOf(ShellLayout(drawerMode = true))
    val input = ShellInput()
    val detail = emptyDetail.copy(
      subtasks = listOf(WorkSubtask(1, "Build", "completed", null, null, emptyList())),
      goalFinishCards = listOf(finishCard("goal_assessment"), finishCard("qa")),
    )
    val state = mutableStateOf(DetailUiState(
      workItem("goal"), SectionsUi.Loaded(detail, null, emptyMap(), emptySet()), false, epoch,
      subtaskChoice = SubtaskChoice(SubtaskKey.GoalFinish),
      phaseChoice = mapOf(PhaseChoiceKey("goal", SubtaskKey.GoalFinish) to PhaseDisclosure.Open("goal_assessment")),
    ))
    setContent {
      SkillBillTheme(reducedMotion = true) {
        CompositionLocalProvider(LocalShellLayout provides layout.value, LocalShellInput provides input) {
          DetailScreen(state.value, DetailScreenHost(true, "Ctrl I", {}, {})) {}
        }
      }
    }
    onNodeWithText("No assessment result is available for this run.").assertExists()
    onNodeWithText("2/2").assertExists()
    onNodeWithText("No assessment result is available for this run.").assertExists()
  }

  private fun round(id: String) = QaRunOccurrence(
    occurrenceId = id, kind = id, phaseAttempt = 1, workspaceInvocationId = id,
    startedAt = epoch.toString(), finishedAt = epoch.toString(), disposition = "completed",
    testedTreeIdentity = null, resultReference = null, frameReference = null, frameAvailability = "not_captured",
    screenshots = emptyList(), stages = listOf(QaRunStage("$id stage", "completed", null, null, null)),
  )
}
