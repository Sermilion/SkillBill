package dev.skillbill.desktop.core.designsystem.tray

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.skillbill.desktop.core.designsystem.LocalContentColor
import dev.skillbill.desktop.core.designsystem.SkillBillColors
import dev.skillbill.desktop.core.designsystem.SkillBillRadius
import dev.skillbill.desktop.core.designsystem.SkillBillSize
import dev.skillbill.desktop.core.designsystem.SkillBillTheme
import dev.skillbill.desktop.core.designsystem.composite.Edge
import dev.skillbill.desktop.core.designsystem.composite.hairline
import dev.skillbill.desktop.core.designsystem.icon.BrandMark
import dev.skillbill.desktop.core.designsystem.layeredShadow
import dev.skillbill.desktop.core.designsystem.tabular

enum class TrayStatTone { Neutral, Accent, Attention }

@Immutable
class TrayStat(val value: String, val label: String, val tone: TrayStatTone = TrayStatTone.Neutral)

private fun TrayStatTone.color(colors: SkillBillColors) = when (this) {
  TrayStatTone.Neutral -> colors.fg
  TrayStatTone.Accent -> colors.accentText
  TrayStatTone.Attention -> colors.stBlocked
}

@Composable
fun TrayPopover(
  stats: List<TrayStat>,
  tabs: @Composable () -> Unit,
  footer: @Composable RowScope.() -> Unit,
  modifier: Modifier = Modifier,
  headerEnd: (@Composable () -> Unit)? = null,
  rows: @Composable ColumnScope.() -> Unit,
) {
  val colors = SkillBillTheme.colors
  val type = SkillBillTheme.type
  val shape = RoundedCornerShape(SkillBillRadius.lg)
  Column(
    modifier.width(SkillBillSize.trayPopover).layeredShadow(SkillBillTheme.shadows.popover, shape)
      .background(colors.bgOverlay, shape).border(1.dp, colors.borderStrong, shape).clip(shape)
      // `.sb-tray` is border-box: its sections sit inside the 1px border, which Modifier.border does not inset.
      .padding(1.dp),
  ) {
    Row(
      Modifier.fillMaxWidth().hairline(Edge.Bottom, colors.borderSubtle).padding(horizontal = 12.dp, vertical = 10.dp),
      horizontalArrangement = Arrangement.spacedBy(8.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      BrandMark(18.dp)
      BasicText("Skill Bill", style = type.tBase.copy(color = colors.fg, fontWeight = FontWeight.SemiBold))
      if (headerEnd != null) {
        Spacer(Modifier.weight(1f))
        CompositionLocalProvider(LocalContentColor provides colors.fgFaint) { headerEnd() }
      }
    }
    Row(Modifier.fillMaxWidth().hairline(Edge.Bottom, colors.borderSubtle).padding(horizontal = 12.dp, vertical = 10.dp)) {
      stats.forEach { stat ->
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
          // `.sb-tray-stats b` is 600 at the lg size on a 1.0 line; the label is regular weight.
          BasicText(stat.value, style = type.tLg.tabular().copy(color = stat.tone.color(colors), fontWeight = FontWeight.SemiBold, lineHeight = 16.sp), maxLines = 1)
          BasicText(stat.label, style = type.tXs.copy(color = colors.fgFaint, fontWeight = FontWeight.Normal), maxLines = 1)
        }
      }
    }
    Box(Modifier.fillMaxWidth().hairline(Edge.Bottom, colors.borderSubtle).padding(horizontal = 10.dp, vertical = 8.dp)) { tabs() }
    // The body takes its content's height, scrolling only once the popover reaches the window's height.
    Column(Modifier.fillMaxWidth().weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(4.dp), content = rows)
    Row(
      Modifier.fillMaxWidth().hairline(Edge.Top, colors.borderSubtle).padding(start = 10.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
      horizontalArrangement = Arrangement.SpaceBetween,
      verticalAlignment = Alignment.CenterVertically,
      content = footer,
    )
  }
}
