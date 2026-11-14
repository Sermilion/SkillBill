package dev.skillbill.desktop.feature.detail

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.runComposeUiTest
import dev.skillbill.desktop.core.designsystem.SkillBillTheme
import dev.skillbill.desktop.core.domain.model.Lifecycle
import dev.skillbill.desktop.core.domain.model.RunOptions
import dev.skillbill.desktop.core.domain.model.WorkKind
import dev.skillbill.desktop.core.testing.workItem
import dev.skillbill.desktop.core.ui.input.LocalShellInput
import dev.skillbill.desktop.core.ui.input.ShellInput
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class DetailHeaderActionsTest {
  @Test
  fun `Copy report shares the run actions row instead of centering on the harness note`() = runComposeUiTest {
    val base = workItem("validation", kind = WorkKind.Phase, lifecycle = Lifecycle.Terminal, finishedAt = epoch)
    val item = base.copy(
      stepLabel = "validation",
      agentId = "codex",
      branch = "feature/x",
      control = base.control.copy(phaseId = "validation", invocationId = "invocation"),
    )
    val detail = emptyDetail.copy(selectedValidationResult = "All checks passed")
    val options = RunOptions(false, true, "Run again", "codex", listOf("codex"), null)
    setContent {
      SkillBillTheme(reducedMotion = true) {
        CompositionLocalProvider(LocalShellInput provides remember { ShellInput() }) {
          DetailScreen(DetailUiState(item, SectionsUi.Loaded(detail, options, emptyMap(), emptySet()), false, epoch), DetailScreenHost(false, "Ctrl I", {}, {})) {}
        }
      }
    }

    val launch = onNode(hasContentDescription("Run again")).getBoundsInRoot()
    val agent = onNode(hasContentDescription("Agent: Codex")).getBoundsInRoot()
    val copy = onNode(hasContentDescription("Copy report")).getBoundsInRoot()
    assertEquals(launch.top, agent.top)
    assertEquals(launch.top, copy.top)
  }
}
