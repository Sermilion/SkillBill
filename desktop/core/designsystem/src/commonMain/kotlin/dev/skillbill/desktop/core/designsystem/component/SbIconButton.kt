package dev.skillbill.desktop.core.designsystem.component

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.skillbill.desktop.core.designsystem.LocalContentColor
import dev.skillbill.desktop.core.designsystem.SkillBillRadius
import dev.skillbill.desktop.core.designsystem.SkillBillTheme
import dev.skillbill.desktop.core.designsystem.icon.SbIcon
import dev.skillbill.desktop.core.designsystem.icon.SbIconSize

@Composable
fun SbIconButton(
  icon: ImageVector, label: String, onClick: () -> Unit, modifier: Modifier = Modifier,
  size: SbControlSize = SbControlSize.Regular, shortcut: String? = null, boxed: Boolean = false,
  selected: Boolean = false, enabled: Boolean = true,
) {
  require(label.isNotBlank())
  val interaction = remember { MutableInteractionSource() }
  val hovered = interaction.collectIsHoveredAsState().value
  val colors = SkillBillTheme.colors
  val background = rememberHoverColor(hovered || selected, if (boxed) colors.bgRaised else androidx.compose.ui.graphics.Color.Transparent, colors.bgHover).value
  val shape = RoundedCornerShape(SkillBillRadius.sm)
  val dimension = if (size == SbControlSize.Small) 24.dp else 28.dp
  val content: @Composable () -> Unit = {
    Box(
      modifier.size(dimension).alpha(if (enabled) 1f else .45f).background(background, shape)
        .then(if (boxed) Modifier.border(1.dp, colors.border, shape) else Modifier)
        .hoverable(interaction)
        .clickable(interactionSource = interaction, indication = null, enabled = enabled, onClick = onClick)
        .sbFocusRing(interaction, shape)
        .semantics {
          contentDescription = label
          role = Role.Button
          if (!enabled) disabled()
        },
      contentAlignment = Alignment.Center,
    ) {
      CompositionLocalProvider(LocalContentColor provides if (hovered || selected) colors.fg else colors.fgMuted) {
        SbIcon(icon, if (size == SbControlSize.Small) SbIconSize.Sm else SbIconSize.Md)
      }
    }
  }
  SbTooltip(label, shortcut) { content() }
}
