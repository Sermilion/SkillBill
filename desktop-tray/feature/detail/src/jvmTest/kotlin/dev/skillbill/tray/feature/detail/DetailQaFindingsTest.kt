package dev.skillbill.tray.feature.detail

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.v2.runComposeUiTest
import dev.skillbill.tray.core.designsystem.DesignSystemTheme
import dev.skillbill.tray.core.domain.model.GoalQaFinding
import dev.skillbill.tray.core.domain.model.GoalQaResult
import dev.skillbill.tray.core.domain.repository.Outcome
import dev.skillbill.tray.core.testing.detail
import dev.skillbill.tray.core.testing.fixtureInstant
import dev.skillbill.tray.core.testing.workItem
import dev.skillbill.tray.core.ui.LocalRepositoryNavigation
import dev.skillbill.tray.core.ui.RepositoryNavigation
import kotlin.test.Test

@OptIn(ExperimentalTestApi::class)
class DetailQaFindingsTest {
  private val navigation = object : RepositoryNavigation {
    override suspend fun openRepository(root: String, branch: String?) = Outcome.Value(Unit)
    override suspend fun openDirectory(root: String) = Outcome.Value(Unit)
    override suspend fun openFile(path: String) = Outcome.Value(Unit)
  }

  @Test
  fun `findings QA could not fix are listed for the operator beside the fixed ones`() = runComposeUiTest {
    showGoalQa(
      qa("findings", 3, GoalQaFinding("F-1", "Login button does nothing.", "/qa/run-1/login.png", fixed = true),
        GoalQaFinding("F-2", "Home screen stays blank.", "/qa/run-4/home.png", fixed = false),
        GoalQaFinding("F-3", "Settings screen crashes.", "/qa/run-4/settings.png", fixed = false)),
    )

    onNodeWithText("QA FINDINGS").assertExists()
    onNodeWithText("QA could not fix 2 findings after 3 attempts. Review them yourself.").assertExists()
    onNodeWithText("Not fixed").assertExists()
    onNodeWithText("Home screen stays blank.").assertExists()
    onNodeWithText("/qa/run-4/home.png").assertExists()
    onNodeWithText("F-3").assertExists()
    onNodeWithText("Fixed").assertExists()
    onNodeWithText("Login button does nothing.").assertExists()
    onNodeWithText("/qa/run-1/login.png").assertDoesNotExist()
  }

  @Test
  fun `a QA run that passed after qa_fix still lists every finding it caught as fixed`() = runComposeUiTest {
    showGoalQa(
      qa("passed", 1, GoalQaFinding("F-1", "Login button does nothing.", null, fixed = true),
        GoalQaFinding("F-2", "Home screen stays blank.", null, fixed = true)),
    )

    onNodeWithText("QA found 2 findings; all fixed after 1 attempt.").assertExists()
    onNodeWithText("Login button does nothing.").assertExists()
    onNodeWithText("Home screen stays blank.").assertExists()
    onNodeWithText("Not fixed").assertDoesNotExist()
  }

  @Test
  fun `a QA run without findings shows no QA findings section`() = runComposeUiTest {
    showGoalQa(qa("passed", 0))

    onNodeWithText("Goal QA").assertExists()
    onNodeWithText("QA FINDINGS").assertDoesNotExist()
  }

  private fun qa(outcome: String, attempts: Int, vararg findings: GoalQaFinding) =
    GoalQaResult(outcome, null, attempts, fixtureInstant, findings.toList())

  private fun ComposeUiTest.showGoalQa(qa: GoalQaResult) {
    val item = workItem("qa")
    setContent {
      CompositionLocalProvider(LocalRepositoryNavigation provides navigation) {
        DesignSystemTheme {
          DetailContent(DetailUiState(item.identity, DetailLoad.Loaded(detail(item).copy(goalQa = qa))), fixtureInstant, {})
        }
      }
    }
  }
}
