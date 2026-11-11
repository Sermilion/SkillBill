package dev.skillbill.desktop.feature.chat

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.ComposeUiTest
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
import kotlin.test.assertNull

@OptIn(ExperimentalTestApi::class)
class AgentPickerTest {
  @Test
  fun `one chip lists agents and the current agent's models`() = runComposeUiTest {
    var agentChoice: String? = null
    picker(ChatAgent("codex", null, null), onChoose = { agentChoice = it }) {
      ChatModelsRead.Loaded(listOf(ChatModel("new-model", "New model")))
    }
    onNodeWithText("Model: default").assertDoesNotExist()
    onNodeWithText(chipText(ChatAgent("codex", null, null))).performClick()
    onNodeWithText("New model").assertExists()
    onNodeWithText("claude").performClick()
    runOnIdle { assertEquals("claude", agentChoice) }
  }

  @Test
  fun `operator searches provider models and chooses an exact model id`() = runComposeUiTest {
    var choice: ChatModel? = null
    picker(ChatAgent("codex", null, null), onChooseModel = { choice = it }) {
      ChatModelsRead.Loaded(listOf(ChatModel("new-model", "New model"), ChatModel("other", "Other model")))
    }
    onNodeWithText(chipText(ChatAgent("codex", null, null))).performClick()
    onNode(hasSetTextAction()).performTextInput("new")
    onNodeWithText("Other model").assertDoesNotExist()
    onNodeWithText("New model").performClick()
    runOnIdle { assertEquals(ChatModel("new-model", "New model"), choice) }
  }

  @Test
  fun `agent default clears the explicit model`() = runComposeUiTest {
    var choice: ChatModel? = ChatModel("x", "x")
    picker(ChatAgent("codex", "new-model", null), onChooseModel = { choice = it }) {
      ChatModelsRead.Loaded(listOf(ChatModel("new-model", "New model")))
    }
    onNodeWithText(chipText(ChatAgent("codex", "new-model", null))).performClick()
    onNodeWithText("Agent default").performClick()
    runOnIdle { assertNull(choice) }
  }

  @Test
  fun `failed discovery is visible and retry fetches the model list again`() = runComposeUiTest {
    var reads = 0
    picker(ChatAgent("claude", null, null)) {
      if (++reads == 1) ChatModelsRead.Failed("Sign in and retry")
      else ChatModelsRead.Loaded(listOf(ChatModel("new-model", "New model")))
    }
    onNodeWithText(chipText(ChatAgent("claude", null, null))).performClick()
    onNodeWithText("Sign in and retry").assertExists()
    onNodeWithText("Retry").performClick()
    onNodeWithText("New model").assertExists()
    runOnIdle { assertEquals(2, reads) }
  }

  @Test
  fun `without a repository the model section asks for one and loads nothing`() = runComposeUiTest {
    var reads = 0
    picker(ChatAgent("claude", null, null), repositoryRoot = null) { reads++; ChatModelsRead.Loaded(emptyList()) }
    onNodeWithText(chipText(ChatAgent("claude", null, null))).performClick()
    onNodeWithText("Choose a repository to list models.").assertExists()
    runOnIdle { assertEquals(0, reads) }
  }

  private fun ComposeUiTest.picker(
    agent: ChatAgent,
    repositoryRoot: String? = "/repo",
    onChoose: (String) -> Unit = {},
    onChooseModel: (ChatModel?) -> Unit = {},
    load: () -> ChatModelsRead,
  ) {
    setContent {
      Themed {
        AgentPicker(agent, listOf("claude", "codex"), repositoryRoot, enabled = true,
          loadModels = { _, _ -> load() }, onChooseModel = onChooseModel, onChoose = onChoose)
      }
    }
  }

  @Composable
  private fun Themed(content: @Composable () -> Unit) {
    SkillBillTheme(reducedMotion = true) {
      CompositionLocalProvider(LocalShellInput provides ShellInput(), content = content)
    }
  }
}
