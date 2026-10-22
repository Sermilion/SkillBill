package dev.skillbill.tray.feature.detail

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.text.TextLayoutResult
import dev.skillbill.tray.core.designsystem.DesignSystemTheme
import dev.skillbill.tray.core.domain.model.Lifecycle
import dev.skillbill.tray.core.domain.model.PhaseExecution
import dev.skillbill.tray.core.domain.model.PhaseExecutionKind
import dev.skillbill.tray.core.domain.model.RunOptions
import dev.skillbill.tray.core.domain.model.WorkPhase
import dev.skillbill.tray.core.domain.model.WorkSubtask
import dev.skillbill.tray.core.domain.repository.Outcome
import dev.skillbill.tray.core.testing.detail
import dev.skillbill.tray.core.testing.fixtureInstant
import dev.skillbill.tray.core.testing.workItem
import dev.skillbill.tray.core.ui.LocalRepositoryNavigation
import dev.skillbill.tray.core.ui.RepositoryNavigation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

@OptIn(ExperimentalTestApi::class)
class DetailFailureBannerTest {
  private val navigation = object : RepositoryNavigation {
    override suspend fun openRepository(root: String, branch: String?) = Outcome.Value(Unit)
    override suspend fun openDirectory(root: String) = Outcome.Value(Unit)
  }

  @Test
  fun `a provider blocked run offers Resume once its running owner exits`() = runComposeUiTest {
    val item = workItem("provider-limit", Lifecycle.BLOCKED)
    val reason = "This run still has a running owner."
    var options by mutableStateOf(RunOptions(
      canCancel = true,
      canLaunch = false,
      launchLabel = "Resume",
      agentId = "claude",
      agentChoices = listOf("claude"),
      unavailableReason = reason,
    ))
    val actions = mutableListOf<DetailAction>()
    setContent {
      DesignSystemTheme {
        RunActionButtons(detail(item).copy(runOptions = options), busy = false, available = true, onAction = actions::add)
      }
    }

    onNodeWithText("Resume").assertIsNotEnabled()
    onNodeWithText(reason).assertExists()
    runOnIdle { options = options.copy(canLaunch = true, unavailableReason = null) }
    onNodeWithText(reason).assertDoesNotExist()
    onNodeWithText("Resume").assertIsEnabled().performClick()
    assertEquals(listOf<DetailAction>(DetailAction.Launch("claude")), actions)
  }

  @Test
  fun `audit labels follow the current criterion while pipeline chips omit every execution counter`() = runComposeUiTest {
    var item by mutableStateOf(workItem("repair").copy(
      currentSubtaskId = "1",
      currentPhaseId = "audit_plan_fix",
      phaseExecution = PhaseExecution("audit_plan_fix", PhaseExecutionKind.ATTEMPT, 1, null, "AC-005"),
    ))
    setContent {
      val detail = detail(item).copy(
        phases = listOf(WorkPhase(item.currentPhaseId!!, "running", 7, false)),
        subtasks = listOf(WorkSubtask(1, "Block capture", "in_progress", null, null, emptyList())),
      )
      CompositionLocalProvider(LocalRepositoryNavigation provides navigation) {
        DesignSystemTheme {
          DetailContent(DetailUiState(item.identity, DetailLoad.Loaded(detail)), fixtureInstant, {})
        }
      }
    }

    onAllNodesWithText("audit_plan_fix · AC-5").assertCountEquals(2)
    onNodeWithText("in_progress · audit_plan_fix · AC-5").assertExists()
    onNodeWithText("repair attempt 1").assertExists()
    onNodeWithText("audit_plan_fix · repair attempt 1").assertDoesNotExist()
    onNodeWithText("audit_plan_fix · 7 runs").assertDoesNotExist()

    runOnIdle {
      item = item.copy(phaseExecution = item.phaseExecution!!.copy(criterionRef = "AC-006"))
    }
    onAllNodesWithText("audit_plan_fix · AC-6").assertCountEquals(2)
    onAllNodesWithText("audit_plan_fix · AC-5").assertCountEquals(0)

    listOf(
      "review" to PhaseExecutionKind.PASS,
      "simplify" to PhaseExecutionKind.SEMANTIC_LOOP,
      "build" to PhaseExecutionKind.GATE_RUN,
      "plan" to PhaseExecutionKind.BOUNDED_EDGE,
      "implement" to PhaseExecutionKind.ATTEMPT,
    ).forEach { (phase, kind) ->
      runOnIdle {
        item = item.copy(currentPhaseId = phase, phaseExecution = PhaseExecution(phase, kind, 2, null))
      }
      onAllNodesWithText(phase).assertCountEquals(2)
      onNodeWithText("$phase · pass 2").assertDoesNotExist()
      onNodeWithText("$phase · run 2").assertDoesNotExist()
      onNodeWithText("$phase · loop 2").assertDoesNotExist()
      onNodeWithText("$phase · gate run 2").assertDoesNotExist()
      onNodeWithText("$phase · edge 2").assertDoesNotExist()
    }
    onNodeWithText("run 2").assertExists()
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
    onNodeWithText("Show full reason").performClick()
    onNodeWithText("Collapse reason").assertExists()
    val layouts = mutableListOf<TextLayoutResult>()
    onAllNodesWithText(reason).onFirst().performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
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
