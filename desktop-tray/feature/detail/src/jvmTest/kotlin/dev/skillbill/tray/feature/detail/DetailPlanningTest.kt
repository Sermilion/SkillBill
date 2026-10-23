package dev.skillbill.tray.feature.detail

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.v2.runComposeUiTest
import dev.skillbill.tray.core.designsystem.DesignSystemTheme
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
class DetailPlanningTest {
  private val navigation = object : RepositoryNavigation {
    override suspend fun openRepository(root: String, branch: String?) = Outcome.Value(Unit)
    override suspend fun openDirectory(root: String) = Outcome.Value(Unit)
    override suspend fun openFile(path: String) = Outcome.Value(Unit)
  }

  @Test
  fun `review shows its pass instead of missing planning checkpoints and real planning keeps its progress`() = runComposeUiTest {
    var item by mutableStateOf(
      workItem("review").copy(
        currentStepId = "review",
        currentStepLabel = "review",
        currentPhaseId = "review",
        currentSubtaskId = "1",
        phaseExecution = PhaseExecution("review", PhaseExecutionKind.PASS, 2, null),
        planningState = "not_started",
        plannedSubtasks = 0,
        totalSubtasks = 3,
      ),
    )
    setContent {
      CompositionLocalProvider(LocalRepositoryNavigation provides navigation) {
        DesignSystemTheme {
          DetailContent(DetailUiState(item.identity, DetailLoad.Loaded(detail(item))), fixtureInstant, {})
        }
      }
    }

    onNodeWithText("pass 2").assertExists()
    onAllNodesWithText("review").onFirst().assertExists()
    onNodeWithText("0/3 plans saved").assertDoesNotExist()

    runOnIdle {
      item = item.copy(
        currentStepId = "planning",
        currentStepLabel = "Planning",
        currentPhaseId = null,
        phaseExecution = null,
      )
    }

    onAllNodesWithText("Planning").onFirst().assertExists()
    onNodeWithText("0/3 plans saved").assertExists()
    onNodeWithText("pass 2").assertDoesNotExist()
  }
}
