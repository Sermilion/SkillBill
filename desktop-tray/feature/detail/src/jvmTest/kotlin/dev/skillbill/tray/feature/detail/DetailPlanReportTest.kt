package dev.skillbill.tray.feature.detail

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.v2.runComposeUiTest
import dev.skillbill.tray.core.designsystem.DesignSystemTheme
import dev.skillbill.tray.core.domain.model.ExecutionInstance
import dev.skillbill.tray.core.domain.model.PlanRunReport
import dev.skillbill.tray.core.domain.model.PlannedSubtask
import dev.skillbill.tray.core.domain.repository.Outcome
import dev.skillbill.tray.core.testing.detail
import dev.skillbill.tray.core.testing.fixtureInstant
import dev.skillbill.tray.core.testing.workItem
import dev.skillbill.tray.core.ui.LocalRepositoryNavigation
import dev.skillbill.tray.core.ui.RepositoryNavigation
import kotlin.test.Test

@OptIn(ExperimentalTestApi::class)
class DetailPlanReportTest {
  private val navigation = object : RepositoryNavigation {
    override suspend fun openRepository(root: String, branch: String?) = Outcome.Value(Unit)
    override suspend fun openDirectory(root: String) = Outcome.Value(Unit)
  }

  @Test
  fun `a plan run lists the feature and every subtask it planned`() = runComposeUiTest {
    val report = PlanRunReport(
      "Record in-workflow review runs", ".feature-specs/SKILL-433-review-runs/spec.md",
      listOf(
        PlannedSubtask(1, "Persist review runs", ".feature-specs/SKILL-433-review-runs/spec_subtask_1.md", emptyList()),
        PlannedSubtask(2, "Show review runs", ".feature-specs/SKILL-433-review-runs/spec_subtask_2.md", listOf(1)),
      ),
    )
    val item = workItem("plan")
    val run = ExecutionInstance(
      "plan-1", "phase-plan-1", "terminal", fixtureInstant, fixtureInstant, fixtureInstant, "completed", planReport = report,
    )
    setContent {
      CompositionLocalProvider(LocalRepositoryNavigation provides navigation) {
        DesignSystemTheme {
          DetailContent(DetailUiState(item.identity, DetailLoad.Loaded(detail(item).copy(executionHistory = listOf(run)))), fixtureInstant, {})
        }
      }
    }

    onNodeWithText("Planned 2 subtasks.").assertExists()
    onNodeWithText("Record in-workflow review runs").assertExists()
    onNodeWithText("Persist review runs").assertExists()
    onNodeWithText("spec_subtask_1.md").assertExists()
    onNodeWithText("Show review runs").assertExists()
    onNodeWithText("spec_subtask_2.md · after #1").assertExists()
  }
}
