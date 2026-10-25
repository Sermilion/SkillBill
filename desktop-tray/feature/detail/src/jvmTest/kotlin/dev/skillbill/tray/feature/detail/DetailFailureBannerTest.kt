package dev.skillbill.tray.feature.detail

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.text.TextLayoutResult
import dev.skillbill.tray.core.designsystem.DesignSystemTheme
import dev.skillbill.tray.core.domain.model.Lifecycle
import dev.skillbill.tray.core.domain.model.PhaseExecution
import dev.skillbill.tray.core.domain.model.PhaseExecutionKind
import dev.skillbill.tray.core.domain.model.WorkPhase
import dev.skillbill.tray.core.domain.model.WorkSubtask
import dev.skillbill.tray.core.domain.repository.Outcome
import dev.skillbill.tray.core.testing.detail
import dev.skillbill.tray.core.testing.fixtureInstant
import dev.skillbill.tray.core.testing.workItem
import dev.skillbill.tray.core.ui.LocalRepositoryNavigation
import dev.skillbill.tray.core.ui.RepositoryNavigation
import kotlin.test.Test
import kotlin.test.assertFalse

@OptIn(ExperimentalTestApi::class)
class DetailFailureBannerTest {
  private val navigation = object : RepositoryNavigation {
    override suspend fun openRepository(root: String, branch: String?) = Outcome.Value(Unit)
    override suspend fun openDirectory(root: String) = Outcome.Value(Unit)
    override suspend fun openFile(path: String) = Outcome.Value(Unit)
  }

  @Test
  fun `the phase header and chip show the criterion repair attempt instead of accumulated launches`() = runComposeUiTest {
    val item = workItem("repair").copy(
      currentSubtaskId = "1",
      currentPhaseId = "audit_plan_fix",
      phaseExecution = PhaseExecution("audit_plan_fix", PhaseExecutionKind.ATTEMPT, 1, null),
    )
    val detail = detail(item).copy(
      phases = listOf(WorkPhase("audit_plan_fix", "running", 3, false)),
      subtasks = listOf(WorkSubtask(1, "Block capture", "in_progress", null, null, emptyList())),
    )
    setContent {
      CompositionLocalProvider(LocalRepositoryNavigation provides navigation) {
        DesignSystemTheme {
          DetailContent(DetailUiState(item.identity, DetailLoad.Loaded(detail)), fixtureInstant, {})
        }
      }
    }

    onNodeWithText("repair attempt 1").assertExists()
    onNodeWithText("audit_plan_fix · repair attempt 1").assertExists()
    onNodeWithText("audit_plan_fix · 3 runs").assertDoesNotExist()
  }

  @Test
  fun `a blocked goal shows its child failure above the acceptance progress and expands its full diagnostic`() = runComposeUiTest {
    val reason = "Subtask 1 finished without a terminal workflow-store outcome. " +
      "The child process exited with status 1. " +
      "Child stderr: Runtime-owned persistence fact could not be established at serial_repair_write. " +
      "Acceptance criterion repair state fails schema validation: evidence_ref must be at most 512 characters long."
    val item = workItem("blocked", Lifecycle.BLOCKED).copy(
      summary = "Acceptance criteria: Criterion AC-001; 0 failures used; 3 failures remaining.",
      exactPauseReason = reason,
    )
    setContent {
      CompositionLocalProvider(LocalRepositoryNavigation provides navigation) {
        DesignSystemTheme {
          DetailContent(DetailUiState(item.identity, DetailLoad.Loaded(detail(item))), fixtureInstant, {})
        }
      }
    }

    onNodeWithText("Run blocked").assertExists()
    onAllNodesWithText(reason).onFirst().assertExists()
    onNodeWithText(item.summary).assertDoesNotExist()
    onAllNodesWithText("Show more").onFirst().performClick()
    onNodeWithText("Close").assertExists()
    val layouts = mutableListOf<TextLayoutResult>()
    onAllNodesWithText(reason).onLast().performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
    assertFalse(layouts.single().hasVisualOverflow)
  }

  @Test
  fun `a failed run displays an error banner with its saved failure`() = runComposeUiTest {
    val reason = "The repair process failed because its workflow record could not be saved."
    val item = workItem("failed", Lifecycle.FAILED).copy(exactPauseReason = reason)
    setContent {
      CompositionLocalProvider(LocalRepositoryNavigation provides navigation) {
        DesignSystemTheme {
          DetailContent(DetailUiState(item.identity, DetailLoad.Loaded(detail(item))), fixtureInstant, {})
        }
      }
    }

    onNodeWithText("Run failed").assertExists()
    onNodeWithText(reason).assertExists()
    onNodeWithText("Copy reason").assertExists()
  }
}
