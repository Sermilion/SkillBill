package dev.skillbill.desktop.core.designsystem.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import dev.skillbill.desktop.core.designsystem.SkillBillRadius
import dev.skillbill.desktop.core.designsystem.SkillBillTheme

/**
 * True for every row after the first in a [SettingsGroup]. In the design that row's 1px `border-top` sits inside
 * its own box (`box-sizing: border-box`), so a fixed-height row such as `.sb-cfg` shrinks by the divider.
 */
internal val LocalSettingsRowDivided = staticCompositionLocalOf { false }

/** Draws a divider between neighboring [rows]; rows inside a group draw none themselves. */
@Composable
fun SettingsGroup(
  rows: List<@Composable () -> Unit>,
  modifier: Modifier = Modifier,
  title: String? = null,
  end: (@Composable RowScope.() -> Unit)? = null,
  foot: String? = null,
) {
  val colors = SkillBillTheme.colors
  val type = SkillBillTheme.type
  val shape = RoundedCornerShape(SkillBillRadius.md)
  Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
    if (title != null || end != null) {
      Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        BasicText(title.orEmpty(), style = type.tBaseMedium.copy(color = colors.fg, lineHeight = 1.em))
        if (end != null) {
          Spacer(Modifier.weight(1f))
          end()
        }
      }
    }
    // `.sb-sg-box` rows sit inside its 1px border; Compose's border does not inset content.
    Column(Modifier.fillMaxWidth().clip(shape).background(colors.bgPanel, shape).border(1.dp, colors.border, shape).padding(1.dp)) {
      rows.forEachIndexed { index, row ->
        if (index > 0) Box(Modifier.fillMaxWidth().height(1.dp).background(colors.borderSubtle))
        CompositionLocalProvider(LocalSettingsRowDivided provides (index > 0)) { row() }
      }
    }
    foot?.let { BasicText(it, style = type.tXs.copy(color = colors.fgFaint, fontWeight = FontWeight.Normal)) }
  }
}
