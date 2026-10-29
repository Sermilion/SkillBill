package dev.skillbill.desktop.core.designsystem.detail

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
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
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import dev.skillbill.desktop.core.designsystem.SkillBillRadius
import dev.skillbill.desktop.core.designsystem.SkillBillTheme
import dev.skillbill.desktop.core.designsystem.composite.Edge
import dev.skillbill.desktop.core.designsystem.composite.hairline
import dev.skillbill.desktop.core.designsystem.tabular

private val metricMinCellWidth = 140.dp

/** [valueContent] replaces [value] when the value is live, for example a running clock. */
@Immutable
class MetricCell(
  val label: String,
  val value: String = "",
  val unit: String? = null,
  val note: String? = null,
  val valueContent: (@Composable () -> Unit)? = null,
)

@Composable
fun MetricStrip(cells: List<MetricCell>, modifier: Modifier = Modifier) {
  val colors = SkillBillTheme.colors
  val shape = RoundedCornerShape(SkillBillRadius.md)
  BoxWithConstraints(modifier.fillMaxWidth().clip(shape).border(1.dp, colors.borderSubtle, shape)) {
    val columns = (maxWidth / metricMinCellWidth).toInt().coerceAtLeast(1).coerceAtMost(cells.size.coerceAtLeast(1))
    Column {
      cells.chunked(columns).forEachIndexed { rowIndex, rowCells ->
        Row(
          Modifier.fillMaxWidth().height(IntrinsicSize.Min)
            .then(if (rowIndex > 0) Modifier.hairline(Edge.Top, colors.borderSubtle) else Modifier),
        ) {
          rowCells.forEachIndexed { index, cell ->
            MetricCellView(cell, Modifier.weight(1f).fillMaxHeight().then(if (index > 0) Modifier.hairline(Edge.Start, colors.borderSubtle) else Modifier))
          }
          repeat(columns - rowCells.size) { Spacer(Modifier.weight(1f)) }
        }
      }
    }
  }
}

@Composable
private fun MetricCellView(cell: MetricCell, modifier: Modifier) {
  val colors = SkillBillTheme.colors
  val type = SkillBillTheme.type
  Column(modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
    BasicText(cell.label, style = type.tXs.copy(color = colors.fgFaint), maxLines = 1)
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
      val valueModifier = Modifier.alignByBaseline()
      if (cell.valueContent != null) {
        Box(valueModifier) { cell.valueContent.invoke() }
      } else {
        BasicText(cell.value, valueModifier, style = type.tLg.tabular().copy(color = colors.fg, fontWeight = FontWeight.Medium, letterSpacing = (-0.01).em), maxLines = 1)
      }
      cell.unit?.let { BasicText(it, Modifier.alignByBaseline(), style = type.tSm.copy(color = colors.fgFaint), maxLines = 1) }
    }
    cell.note?.let { BasicText(it, style = type.tXs.copy(color = colors.fgFaint)) }
  }
}
