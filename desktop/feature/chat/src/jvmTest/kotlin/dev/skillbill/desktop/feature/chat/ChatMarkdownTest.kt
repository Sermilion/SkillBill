package dev.skillbill.desktop.feature.chat

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.text.font.FontWeight
import dev.skillbill.desktop.core.designsystem.SkillBillTheme
import kotlin.test.Test
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class ChatMarkdownTest {
  @Test
  fun `agent replies render markdown while a code fence is still streaming`() = runComposeUiTest {
    val reply = "## Status\n\n**Blocked** on `check`.\n\n- Retry **validation**\n\n" +
      "[Open issue](https://github.com/Sermilion/SkillBill/issues/1)\n\n```sh\n./gradlew check"
    setContent {
      CompositionLocalProvider(LocalInspectionMode provides true) {
        SkillBillTheme(reducedMotion = true) { ChatMarkdown(reply) }
      }
    }

    onNodeWithText("Status").assertExists()
    val paragraph = onNodeWithText("Blocked on", substring = true).fetchSemanticsNode().config[SemanticsProperties.Text].single()
    assertTrue(paragraph.spanStyles.any { it.item.fontWeight == FontWeight.Bold })
    onNodeWithText("Retry validation").assertExists()
    onNodeWithText("./gradlew check", substring = true).assertExists()
    onNodeWithText("Open issue", substring = true).assertExists()
    onNodeWithText("**Blocked**", substring = true).assertDoesNotExist()
    onNodeWithText("[Open issue]", substring = true).assertDoesNotExist()
  }
}
