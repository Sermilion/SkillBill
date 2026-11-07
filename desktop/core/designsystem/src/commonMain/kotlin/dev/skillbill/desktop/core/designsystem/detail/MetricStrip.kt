package dev.skillbill.desktop.core.designsystem.detail

import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import dev.skillbill.desktop.core.designsystem.LocalContentColor
import dev.skillbill.desktop.core.designsystem.SkillBillRadius
import dev.skillbill.desktop.core.designsystem.SkillBillTheme
import dev.skillbill.desktop.core.designsystem.composite.Edge
import dev.skillbill.desktop.core.designsystem.composite.hairline
import dev.skillbill.desktop.core.designsystem.tabular

private val metricMinCellWidth = 140.dp

/** [lead] precedes the label, [tone] colors the label and cell fill, and [content] replaces the value row. [valueContent] replaces [value] when live and should use [metricValueStyle]. */
@Immutable
class MetricCell(
  val label: String,
  val value: String = "",
  val unit: String? = null,
  val note: String? = null,
  val lead: (@Composable () -> Unit)? = null,
  val tone: Color? = null,
  val content: (@Composable ColumnScope.() -> Unit)? = null,
  val valueContent: (@Composable () -> Unit)? = null,
)

/** `.sb-metric > b`: 16/500 at line height 1, tabular, tracking −0.01em. */
@Composable
fun metricValueStyle(): TextStyle =
  SkillBillTheme.type.tLg.tabular().copy(color = SkillBillTheme.colors.fg, fontWeight = FontWeight.Medium, letterSpacing = (-0.01).em, lineHeight = 1.em)

/**
 * `.sb-metrics`: a 1px frame with the grid inside it, `repeat(auto-fit, minmax(140px, 1fr))`. Every cell but the first draws
 * `border-left`, including the first cell of a wrapped row, and the grid draws no rule between rows.
 */
@Composable
fun MetricStrip(cells: List<MetricCell>, modifier: Modifier = Modifier) {
  val colors = SkillBillTheme.colors
  val shape = RoundedCornerShape(SkillBillRadius.md)
  BoxWithConstraints(modifier.fillMaxWidth().clip(shape).border(1.dp, colors.borderSubtle, shape).padding(1.dp)) {
    val columns = (maxWidth / metricMinCellWidth).toInt().coerceAtLeast(1).coerceAtMost(cells.size.coerceAtLeast(1))
    Column {
      cells.chunked(columns).forEachIndexed { rowIndex, rowCells ->
        Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
          rowCells.forEachIndexed { index, cell ->
            val bordered = rowIndex > 0 || index > 0
            MetricCellView(
              cell,
              Modifier.weight(1f).fillMaxHeight().then(if (bordered) Modifier.hairline(Edge.Start, colors.borderSubtle).padding(start = 1.dp) else Modifier),
            )
          }
          repeat(columns - rowCells.size) { Spacer(Modifier.weight(1f)) }
        }
      }
    }
  }
}

/** `.sb-metric`: 12 padding, 3 between label, value and note; the label and note are 11.5 at the regular weight. */
@Composable
private fun MetricCellView(cell: MetricCell, modifier: Modifier) {
  val colors = SkillBillTheme.colors
  val type = SkillBillTheme.type
  val caption = type.tXs.copy(color = cell.tone ?: colors.fgFaint, fontWeight = FontWeight.Normal)
  Column(modifier.then(if (cell.tone != null) Modifier.background(cell.tone.copy(alpha = 0.06f)) else Modifier).padding(12.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
    if (cell.lead != null) {
      Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        cell.lead.invoke()
        BasicText(cell.label, style = caption, maxLines = 1)
      }
    } else {
      BasicText(cell.label, style = caption, maxLines = 1)
    }
    if (cell.content != null) {
      cell.content.invoke(this)
    } else {
      Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        val valueModifier = Modifier.alignByBaseline()
        if (cell.valueContent != null) {
          Box(valueModifier) { CompositionLocalProvider(LocalContentColor provides colors.fg) { cell.valueContent.invoke() } }
        } else {
          BasicText(cell.value, valueModifier, style = metricValueStyle(), maxLines = 1)
        }
        cell.unit?.let { BasicText(it, Modifier.alignByBaseline(), style = type.tSm.copy(color = colors.fgFaint, lineHeight = 1.em), maxLines = 1) }
      }
    }
    cell.note?.let { BasicText(it, style = caption) }
  }
}
