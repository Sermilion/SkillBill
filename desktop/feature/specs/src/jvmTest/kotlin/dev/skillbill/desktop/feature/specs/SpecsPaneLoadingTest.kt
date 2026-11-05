package dev.skillbill.desktop.feature.specs

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runComposeUiTest
import dev.skillbill.desktop.core.designsystem.SkillBillTheme
import dev.skillbill.desktop.core.domain.model.SpecCounts
import dev.skillbill.desktop.core.domain.model.SpecProject
import dev.skillbill.desktop.core.domain.model.SpecTree
import dev.skillbill.desktop.core.domain.model.SpecTreeState
import dev.skillbill.desktop.core.ui.input.LocalShellInput
import dev.skillbill.desktop.core.ui.input.ShellInput
import kotlin.test.Test

@OptIn(ExperimentalTestApi::class)
class SpecsPaneLoadingTest {
  @Test
  fun `loading shows placeholders after the delay and unknown counts until the tree arrives`() = runComposeUiTest {
    val project = SpecProject("project", "Project", "/specs", "/repo")
    var state by mutableStateOf(SpecsUiState(project = project, tree = SpecTreeState.Loading))
    mainClock.autoAdvance = false
    setContent {
      SkillBillTheme(reducedMotion = true) {
        CompositionLocalProvider(LocalShellInput provides ShellInput()) {
          SpecsPane(state, {}, {})
        }
      }
    }

    mainClock.advanceTimeByFrame()
    onNodeWithContentDescription(LOADING_SPECS).assertDoesNotExist()
    onNodeWithText("All").assertIsDisplayed()
    onNodeWithText("All 0").assertDoesNotExist()

    mainClock.advanceTimeBy(SPECS_LOADING_REVEAL_DELAY_MS + 50)
    onNodeWithContentDescription(LOADING_SPECS).assertIsDisplayed()

    val tree = SpecTree(project, emptyList(), SpecCounts(3, 1, 1, 1), false)
    state = state.copy(tree = SpecTreeState.Loaded(tree, "/specs"))
    mainClock.advanceTimeByFrame()
    onNodeWithContentDescription(LOADING_SPECS).assertDoesNotExist()
    onNodeWithText("All 3").assertIsDisplayed()
  }
}
