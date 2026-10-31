package dev.skillbill.desktop.feature.chat

import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import com.mikepenz.markdown.compose.Markdown
import com.mikepenz.markdown.compose.components.markdownComponents
import com.mikepenz.markdown.compose.elements.MarkdownCodeBlock
import com.mikepenz.markdown.compose.elements.MarkdownCodeFence
import com.mikepenz.markdown.compose.elements.MarkdownTable
import com.mikepenz.markdown.model.markdownDimens
import dev.skillbill.desktop.core.designsystem.SkillBillRadius
import com.mikepenz.markdown.model.DefaultMarkdownColors
import com.mikepenz.markdown.model.DefaultMarkdownTypography
import com.mikepenz.markdown.model.markdownPadding
import dev.skillbill.desktop.core.designsystem.SkillBillTheme
import dev.skillbill.desktop.core.designsystem.component.runtimeTextStyles

@Composable
internal fun ChatMarkdown(text: String, modifier: Modifier = Modifier) {
  val colors = SkillBillTheme.colors
  val type = SkillBillTheme.type
  val runtimeStyles = runtimeTextStyles()
  val annotator = remember(runtimeStyles) { chatMarkdownAnnotator(runtimeStyles) }
  SelectionContainer {
    Markdown(
      content = text,
      modifier = modifier,
      retainState = true,
      colors = DefaultMarkdownColors(
        text = colors.fg,
        codeBackground = colors.bgInset,
        inlineCodeBackground = colors.bgRaised,
        dividerColor = colors.border,
        tableBackground = colors.bgPanel,
        highlightText = colors.accentText,
        highlightBackground = colors.accentTint,
      ),
      typography = DefaultMarkdownTypography(
        h1 = type.tXl,
        h2 = type.tLgStrong,
        h3 = type.tMd,
        h4 = type.tBaseMedium,
        h5 = type.tBaseMedium,
        h6 = type.tSmMedium,
        text = type.tBase,
        code = type.monoSm,
        inlineCode = type.monoSm,
        quote = type.tBase.copy(color = colors.fgMuted),
        paragraph = type.tBase,
        ordered = type.tBase,
        bullet = type.tBase,
        list = type.tBase,
        textLink = TextLinkStyles(SpanStyle(color = colors.accentText, textDecoration = TextDecoration.Underline)),
        table = type.tSm,
      ),
      annotator = annotator,
      padding = markdownPadding(block = 8.dp, listIndent = 16.dp),
      dimens = markdownDimens(tableCellPadding = 10.dp, tableCellWidth = 150.dp, tableCornerSize = SkillBillRadius.md),
      components = markdownComponents(
        codeFence = { MarkdownCodeFence(it.content, it.node, it.typography.code) { code, language, _ -> ChatCodeBlock(code, language) } },
        codeBlock = { MarkdownCodeBlock(it.content, it.node, it.typography.code) { code, language, _ -> ChatCodeBlock(code, language) } },
        table = {
          Box(Modifier.padding(vertical = 4.dp).border(1.dp, colors.border, RoundedCornerShape(SkillBillRadius.md))) {
            MarkdownTable(it.content, it.node, it.typography.table)
          }
        },
      ),
    )
  }
}
