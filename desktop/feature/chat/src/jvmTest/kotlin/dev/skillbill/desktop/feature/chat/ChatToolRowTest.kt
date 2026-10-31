package dev.skillbill.desktop.feature.chat

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import dev.skillbill.desktop.core.designsystem.SkillBillTheme
import dev.skillbill.desktop.core.domain.model.ChatEvent
import dev.skillbill.desktop.core.domain.model.ChatToolCall
import kotlin.test.Test

@OptIn(ExperimentalTestApi::class)
class ChatToolRowTest {
  @Test
  fun `tool details expand independently and stay open when the result arrives until collapsed`() = runComposeUiTest {
    val call = ChatToolCall("bash-1", "Bash", "git status --short\ngit diff --stat", "Check branch changes")
    val tool = mutableStateOf(ChatToolEntry(call))
    setContent {
      SkillBillTheme(reducedMotion = true) {
        Column {
          ChatToolRow(tool.value)
          ChatToolRow(ChatToolEntry(ChatToolCall("read-1", "Read", "README.md", "Read project instructions")))
        }
      }
    }

    onNodeWithText("Bash: Check branch changes").assertExists()
    onNodeWithText("Input").assertDoesNotExist()
    onAllNodesWithText("Show more")[0].performClick()
    onNodeWithText(call.input).assertExists()
    onNodeWithText("Result not received yet.").assertExists()
    onNodeWithText("README.md").assertDoesNotExist()

    runOnIdle { tool.value = tool.value.copy(result = ChatEvent.ToolResult(call.id, " M app.kt", false)) }
    onNodeWithText("Completed").assertExists()
    onNodeWithText(" M app.kt").assertExists()
    onNodeWithText("Show less").performClick()
    onNodeWithText(call.input).assertDoesNotExist()
    onNodeWithText(" M app.kt").assertDoesNotExist()
    onNodeWithText("Bash: Check branch changes").assertExists()
  }
}
