package dev.skillbill.desktop.feature.detail

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.click
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.unit.dp
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
import dev.skillbill.desktop.core.domain.model.PlanRunReport
import dev.skillbill.desktop.core.domain.model.PlannedSubtask
import dev.skillbill.desktop.core.domain.model.QaAssertionOutcome
import dev.skillbill.desktop.core.domain.model.QaChecklist
import dev.skillbill.desktop.core.domain.model.QaChecklistCase
import dev.skillbill.desktop.core.domain.model.QaChecklistFinding
import dev.skillbill.desktop.core.domain.model.QaChecklistStep
import dev.skillbill.desktop.core.domain.model.QaRunAssertion
import dev.skillbill.desktop.core.domain.model.QaRunDetails
import dev.skillbill.desktop.core.domain.model.QaStepState
import dev.skillbill.desktop.core.domain.model.ChangeScope
import dev.skillbill.desktop.core.domain.model.PhaseCardEdit
import dev.skillbill.desktop.core.domain.model.PhaseCardOutput
import dev.skillbill.desktop.core.domain.model.ScopeSubtask
import dev.skillbill.desktop.core.domain.model.ScopeSubtaskState
import dev.skillbill.desktop.core.domain.model.SubtaskPhaseResults
import dev.skillbill.desktop.core.domain.model.SubtaskScopesState
import dev.skillbill.desktop.core.domain.model.ValidationGateRun
import dev.skillbill.desktop.core.domain.model.ValidationResult
import dev.skillbill.desktop.core.domain.model.AuditCriterion
import dev.skillbill.desktop.core.domain.model.AuditOutcome
import dev.skillbill.desktop.core.domain.model.ReviewFindingStatus
import dev.skillbill.desktop.core.domain.model.SubtaskAuditResult
import dev.skillbill.desktop.core.domain.model.ReviewPass
import dev.skillbill.desktop.core.domain.model.ReviewReportFinding
import dev.skillbill.desktop.core.domain.model.WorkKind
import dev.skillbill.desktop.core.domain.model.WorkSubtask
import dev.skillbill.desktop.core.testing.workItem
import dev.skillbill.desktop.core.ui.input.LocalShellInput
import dev.skillbill.desktop.core.ui.input.ShellInput
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.Test
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class DetailPeekTest {
  @Test
  fun `inspector review finding disclosure follows the selected subtask`() = runComposeUiTest {
    fun reviewFinding(ref: String, location: String, specialist: String, confidence: String) =
      ReviewReportFinding(ref, "major", confidence, specialist, location, "Finding $ref", ReviewFindingStatus.Open)
    val detail = emptyDetail.copy(
      stepPipeline = listOf("review"),
      subtasks = listOf(
        WorkSubtask(1, "First subtask", "completed", null, null, emptyList()),
        WorkSubtask(2, "Second subtask", "completed", null, null, emptyList()),
      ),
      phaseResults = listOf(
        SubtaskPhaseResults(
          1,
          listOf(ReviewPass(1, "changes_requested", 0, listOf(reviewFinding("F-S1", "src/a/First.kt:1", "bill-kotlin-code-review-persistence", "high")), emptyList())),
          null,
          null,
          phaseCards = listOf(finishCard("review")),
        ),
        SubtaskPhaseResults(
          2,
          listOf(ReviewPass(1, "changes_requested", 0, listOf(reviewFinding("F-S2", "src/b/Second.kt:2", "bill-kotlin-code-review-testing", "medium")), emptyList())),
          null,
          null,
          phaseCards = listOf(finishCard("review")),
        ),
      ),
    )
    val state = mutableStateOf(
      DetailUiState(
        workItem("goal"), SectionsUi.Loaded(detail, null, emptyMap(), emptySet()), false, epoch,
        subtaskChoice = SubtaskChoice(SubtaskKey.Subtask(1)),
        phaseChoice = mapOf(
          PhaseChoiceKey("goal", SubtaskKey.Subtask(1)) to PhaseDisclosure.Open("review"),
          PhaseChoiceKey("goal", SubtaskKey.Subtask(2)) to PhaseDisclosure.Open("review"),
        ),
      ),
    )
    setContent {
      SkillBillTheme(reducedMotion = true) {
        CompositionLocalProvider(LocalShellInput provides ShellInput()) {
          Box(Modifier.requiredSize(400.dp, 700.dp)) {
            DetailsPane(state.value, remember { mutableStateOf(AssessmentOrder.Default) }, {}, { action ->
              if (action is DetailAction.MoveSubtaskSelection) {
                val keys = subtaskKeys(state.value.header, detail)
                val current = keys.indexOf(state.value.subtaskChoice.key)
                state.value = state.value.copy(subtaskChoice = SubtaskChoice(keys[(current + action.delta).coerceIn(0, keys.lastIndex)]))
              }
            })
          }
        }
      }
    }

    onNodeWithText("Finding F-S1", substring = true).performScrollTo().performClick()
    onNodeWithText("First.kt").assertExists()
    onNodeWithContentDescription("Next subject").performClick()
    onNodeWithText("Finding F-S2", substring = true).performScrollTo().performClick()
    onNodeWithText("Second.kt").assertExists()
    onNodeWithText("testing · medium confidence").assertExists()
  }

  @Test
  fun `inspector audit criterion disclosure follows the selected subtask`() = runComposeUiTest {
    val detail = emptyDetail.copy(
      stepPipeline = listOf("audit"),
      subtasks = listOf(
        WorkSubtask(1, "First subtask", "completed", null, null, emptyList()),
        WorkSubtask(2, "Second subtask", "completed", null, null, emptyList()),
      ),
      phaseResults = listOf(
        SubtaskPhaseResults(
          1,
          emptyList(),
          null,
          null,
          auditResult = SubtaskAuditResult(AuditOutcome.Passed, 1, 1, listOf(AuditCriterion("AC-001", "First criterion", true, 1)), emptyList(), 1),
          phaseCards = listOf(finishCard("audit")),
        ),
        SubtaskPhaseResults(
          2,
          emptyList(),
          null,
          null,
          auditResult = SubtaskAuditResult(AuditOutcome.Passed, 1, 1, listOf(AuditCriterion("AC-001", "Second criterion", true, 3)), emptyList(), 1),
          phaseCards = listOf(finishCard("audit")),
        ),
      ),
    )
    val state = mutableStateOf(
      DetailUiState(
        workItem("goal"), SectionsUi.Loaded(detail, null, emptyMap(), emptySet()), false, epoch,
        subtaskChoice = SubtaskChoice(SubtaskKey.Subtask(1)),
        phaseChoice = mapOf(
          PhaseChoiceKey("goal", SubtaskKey.Subtask(1)) to PhaseDisclosure.Open("audit"),
          PhaseChoiceKey("goal", SubtaskKey.Subtask(2)) to PhaseDisclosure.Open("audit"),
        ),
      ),
    )
    setContent {
      SkillBillTheme(reducedMotion = true) {
        CompositionLocalProvider(LocalShellInput provides ShellInput()) {
          Box(Modifier.requiredSize(400.dp, 700.dp)) {
            DetailsPane(state.value, remember { mutableStateOf(AssessmentOrder.Default) }, {}, { action ->
              if (action is DetailAction.MoveSubtaskSelection) {
                val keys = subtaskKeys(state.value.header, detail)
                val current = keys.indexOf(state.value.subtaskChoice.key)
                state.value = state.value.copy(subtaskChoice = SubtaskChoice(keys[(current + action.delta).coerceIn(0, keys.lastIndex)]))
              }
            })
          }
        }
      }
    }

    onNodeWithText("AC-001").performScrollTo().performClick()
    onNodeWithText("fixed in attempt 1").assertExists()
    onNodeWithContentDescription("Next subject").performClick()
    onNodeWithText("AC-001").performScrollTo().performClick()
    onNodeWithText("fixed in attempt 3").assertExists()
    onNodeWithText("fixed in attempt 1").assertDoesNotExist()
  }

  @Test
  fun `inspector audit criterion disclosure survives in place criterion swap at same row`() = runComposeUiTest {
    val initialAudit = SubtaskAuditResult(
      AuditOutcome.Passed,
      1,
      1,
      listOf(AuditCriterion("AC-001", "First criterion", true, 1)),
      emptyList(),
      1,
    )
    val detail = emptyDetail.copy(
      stepPipeline = listOf("audit"),
      subtasks = listOf(WorkSubtask(1, "First subtask", "completed", null, null, emptyList())),
      phaseResults = listOf(
        SubtaskPhaseResults(
          1,
          emptyList(),
          null,
          null,
          auditResult = initialAudit,
          phaseCards = listOf(finishCard("audit")),
        ),
      ),
    )
    val state = mutableStateOf(
      DetailUiState(
        workItem("goal"), SectionsUi.Loaded(detail, null, emptyMap(), emptySet()), false, epoch,
        subtaskChoice = SubtaskChoice(SubtaskKey.Subtask(1)),
        phaseChoice = mapOf(PhaseChoiceKey("goal", SubtaskKey.Subtask(1)) to PhaseDisclosure.Open("audit")),
      ),
    )
    setContent {
      SkillBillTheme(reducedMotion = true) {
        CompositionLocalProvider(LocalShellInput provides ShellInput()) {
          Box(Modifier.requiredSize(400.dp, 700.dp)) {
            DetailsPane(state.value, remember { mutableStateOf(AssessmentOrder.Default) }, {}, {})
          }
        }
      }
    }

    onNodeWithText("AC-001").performScrollTo().performClick()
    onNodeWithText("fixed in attempt 1").assertExists()
    val refreshedAudit = initialAudit.copy(
      criteria = listOf(AuditCriterion("AC-002", "Second criterion", true, 2)),
    )
    runOnIdle {
      val refreshed = detail.copy(
        phaseResults = listOf(
          SubtaskPhaseResults(
            1,
            emptyList(),
            null,
            null,
            auditResult = refreshedAudit,
            phaseCards = listOf(finishCard("audit")),
          ),
        ),
      )
      state.value = state.value.copy(sections = SectionsUi.Loaded(refreshed, null, emptyMap(), emptySet()))
    }
    onNodeWithText("AC-002").performScrollTo().performClick()
    onNodeWithText("fixed in attempt 2").assertExists()
    onNodeWithText("fixed in attempt 1").assertDoesNotExist()
  }

  @Test
  fun `subject header follows the index and disables navigation at both ends`() = runComposeUiTest {
    val detail = emptyDetail.copy(subtasks = listOf(
      WorkSubtask(1, "First", "completed", null, null, emptyList()),
      WorkSubtask(2, "Second", "blocked", null, null, emptyList()),
    ))
    val state = mutableStateOf(DetailUiState(
      workItem("goal"), SectionsUi.Loaded(detail, null, emptyMap(), emptySet()), false, epoch,
      subtaskChoice = SubtaskChoice(SubtaskKey.Subtask(1)),
    ))
    setContent {
      SkillBillTheme(reducedMotion = true) {
        Box(Modifier.requiredSize(264.dp, 700.dp)) {
          DetailsPane(state.value, remember { mutableStateOf(AssessmentOrder.Default) }, {}, { action ->
            if (action is DetailAction.MoveSubtaskSelection) {
              val keys = subtaskKeys(state.value.header, detail)
              val current = keys.indexOf(state.value.subtaskChoice.key)
              state.value = state.value.copy(subtaskChoice = SubtaskChoice(keys[(current + action.delta).coerceIn(0, keys.lastIndex)]))
            }
          })
        }
      }
    }

    onNodeWithText("1/3").assertExists()
    onNodeWithContentDescription("Close details").assertIsDisplayed().assertWidthIsEqualTo(28.dp)
    onNodeWithContentDescription("Previous subject").assertIsNotEnabled()
    onNodeWithContentDescription("Next subject").performClick()
    onNodeWithText("Subtask 02").assertExists()
    onNodeWithText("2/3").assertExists()
    onNodeWithContentDescription("Next subject").performClick()
    onNodeWithText("Goal finish").assertExists()
    onNodeWithText("3/3").assertExists()
    onNodeWithContentDescription("Next subject").assertIsNotEnabled()
  }

  @Test
  fun `restored details width is not capped by the initial default ratio`() = runComposeUiTest {
    setContent {
      SkillBillTheme(reducedMotion = true) {
        CompositionLocalProvider(LocalShellInput provides remember { ShellInput() }) {
          Box(Modifier.requiredSize(900.dp, 700.dp).testTag("details-body")) {
            DetailPeek(true, true, 800, {}, {}, {}) { BasicText("Details content") }
          }
        }
      }
    }

    val bodyWidth = onNodeWithTag("details-body").fetchSemanticsNode().boundsInRoot.width
    val bodyLeft = onNodeWithTag("details-body").fetchSemanticsNode().boundsInRoot.left
    val gripLeft = onNodeWithContentDescription("Details panel resize separator").fetchSemanticsNode().boundsInRoot.left
    assertEquals(100f / 900f, (gripLeft - bodyLeft) / bodyWidth, 0.01f)
  }

  @Test
  fun `compact details fill the body without exposing a resize grip`() = runComposeUiTest {
    setContent {
      SkillBillTheme(reducedMotion = true) {
        CompositionLocalProvider(LocalShellInput provides remember { ShellInput() }) {
          Box(Modifier.requiredSize(700.dp, 600.dp)) {
            DetailPeek(true, false, 640, {}, {}, {}) {
              Box(Modifier.fillMaxSize().testTag("compact-content")) { BasicText("Details content") }
            }
          }
        }
      }
    }

    onNodeWithContentDescription("Details panel resize separator").assertDoesNotExist()
    onNodeWithTag("compact-content").assertWidthIsEqualTo(700.dp)
    onNodeWithText("Details content").assertIsDisplayed()
  }

  @Test
  fun `details grip uses 24dp keyboard steps and double click clears the override`() = runComposeUiTest {
    val resized = mutableListOf<Int>()
    var reset = false
    setContent {
      SkillBillTheme(reducedMotion = true) {
        CompositionLocalProvider(LocalShellInput provides remember { ShellInput() }) {
          Box(Modifier.requiredSize(900.dp, 700.dp)) {
            DetailPeek(true, true, 500, { resized += it }, { reset = true }, {}) { BasicText("Details content") }
          }
        }
      }
    }
    val grip = onNodeWithContentDescription("Details panel resize separator")
    grip.performSemanticsAction(SemanticsActions.RequestFocus)
    grip.performKeyInput { keyDown(Key.DirectionLeft); keyUp(Key.DirectionLeft) }
    assertEquals(listOf(524), resized)
    grip.performMouseInput { doubleClick() }
    runOnIdle { assertEquals(true, reset) }
  }

  @Test
  fun `goal details show only the selected desktop QA preview and remove its ended image`() = runComposeUiTest {
    val preview = GoalQaPreview("goal-qa", "/qa/goal", listOf("desktop"), true, null)
    val detail = emptyDetail.copy(
      currentPhase = "qa",
      goalFinishCards = listOf(finishCard("qa", dev.skillbill.desktop.core.domain.model.PhaseCardState.Running)),
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
        DetailsPane(state.value, remember { mutableStateOf(AssessmentOrder.Priority) }, {}, {})
      }
    }

    onNodeWithText("Desktop preview").assertIsDisplayed()
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
  fun `closed subtask index has no inline pipeline and activation opens its inspector`() = runComposeUiTest {
    val detail = emptyDetail.copy(
      stepPipeline = listOf("implement", "review"),
      goalFinishPipeline = listOf("qa", "qa_fix"),
      currentPhase = "review",
      subtasks = listOf(
        WorkSubtask(1, "First subtask", "completed", null, null, emptyList()),
        WorkSubtask(2, "Second subtask", "completed", null, null, emptyList()),
        WorkSubtask(3, "Current subtask", "in_progress", null, null, emptyList()),
      ),
      phaseResults = listOf(SubtaskPhaseResults(3, emptyList(), null, null)),
    )
    val state = DetailUiState(
      workItem("goal").copy(currentSubtaskId = "3"),
      SectionsUi.Loaded(detail, null, emptyMap(), emptySet()),
      false,
      epoch,
      subtaskChoice = SubtaskChoice(SubtaskKey.Subtask(3)),
    )
    val open = mutableStateOf(false)
    val input = ShellInput()
    setContent {
      SkillBillTheme(reducedMotion = true) {
        CompositionLocalProvider(LocalShellInput provides input) {
          DetailScreen(state, DetailScreenHost(open.value, "Ctrl I", { open.value = !open.value }, { open.value = true })) {}
        }
      }
    }

    onAllNodesWithContentDescription("implement, done").assertCountEquals(0)
    onAllNodesWithContentDescription("review, current").assertCountEquals(0)
    onAllNodesWithContentDescription("qa, pending").assertCountEquals(0)
    onNodeWithContentDescription("Close details").assertDoesNotExist()
    onNodeWithText("No phase has run yet.").assertDoesNotExist()
    onNodeWithText("Current subtask").performScrollTo().performClick()
    onNodeWithText("Subtask 03").assertExists()
    onNodeWithText("No phase has run yet.").assertExists()
    onNodeWithContentDescription("Close details").assertIsDisplayed()
  }

  @Test
  fun `goal spec files stay in the document until a preplan card can carry them`() = runComposeUiTest {
    val bundle = PlanRunReport(
      featureName = "GOAL-1",
      parentSpecPath = "/specs/GOAL-1/spec.md",
      subtasks = listOf(PlannedSubtask(1, "Build", "/specs/GOAL-1/spec_subtask_1.md", emptyList())),
      bundleFiles = listOf("/specs/GOAL-1/decomposition-manifest.yaml", "/specs/GOAL-1/spec.md", "/specs/GOAL-1/spec_subtask_1.md"),
    )
    val subtask = WorkSubtask(1, "Build", "completed", null, null, emptyList())
    val withoutCard = emptyDetail.copy(subtasks = listOf(subtask), goalSpecBundle = bundle)
    val withCard = withoutCard.copy(
      phaseResults = listOf(SubtaskPhaseResults(1, emptyList(), null, null, phaseCards = listOf(finishCard("preplan")))),
    )
    val state = mutableStateOf(DetailUiState(
      workItem("goal"), SectionsUi.Loaded(withoutCard, null, emptyMap(), emptySet()), false, epoch,
      subtaskChoice = SubtaskChoice(SubtaskKey.Subtask(1)),
    ))
    setContent {
      SkillBillTheme(reducedMotion = true) {
        DetailsPane(state.value, remember { mutableStateOf(AssessmentOrder.Default) }, {}, { action ->
          if (action is DetailAction.SelectPhase) {
            state.value = state.value.copy(
              phaseChoice = state.value.phaseChoice + (PhaseChoiceKey(action.itemId, action.key) to PhaseDisclosure.Open(action.phaseId)),
            )
          }
        })
      }
    }
    onNodeWithText("Spec files").assertExists()
    onNodeWithText("spec.md").assertExists()
    onNode(hasText("spec_subtask_1.md") and hasAnyAncestor(hasText("Spec files"))).assertDoesNotExist()
    onNodeWithText("spec_subtask_1.md").assertExists()
    runOnIdle {
      state.value = state.value.copy(
        sections = SectionsUi.Loaded(withCard, null, emptyMap(), emptySet()),
        phaseChoice = mapOf(PhaseChoiceKey("goal", SubtaskKey.Subtask(1)) to PhaseDisclosure.Open("preplan")),
      )
    }
    onAllNodesWithText("Spec files").assertCountEquals(0)
    onNode(hasText("Goal spec") and hasClickAction()).performClick()
    onNodeWithText("spec.md").assertExists()
    onNodeWithText("spec_subtask_1.md").assertExists()
    onNodeWithText("Shared by every subtask in this goal.").assertExists()
  }

  @Test
  fun `validate cards reveal extra checks without losing gate facts`() = runComposeUiTest {
    val checks = (1..7).map { "check-$it" }
    val gate = ValidationGateRun(1_000, "passed", "warm", 3, listOf("gate-check"), "./gradlew check", 0)
    val receipt = ValidationResult("failed", checks, 1, listOf(gate))
    val laterGate = ValidationGateRun(2_000, "failed", "cold", 1, listOf("later-gate"), "./gradlew later", 1)
    val laterReceipt = ValidationResult("failed", (1..8).map { "later-$it" }, 1, listOf(laterGate))
    val implement = finishCard("implement").copy(
      edit = PhaseCardEdit(epoch, 2, 12, 1, emptyList()),
    )
    fun validateCard(result: ValidationResult) =
      finishCard("validate").copy(output = PhaseCardOutput("failed", null, result, null, null, null, null, null, null))
    val detail = emptyDetail.copy(
      stepPipeline = listOf("implement", "validate"),
      subtasks = listOf(WorkSubtask(1, "Build", "completed", null, null, emptyList())),
      phaseResults = listOf(SubtaskPhaseResults(1, emptyList(), null, null, phaseCards = listOf(implement, validateCard(receipt)))),
    )
    val state = mutableStateOf(
      DetailUiState(
        workItem("goal"), SectionsUi.Loaded(detail, null, emptyMap(), emptySet()), false, epoch,
        subtaskChoice = SubtaskChoice(SubtaskKey.Subtask(1)),
        phaseChoice = mapOf(PhaseChoiceKey("goal", SubtaskKey.Subtask(1)) to PhaseDisclosure.Open("validate")),
      ),
    )
    setContent {
      SkillBillTheme(reducedMotion = true) {
        DetailsPane(state.value, remember { mutableStateOf(AssessmentOrder.Default) }, {}, {})
      }
    }
    onNodeWithText("check-1").assertExists()
    onNodeWithText("check-6").assertExists()
    onNodeWithText("check-7").assertDoesNotExist()
    onNodeWithText("Show 1 more checks").performClick()
    onNodeWithText("check-7").assertExists()
    onNodeWithText("Gate 1").assertExists()
    onNodeWithText("Duration").assertExists()
    onNodeWithText("Show fewer").performClick()
    onNodeWithText("check-7").assertDoesNotExist()
    onNodeWithText("Show 1 more checks").performClick()
    onNodeWithText("check-7").assertExists()
    runOnIdle {
      val switched = detail.copy(
        phaseResults = listOf(SubtaskPhaseResults(1, emptyList(), null, null, phaseCards = listOf(implement, validateCard(laterReceipt)))),
      )
      state.value = state.value.copy(sections = SectionsUi.Loaded(switched, null, emptyMap(), emptySet()))
    }
    onNodeWithText("check-7").assertDoesNotExist()
    onNodeWithText("Show fewer").assertDoesNotExist()
    onNodeWithText("Show 2 more checks").assertExists()
    onNodeWithText("later-7").assertDoesNotExist()
    onNodeWithText("later-8").assertDoesNotExist()
    onNodeWithText("Show 2 more checks").performClick()
    onNodeWithText("later-8").assertExists()
    runOnIdle {
      val sixChecks = laterReceipt.copy(checks = laterReceipt.checks.take(6))
      val switched = detail.copy(
        phaseResults = listOf(SubtaskPhaseResults(1, emptyList(), null, null, phaseCards = listOf(implement, validateCard(sixChecks)))),
      )
      state.value = state.value.copy(sections = SectionsUi.Loaded(switched, null, emptyMap(), emptySet()))
    }
    onNodeWithText("Show fewer").assertDoesNotExist()
    onNodeWithText("Show 2 more checks").assertDoesNotExist()
    onNodeWithText("later-1").assertExists()
    onNodeWithText("later-6").assertExists()
    onNodeWithText("Duration").assertExists()
    onNodeWithText("Gate 1").assertExists()
  }

  @Test
  fun `commit push footer opens the recorded branch instead of the header branch`() = runComposeUiTest {
    val card = finishCard("commit_push").copy(
      output = PhaseCardOutput(null, null, null, null, null, null, null, null, null).copy(pushedBranch = "feature/receipt"),
    )
    val actions = mutableListOf<DetailAction>()
    setContent {
      SkillBillTheme(reducedMotion = true) {
        SubtaskPhaseCard(
          workItem("goal").copy(branch = "feature/header"), emptyDetail,
          WorkSubtask(1, "Build", "completed", null, null, emptyList()), card,
          null, null, ChangesUi(), dev.skillbill.desktop.core.domain.model.ActivityResult.Loading,
          epoch, actions::add,
        )
      }
    }
    onNodeWithText("Open branch").performClick()
    assertEquals(listOf<DetailAction>(DetailAction.OpenBranch("feature/receipt")), actions)
  }

  @Test
  fun `implement open changes stays disabled until subtask scopes list the subject`() = runComposeUiTest {
    val implement = finishCard("implement").copy(
      edit = PhaseCardEdit(epoch, 2, 12, 1, emptyList()),
    )
    val detail = emptyDetail.copy(
      stepPipeline = listOf("implement"),
      subtasks = listOf(WorkSubtask(1, "Build", "completed", null, null, emptyList())),
      phaseResults = listOf(SubtaskPhaseResults(1, emptyList(), null, null, phaseCards = listOf(implement))),
    )
    val state = mutableStateOf(
      DetailUiState(
        workItem("goal"), SectionsUi.Loaded(detail, null, emptyMap(), emptySet()), false, epoch,
        subtaskChoice = SubtaskChoice(SubtaskKey.Subtask(1)),
        phaseChoice = mapOf(PhaseChoiceKey("goal", SubtaskKey.Subtask(1)) to PhaseDisclosure.Open("implement")),
      ),
    )
    val opens = mutableListOf<DetailAction>()
    setContent {
      SkillBillTheme(reducedMotion = true) {
        DetailsPane(state.value, remember { mutableStateOf(AssessmentOrder.Default) }, {}, opens::add)
      }
    }
    onNodeWithText("Open changes").assertDoesNotExist()
    runOnIdle {
      state.value = state.value.copy(
        changes = ChangesUi(
          subtasks = SubtaskScopesState.Listed(
            "/repo", "main", true,
            listOf(ScopeSubtask(1, "Build", ScopeSubtaskState.DONE, null, emptyList(), false, emptyList())),
            emptyMap(),
          ),
        ),
      )
    }
    onNodeWithText("Open changes").assertDoesNotExist()
    runOnIdle {
      state.value = state.value.copy(
        changes = ChangesUi(
          subtasks = SubtaskScopesState.Listed(
            "/repo", "main", true,
            listOf(ScopeSubtask(1, "Build", ScopeSubtaskState.DONE, "e1", listOf("e1"), false, emptyList())),
            emptyMap(),
          ),
        ),
      )
    }
    onNodeWithText("Open changes").performClick()
    assertEquals(DetailAction.OpenChangesWindow(ChangeScope.Subtasks(1, 1)), opens.single())
  }

  @Test
  fun `goal finish findings open in the peek and keep their ordering when reopened`() = runComposeUiTest {
    val detail = emptyDetail.copy(
      subtasks = (1..12).map { WorkSubtask(it, "Build the view $it", "completed", null, null, emptyList()) },
      goalFinishPipeline = listOf("goal_assessment", "qa"),
      goalFinishCards = listOf(finishCard("goal_assessment"), finishCard("qa")),
      goalAssessment = GoalAssessmentResult(
        GoalAssessmentState.Completed, 3, null, false,
        listOf(
          GoalAssessmentFinding("GA-1", "minor", "View.kt", 10, "An action has no label", GoalAssessmentVerification.Confirmed, GoalAssessmentFindingState.Open, ""),
          GoalAssessmentFinding("GA-2", "major", "View.kt", 20, "A completed run loses its findings", GoalAssessmentVerification.Confirmed, GoalAssessmentFindingState.Refuted, ""),
          GoalAssessmentFinding("GA-3", "blocker", "View.kt", 30, "Fixed blocker", GoalAssessmentVerification.Confirmed, GoalAssessmentFindingState.Fixed, ""),
          GoalAssessmentFinding("GA-4", "blocker", "View.kt", 40, "Open blocker", GoalAssessmentVerification.Confirmed, GoalAssessmentFindingState.Open, ""),
        ),
      ),
      goalQa = GoalQaResult("findings", null, 0, epoch, listOf(GoalQaFinding("QA-1", "The drawer covers its close button", false)), "Verdict: skipped\n\n## Coverage\n\n- No supported target\n- No application exercised", "unsupported_target", null),
    )
    val state = mutableStateOf(DetailUiState(
      workItem("goal"), SectionsUi.Loaded(detail, null, emptyMap(), emptySet()), false, epoch,
      subtaskChoice = SubtaskChoice(SubtaskKey.Subtask(1)),
    ))
    val open = mutableStateOf(false)
    val input = ShellInput()
    setContent {
      SkillBillTheme(reducedMotion = true) {
        CompositionLocalProvider(LocalShellInput provides input) {
          DetailScreen(state.value, DetailScreenHost(open.value, "Ctrl I", { open.value = !open.value }, { open.value = true })) { action ->
            if (action is DetailAction.SelectPhase) {
              state.value = state.value.copy(phaseChoice = state.value.phaseChoice + (PhaseChoiceKey(action.itemId, action.key) to PhaseDisclosure.Open(action.phaseId)))
            }
            if (action is DetailAction.SelectSubtask) {
              state.value = state.value.copy(subtaskChoice = SubtaskChoice(action.key))
              open.value = true
            }
          }
        }
      }
    }

    onNodeWithText("An action has no label").assertDoesNotExist()
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
    onNode(hasText("goal_assessment") and isSelectable()).performClick()
    onNodeWithText("Priority").performClick()
    onNodeWithText("Priority").assertIsSelected()
    fun assertOrder(vararg titles: String) {
      val tops = titles.map { onNodeWithText(it).fetchSemanticsNode().boundsInRoot.top }
      assertEquals(tops.sorted(), tops)
    }
    assertOrder("Open blocker", "Fixed blocker", "A completed run loses its findings", "An action has no label")
    onNodeWithText("Status").performClick()
    assertOrder("Open blocker", "An action has no label", "A completed run loses its findings", "Fixed blocker")
    onNodeWithText("Priority").performClick()
    runOnIdle { state.value = state.value.copy(header = workItem("next-goal")) }
    runOnIdle { state.value = state.value.copy(header = workItem("goal")) }
    onNodeWithText("Priority").assertIsSelected()
    onNode(hasText("qa") and isSelectable()).performClick()
    onNodeWithText("Coverage").assertDoesNotExist()
    onNodeWithText("Full report").performClick()
    onNodeWithText("Coverage").assertExists()
    onNodeWithText("## Coverage").assertDoesNotExist()
    onNode(hasText("goal_assessment") and isSelectable()).performClick()
    onNodeWithText("Priority").assertIsSelected()
    onNodeWithContentDescription("Close details").performClick()
    onNodeWithText("An action has no label").assertDoesNotExist()
    onNodeWithText("Goal finish").performScrollTo().performClick()
    onNodeWithText("Priority").assertIsSelected()
    runOnIdle { assertFalse(input.dismissTopLayer()) }
    onNodeWithText("An action has no label").assertExists()
    onNode(hasText("Build the view 1") and hasClickAction()).performScrollTo().performMouseInput {
      click(androidx.compose.ui.geometry.Offset(4f, centerY))
    }
    onNodeWithText("An action has no label").assertDoesNotExist()
    onNodeWithContentDescription("Close details").assertExists()
  }

  private val invocationDirectory = "/home/me/.skill-bill/qa-4f7e0edf"

  @Test
  fun `goal qa shows what needs the reader first with no full paths`() = runComposeUiTest {
    val dir = invocationDirectory
    val detail = emptyDetail.copy(
      subtasks = listOf(WorkSubtask(1, "Build the view", "completed", null, null, emptyList())),
      goalFinishPipeline = listOf("qa"),
      goalFinishCards = listOf(finishCard("qa")),
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
          DetailUiState(
            workItem("goal", finishedAt = epoch), SectionsUi.Loaded(detail, null, emptyMap(), emptySet()), false, epoch,
            subtaskChoice = SubtaskChoice(SubtaskKey.GoalFinish),
            phaseChoice = mapOf(PhaseChoiceKey("goal", SubtaskKey.GoalFinish) to PhaseDisclosure.Open("qa")),
          ),
          remember { mutableStateOf(AssessmentOrder.Default) },
          {},
        ) { actions += it }
      }
    }
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
      subtasks = listOf(WorkSubtask(1, "Build the view", "completed", null, null, emptyList())),
      goalFinishPipeline = listOf("qa"),
      goalFinishCards = listOf(finishCard("qa")),
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
          DetailUiState(
            workItem("goal", finishedAt = epoch), SectionsUi.Loaded(detail, null, emptyMap(), emptySet()), false, epoch,
            subtaskChoice = SubtaskChoice(SubtaskKey.GoalFinish),
            phaseChoice = mapOf(PhaseChoiceKey("goal", SubtaskKey.GoalFinish) to PhaseDisclosure.Open("qa")),
          ),
          remember { mutableStateOf(AssessmentOrder.Default) },
          {},
        ) { actions += it }
      }
    }
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
          DetailScreen(DetailUiState(item, SectionsUi.Loaded(detail, null, emptyMap(), emptySet()), false, epoch), DetailScreenHost(false, "Ctrl I", {}, {})) {}
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
