package dev.skillbill.tray.feature.detail

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.v2.runComposeUiTest
import dev.skillbill.tray.core.designsystem.DesignSystemTheme
import dev.skillbill.tray.core.domain.model.ExecutionInstance
import dev.skillbill.tray.core.domain.model.QaCheckResult
import dev.skillbill.tray.core.domain.model.QaCriterionFinding
import dev.skillbill.tray.core.domain.model.QaCriterionResult
import dev.skillbill.tray.core.domain.model.QaRunReport
import dev.skillbill.tray.core.domain.model.QaVerdict
import dev.skillbill.tray.core.domain.repository.Outcome
import dev.skillbill.tray.core.testing.detail
import dev.skillbill.tray.core.testing.fixtureInstant
import dev.skillbill.tray.core.testing.workItem
import dev.skillbill.tray.core.ui.LocalRepositoryNavigation
import dev.skillbill.tray.core.ui.RepositoryNavigation
import kotlin.test.Test

@OptIn(ExperimentalTestApi::class)
class DetailQaCriteriaTest {
  private val navigation = object : RepositoryNavigation {
    override suspend fun openRepository(root: String, branch: String?) = Outcome.Value(Unit)
    override suspend fun openDirectory(root: String) = Outcome.Value(Unit)
  }

  @Test
  fun `a QA run lists every criterion and says how the failed one failed`() = runComposeUiTest {
    showQaRun(
      QaRunReport(
        listOf(
          QaCriterionResult("AC-1", passed = true, listOf(QaCheckResult("Card field opens the saved-card picker.", true)), emptyList()),
          QaCriterionResult(
            "AC-2", passed = false, listOf(QaCheckResult("Card field stays empty for a profile with no cards.", false)),
            listOf(QaCriterionFinding("QA-1-stale-card-prefill", listOf("Delete the work card"), "The Card field is empty.", "The Card field shows 4444.")),
          ),
        ),
      ),
    )

    onNodeWithText("QA verdict: findings_open.").assertExists()
    onNodeWithText("AC-2 failed; 1 of 2 criteria passed.").assertExists()
    onNodeWithText("AC-1").assertExists()
    onNodeWithText("✓ Card field opens the saved-card picker.").assertExists()
    onNodeWithText("✗ Card field stays empty for a profile with no cards.").assertExists()
    onNodeWithText("QA-1-stale-card-prefill").assertExists()
    onNodeWithText("1. Delete the work card").assertExists()
    onNodeWithText("The Card field is empty.").assertExists()
    onNodeWithText("The Card field shows 4444.").assertExists()
  }

  private fun ComposeUiTest.showQaRun(report: QaRunReport) {
    val item = workItem("qa")
    val run = ExecutionInstance(
      "phr-1", "phase-phr-1", "terminal", fixtureInstant, fixtureInstant, fixtureInstant,
      "QA verdict: findings_open.", qaVerdict = QaVerdict.FINDINGS, qaReport = report,
    )
    setContent {
      CompositionLocalProvider(LocalRepositoryNavigation provides navigation) {
        DesignSystemTheme {
          DetailContent(DetailUiState(item.identity, DetailLoad.Loaded(detail(item).copy(executionHistory = listOf(run)))), fixtureInstant, {})
        }
      }
    }
  }
}
