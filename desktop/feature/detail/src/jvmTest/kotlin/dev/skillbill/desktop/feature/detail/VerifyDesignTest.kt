package dev.skillbill.desktop.feature.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.runtime.remember
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performScrollToNode
import dev.skillbill.desktop.core.domain.model.VerifyReviewSection
import dev.skillbill.desktop.core.domain.model.VerifyReviewFinding
import dev.skillbill.desktop.core.domain.model.VerifyUnitTestValue
import dev.skillbill.desktop.core.domain.model.VerifyTestValueRow
import dev.skillbill.desktop.core.domain.model.WorkKind
import dev.skillbill.desktop.core.domain.model.Lifecycle
import dev.skillbill.desktop.core.domain.model.QaRunDetails
import dev.skillbill.desktop.core.domain.model.QaRunFinding
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import dev.skillbill.desktop.core.designsystem.SkillBillTheme
import dev.skillbill.desktop.core.designsystem.detail.rememberSectionCollapse
import dev.skillbill.desktop.core.domain.model.RunOptions
import dev.skillbill.desktop.core.domain.model.VerifyCompletenessAudit
import dev.skillbill.desktop.core.domain.model.VerifyCriterionGrade
import dev.skillbill.desktop.core.domain.model.VerifyRunCriterion
import dev.skillbill.desktop.core.domain.model.VerifyRunDetails
import dev.skillbill.desktop.core.domain.model.VerifyRunStep
import dev.skillbill.desktop.core.domain.model.VerifyRunVerdict
import dev.skillbill.desktop.core.testing.workItem
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class VerifyDesignTest {
  @Test
  fun `lifecycle cards distinguish waiting running stopped superseded and verdicts`() {
    val run = verifyDesignFixture()
    val cases = listOf(
      run.copy(workflowStatus = "pending", currentStepId = "collect_inputs", steps = run.steps.map { it.copy(status = "pending", attemptCount = 0) }) to ("Waiting to start" to VerifyScoreTone.Faint),
      run to ("Code review" to VerifyScoreTone.Active),
      run.copy(workflowStatus = "pending", currentStepId = "extract_criteria") to ("Confirm the criteria" to VerifyScoreTone.Amber),
      run.copy(workflowStatus = "pending", currentStepId = "extract_criteria", criteria = emptyList()) to ("No criteria to audit" to VerifyScoreTone.Amber),
      run.copy(workflowStatus = "failed") to ("Code review" to VerifyScoreTone.Red),
      run.copy(workflowStatus = "abandoned", supersededBy = "newer") to ("Extract criteria" to VerifyScoreTone.Faint),
    ) + listOf("APPROVE" to VerifyScoreTone.Green, "APPROVE WITH FIXES" to VerifyScoreTone.Amber, "REQUEST CHANGES" to VerifyScoreTone.Red).map { (verdict, tone) ->
      run.copy(workflowStatus = "completed", verdict = VerifyRunVerdict(verdict, emptyList(), "Work summary")) to (verdict to tone)
    }
    cases.forEach { (fixture, expected) ->
      val view = verifyOutcomeView(fixture)
      assertEquals(expected.first, view.headline)
      assertEquals(expected.second, view.tone)
      assertEquals(if (verifyRunState(fixture) in setOf(VerifyRunState.Parked, VerifyRunState.Superseded)) 0 else 4, view.cells.size)
    }
    assertNull(verifySummary(cases.first().first))
    assertEquals(VerifyScoreTone.Amber, verifyStepTone(run.copy(completenessAudit = VerifyCompletenessAudit("had_gaps", emptyList(), listOf(VerifyCriterionGrade(1, "PARTIAL", "criterion", emptyList(), "gap", null, emptyList())))), VerifyRunStep("completeness_audit", "completed", 1)))
  }

  @Test
  fun `criterion disclosures keep evidence hidden until opened and raw text readable`() = runComposeUiTest {
    val run = verifyDesignFixture().copy(workflowStatus = "completed", completenessAudit = VerifyCompletenessAudit("had_gaps", emptyList(), listOf(VerifyCriterionGrade(1, "PARTIAL", "criterion", listOf("Cleanup.kt:12"), "The last row remains", null, emptyList()))))
    setContent {
      SkillBillTheme(reducedMotion = true) {
        val list = rememberLazyListState()
        val collapse = rememberSectionCollapse()
        val state = verifyDesignState(run)
        LazyColumn(Modifier.width(700.dp).background(SkillBillTheme.colors.bgPanel)) {
          DetailSections(this, collapse, list).verifyCriteriaSection(verifyCriteriaView(run, VerifyCriteriaFilter.All), run, state, verifyFixItems(run, emptyMap()), {})
        }
      }
    }
    onNodeWithText("The last row remains").assertDoesNotExist()
    onNodeWithText("Retention cleanup deletes every old row.").performClick()
    onNodeWithText("The last row remains").assertExists()
    onNodeWithText("Cleanup.kt:12").assertExists()
    onNodeWithText("Not a code gap here?").assertExists()
    onNodeWithText("Retention cleanup deletes every old row.").performClick()
    onNodeWithText("The last row remains").assertDoesNotExist()
  }

  @Test
  fun `design states render readable summaries and score navigation at narrow widths`() = runComposeUiTest {
    var run by mutableStateOf(verifyDesignFixture())
    var narrow by mutableStateOf(false)
    var jump = ""
    var expectedColor = androidx.compose.ui.graphics.Color.Unspecified
    setContent {
      SkillBillTheme(reducedMotion = true) {
        expectedColor = SkillBillTheme.colors.fg
        val list = rememberLazyListState()
        val collapse = rememberSectionCollapse()
        val state = verifyDesignState(run)
        LazyColumn(Modifier.width(if (narrow) 330.dp else 760.dp).background(SkillBillTheme.colors.bgPanel).padding(24.dp), state = list) {
          item { VerifyHeader(run, {}) { VerifyHeaderActions(state, run, runActionsView(state), remember { FocusRequester() }, {}, {}) } }
          DetailSections(this, collapse, list).verifyDocument(state, run, {}, { jump = it })
        }
      }
    }
    val layouts = mutableListOf<TextLayoutResult>()
    onAllNodesWithText("Retention cleanup deletes every old row.").onFirst().performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
    assertEquals(expectedColor, layouts.single().layoutInput.style.color)
    val completed = run.copy(
      workflowStatus = "completed", steps = run.steps.map { it.copy(status = "completed", attemptCount = 1) },
      completenessAudit = VerifyCompletenessAudit("had_gaps", emptyList(), listOf(
        VerifyCriterionGrade(1, "PARTIAL", run.criteria[0].text, listOf("Cleanup.kt:12"), "The last row remains", null, listOf("Cleanup.kt:12")),
        VerifyCriterionGrade(2, "PASS", run.criteria[1].text, listOf("Links.kt:42"), null, null, emptyList()),
      )),
      reviewFindings = VerifyReviewSection("changes_requested", listOf("F-001"), listOf(VerifyReviewFinding("F-001", "Major", "High", "data/Cleanup.kt:12", "The last old row is retained.", emptyList(), listOf("Cleanup.kt:12")))),
      unitTestValue = VerifyUnitTestValue("completed", emptyList(), null, "Weak", "Test cleanup with populated data.", listOf(VerifyTestValueRow("Retention cleanup", "Weak", "High", listOf("CleanupTest.kt"), "The empty-store test misses the retention boundary.", "Rewrite", emptyList())), emptyList(), listOf("Use populated data."), emptyList(), emptyList()),
    )
    val cases = linkedMapOf(
      "running" to run,
      "pending" to run.copy(workflowStatus = "pending", currentStepId = "collect_inputs", steps = run.steps.map { it.copy(status = "pending", attemptCount = 0) }),
      "confirm" to run.copy(workflowStatus = "pending", currentStepId = "extract_criteria", steps = run.steps.mapIndexed { index, step -> step.copy(status = if (index < 2) "completed" else "pending", attemptCount = if (index < 2) 1 else 0) }),
      "no-criteria" to run.copy(workflowStatus = "pending", currentStepId = "extract_criteria", criteria = emptyList(), criteriaRaw = "No numbered criteria were found.", technicalConstraints = listOf("Keep user data.")),
      "stopped" to run.copy(workflowStatus = "failed"),
      "superseded" to run.copy(workflowStatus = "abandoned", supersededBy = "newer"),
      "approved" to completed.copy( verdict = VerifyRunVerdict("APPROVE", emptyList(), "Deletes records before the retention window.")),
      "request-changes" to completed.copy( verdict = VerifyRunVerdict("REQUEST CHANGES", listOf("Old records remain."), "Deletes records before the retention window.")),
      "qa-fixes" to completed.copy(qa = QaRunDetails(findings = listOf(QaRunFinding("QA-1", "Old records remain visible"))), qaSelection = "on", qaVerdict = "findings_open", verdict = VerifyRunVerdict("APPROVE WITH FIXES", listOf("Warning: QA findings"), "Deletes records before the retention window.")),
    )
    val directory = File("build/reports/verify-design").apply { mkdirs() }
    cases.forEach { (name, fixture) ->
      runOnIdle { run = fixture }
      waitForIdle()
      ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", File(directory, "$name.png"))
    }
    runOnIdle { run = cases.getValue("request-changes") }
    onNode(hasScrollToIndexAction()).performScrollToNode(hasText("Criteria", substring = false))
    onNodeWithText("Retention cleanup deletes every old row.").performClick()
    onNodeWithText("The last row remains").assertExists()
    ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", File(directory, "graded-criteria.png"))
    runOnIdle { run = cases.getValue("running"); narrow = true }
    onNode(hasScrollToIndexAction()).performScrollToIndex(0)
    onNodeWithContentDescription("Criteria: 2 extracted. Go to Criteria").performClick()
    assertEquals(VERIFY_CRITERIA_KEY, jump)
    ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", File(directory, "running-narrow.png"))
  }
}

