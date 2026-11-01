package dev.skillbill.desktop.feature.detail

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runComposeUiTest
import dev.skillbill.desktop.core.designsystem.SkillBillTheme
import dev.skillbill.desktop.core.domain.model.Detail
import dev.skillbill.desktop.core.domain.model.PhaseConclusion
import dev.skillbill.desktop.core.domain.model.ReviewFindingStatus
import dev.skillbill.desktop.core.domain.model.ReviewPass
import dev.skillbill.desktop.core.domain.model.ReviewReportFinding
import dev.skillbill.desktop.core.domain.model.SubtaskPhaseResults
import dev.skillbill.desktop.core.domain.model.WorkSubtask
import dev.skillbill.desktop.core.ui.input.LocalShellInput
import dev.skillbill.desktop.core.ui.input.ShellInput
import kotlin.test.Test

@OptIn(ExperimentalTestApi::class)
class SubtaskInspectorResultsTest {
  private fun subtask(status: String) = WorkSubtask(1, "Build the view", status, null, null, emptyList())

  private fun finding(ref: String, status: ReviewFindingStatus) =
    ReviewReportFinding(ref, "major", "high", "bill-kotlin-code-review-persistence", "src/a/File.kt:84", "Finding $ref", status)

  private fun ComposeUiTest.show(status: String, detail: Detail) {
    setContent {
      SkillBillTheme(reducedMotion = true) {
        CompositionLocalProvider(LocalShellInput provides ShellInput()) {
          SubtaskInspector(detail.copy(subtasks = listOf(subtask(status))), subtask(status), inline = true) {}
        }
      }
    }
  }

  private fun results(vararg passes: ReviewPass) = SubtaskPhaseResults(1, passes.toList(), null, null)

  @Test
  fun `completed passes show the count, a clean pass line, its lane and a finding's meta`() = runComposeUiTest {
    val first = ReviewPass(1, "changes_requested", 0, listOf(finding("F-001", ReviewFindingStatus.Fixed)), listOf("bill-kotlin-code-review-persistence"))
    val second = ReviewPass(2, "approved", 0, emptyList(), emptyList())
    show("complete", emptyDetail.copy(phaseResults = listOf(results(first, second))))

    onNodeWithText("2 passes").assertExists()
    onNodeWithText("Pass 2").assertExists()
    onNodeWithText("· no findings").assertExists()
    onNodeWithText("Lanes").assertExists()
    onNodeWithText("F-001").assertExists()
    onNodeWithText("File.kt:84").assertExists()
    onNodeWithText("persistence · high confidence").assertExists()
  }

  @Test
  fun `an open finding reads after the fixed one and both show their state`() = runComposeUiTest {
    val pass = ReviewPass(
      1, "changes_requested", 1,
      listOf(finding("F-001", ReviewFindingStatus.Open), finding("F-002", ReviewFindingStatus.Fixed)),
      emptyList(),
    )
    show("blocked", emptyDetail.copy(phaseResults = listOf(results(pass))))

    onNodeWithText("· 2 findings · 1 fixed · 1 open").assertExists()
    onNodeWithText("Open").assertExists()
    onNodeWithText("Fixed").assertExists()
  }

  @Test
  fun `a pending subtask says when review and audit run`() = runComposeUiTest {
    show("pending", emptyDetail)

    onNodeWithText("Runs after implementation").assertExists()
    onNodeWithText("Runs after review").assertExists()
  }

  @Test
  fun `an unreadable read names its source and shows no verdict`() = runComposeUiTest {
    val audit = PhaseConclusion("audit_verdict", null, null, 1)
    val entry = SubtaskPhaseResults(1, emptyList(), audit, null, "subtask_review_findings")
    show("complete", emptyDetail.copy(phaseResults = listOf(entry)))

    onNodeWithText("degraded at subtask_review_findings", substring = true).assertExists()
    onNodeWithText("· audit_verdict").assertDoesNotExist()
    onNodeWithText("Audit").assertDoesNotExist()
  }

  @Test
  fun `a loading subtask shows captions and no pending copy`() = runComposeUiTest {
    show("complete", emptyDetail)

    onNodeWithText("Review").assertExists()
    onNodeWithText("Audit").assertExists()
    onNodeWithText("Runs after", substring = true).assertDoesNotExist()
  }
}
