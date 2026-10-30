package dev.skillbill.desktop.core.designsystem.settings

import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
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
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.skillbill.desktop.core.designsystem.LocalContentColor
import dev.skillbill.desktop.core.designsystem.SkillBillRadius
import dev.skillbill.desktop.core.designsystem.SkillBillTheme
import dev.skillbill.desktop.core.designsystem.component.sbFocusRing
import dev.skillbill.desktop.core.designsystem.icon.LucideIcons
import dev.skillbill.desktop.core.designsystem.icon.SbIcon
import dev.skillbill.desktop.core.designsystem.icon.SbIconSize

/** The design's picker button (`.sb-pick`): 24 dp, hairline border, optional lead icon, [text], and a chevron. */
@Composable
fun SettingPick(
  text: String,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
  icon: ImageVector? = null,
  mono: Boolean = false,
  enabled: Boolean = true,
  contentDescription: String = text,
) {
  val colors = SkillBillTheme.colors
  val type = SkillBillTheme.type
  val shape = RoundedCornerShape(SkillBillRadius.sm)
  val interaction = remember { MutableInteractionSource() }
  val hovered = enabled && interaction.collectIsHoveredAsState().value
  // `.sb-pick` declares no transition, so hover switches instantly.
  val fill = if (hovered) colors.bgHover else Color.Transparent
  val fg = if (hovered) colors.fg else colors.fg2
  val style = type.tSmMedium.let { if (mono) it.copy(fontFamily = type.mono.fontFamily) else it }.copy(color = fg)
  Row(
    modifier.height(24.dp).alpha(if (enabled) 1f else .45f).background(fill, shape).border(1.dp, colors.border, shape)
      .hoverable(interaction)
      .then(if (enabled) Modifier.pointerHoverIcon(PointerIcon.Hand) else Modifier)
      .clickable(interaction, indication = null, enabled = enabled, onClick = onClick)
      .sbFocusRing(interaction, shape)
      .semantics {
        role = Role.Button
        this.contentDescription = contentDescription
        if (!enabled) disabled()
      }
      // `.sb-pick` pads 8px inside its 1px border.
      .padding(horizontal = 9.dp),
    horizontalArrangement = Arrangement.spacedBy(6.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    CompositionLocalProvider(LocalContentColor provides colors.fgFaint) {
      icon?.let { SbIcon(it, SbIconSize.Xs) }
      BasicText(text, style = style, maxLines = 1, overflow = TextOverflow.Ellipsis)
      SbIcon(LucideIcons.chevronDown, SbIconSize.Xs)
    }
  }
}
