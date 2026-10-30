package dev.skillbill.desktop.core.designsystem.component

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import dev.skillbill.desktop.core.designsystem.SkillBillTheme
import dev.skillbill.desktop.core.designsystem.tabular

enum class ProgressRingTone { Subtasks, PlansSaved }

@Composable
fun ProgressRing(done: Int, total: Int, tone: ProgressRingTone = ProgressRingTone.Subtasks, showText: Boolean = true, tooltip: String? = null) {
  val fraction = if (total > 0) (done.toFloat() / total).coerceIn(0f, 1f) else 0f
  val colors = SkillBillTheme.colors
  val trackColor = colors.borderStrong
  val arcColor = when { tone == ProgressRingTone.PlansSaved -> colors.stPlanning; fraction >= 1f -> colors.stDone; else -> colors.accent }
  val label = "$done of $total ${if (tone == ProgressRingTone.PlansSaved) "plans saved" else "subtasks complete"}"
  val content: @Composable () -> Unit = { Row(Modifier.semantics { contentDescription = label }, horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
    Canvas(Modifier.size(14.dp)) {
      drawCircle(trackColor, radius = 5.5.dp.toPx(), style = Stroke(width = 2.dp.toPx()))
      if (fraction > 0f) {
        drawArc(arcColor, -90f, 360f * fraction, false, topLeft = Offset(1.5.dp.toPx(), 1.5.dp.toPx()), size = Size(11.dp.toPx(), 11.dp.toPx()), style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round))
      } else {
        // A zero-length dash keeps its round cap in SVG, so 0/n still shows a dot at the top of the ring.
        drawCircle(arcColor, radius = 1.dp.toPx(), center = Offset(size.width / 2, 1.5.dp.toPx()))
      }
    }
    if (showText) BasicText("$done/$total", style = SkillBillTheme.type.tSmMedium.tabular().copy(color = SkillBillTheme.colors.fgMuted, lineHeight = 1.em), maxLines = 1, softWrap = false)
  } }
  if (tooltip != null) SbTooltip(tooltip) { content() } else content()
}
