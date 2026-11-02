package dev.skillbill.desktop.feature.detail

import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import dev.skillbill.desktop.core.designsystem.SkillBillTheme
import dev.skillbill.desktop.core.designsystem.detail.rememberSectionCollapse
import dev.skillbill.desktop.core.domain.model.DatabaseFileIdentity
import dev.skillbill.desktop.core.domain.model.ExecutionInstance
import dev.skillbill.desktop.core.domain.model.Lifecycle
import dev.skillbill.desktop.core.domain.model.ReviewFindingStatus
import dev.skillbill.desktop.core.domain.model.ReviewFindingsAction
import dev.skillbill.desktop.core.domain.model.ReviewReport
import dev.skillbill.desktop.core.domain.model.ReviewReportFinding
import dev.skillbill.desktop.core.domain.model.WorkKind
import dev.skillbill.desktop.core.testing.workItem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class ReviewActionsSectionTest {
  private val base = workItem("review", lifecycle = Lifecycle.Terminal, finishedAt = epoch)
  private val item = base.copy(kind = WorkKind.Phase, standaloneExecution = true,
    control = base.control.copy(invocationId = "original", phaseId = "review", databaseFile = DatabaseFileIdentity("/selected.db", "file")))
  private val run = ExecutionInstance("original", "execution", "terminal", epoch, epoch, epoch, null, null, null, null, null,
    ReviewReport("changes_requested", null, listOf(ReviewReportFinding("F-007", "Major", null, null, null, "Broken boundary"))))
  private val detail = emptyDetail.copy(executionHistory = listOf(run))

  @Test
  fun `both review action buttons disable when every finding is fixed`() = runComposeUiTest {
    val fixed = detail.copy(executionHistory = listOf(run.copy(reviewReport = run.reviewReport!!.copy(
      findings = run.reviewReport!!.findings.map { it.copy(status = ReviewFindingStatus.Fixed) }))))
    setContent {
      SkillBillTheme(reducedMotion = true) {
        val scroll = rememberLazyListState()
        val collapse = rememberSectionCollapse()
        LazyColumn { DetailSections(this, collapse, scroll).reviewActionsSection(item, fixed) {} }
      }
    }
    onNodeWithText("Fix findings").assertIsNotEnabled()
    onNodeWithText("Verify findings").assertIsNotEnabled()
  }

  @Test
  fun `the review Actions section offers separate fix and verify buttons`() = runComposeUiTest {
    val actions = mutableListOf<DetailAction>()
    setContent {
      SkillBillTheme(reducedMotion = true) {
        val scroll = rememberLazyListState()
        val collapse = rememberSectionCollapse()
        LazyColumn(state = scroll) { DetailSections(this, collapse, scroll).reviewActionsSection(item, detail, actions::add) }
      }
    }
    onNodeWithText("Actions").assertExists()
    onNodeWithText("Fix findings").performClick()
    onNodeWithText("Verify findings").performClick()
    runOnIdle {
      assertEquals<List<DetailAction>>(ReviewFindingsAction.entries.map { DetailAction.ReviewFindings(it) }, actions)
    }
  }

  @Test
  fun `review actions require the selected settled invocation with findings and a database identity`() {
    assertTrue(reviewActionsAvailable(item, detail))
    assertFalse(reviewActionsAvailable(item.copy(running = true), detail))
    assertFalse(reviewActionsAvailable(item.copy(standaloneExecution = false, kind = WorkKind.Goal), detail))
    assertFalse(reviewActionsAvailable(item.copy(control = item.control.copy(databaseFile = null)), detail))
    assertFalse(reviewActionsAvailable(item, detail.copy(executionHistory = listOf(run.copy(invocationId = "other")))))
    assertFalse(reviewActionsAvailable(item, detail.copy(executionHistory = listOf(run.copy(finishedAt = null)))))
    assertFalse(reviewActionsAvailable(item, detail.copy(executionHistory = listOf(run.copy(reviewReport = run.reviewReport!!.copy(findings = emptyList()))))))
  }
}
