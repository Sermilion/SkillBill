package dev.skillbill.tray.feature.detail

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.v2.runComposeUiTest
import dev.skillbill.tray.core.designsystem.DesignSystemTheme
import dev.skillbill.tray.core.domain.model.ExecutionInstance
import dev.skillbill.tray.core.domain.model.WorkFamily
import dev.skillbill.tray.core.domain.model.PhaseExecution
import dev.skillbill.tray.core.domain.model.PhaseExecutionKind
import dev.skillbill.tray.core.domain.repository.Outcome
import dev.skillbill.tray.core.testing.detail
import dev.skillbill.tray.core.testing.fixtureInstant
import dev.skillbill.tray.core.testing.workItem
import dev.skillbill.tray.core.ui.LocalRepositoryNavigation
import dev.skillbill.tray.core.ui.RepositoryNavigation
import kotlin.test.Test

@OptIn(ExperimentalTestApi::class)
class DetailPhaseTest {
  private val navigation = object : RepositoryNavigation {
    override suspend fun openRepository(root: String, branch: String?) = Outcome.Value(Unit)
    override suspend fun openDirectory(root: String) = Outcome.Value(Unit)
  }

  @Test
  fun `verify displays operation activity without phase and goal panels`() = runComposeUiTest {
    val item = workItem("verify", family = WorkFamily.FEATURE_VERIFY, issueKey = null).copy(
      currentStepId = "code_review",
      currentStepLabel = "Code review",
      startedAt = fixtureInstant,
    )
    val detail = detail(item).copy(executionHistory = listOf(
      ExecutionInstance("invocation", "execution", "active", fixtureInstant, fixtureInstant, null, null),
    ))
    setContent {
      CompositionLocalProvider(LocalRepositoryNavigation provides navigation) {
        DesignSystemTheme {
          DetailContent(DetailUiState(item.identity, DetailLoad.Loaded(detail)), fixtureInstant, {})
        }
      }
    }

    onNodeWithText("Operation: Verify").assertExists()
    onNodeWithText("operation").assertExists()
    onNodeWithText("STEP").assertExists()
    onNodeWithText("Code review").assertExists()
    onNodeWithText("OPERATION ELAPSED").assertExists()
    onNodeWithText("OPERATION ACTIVITY").assertExists()
    listOf("PHASE", "Goal finish", "GOAL ACTIVE", "SUBTASK ACTIVE", "PHASE PIPELINE", "GOAL ACTIVITY").forEach {
      onNodeWithText(it).assertDoesNotExist()
    }
  }

  @Test
  fun `missing planning checkpoints do not hide the executing phase and its progress`() = runComposeUiTest {
    val item = workItem("executing").copy(
      currentSubtaskId = "2",
      currentStepId = "audit",
      currentStepLabel = "audit",
      planningState = "preplanned",
      plannedSubtasks = 0,
      totalSubtasks = 3,
      phaseExecution = PhaseExecution("audit", PhaseExecutionKind.PASS, 1, 2),
    )
    setContent {
      CompositionLocalProvider(LocalRepositoryNavigation provides navigation) {
        DesignSystemTheme {
          DetailContent(DetailUiState(item.identity, DetailLoad.Loaded(detail(item).copy(currentPhase = "audit"))), fixtureInstant, {})
        }
      }
    }

    onNodeWithText("audit").assertExists()
    onNodeWithText("pass 1").assertExists()
    onNodeWithText("0/3 plans saved").assertDoesNotExist()
  }

  @Test
  fun `a planning goal still shows its saved plan count`() = runComposeUiTest {
    val item = workItem("planning").copy(
      currentStepId = "planning",
      currentStepLabel = "Planning",
      planningState = "partially_planned",
      plannedSubtasks = 1,
      totalSubtasks = 3,
    )
    setContent {
      CompositionLocalProvider(LocalRepositoryNavigation provides navigation) {
        DesignSystemTheme {
          DetailContent(DetailUiState(item.identity, DetailLoad.Loaded(detail(item))), fixtureInstant, {})
        }
      }
    }

    onNodeWithText("Planning").assertExists()
    onNodeWithText("1/3 plans saved").assertExists()
  }
}
