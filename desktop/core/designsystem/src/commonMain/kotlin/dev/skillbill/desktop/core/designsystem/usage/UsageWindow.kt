package dev.skillbill.desktop.core.designsystem.usage

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.skillbill.desktop.core.designsystem.SkillBillTheme
import dev.skillbill.desktop.core.designsystem.tabular

@Composable
fun UsageMeterRow(meter: UsageUiMeter, compact: Boolean = false, modifier: Modifier = Modifier) {
  val colors = SkillBillTheme.colors
  val fillColor = when {
    meter.stale -> colors.fgFaint
    meter.exhausted -> colors.stFailed
    meter.warning -> colors.accent
    else -> colors.fgMuted
  }
  val valueColor = when {
    meter.stale -> colors.fgMuted
    meter.exhausted -> colors.stFailed
    meter.warning -> colors.accentText
    else -> colors.fg2
  }
  val label = if (compact && meter.stateText != null) "${meter.stateText} · ${meter.label}" else meter.label
  Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(5.dp)) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      BasicText(label, Modifier.weight(1f).widthIn(max = 180.dp), style = SkillBillTheme.type.tXs.copy(color = colors.fgMuted), maxLines = if (compact) 1 else Int.MAX_VALUE, overflow = TextOverflow.Ellipsis)
      BasicText(meter.valueText, Modifier.weight(1f).widthIn(max = 180.dp), style = SkillBillTheme.type.tXs.tabular().copy(color = valueColor, textAlign = TextAlign.End), maxLines = if (compact) 1 else Int.MAX_VALUE, overflow = TextOverflow.Ellipsis)
    }
    meter.progress?.let { progress ->
      Canvas(
        Modifier.fillMaxWidth().height(3.dp).semantics {
          contentDescription = listOf(meter.label, meter.progressLabel ?: meter.valueText).joinToString(". ")
          stateDescription = meter.progressStateDescription ?: "Current usage"
          progressBarRangeInfo = ProgressBarRangeInfo(progress.coerceIn(0f, 1f), 0f..1f)
        },
      ) {
        drawRoundRect(colors.border, cornerRadius = androidx.compose.ui.geometry.CornerRadius(size.height / 2))
        drawRoundRect(fillColor, size = androidx.compose.ui.geometry.Size(size.width * progress.coerceIn(0f, 1f), size.height), cornerRadius = androidx.compose.ui.geometry.CornerRadius(size.height / 2))
      }
    }
    if (!compact) meter.detail.takeIf(String::isNotBlank)?.let { BasicText(it, style = SkillBillTheme.type.tXs.copy(color = colors.fgMuted)) }
    if (!compact) meter.stateText?.let {
      val stateColor = when {
        meter.stale -> colors.fgMuted
        meter.exhausted || it == "Expired" || it == "Limit reached" -> colors.stFailed
        meter.warning -> colors.accentText
        else -> colors.fgMuted
      }
      BasicText(it, style = SkillBillTheme.type.tXs.copy(color = stateColor))
    }
  }
}
