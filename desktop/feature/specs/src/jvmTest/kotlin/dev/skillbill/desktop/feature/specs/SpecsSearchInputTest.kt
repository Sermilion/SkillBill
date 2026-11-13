package dev.skillbill.desktop.feature.specs

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextInputSelection
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.semantics.SemanticsProperties
import dev.skillbill.desktop.core.designsystem.SkillBillTheme
import dev.skillbill.desktop.core.domain.model.SpecTreeState
import dev.skillbill.desktop.core.ui.input.LocalShellInput
import dev.skillbill.desktop.core.ui.input.ShellInput
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class SpecsSearchInputTest {
  @Test
  fun `delayed filter projections cannot overwrite typing or move the insertion point`() = runComposeUiTest {
    var state by mutableStateOf(SpecsUiState(searchOpen = true, tree = SpecTreeState.NoCheckout))
    val edits = mutableListOf<String>()
    val shellInput = ShellInput()
    setContent {
      SkillBillTheme(reducedMotion = true) {
        CompositionLocalProvider(LocalShellInput provides shellInput) {
          SpecsPane(state, { action ->
            when (action) {
              is SpecsAction.FilterChanged -> edits += action.text
              SpecsAction.ClearFilters -> state = state.copy(filterText = "", searchOpen = false)
              SpecsAction.OpenSearch -> state = state.copy(searchOpen = true)
              else -> Unit
            }
          }, {})
        }
      }
    }

    val input = onNode(hasSetTextAction())
    input.performClick()
    for (letter in "SONA") input.performTextInput(letter.toString())
    input.assertTextEquals("SONA")
    runOnIdle { assertEquals(listOf("S", "SO", "SON", "SONA"), edits) }

    input.performTextInputSelection(TextRange(2))
    runOnIdle { state = state.copy(filterText = "SO") }
    input.assertTextEquals("SONA")
    input.performTextInput("-")
    input.assertTextEquals("SO-NA")
    input.performTextInputSelection(TextRange(0, 2))
    runOnIdle { state = state.copy(filterText = "SONA") }
    input.performTextInput("AB")
    input.assertTextEquals("AB-NA")
    runOnIdle { assertEquals("AB-NA", edits.last()) }

    runOnIdle { state = state.copy(filterText = edits.last()) }
    onNodeWithContentDescription("Clear filters").performClick()
    input.assertDoesNotExist()
    onNodeWithContentDescription("Search plans").performClick()
    input.assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("")))
    input.performTextInput("SONA")
    input.assertTextEquals("SONA")
  }
}
