package dev.skillbill.desktop.feature.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
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
        if (section.numbered.isNotEmpty()) {
          section.numbered.forEach { (number, item) ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
              BasicText(number, style = SkillBillTheme.type.tSm.copy(color = colors.fgFaint))
              VerifyReportText(item, Modifier.weight(1f))
            }
          }
        } else if (section.table) {
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
            is ReportBlock.Paragraph -> BasicText(verifyText(block.text), style = SkillBillTheme.type.tSm.copy(color = colors.fg2, lineHeight = 1.5.em))
            is ReportBlock.Code -> BasicText(block.text, Modifier.fillMaxWidth().background(colors.bgInset).horizontalScroll(rememberScrollState()).padding(10.dp), style = SkillBillTheme.type.monoSm.copy(color = colors.fg2, lineHeight = 1.5.em))
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

private data class VerifyReportSection(val text: String, val table: Boolean, val numbered: List<Pair<String, String>> = emptyList())

private val VERIFY_NUMBERED_ITEM = Regex("^\\s*(\\d+[.)])\\s+(.+)$")

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
    if (!fenced && VERIFY_NUMBERED_ITEM.matches(line)) {
      flush()
      val items = mutableListOf<Pair<String, String>>()
      while (index < lines.size) {
        val item = VERIFY_NUMBERED_ITEM.matchEntire(lines[index])
        if (item != null) {
          items += item.groupValues[1] to item.groupValues[2]
        } else if (lines[index].startsWith(" ") && lines[index].isNotBlank()) {
          val last = items.removeAt(items.lastIndex)
          items += last.first to "${last.second}\n${lines[index].trim()}"
        } else break
        index++
      }
      sections += VerifyReportSection("", false, items)
    } else if (table) {
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
