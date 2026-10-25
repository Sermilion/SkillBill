package dev.skillbill.tray.feature.detail

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.v2.runComposeUiTest
import dev.skillbill.tray.core.designsystem.DesignSystemTheme
import dev.skillbill.tray.core.domain.model.GoalAssessmentFinding
import dev.skillbill.tray.core.domain.model.GoalAssessmentFindingState
import dev.skillbill.tray.core.domain.model.GoalAssessmentResult
import dev.skillbill.tray.core.domain.model.GoalAssessmentState
import dev.skillbill.tray.core.domain.model.GoalAssessmentVerification
import dev.skillbill.tray.core.domain.model.GoalQaResult
import dev.skillbill.tray.core.domain.model.PhaseConclusion
import dev.skillbill.tray.core.domain.model.ReviewFinding
import dev.skillbill.tray.core.domain.model.ReviewPass
import dev.skillbill.tray.core.domain.model.SubtaskPhaseResults
import dev.skillbill.tray.core.domain.repository.Outcome
import dev.skillbill.tray.core.testing.detail
import dev.skillbill.tray.core.testing.fixtureInstant
import dev.skillbill.tray.core.testing.workItem
import dev.skillbill.tray.core.ui.LocalRepositoryNavigation
import dev.skillbill.tray.core.ui.RepositoryNavigation
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class DetailPhaseResultsTest {
  private val navigation = object : RepositoryNavigation {
    override suspend fun openRepository(root: String, branch: String?) = Outcome.Value(Unit)
    override suspend fun openDirectory(root: String) = Outcome.Value(Unit)
    override suspend fun openFile(path: String) = Outcome.Value(Unit)
  }

  @Test
  fun `an approved review with no itemised findings still shows its compact conclusion`() = runComposeUiTest {
    showPhaseResults(
      SubtaskPhaseResults(
        subtaskId = 1,
        reviewPasses = listOf(ReviewPass(1, "approved", 0, emptyList())),
        audit = PhaseConclusion("satisfied", "All criteria met.", "AC-001 met.", 2),
        review = PhaseConclusion(
          "approved",
          "No production-blocking findings; the retained field values survive a restart.",
          "Reviewed pause-state persistence and the detail query.",
          1,
        ),
      ),
    )

    onNodeWithText("pass 1 · approved").assertExists()
    onNodeWithText("No production-blocking findings; the retained field values survive a restart.").assertExists()
    onNodeWithText("Reviewed pause-state persistence and the detail query.").assertExists()
    onNodeWithText("satisfied · 2 runs").assertExists()
    onNodeWithText("All criteria met.").assertExists()
  }

  @Test
  fun `an active audit repair keeps its failed criterion visible in goal activity`() = runComposeUiTest {
    showPhaseResults(
      SubtaskPhaseResults(
        subtaskId = 1,
        reviewPasses = emptyList(),
        audit = PhaseConclusion("advance", "AC-001 remains unresolved.", "AC-001: the caller is not authenticated.", 2),
      ),
    )

    onNodeWithText("AC-001 remains unresolved.").assertExists()
    onNodeWithText("AC-001: the caller is not authenticated.").assertExists()
  }

  @Test
  fun `review findings render as compact prose under the review heading`() = runComposeUiTest {
    showPhaseResults(
      SubtaskPhaseResults(
        subtaskId = 1,
        reviewPasses = listOf(
          ReviewPass(
            1, "changes_requested", 1,
            listOf(ReviewFinding("blocker", "Pause is lost on restart", "The pause flag lives only in memory.")),
          ),
        ),
        audit = null,
      ),
    )

    onNodeWithText("pass 1 · changes requested · 1 unresolved").assertExists()
    onNodeWithText("blocker · Pause is lost on restart").assertExists()
    onNodeWithText("The pause flag lives only in memory.").assertExists()
    onNodeWithText("BLOCKER").assertDoesNotExist()
  }

  @Test
  fun `a review conclusion still appears when the pass list is empty`() = runComposeUiTest {
    showPhaseResults(
      SubtaskPhaseResults(
        subtaskId = 1,
        reviewPasses = emptyList(),
        audit = null,
        review = PhaseConclusion("approved", "No production-blocking findings.", null, 1),
      ),
    )

    onNodeWithText("approved").assertExists()
    onNodeWithText("No production-blocking findings.").assertExists()
  }

  @Test
  fun `goal QA shows its stored prose report`() = runComposeUiTest {
    showPhaseResults(
      qa =
        GoalQaResult(
          "incomplete",
          "other",
          0,
          fixtureInstant,
          reportText = "AC-001 failed. Retained field values are empty after restart.",
        ),
    )

    onNodeWithText("Goal QA").assertExists()
    onNodeWithText("incomplete", substring = true).assertExists()
    onNodeWithText("AC-001 failed. Retained field values are empty after restart.").assertExists()
  }

  @Test
  fun `an assessment-only goal shows its round, exit reason and each finding with its verification label`() = runComposeUiTest {
    showPhaseResults(
      assessment = GoalAssessmentResult(
        GoalAssessmentState.COMPLETED, 3, "persistent_finding", true,
        listOf(
          assessmentFinding("GA-004", "The cache read can be null.", GoalAssessmentVerification.REFUTED, GoalAssessmentFindingState.REFUTED, "The caller guards the null."),
          assessmentFinding("GA-002", "The retry loop never stops.", GoalAssessmentVerification.CONFIRMED, GoalAssessmentFindingState.OPEN, "Reproduced twice."),
          assessmentFinding("GA-006", "Saving twice drops the second edit.", GoalAssessmentVerification.UNVERIFIED, GoalAssessmentFindingState.OPEN, ""),
        ),
      ),
    )

    onNodeWithText("GOAL ASSESSMENT").assertExists()
    onNodeWithText("Completed · round 3 · persistent finding").assertExists()
    onNodeWithText("major · src/Retry.kt:12 · confirmed · open").assertExists()
    onNodeWithText("The retry loop never stops.").assertExists()
    onNodeWithText("Evidence: Reproduced twice.").assertExists()
    onNodeWithText("major · src/Retry.kt:12 · refuted · refuted").assertExists()
    onNodeWithText("Evidence: The caller guards the null.").assertExists()
    onNodeWithText("major · src/Retry.kt:12 · unverified · open").assertExists()
  }

  @Test
  fun `a running second round and a skipped assessment render their own state without findings`() = runComposeUiTest {
    showPhaseResults(assessment = GoalAssessmentResult(GoalAssessmentState.RUNNING, 2, null, true))
    onNodeWithText("Running · round 2").assertExists()
  }

  @Test
  fun `a skipped assessment reads skipped in its summary`() {
    val skipped = GoalAssessmentResult(GoalAssessmentState.SKIPPED, 0, "skipped_no_changes", false)

    assertEquals("Skipped · skipped no changes", goalAssessmentSummary(skipped))
  }

  private fun assessmentFinding(
    ref: String,
    scenario: String,
    verification: GoalAssessmentVerification,
    state: GoalAssessmentFindingState,
    evidence: String,
  ) = GoalAssessmentFinding(ref, "major", "src/Retry.kt", 12, scenario, verification, state, evidence)

  private fun ComposeUiTest.showPhaseResults(
    vararg results: SubtaskPhaseResults,
    qa: GoalQaResult? = null,
    assessment: GoalAssessmentResult? = null,
  ) {
    val item = workItem("phase-results")
    setContent {
      CompositionLocalProvider(LocalRepositoryNavigation provides navigation) {
        DesignSystemTheme {
          DetailContent(
            DetailUiState(
              item.identity,
              DetailLoad.Loaded(detail(item).copy(phaseResults = results.toList(), goalQa = qa, goalAssessment = assessment)),
            ),
            fixtureInstant,
            {},
          )
        }
      }
    }
  }
}
