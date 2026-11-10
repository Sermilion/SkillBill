package dev.skillbill.desktop.feature.chat

import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.skillbill.desktop.core.designsystem.SkillBillTheme
import kotlin.test.Test
import kotlin.test.assertFalse
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

  @Test
  fun `wide streaming tables scroll without truncating headers or cell content`() = runComposeUiTest {
    val header = "A long route heading that needs several lines to remain readable"
    val firstCell = "Hosted dashboard with a customer-managed execution worker and complete progress reporting"
    val lastCell = "Delivers the laptop-closed experience while keeping every result available to the customer"
    val reply = mutableStateOf("| $header | What you host | Main tradeoff |\n| --- | --- | --- |\n| $firstCell | Accounts and jobs | Partial")
    setContent {
      CompositionLocalProvider(LocalInspectionMode provides true) {
        SkillBillTheme(reducedMotion = true) { ChatMarkdown(reply.value, Modifier.width(320.dp)) }
      }
    }

    runOnIdle {
      reply.value = "| $header | What you host | Main tradeoff |\n| --- | --- | --- |\n| $firstCell | **Dashboard** plus temporary machines | $lastCell |"
    }
    listOf(header, firstCell, lastCell).forEach { text ->
      val layouts = mutableListOf<TextLayoutResult>()
      onNodeWithText(text, substring = true).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
      val layout = layouts.single()
      assertFalse(layout.hasVisualOverflow, "Table text must remain readable: $text")
      assertTrue((0 until layout.lineCount).none(layout::isLineEllipsized))
      assertTrue(layout.lineCount > 1, "Long cells must wrap")
    }
    val formatted = onNodeWithText("Dashboard plus temporary machines", substring = true)
      .fetchSemanticsNode().config[SemanticsProperties.Text].single()
    assertTrue(formatted.spanStyles.any { it.item.fontWeight == FontWeight.Bold })

    val table = onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.HorizontalScrollAxisRange))
    val range = table.fetchSemanticsNode().config[SemanticsProperties.HorizontalScrollAxisRange]
    assertTrue(range.maxValue() > 0f)
    table.performSemanticsAction(SemanticsActions.ScrollBy) { it(range.maxValue(), 0f) }
    runOnIdle { assertTrue(range.value() > 0f) }
    onNodeWithText(lastCell, substring = true).assertIsDisplayed()
  }

}
