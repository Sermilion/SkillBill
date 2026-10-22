package dev.skillbill.tray.feature.work

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.v2.runComposeUiTest
import dev.skillbill.tray.core.designsystem.DesignSystemTheme
import dev.skillbill.tray.core.domain.model.PhaseExecution
import dev.skillbill.tray.core.domain.model.PhaseExecutionKind
import dev.skillbill.tray.core.testing.fixtureInstant
import dev.skillbill.tray.core.testing.workItem
import kotlin.test.Test

@OptIn(ExperimentalTestApi::class)
class TrayProgressLabelsTest {
  @Test
  fun `tray progress never adds execution counters to any phase label`() = runComposeUiTest {
    var item by mutableStateOf(workItem("compact-progress").copy(
      currentStepLabel = "audit",
      phaseExecution = PhaseExecution("audit", PhaseExecutionKind.ATTEMPT, 7, null, "AC-005"),
    ))
    setContent {
      DesignSystemTheme {
        PopoverWorkRow(item, selected = false, now = fixtureInstant, stale = false, select = {})
      }
    }

    onNodeWithText("audit · AC-5").assertExists()
    onNodeWithText("attempt", substring = true).assertDoesNotExist()

    listOf(
      "review" to PhaseExecutionKind.PASS,
      "implement" to PhaseExecutionKind.ATTEMPT,
      "simplify" to PhaseExecutionKind.SEMANTIC_LOOP,
      "build" to PhaseExecutionKind.GATE_RUN,
      "plan" to PhaseExecutionKind.BOUNDED_EDGE,
    ).forEach { (phase, kind) ->
      runOnIdle {
        item = item.copy(currentStepLabel = phase, phaseExecution = PhaseExecution(phase, kind, 7, null))
      }
      onNodeWithText(phase).assertExists()
      onNodeWithText("7", substring = true).assertDoesNotExist()
    }
  }
}
