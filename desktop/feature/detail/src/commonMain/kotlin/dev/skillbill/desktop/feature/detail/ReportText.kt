package dev.skillbill.desktop.feature.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.skillbill.desktop.core.designsystem.SkillBillTheme
import dev.skillbill.desktop.core.designsystem.component.rememberRuntimeText

/** One block of an agent's Markdown-like report, as [ReportText] draws it. */
internal sealed interface ReportBlock {
  data class Heading(val text: String) : ReportBlock

  data class Paragraph(val text: String) : ReportBlock

  data class Bullets(val items: List<String>) : ReportBlock

  data class Code(val text: String) : ReportBlock
}

private val BULLET = Regex("""^\s*[-*•]\s+""")

/**
 * [text] split into blocks: `#` headings, fenced code, runs of `-`/`*` bullets (an indented line continues the bullet above it),
 * and paragraphs, which a blank line, a heading or a bullet ends.
 */
internal fun reportBlocks(text: String): List<ReportBlock> {
  val blocks = mutableListOf<ReportBlock>()
  val paragraph = mutableListOf<String>()
  val bullets = mutableListOf<String>()
  var code: MutableList<String>? = null
  fun flush() {
    if (paragraph.isNotEmpty()) blocks += ReportBlock.Paragraph(paragraph.joinToString(" "))
    if (bullets.isNotEmpty()) blocks += ReportBlock.Bullets(bullets.toList())
    paragraph.clear()
    bullets.clear()
  }
  text.trim().lines().forEach { line ->
    val open = code
    when {
      open != null && line.trimStart().startsWith("```") -> { blocks += ReportBlock.Code(open.joinToString("\n")); code = null }
      open != null -> open += line
      line.trimStart().startsWith("```") -> { flush(); code = mutableListOf() }
      line.isBlank() -> flush()
      line.startsWith("#") -> { flush(); blocks += ReportBlock.Heading(line.trimStart('#', ' ')) }
      BULLET.containsMatchIn(line) -> {
        if (paragraph.isNotEmpty()) flush()
        bullets += line.replaceFirst(BULLET, "")
      }
      bullets.isNotEmpty() && line.startsWith(" ") -> bullets[bullets.lastIndex] = bullets.last() + " " + line.trim()
      else -> {
        if (bullets.isNotEmpty()) flush()
        paragraph += line.trim()
      }
    }
  }
  code?.let { blocks += ReportBlock.Code(it.joinToString("\n")) }
  flush()
  return blocks
}

/** An agent's report text, selectable, as headings, paragraphs, bullet lists and code, 12 apart. */
@Composable
internal fun ReportText(text: String, modifier: Modifier = Modifier, blocks: List<ReportBlock> = remember(text) { reportBlocks(text) }) {
  val colors = SkillBillTheme.colors
  val type = SkillBillTheme.type
  SelectionContainer(modifier) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
      blocks.forEach { block ->
        when (block) {
          is ReportBlock.Heading -> BasicText(rememberRuntimeText(block.text), style = type.tBaseMedium.copy(color = colors.fg))
          is ReportBlock.Paragraph -> BasicText(rememberRuntimeText(block.text), style = type.tSm.copy(color = colors.fg2))
          is ReportBlock.Code -> BasicText(block.text, style = type.monoSm.copy(color = colors.fg2))
          is ReportBlock.Bullets -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            block.items.forEach { item ->
              Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                BasicText("•", style = type.tSm.copy(color = colors.fgFaint))
                BasicText(rememberRuntimeText(item), style = type.tSm.copy(color = colors.fg2))
              }
            }
          }
        }
      }
    }
  }
}
