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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

@OptIn(ExperimentalTestApi::class)
class AgentPickerTest {
  @Test
  fun `unsupported profiled agent uses unavailable chip label and keeps model`() {
    assertEquals("Unavailable agent · retained-model", chipText(ChatAgent("retired-agent", "retained-model", null, "/profiles/retired")))
  }

  // Choosing an agent used to close the popover and switch at once, so picking its model took a second trip.
  @Test
  fun `highlighting another agent previews its models and one model pick switches both`() = runComposeUiTest {
    val choices = mutableListOf<Pair<String, ModelPick>>()
    picker(ChatAgent("codex", null, null), onChoose = { agent, pick -> choices += agent to pick }) { agent ->
      flowOf(ChatModelsRead.Loaded(listOf(ChatModel("${agent.agentId}-model", "${agent.agentId}-model"))))
    }
    onNodeWithText(chipText(ChatAgent("codex", null, null))).performClick()
    onNodeWithText("codex-model").assertExists()

    onNodeWithText("claude").performClick()
    onNodeWithText("claude-model").assertExists()
    onNodeWithText("codex-model").assertDoesNotExist()
    runOnIdle { assertEquals(emptyList(), choices) }

    onNodeWithText("claude-model").performClick()
    runOnIdle { assertEquals(listOf("claude" to ModelPick("claude-model", null, "claude-model")), choices) }
    onNodeWithText("claude-model").assertDoesNotExist()
  }

  @Test
  fun `configured row picks the matrix model with its effort`() = runComposeUiTest {
    var choice: ModelPick? = null
    picker(ChatAgent("codex", null, null), resolve = { ChatAgent(it, "gpt-5", "high") }, onChoose = { _, pick -> choice = pick }) {
      flowOf(ChatModelsRead.Loaded(listOf(ChatModel("gpt-5", "GPT-5"))))
    }
    onNodeWithText(chipText(ChatAgent("codex", null, null))).performClick()
    onNodeWithText("Configured").performClick()
    runOnIdle { assertEquals(ModelPick("gpt-5", "high", "gpt-5"), choice) }
  }

  @Test
  fun `operator searches provider models and chooses an exact model id`() = runComposeUiTest {
    var choice: ModelPick? = null
    picker(ChatAgent("codex", null, null), onChoose = { _, pick -> choice = pick }) {
      flowOf(ChatModelsRead.Loaded(listOf(ChatModel("new-model", "New model"), ChatModel("other", "Other model"))))
    }
    onNodeWithText(chipText(ChatAgent("codex", null, null))).performClick()
    onNode(hasSetTextAction()).performTextInput("new")
    onNodeWithText("Other model").assertDoesNotExist()
    onNodeWithText("New model").performClick()
    runOnIdle { assertEquals(ModelPick("new-model", null, "New model"), choice) }
  }

  @Test
  fun `agent default clears the explicit model`() = runComposeUiTest {
    var choice: ModelPick? = null
    picker(ChatAgent("codex", "new-model", null), onChoose = { _, pick -> choice = pick }) {
      flowOf(ChatModelsRead.Loaded(listOf(ChatModel("new-model", "New model"))))
    }
    onNodeWithText(chipText(ChatAgent("codex", "new-model", null))).performClick()
    onNodeWithText("Agent default").performClick()
    runOnIdle { assertNull(choice?.model) }
  }

  @Test
  fun `failed discovery is visible and retry fetches the model list again`() = runComposeUiTest {
    var reads = 0
    picker(ChatAgent("claude", null, null)) {
      if (++reads == 1) flowOf(ChatModelsRead.Failed("Sign in and retry"))
      else flowOf(ChatModelsRead.Loaded(listOf(ChatModel("new-model", "New model"))))
    }
    onNodeWithText(chipText(ChatAgent("claude", null, null))).performClick()
    onNodeWithText("Sign in and retry").assertExists()
    onNodeWithText("Retry").performClick()
    onNodeWithText("New model").assertExists()
    runOnIdle { assertEquals(2, reads) }
  }

  @Test
  fun `a stored list renders while discovery runs and keeps a refresh failure under it`() = runComposeUiTest {
    picker(ChatAgent("codex", null, null)) {
      flowOf(ChatModelsRead.Cached(listOf(ChatModel("old-model", "Old model")), "Discovery failed"))
    }
    onNodeWithText(chipText(ChatAgent("codex", null, null))).performClick()
    onNodeWithText("Old model").assertExists()
    onNodeWithText("Discovery failed").assertExists()
    onNodeWithText("Refresh models").assertExists()
  }

  @Test
  fun `a differing fresh read replaces the stored list`() = runComposeUiTest {
    picker(ChatAgent("codex", null, null)) {
      flow {
        emit(ChatModelsRead.Cached(listOf(ChatModel("old-model", "Old model"))))
        emit(ChatModelsRead.Loaded(listOf(ChatModel("fresh-model", "Fresh model"))))
      }
    }
    onNodeWithText(chipText(ChatAgent("codex", null, null))).performClick()
    onNodeWithText("Fresh model").assertExists()
    onNodeWithText("Old model").assertDoesNotExist()
  }

  @Test
  fun `without a repository the model section asks for one and loads nothing`() = runComposeUiTest {
    var reads = 0
    picker(ChatAgent("claude", null, null), repositoryRoot = null) { reads++; flowOf(ChatModelsRead.Loaded(emptyList())) }
    onNodeWithText(chipText(ChatAgent("claude", null, null))).performClick()
    onNodeWithText("Choose a repository to list models.").assertExists()
    runOnIdle { assertEquals(0, reads) }
  }

  private fun ComposeUiTest.picker(
    agent: ChatAgent,
    repositoryRoot: String? = "/repo",
    resolve: (String) -> ChatAgent = { ChatAgent(it, null, null) },
    onChoose: (String, ModelPick) -> Unit = { _, _ -> },
    load: (ChatAgent) -> Flow<ChatModelsRead>,
  ) {
    setContent {
      Themed {
        AgentPicker(agent, listOf("claude", "codex"), repositoryRoot, enabled = true,
          resolveAgent = { resolve(it) }, loadModels = { chosen, _ -> load(chosen) }, onChoose = onChoose)
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
