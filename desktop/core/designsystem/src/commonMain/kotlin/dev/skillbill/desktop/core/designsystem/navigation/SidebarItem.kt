package dev.skillbill.desktop.core.designsystem.navigation

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.skillbill.desktop.core.designsystem.LocalContentColor
import dev.skillbill.desktop.core.designsystem.SkillBillRadius
import dev.skillbill.desktop.core.designsystem.SkillBillTheme
import dev.skillbill.desktop.core.designsystem.component.SbTooltip
import dev.skillbill.desktop.core.designsystem.component.rememberHoverColor
import dev.skillbill.desktop.core.designsystem.icon.SbIcon
import dev.skillbill.desktop.core.designsystem.tabular

@Composable
fun SidebarItem(
  label: String,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
  icon: ImageVector? = null,
  lead: (@Composable () -> Unit)? = null,
  count: Int? = null,
  attention: Int? = null,
  on: Boolean = false,
  tooltip: String? = null,
  shortcut: String? = null,
) {
  val colors = SkillBillTheme.colors
  val type = SkillBillTheme.type
  val interaction = remember { MutableInteractionSource() }
  val hovered = interaction.collectIsHoveredAsState().value
  val focused = interaction.collectIsFocusedAsState().value
  val shape = RoundedCornerShape(SkillBillRadius.sm)
  val fill = rememberHoverColor(hovered || focused, if (on) colors.bgActive else Color.Transparent, if (on) colors.bgActive else colors.bgHover).value
  val textColor = if (on || hovered) colors.fg else colors.fg2
  val item: @Composable () -> Unit = {
    Row(
      modifier.fillMaxWidth().height(28.dp).background(fill, shape)
        .hoverable(interaction).clickable(interaction, indication = null, role = Role.Button, onClick = onClick)
        .semantics { selected = on }.padding(horizontal = 8.dp),
      horizontalArrangement = Arrangement.spacedBy(9.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      when {
        lead != null -> lead()
        icon != null -> CompositionLocalProvider(LocalContentColor provides if (on) colors.fg else colors.fgMuted) { SbIcon(icon) }
      }
      BasicText(label, Modifier.weight(1f), style = type.tBaseMedium.copy(color = textColor), maxLines = 1, overflow = TextOverflow.Ellipsis)
      if (attention != null && attention > 0) {
        Box(
          Modifier.defaultMinSize(minWidth = 18.dp).height(18.dp).background(colors.accentTint, SkillBillRadius.full).padding(horizontal = 5.dp),
          contentAlignment = Alignment.Center,
        ) {
          BasicText(attention.toString(), style = type.t2xs.tabular().copy(color = colors.accentText, fontWeight = FontWeight.SemiBold))
        }
      } else if (count != null) {
        BasicText(count.toString(), style = type.tSm.tabular().copy(color = colors.fgFaint))
      }
    }
  }
  if (tooltip != null) SbTooltip(tooltip, shortcut) { item() } else item()
}
