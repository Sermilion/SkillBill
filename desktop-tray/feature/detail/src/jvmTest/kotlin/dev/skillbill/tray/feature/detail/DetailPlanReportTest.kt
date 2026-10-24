package dev.skillbill.tray.feature.detail

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import dev.skillbill.tray.core.designsystem.DesignSystemTheme
import dev.skillbill.tray.core.domain.model.ExecutionInstance
import dev.skillbill.tray.core.domain.model.PlanRunReport
import dev.skillbill.tray.core.domain.model.PlannedSubtask
import dev.skillbill.tray.core.domain.model.WorkSubtask
import dev.skillbill.tray.core.domain.repository.Outcome
import dev.skillbill.tray.core.testing.detail
import dev.skillbill.tray.core.testing.fixtureInstant
import dev.skillbill.tray.core.testing.workItem
import dev.skillbill.tray.core.ui.LocalRepositoryNavigation
import dev.skillbill.tray.core.ui.RepositoryNavigation
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class DetailPlanReportTest {
  private val openedFiles = mutableListOf<String>()
  private val navigation = object : RepositoryNavigation {
    override suspend fun openRepository(root: String, branch: String?) = Outcome.Value(Unit)
    override suspend fun openDirectory(root: String) = Outcome.Value(Unit)
    override suspend fun openFile(path: String): Outcome<Unit> {
      openedFiles += path
      return Outcome.Value(Unit)
    }
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
    onAllNodesWithText("spec_subtask_1.md").assertCountEquals(2)
    onNodeWithText("Show review runs").assertExists()
    onNodeWithText("spec_subtask_2.md · after #1").assertExists()
  }

  @Test
  fun `bundle files open with their exact absolute path and a relative path is not clickable`() = runComposeUiTest {
    val store = "/home/user/.skill-bill/projects/p1/specs/SKILL-1-x"
    val report = PlanRunReport(
      "Feature", "$store/spec.md", emptyList(),
      bundleFiles = listOf("$store/spec.md", "$store/spec_subtask_1_a.md", ".feature-specs/SKILL-1-x/legacy.md"),
    )
    val item = workItem("bundle")
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

    onNodeWithContentDescription("Open spec.md").performClick()
    onNodeWithContentDescription("Open spec_subtask_1_a.md").performClick()
    waitForIdle()
    assertEquals(listOf("$store/spec.md", "$store/spec_subtask_1_a.md"), openedFiles)
    onNodeWithText("legacy.md").assertHasNoClickAction()
  }

  @Test
  fun `a goal lists its spec bundle files the same way a plan run does`() = runComposeUiTest {
    val store = "/home/user/.skill-bill/projects/p1/specs/SKILL-2-goal"
    val bundle = PlanRunReport(
      "Goal", "$store/spec.md", emptyList(),
      bundleFiles = listOf("$store/decomposition-manifest.yaml", "$store/spec.md", "$store/spec_subtask_1.md"),
    )
    val item = workItem("goal")
    val goal = detail(item).copy(
      subtasks = listOf(WorkSubtask(1, "First subtask", "pending", null, null, emptyList())),
      goalSpecBundle = bundle,
    )
    setContent {
      CompositionLocalProvider(LocalRepositoryNavigation provides navigation) {
        DesignSystemTheme {
          DetailContent(DetailUiState(item.identity, DetailLoad.Loaded(goal)), fixtureInstant, {})
        }
      }
    }

    onNodeWithText("Spec bundle").assertExists()
    onNodeWithContentDescription("Open decomposition-manifest.yaml").performClick()
    onNodeWithContentDescription("Open spec_subtask_1.md").performClick()
    waitForIdle()
    assertEquals(listOf("$store/decomposition-manifest.yaml", "$store/spec_subtask_1.md"), openedFiles)
  }
}
