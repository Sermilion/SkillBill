package dev.skillbill.desktop.feature.detail

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.onAllNodesWithContentDescription
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
import dev.skillbill.desktop.core.domain.model.GoalQaPreview
import dev.skillbill.desktop.core.domain.model.QaPreviewState
import dev.skillbill.desktop.core.domain.model.QaPreviewFrame
import dev.skillbill.desktop.core.domain.model.GoalQaFinding
import dev.skillbill.desktop.core.domain.model.GoalQaResult
import dev.skillbill.desktop.core.domain.model.ExecutionInstance
import dev.skillbill.desktop.core.domain.model.QaAssertionOutcome
import dev.skillbill.desktop.core.domain.model.QaChecklist
import dev.skillbill.desktop.core.domain.model.QaChecklistCase
import dev.skillbill.desktop.core.domain.model.QaChecklistFinding
import dev.skillbill.desktop.core.domain.model.QaChecklistStep
import dev.skillbill.desktop.core.domain.model.QaRunAssertion
import dev.skillbill.desktop.core.domain.model.QaRunDetails
import dev.skillbill.desktop.core.domain.model.QaStepState
import dev.skillbill.desktop.core.domain.model.WorkKind
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
  fun `goal details show only the selected desktop QA preview and remove its ended image`() = runComposeUiTest {
    val preview = GoalQaPreview("goal-qa", "/qa/goal", listOf("desktop"), true, null)
    val detail = emptyDetail.copy(
      currentPhase = "qa",
      subtasks = listOf(WorkSubtask(1, "Build", "completed", null, null, emptyList())),
      goalQaPreview = preview,
    )
    val state = mutableStateOf(DetailUiState(
      workItem("goal"), SectionsUi.Loaded(detail, null, emptyMap(), emptySet()), false, epoch,
      subtaskChoice = SubtaskChoice(SubtaskKey.GoalFinish), qaPreviewVisible = true,
      qaPreview = QaPreviewState.Live(QaPreviewFrame(1, 1, intArrayOf(0xff112233.toInt()))),
    ))
    setContent {
      SkillBillTheme(reducedMotion = true) {
        DetailsPane(state.value, remember { mutableStateOf(AssessmentOrder.Priority) }) {}
      }
    }

    onNodeWithContentDescription("Live desktop QA preview for run goal-qa").performScrollTo().assertIsDisplayed()
    runOnIdle { state.value = state.value.copy(qaPreview = QaPreviewState.Ended) }
    onNodeWithContentDescription("Live desktop QA preview for run goal-qa").assertDoesNotExist()
    onNodeWithText("The selected QA run has ended.").assertExists()
    runOnIdle { state.value = state.value.copy(subtaskChoice = SubtaskChoice(SubtaskKey.Subtask(1))) }
    onNodeWithText("Desktop preview").assertDoesNotExist()
    runOnIdle {
      state.value = state.value.copy(
        subtaskChoice = SubtaskChoice(SubtaskKey.GoalFinish),
        sections = SectionsUi.Loaded(detail.copy(goalQaPreview = preview.copy(targets = listOf("android"))), null, emptyMap(), emptySet()),
      )
    }
    onNodeWithText("Desktop preview").assertDoesNotExist()
  }

  @Test
  fun `phase pipeline appears only on the current subtask`() = runComposeUiTest {
    val detail = emptyDetail.copy(
      stepPipeline = listOf("implement", "review"),
      goalFinishPipeline = listOf("qa", "qa_fix"),
      currentPhase = "review",
      subtasks = listOf(
        WorkSubtask(1, "First subtask", "completed", null, null, emptyList()),
        WorkSubtask(2, "Second subtask", "completed", null, null, emptyList()),
        WorkSubtask(3, "Current subtask", "in_progress", null, null, emptyList()),
      ),
    )
    val state = DetailUiState(
      workItem("goal").copy(currentSubtaskId = "3"),
      SectionsUi.Loaded(detail, null, emptyMap(), emptySet()),
      false,
      epoch,
    )
    val open = mutableStateOf(false)
    val input = ShellInput()
    setContent {
      SkillBillTheme(reducedMotion = true) {
        CompositionLocalProvider(LocalShellInput provides input) {
          DetailScreen(state, DetailScreenHost(open.value, "Ctrl I", { open.value = !open.value })) {}
        }
      }
    }

    onAllNodesWithContentDescription("implement, done").assertCountEquals(1)
    onAllNodesWithContentDescription("review, current").assertCountEquals(1)
    onAllNodesWithContentDescription("qa, pending").assertCountEquals(0)
  }

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
      goalQa = GoalQaResult("findings", null, 0, epoch, listOf(GoalQaFinding("QA-1", "The drawer covers its close button", false)), "Verdict: skipped\n\n## Coverage\n\n- No supported target\n- No application exercised", "unsupported_target", null),
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
    onNodeWithText("Coverage").assertDoesNotExist()
    onNodeWithText("Full report").performClick()
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

  private val invocationDirectory = "/home/me/.skill-bill/qa-4f7e0edf"

  @Test
  fun `goal qa shows what needs the reader first with no full paths`() = runComposeUiTest {
    val dir = invocationDirectory
    val detail = emptyDetail.copy(
      goalFinishPipeline = listOf("qa"),
      goalQa = GoalQaResult(
        "attempted", null, 1, epoch,
        listOf(GoalQaFinding("QA-1", "Benchmark times out", false, listOf("$dir/benchmark.log"), "$dir/report.md", "it exited under $dir/tmp", "It finishes", "Run `gradle`")),
        "Verdict: findings_open\n\n## Coverage\n\nLog at $dir/benchmark.log", null,
        QaRunDetails(
          reportPath = "$dir/report.md", invocationDirectory = dir,
          assertions = listOf(
            QaRunAssertion(QaAssertionOutcome.NotExercised, "Live app", "No window"),
            QaRunAssertion(QaAssertionOutcome.NotExercised, "Native tray", "Not exercised"),
            QaRunAssertion(QaAssertionOutcome.Passed, "UI tests", "Passed"),
          ),
        ),
      ),
    )
    val actions = mutableListOf<DetailAction>()
    setContent {
      SkillBillTheme(reducedMotion = true) {
        DetailsPane(
          DetailUiState(workItem("goal", finishedAt = epoch), SectionsUi.Loaded(detail, null, emptyMap(), emptySet()), false, epoch, subtaskChoice = SubtaskChoice(SubtaskKey.GoalFinish)),
          remember { mutableStateOf(AssessmentOrder.Default) },
        ) { actions += it }
      }
    }
    onNodeWithText("QA").performClick()
    onNodeWithText("Needs your review").assertIsDisplayed()
    onNodeWithText("QA-1").assertIsDisplayed()
    onNodeWithText("Live app").assertIsDisplayed()
    onNodeWithText("Native tray").assertIsDisplayed()
    onNodeWithText("Coverage").assertDoesNotExist()
    onAllNodes(hasText(dir, substring = true)).assertCountEquals(0)

    onNodeWithText("Benchmark times out").performClick()
    onNodeWithContentDescription("Open evidence benchmark.log").performClick()
    runOnIdle { assertTrue(DetailAction.OpenQaEvidence("$dir/benchmark.log", dir) in actions) }
    onNodeWithText("Full report").performScrollTo().performClick()
    onNodeWithText("Coverage").assertExists()
    onAllNodes(hasText(dir, substring = true)).assertCountEquals(0)
  }

  @Test
  fun `evidence from an earlier qa invocation opens against that invocation's directory`() = runComposeUiTest {
    val qaA = "/home/me/.skill-bill/qa-A"
    val qaB = "/home/me/.skill-bill/qa-B"
    val detail = emptyDetail.copy(
      goalFinishPipeline = listOf("qa"),
      goalQa = GoalQaResult(
        "findings", null, 1, epoch,
        listOf(
          GoalQaFinding("QA-1", "Fixed problem", true, listOf("$qaA/fixed.log"), "$qaA/report.md"),
          GoalQaFinding("QA-2", "Carried problem", false, listOf("$qaA/carried.log"), "$qaA/report.md"),
        ),
        "Verdict: findings_open", null,
        QaRunDetails(reportPath = "$qaB/report.md", invocationDirectory = qaB),
      ),
    )
    val actions = mutableListOf<DetailAction>()
    setContent {
      SkillBillTheme(reducedMotion = true) {
        DetailsPane(
          DetailUiState(workItem("goal", finishedAt = epoch), SectionsUi.Loaded(detail, null, emptyMap(), emptySet()), false, epoch, subtaskChoice = SubtaskChoice(SubtaskKey.GoalFinish)),
          remember { mutableStateOf(AssessmentOrder.Default) },
        ) { actions += it }
      }
    }
    onNodeWithText("QA").performClick()
    onNodeWithText("Carried problem").performClick()
    onNodeWithContentDescription("Open evidence carried.log").performClick()
    runOnIdle { assertTrue(DetailAction.OpenQaEvidence("$qaA/carried.log", qaA) in actions) }
    onNodeWithText("Fixed by QA · 1").performScrollTo().performClick()
    onNodeWithContentDescription("Open evidence fixed.log").performScrollTo().performClick()
    runOnIdle { assertTrue(DetailAction.OpenQaEvidence("$qaA/fixed.log", qaA) in actions) }
  }

  @Test
  fun `a settled standalone run puts results above the strip and the finding link reaches its row`() = runComposeUiTest {
    val steps = (1..40).map { QaChecklistStep("Step $it", QaStepState.Passed) } + QaChecklistStep("Failing step", QaStepState.Failed, findingId = "QA-2")
    val run = ExecutionInstance(
      "run-1", "run-1", "canceled", epoch, epoch, epoch, null, null, null, null, null, null,
      QaChecklist(cases = listOf(QaChecklistCase("C1", "Case", steps)), findings = listOf(QaChecklistFinding("QA-2", "Row title"))),
    )
    val base = workItem("QA", kind = WorkKind.Phase)
    val item = base.copy(control = base.control.copy(phaseId = "qa", invocationId = "run-1"))
    val detail = emptyDetail.copy(executionHistory = listOf(run))
    setContent {
      SkillBillTheme(reducedMotion = true) {
        CompositionLocalProvider(LocalShellInput provides ShellInput()) {
          DetailScreen(DetailUiState(item, SectionsUi.Loaded(detail, null, emptyMap(), emptySet()), false, epoch), DetailScreenHost(false, "Ctrl I", {})) {}
        }
      }
    }
    assertTrue(onNodeWithText("QA results").fetchSemanticsNode().boundsInRoot.top < onNodeWithText("Now testing").fetchSemanticsNode().boundsInRoot.top)
    onNodeWithText("Finding QA-2").performScrollTo()
    onNodeWithText("QA-2").assertDoesNotExist()
    onNodeWithText("Finding QA-2").performClick()
    waitForIdle()
    onNodeWithText("QA-2").assertIsDisplayed()
  }
}
