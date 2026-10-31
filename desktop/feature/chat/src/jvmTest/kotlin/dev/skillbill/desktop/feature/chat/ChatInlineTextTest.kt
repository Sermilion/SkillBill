package dev.skillbill.desktop.feature.chat

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import dev.skillbill.desktop.core.designsystem.component.RuntimeTextStyles
import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.MarkdownTokenTypes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ChatInlineTextTest {
  private val styles =
    RuntimeTextStyles(
      code = SpanStyle(color = Color.Blue),
      path = SpanStyle(color = Color.Yellow),
      link = SpanStyle(color = Color.Cyan),
      bold = SpanStyle(),
      branch = SpanStyle(color = Color.Magenta),
    )

  private fun AnnotatedString.styled(): List<Pair<String, Color>> = spanStyles.map { text.substring(it.start, it.end) to it.item.color }

  @Test
  fun `chat tokens read like runtime text - code spans, paths and the branch after the word branch`() {
    assertEquals(
      listOf("ReviewFindings.kt" to Color.Blue),
      chatInlineToken(MarkdownElementTypes.CODE_SPAN, "`ReviewFindings.kt`", "", styles)?.styled(),
    )
    assertEquals(
      listOf("feature/ui/FindingRow.kt" to Color.Yellow),
      chatInlineToken(MarkdownTokenTypes.TEXT, "feature/ui/FindingRow.kt", "", styles)?.styled(),
    )
    val branch = chatInlineToken(MarkdownTokenTypes.TEXT, "feat/SKILL-453-chat.", "branch", styles)
    assertEquals("feat/SKILL-453-chat.", branch?.text)
    assertEquals(listOf("feat/SKILL-453-chat" to Color.Magenta), branch?.styled())
  }

  @Test
  fun `plain words and a plain word after branch are left to the renderer`() {
    assertNull(chatInlineToken(MarkdownTokenTypes.TEXT, "status", "", styles))
    assertNull(chatInlineToken(MarkdownTokenTypes.TEXT, "is", "branch", styles))
  }
}
