package dev.skillbill.desktop.feature.detail

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runComposeUiTest
import dev.skillbill.desktop.core.designsystem.SkillBillTheme
import dev.skillbill.desktop.core.domain.model.PhaseCardOutput
import dev.skillbill.desktop.core.domain.model.PhaseCardState
import dev.skillbill.desktop.core.domain.model.ValidationResult
import kotlin.test.Test

@OptIn(ExperimentalTestApi::class)
class GateRecordedOutputTest {
  @Test
  fun `unfinished gates do not claim their results were lost`() = runComposeUiTest {
    val state = mutableStateOf(PhaseCardState.Running)
    setContent {
      SkillBillTheme(reducedMotion = true) {
        Column {
          BuildRecordedOutput(null, state.value)
          ValidateRecordedOutput(null, state.value, null, emptyDetail, false) {}
        }
      }
    }

    val messages = listOf(
      PhaseCardState.Running to "is running. Results will appear when it finishes.",
      PhaseCardState.Paused to "has not produced a result yet.",
      PhaseCardState.Blocked to "has not produced a result yet.",
      PhaseCardState.Skipped to "was skipped.",
      PhaseCardState.Failed to "ended without a recorded result.",
    )
    messages.forEach { (phaseState, message) ->
      runOnIdle { state.value = phaseState }
      listOf("Build", "Validation").forEach { label ->
        onNodeWithText("$label $message").assertExists()
        onNodeWithText("The ${label.lowercase()} receipt was not retained for this run.").assertDoesNotExist()
      }
    }

    runOnIdle { state.value = PhaseCardState.Done }
    onNodeWithText("The build receipt was not retained for this run.").assertExists()
    onNodeWithText("The validation receipt was not retained for this run.").assertExists()
  }

  @Test
  fun `receipt arriving during validation replaces the running message`() = runComposeUiTest {
    val output = mutableStateOf<PhaseCardOutput?>(null)
    setContent {
      SkillBillTheme(reducedMotion = true) {
        ValidateRecordedOutput(output.value, PhaseCardState.Running, null, emptyDetail, false) {}
      }
    }

    onNodeWithText("Validation is running. Results will appear when it finishes.").assertExists()
    runOnIdle {
      output.value = PhaseCardOutput(
        null, null, ValidationResult("passed", listOf("Desktop checks"), null, emptyList()),
        null, null, null, null, null, null,
      )
    }
    onNodeWithText("Desktop checks").assertExists()
    onNodeWithText("Validation is running. Results will appear when it finishes.").assertDoesNotExist()
    onNodeWithText("The validation receipt was not retained for this run.").assertDoesNotExist()
  }
}
