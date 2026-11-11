package dev.skillbill.desktop.feature.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.skillbill.desktop.core.designsystem.SkillBillTheme
import dev.skillbill.desktop.core.designsystem.composite.Edge
import dev.skillbill.desktop.core.designsystem.composite.hairline

@Composable
internal fun VerifyReportText(text: String, modifier: Modifier = Modifier) {
  val colors = SkillBillTheme.colors
  val sections = remember(text) { verifyReportSections(text) }
  SelectionContainer(modifier) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
      sections.forEach { section ->
        if (section.table) {
          val rows = section.text.lines().map { line -> line.trim().trim('|').split('|').map(String::trim) }
          val headings = rows.first()
          rows.drop(2).forEach { cells ->
            Column(Modifier.hairline(Edge.Top, colors.borderSubtle).padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
              cells.forEachIndexed { index, cell -> if (cell.isNotBlank()) VerifyReceiptField(headings.getOrNull(index).orEmpty(), cell) }
            }
          }
        } else reportBlocks(section.text).forEach { block ->
          when (block) {
            is ReportBlock.Heading -> BasicText(verifyText(block.text), style = SkillBillTheme.type.tSmMedium.copy(color = colors.fg))
            is ReportBlock.Paragraph -> BasicText(verifyText(block.text), style = SkillBillTheme.type.tSm.copy(color = colors.fg2))
            is ReportBlock.Code -> BasicText(block.text, style = SkillBillTheme.type.monoSm.copy(color = colors.fg2))
            is ReportBlock.Bullets -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
              block.items.forEach { item ->
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                  BasicText("•", style = SkillBillTheme.type.tSm.copy(color = colors.fgFaint))
                  BasicText(verifyText(item), Modifier.weight(1f), style = SkillBillTheme.type.tSm.copy(color = colors.fg2))
                }
              }
            }
          }
        }
      }
    }
  }
}

private data class VerifyReportSection(val text: String, val table: Boolean)

private fun verifyReportSections(text: String): List<VerifyReportSection> {
  val lines = text.lines()
  val sections = mutableListOf<VerifyReportSection>()
  val prose = mutableListOf<String>()
  var index = 0
  var fenced = false
  fun flush() {
    if (prose.any(String::isNotBlank)) sections += VerifyReportSection(prose.joinToString("\n"), false)
    prose.clear()
  }
  while (index < lines.size) {
    val line = lines[index]
    if (line.trimStart().startsWith("```")) fenced = !fenced
    val separator = lines.getOrNull(index + 1)?.trim()?.trim('|')?.split('|')
    val table = !fenced && line.trim().startsWith('|') && separator?.size == line.trim().trim('|').split('|').size && separator.all { it.trim().matches(Regex(":?-{3,}:?")) }
    if (table) {
      flush()
      val rows = mutableListOf(line, lines[index + 1])
      index += 2
      while (index < lines.size && lines[index].trim().startsWith('|')) rows += lines[index++]
      sections += VerifyReportSection(rows.joinToString("\n"), true)
    } else {
      prose += line
      index++
    }
  }
  flush()
  return sections
}
