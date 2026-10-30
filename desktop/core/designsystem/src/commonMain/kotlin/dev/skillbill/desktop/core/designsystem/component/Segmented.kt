package dev.skillbill.desktop.core.designsystem.component

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import dev.skillbill.desktop.core.designsystem.LocalContentColor
import dev.skillbill.desktop.core.designsystem.SkillBillRadius
import dev.skillbill.desktop.core.designsystem.SkillBillTheme
import dev.skillbill.desktop.core.designsystem.icon.SbIcon
import dev.skillbill.desktop.core.designsystem.icon.SbIconSize
import dev.skillbill.desktop.core.designsystem.tabular

private val SEGMENT_HEIGHT = 22.dp
private val TAB_HEIGHT = 24.dp

data class SegmentedOption<T>(val value: T, val label: String, val icon: ImageVector? = null, val enabled: Boolean = true, val tooltip: String? = null)
data class ViewTab<T>(val value: T, val label: String, val icon: ImageVector? = null, val count: Int? = null, val attention: Boolean = false)

@Composable
fun <T> Segmented(options: List<SegmentedOption<T>>, selected: T, modifier: Modifier = Modifier, onSelect: (T) -> Unit) {
  val colors = SkillBillTheme.colors
  // `.sb-seg` pads 2px inside its 1px border.
  Row(modifier.background(colors.bgRaised, RoundedCornerShape(SkillBillRadius.sm)).border(1.dp, colors.border, RoundedCornerShape(SkillBillRadius.sm)).padding(3.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
    options.forEach { option ->
      val isSelected = option.value == selected
      val source = remember(option.value) { MutableInteractionSource() }
      val hovered = source.collectIsHoveredAsState().value
      val foreground = if (isSelected || hovered) colors.fg else colors.fgMuted
      val item: @Composable () -> Unit = { Row(
        Modifier.height(SEGMENT_HEIGHT).alpha(if (option.enabled) 1f else .4f)
          .background(if (isSelected) colors.bgOverlay else Color.Transparent, RoundedCornerShape(SkillBillRadius.xs))
          .then(if (isSelected) Modifier.border(1.dp, colors.borderStrong, RoundedCornerShape(SkillBillRadius.xs)) else Modifier)
          .hoverable(source)
          .clickable(source, null, enabled = option.enabled, onClick = { onSelect(option.value) })
          .sbFocusRing(source, RoundedCornerShape(SkillBillRadius.xs))
          .semantics {
            role = Role.Tab
            this.selected = isSelected
            contentDescription = option.label
            option.tooltip?.let { stateDescription = it }
          }.padding(horizontal = 9.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
      ) {
        CompositionLocalProvider(LocalContentColor provides foreground) {
          option.icon?.let { SbIcon(it, SbIconSize.Sm) }
          BasicText(option.label, style = SkillBillTheme.type.tSmMedium.copy(color = foreground, lineHeight = 1.em), maxLines = 1, softWrap = false)
        }
      } }
      if (option.tooltip != null) SbTooltip(option.tooltip) { item() } else item()
    }
  }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun <T> ViewTabs(tabs: List<ViewTab<T>>, selected: T, onSelect: (T) -> Unit) {
  FlowRow(horizontalArrangement = Arrangement.spacedBy(2.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
    tabs.forEach { tab ->
      val active = tab.value == selected
      val source = remember(tab.value) { MutableInteractionSource() }
      val hovered = source.collectIsHoveredAsState().value
      val colors = SkillBillTheme.colors
      // `.sb-vtab` declares no transition.
      val color = if (active || hovered) colors.fg else colors.fgMuted
      val background = if (active) colors.bgRaised else if (hovered) colors.bgHover else Color.Transparent
      Row(
        Modifier.height(TAB_HEIGHT).background(background, RoundedCornerShape(SkillBillRadius.sm))
          .border(1.dp, if (active) colors.border else Color.Transparent, RoundedCornerShape(SkillBillRadius.sm))
          .hoverable(source).clickable(source, null) { onSelect(tab.value) }
          .sbFocusRing(source, RoundedCornerShape(SkillBillRadius.sm))
          .semantics { role = Role.Tab; this.selected = active; if (tab.attention) stateDescription = "Needs attention"; contentDescription = tab.label }
          // 9px padding inside the 1px border every tab keeps.
          .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
      ) {
        CompositionLocalProvider(LocalContentColor provides color) {
          tab.icon?.let { SbIcon(it, SbIconSize.Sm) }
          BasicText(tab.label, style = SkillBillTheme.type.tSmMedium.copy(color = color, lineHeight = 1.em), maxLines = 1, softWrap = false)
          tab.count?.let { BasicText(it.toString(), style = SkillBillTheme.type.tSmMedium.tabular().copy(color = colors.fgFaint, lineHeight = 1.em), maxLines = 1) }
          if (tab.attention) Box(Modifier.size(6.dp).background(colors.stBlocked, CircleShape))
        }
      }
    }
  }
}
