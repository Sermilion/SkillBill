package dev.skillbill.desktop.feature.chat

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runComposeUiTest
import dev.skillbill.desktop.core.designsystem.SkillBillTheme
import dev.skillbill.desktop.core.domain.model.ChatAgent
import dev.skillbill.desktop.core.domain.model.ChatModel
import dev.skillbill.desktop.core.domain.model.ChatModelsRead
import dev.skillbill.desktop.core.ui.input.LocalShellInput
import dev.skillbill.desktop.core.ui.input.ShellInput
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class ChatModelPickerTest {
  @Test
  fun `operator searches provider models and chooses an exact model id`() = runComposeUiTest {
    var choice: ChatModel? = null
    setContent {
      SkillBillTheme(reducedMotion = true) {
        CompositionLocalProvider(LocalShellInput provides ShellInput()) {
          ChatModelPicker(ChatAgent("codex", null, null), "/repo", true,
            load = { _, _ -> ChatModelsRead.Loaded(listOf(ChatModel("new-model", "New model"), ChatModel("other", "Other model"))) },
            onChoose = { choice = it })
        }
      }
    }
    onNodeWithText("Model: default").performClick()
    onNode(hasSetTextAction()).performTextInput("new")
    onNodeWithText("Other model").assertDoesNotExist()
    onNodeWithText("New model").performClick()
    runOnIdle { assertEquals(ChatModel("new-model", "New model"), choice) }
  }

  @Test
  fun `failed discovery is visible and retry fetches the model list again`() = runComposeUiTest {
    var reads = 0
    setContent {
      SkillBillTheme(reducedMotion = true) {
        CompositionLocalProvider(LocalShellInput provides ShellInput()) {
          ChatModelPicker(ChatAgent("claude", null, null), "/repo", true, load = { _, _ ->
            if (++reads == 1) ChatModelsRead.Failed("Sign in and retry")
            else ChatModelsRead.Loaded(listOf(ChatModel("new-model", "New model")))
          }, onChoose = {})
        }
      }
    }
    onNodeWithText("Model: default").performClick()
    onNodeWithText("Sign in and retry").assertExists()
    onNodeWithText("Retry").performClick()
    onNodeWithText("New model").assertExists()
    runOnIdle { assertEquals(2, reads) }
  }
}
