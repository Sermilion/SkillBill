package dev.skillbill.tray.feature.detail

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.v2.runComposeUiTest
import dev.skillbill.tray.core.designsystem.DesignSystemTheme
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

    onNodeWithText("approved").assertExists()
    onNodeWithText("pass 1", substring = true).assertDoesNotExist()
    onNodeWithText("No production-blocking findings; the retained field values survive a restart.").assertExists()
    onNodeWithText("Reviewed pause-state persistence and the detail query.").assertExists()
    onNodeWithText("satisfied").assertExists()
    onNodeWithText("2 attempts", substring = true).assertDoesNotExist()
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

    onNodeWithText("changes requested · 1 unresolved").assertExists()
    onNodeWithText("pass 1", substring = true).assertDoesNotExist()
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
  fun `goal QA shows its outcome and stored report text without a bare reason token`() = runComposeUiTest {
    showPhaseResults(
      qa =
        GoalQaResult(
          "incomplete",
          "other",
          3,
          fixtureInstant,
          reportText = "incomplete\n\nThe restart flow could not be completed.",
        ),
    )

    onNodeWithText("Goal QA").assertExists()
    onNodeWithText("incomplete ·", substring = true).assertExists()
    onNodeWithText("other").assertDoesNotExist()
    onNodeWithText("fix attempts", substring = true).assertDoesNotExist()
    onNodeWithText("The restart flow could not be completed.", substring = true).assertExists()
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
