package dev.skillbill.tray.feature.detail

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.v2.runComposeUiTest
import dev.skillbill.tray.core.designsystem.DesignSystemTheme
import dev.skillbill.tray.core.domain.model.GoalQaResult
import dev.skillbill.tray.core.domain.model.PhaseConclusion
import dev.skillbill.tray.core.domain.model.QaCheckResult
import dev.skillbill.tray.core.domain.model.QaCriterionFinding
import dev.skillbill.tray.core.domain.model.QaCriterionResult
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

@OptIn(ExperimentalTestApi::class)
class DetailPhaseResultsTest {
  private val navigation = object : RepositoryNavigation {
    override suspend fun openRepository(root: String, branch: String?) = Outcome.Value(Unit)
    override suspend fun openDirectory(root: String) = Outcome.Value(Unit)
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
    onNodeWithText("satisfied · 2 attempts").assertExists()
    onNodeWithText("All criteria met.").assertExists()
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
  fun `goal QA lists each criterion as compact pass or fail rows`() = runComposeUiTest {
    showPhaseResults(
      qa =
        GoalQaResult(
          "incomplete",
          "other",
          0,
          fixtureInstant,
          criteria =
            listOf(
              QaCriterionResult(
                "AC-001",
                false,
                listOf(QaCheckResult("Retained field values survive a restart.", false)),
                listOf(
                  QaCriterionFinding("F-1", emptyList(), "Values are still there.", "Values are empty."),
                ),
              ),
              QaCriterionResult(
                "AC-002",
                true,
                listOf(QaCheckResult("The pause flag is written with run state.", true)),
                emptyList(),
              ),
            ),
        ),
    )

    onNodeWithText("Goal QA").assertExists()
    onNodeWithText("incomplete", substring = true).assertExists()
    onNodeWithText("AC-001 failed; 1 of 2 criteria passed.").assertExists()
    onNodeWithText("other").assertDoesNotExist()
    onNodeWithText("AC-001 · failed").assertExists()
    onNodeWithText("AC-002 · passed").assertExists()
    onNodeWithText("Retained field values survive a restart.", substring = true).assertExists()
    onNodeWithText("The pause flag is written with run state.", substring = true).assertExists()
  }

  private fun ComposeUiTest.showPhaseResults(vararg results: SubtaskPhaseResults, qa: GoalQaResult? = null) {
    val item = workItem("phase-results")
    setContent {
      CompositionLocalProvider(LocalRepositoryNavigation provides navigation) {
        DesignSystemTheme {
          DetailContent(
            DetailUiState(
              item.identity,
              DetailLoad.Loaded(detail(item).copy(phaseResults = results.toList(), goalQa = qa)),
            ),
            fixtureInstant,
            {},
          )
        }
      }
    }
  }
}
