package dev.skillbill.desktop.feature.detail

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import dev.skillbill.desktop.core.designsystem.SkillBillTheme
import dev.skillbill.desktop.core.designsystem.detail.rememberSectionCollapse
import dev.skillbill.desktop.core.domain.model.QaRunDetails
import dev.skillbill.desktop.core.domain.model.QaRunFinding
import dev.skillbill.desktop.core.domain.model.VerifyReviewSection
import dev.skillbill.desktop.core.domain.model.VerifyRunDetails
import dev.skillbill.desktop.core.testing.workItem
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class VerifyQaReceiptTest {
  @Test
  fun `a finished run's QA step shows the shared QA results with fix selection on its findings`() = runComposeUiTest {
    val run = run("completed", "findings_open")
    val items = verifyFixItems(run, emptyMap())
    val actions = mutableListOf<DetailAction>()
    setContent {
      SkillBillTheme(reducedMotion = true) {
        VerifyQaReceipt(run, qaResultsView(verifyQaInput(run)!!), rememberSectionCollapse(), uiState(), items) { actions += it }
      }
    }

    onNodeWithText("Needs your review").assertExists()
    onNodeWithText("Old records remain visible").performClick()
    onNodeWithText("Only records in the retention window remain.").assertExists()
    onNodeWithText("cleanup.png").assertExists()
    onNodeWithContentDescription("Select all 1").performClick()
    onNodeWithContentDescription("Select ${items.single().label}").performClick()

    assertEquals(listOf(DetailAction.ToggleSelectAllFixes(FixKind.QaFinding), DetailAction.ToggleFix("qa:QA-1")), actions)
  }

  @Test
  fun `a running run's QA step draws no selection and keeps the shared Unblock group`() = runComposeUiTest {
    val run = run("running", "incomplete", report = "## Unblock\n#### app_startup_gate\n- remedy: Install the emulator image.")
    setContent {
      SkillBillTheme(reducedMotion = true) {
        VerifyQaReceipt(run, qaResultsView(verifyQaInput(run)!!), rememberSectionCollapse(), uiState(), verifyFixItems(run, emptyMap())) {}
      }
    }

    onNodeWithText("Old records remain visible").assertExists()
    onNodeWithContentDescription("Select all 1").assertDoesNotExist()
    onNodeWithText("Unblock").assertExists()
    onNodeWithText("Open qa.md").assertExists()
  }

  private fun uiState() = DetailUiState(workItem("verify"), SectionsUi.Loaded(emptyDetail, null, emptyMap(), emptySet()), false, epoch)

  private fun run(status: String, verdict: String, report: String? = null) = VerifyRunDetails(
    workflowId = "verify-1", issueKey = null, workflowStatus = status, currentStepId = null,
    startedAt = null, finishedAt = null, intake = "SKILL-1", target = "feature/verify", repoRoot = "/repo",
    baseRevision = "base", headRevision = "head", reviewMode = null, qaSelection = "on", specPath = null,
    steps = emptyList(), criteria = emptyList(), criteriaRaw = null,
    nonGoals = emptyList(), technicalConstraints = emptyList(), rolloutExpectation = null,
    completenessAudit = null, reviewFindings = VerifyReviewSection(null, emptyList(), emptyList()), flagAudit = null,
    unitTestValue = null, changedFiles = emptyList(), verdict = null,
    qa = QaRunDetails(findings = listOf(QaRunFinding("QA-1", "Old records remain visible", "Old records remain after cleanup.", "Only records in the retention window remain.", "Run cleanup against populated data.", listOf("/tmp/qa/cleanup.png")))),
    qaVerdict = verdict, qaOutcome = null, qaFindingLocations = emptyMap(), supersededBy = null, staleReason = null, qaReportText = report,
  )
}
