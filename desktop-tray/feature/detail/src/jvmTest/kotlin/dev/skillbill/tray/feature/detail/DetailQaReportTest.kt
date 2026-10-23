package dev.skillbill.tray.feature.detail

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.v2.runComposeUiTest
import dev.skillbill.tray.core.designsystem.DesignSystemTheme
import dev.skillbill.tray.core.domain.model.ExecutionInstance
import dev.skillbill.tray.core.domain.model.QaVerdict
import dev.skillbill.tray.core.domain.repository.Outcome
import dev.skillbill.tray.core.testing.detail
import dev.skillbill.tray.core.testing.fixtureInstant
import dev.skillbill.tray.core.testing.workItem
import dev.skillbill.tray.core.ui.LocalRepositoryNavigation
import dev.skillbill.tray.core.ui.RepositoryNavigation
import kotlin.test.Test

@OptIn(ExperimentalTestApi::class)
class DetailQaReportTest {
  private val navigation = object : RepositoryNavigation {
    override suspend fun openRepository(root: String, branch: String?) = Outcome.Value(Unit)
    override suspend fun openDirectory(root: String) = Outcome.Value(Unit)
  }

  @Test
  fun `a standalone QA run shows its stored report text verbatim`() = runComposeUiTest {
    val report = "findings\n\n### QA-1-stale-card-prefill The Card field shows a stale value\nObserved: the Card field shows 4444."
    showQaRun(report)

    onNodeWithText("QA verdict: findings_open.").assertExists()
    onNodeWithText(report).assertExists()
  }

  private fun ComposeUiTest.showQaRun(reportText: String) {
    val item = workItem("qa")
    val run = ExecutionInstance(
      "phr-1", "phase-phr-1", "terminal", fixtureInstant, fixtureInstant, fixtureInstant,
      "QA verdict: findings_open.", qaVerdict = QaVerdict.FINDINGS, qaReportText = reportText,
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
