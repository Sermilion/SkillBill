package dev.skillbill.desktop.core.designsystem.usage

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.skillbill.desktop.core.designsystem.SkillBillTheme
import dev.skillbill.desktop.core.designsystem.tabular

/** Usage text is Inter Regular, like the linear-study usage card; the shared tXs style is Medium. */
@Composable
internal fun usageText() = SkillBillTheme.type.tXs.copy(fontWeight = FontWeight.Normal)

/** The reset line: Inter Regular at the design's 2xs size. The shared t2xs style is monospace. */
@Composable
internal fun usageSmallText() = SkillBillTheme.type.tXs.copy(fontWeight = FontWeight.Normal, fontSize = 10.5.sp, lineHeight = 15.75.sp)

/**
 * Green while most of the allowance is left, yellow around half, red as it runs out. [remaining] is 0 (used up) to 1 (untouched).
 */
@Composable
fun usageRemainingColor(remaining: Float): Color {
  val colors = SkillBillTheme.colors
  val green = colors.stDone
  val yellow = colors.data1
  val red = colors.stFailed
  val left = remaining.coerceIn(0f, 1f)
  return if (left >= .5f) lerp(yellow, green, (left - .5f) / .5f) else lerp(red, yellow, left / .5f)
}

/**
 * A label, its value and a thin track. Compact rows put the reset countdown after the label; [detailed] rows give it its own line.
 */
@Composable
fun UsageMeterRow(meter: UsageUiMeter, modifier: Modifier = Modifier, detailed: Boolean = false) {
  val colors = SkillBillTheme.colors
  val fillColor = meter.progress?.let { usageRemainingColor(1f - it.coerceIn(0f, 1f)) } ?: colors.fgMuted
  val valueColor = meter.progress?.let { fillColor } ?: colors.fg2
  Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(5.dp)) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      val label = if (!detailed && meter.resetIn != null) "${meter.label} · ${meter.resetIn}" else meter.label
      BasicText(label, Modifier.weight(1f), style = usageText().copy(color = colors.fgMuted), maxLines = 1, overflow = TextOverflow.Ellipsis)
      BasicText(meter.valueText, style = usageText().tabular().copy(color = valueColor, textAlign = TextAlign.End), maxLines = 1)
    }
    val progress = meter.progress?.coerceIn(0f, 1f)
    Canvas(
      Modifier.fillMaxWidth().height(3.dp).semantics {
        contentDescription = "${meter.label}. ${meter.valueText}"
        if (progress != null) progressBarRangeInfo = ProgressBarRangeInfo(progress, 0f..1f)
      },
    ) {
      val radius = CornerRadius(size.height / 2)
      drawRoundRect(colors.border, cornerRadius = radius)
      if (progress != null) drawRoundRect(fillColor, size = Size(size.width * progress, size.height), cornerRadius = radius)
    }
    if (detailed) meter.resetIn?.let {
      val text = if (it == "now") "Resetting" else listOfNotNull("Resets in $it", meter.resetAt).joinToString(" · ")
      BasicText(text, style = usageSmallText().copy(color = colors.fgMuted), maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
  }
}