internal fun verifyDesignFixture() = VerifyRunDetails(
  workflowId = "verify-design", issueKey = "WE-5246", workflowStatus = "running", currentStepId = "code_review",
  startedAt = null, finishedAt = null, intake = "https://linear.app/team/issue/WE-5246/cleanup", target = "HEAD", repoRoot = "/repo/capmo-android", baseRevision = "a12c54f", headRevision = "3878091", reviewMode = "inline", qaSelection = "off", specPath = null,
  steps = VERIFY_STEP_NAMES.keys.mapIndexed { index, id -> VerifyRunStep(id, if (index < 4) "completed" else if (index == 4) "running" else "pending", if (index <= 4) 1 else 0) },
  criteria = listOf(VerifyRunCriterion(1, "Retention cleanup deletes every old row."), VerifyRunCriterion(2, "Pending links keep the correct sync status.")), criteriaRaw = null,
  nonGoals = emptyList(), technicalConstraints = emptyList(), rolloutExpectation = null, completenessAudit = null, reviewFindings = null, flagAudit = null, unitTestValue = null, changedFiles = listOf("data/Cleanup.kt", "data/CleanupTest.kt"), verdict = null,
  qa = null, qaVerdict = null, qaOutcome = null, qaFindingLocations = emptyMap(), supersededBy = null, staleReason = null,
)

internal fun verifyDesignState(run: VerifyRunDetails) = DetailUiState(
  workItem("verify-design", lifecycle = if (run.workflowStatus == "running") Lifecycle.Active else if (run.workflowStatus == "completed") Lifecycle.Terminal else Lifecycle.Blocked).copy(kind = WorkKind.Operation), SectionsUi.Loaded(emptyDetail.copy(verifyRun = run), RunOptions(true, true, "Resume", "codex", listOf("codex"), null), emptyMap(), emptySet()), false, epoch,
)
