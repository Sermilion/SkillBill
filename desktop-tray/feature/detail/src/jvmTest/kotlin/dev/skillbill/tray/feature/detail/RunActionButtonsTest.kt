package dev.skillbill.tray.feature.detail

import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import dev.skillbill.tray.core.designsystem.DesignSystemTheme
import dev.skillbill.tray.core.domain.model.Lifecycle
import dev.skillbill.tray.core.domain.model.RunOptions
import dev.skillbill.tray.core.testing.detail
import dev.skillbill.tray.core.testing.workItem
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class RunActionButtonsTest {
  @Test
  fun `blocked and failed goals offer resume when the runtime allows launch`() {
    listOf(Lifecycle.BLOCKED, Lifecycle.FAILED).forEach { lifecycle ->
      runComposeUiTest {
        val actions = mutableListOf<DetailAction>()
        val stopped = detail(workItem("stopped", lifecycle)).copy(
          runOptions = RunOptions(true, true, "Resume", "codex", listOf("codex"), null),
        )
        setContent {
          DesignSystemTheme {
            RunActionButtons(
              stopped, false, true, false, false, null, emptySet(), null, false,
              FocusRequester(), false, actions::add,
            )
          }
        }

        onNodeWithText("Resume").assertIsEnabled().performClick()
        assertEquals(listOf<DetailAction>(DetailAction.Launch("codex")), actions)
      }
    }
  }

  @Test
  fun `a blocked goal cannot resume when runtime ownership checks refuse launch`() = runComposeUiTest {
    val stopped = detail(workItem("blocked", Lifecycle.BLOCKED)).copy(
      runOptions = RunOptions(
        true, false, "Resume", "codex", listOf("codex"), "This run still has a running owner.",
      ),
    )
    setContent {
      DesignSystemTheme {
        RunActionButtons(
          stopped, false, true, false, false, null, emptySet(), null, false,
          FocusRequester(), false, {},
        )
      }
    }

    onNodeWithText("Resume").assertIsNotEnabled()
    onNodeWithText("Agent: codex").assertIsNotEnabled()
  }

  @Test
  fun `an idle goal after a crash offers resume with the recorded agent`() = runComposeUiTest {
    val actions = mutableListOf<DetailAction>()
    val interrupted = detail(workItem("interrupted", Lifecycle.IDLE)).copy(
      runOptions = RunOptions(true, true, "Resume", "codex", listOf("codex", "claude"), null),
    )
    setContent {
      DesignSystemTheme {
        RunActionButtons(
          interrupted, false, true, false, false, null, emptySet(), null, false,
          FocusRequester(), false, actions::add,
        )
      }
    }

    onNodeWithText("Resume").assertIsEnabled().performClick()
    assertEquals(listOf<DetailAction>(DetailAction.Launch("codex")), actions)
  }

  @Test
  fun `an idle goal cannot resume when runtime ownership checks refuse launch`() = runComposeUiTest {
    val interrupted = detail(workItem("interrupted", Lifecycle.IDLE)).copy(
      runOptions = RunOptions(
        true, false, "Resume", "codex", listOf("codex"), "This run still has a running owner.",
      ),
    )
    setContent {
      DesignSystemTheme {
        RunActionButtons(
          interrupted, false, true, false, false, null, emptySet(), null, false,
          FocusRequester(), false, {},
        )
      }
    }

    onNodeWithText("Resume").assertIsNotEnabled()
    onNodeWithText("Agent: codex").assertIsNotEnabled()
    onNodeWithText("This run still has a running owner.").assertExists()
  }
}
