package dev.skillbill.desktop.core.designsystem.component

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import dev.skillbill.desktop.core.designsystem.LocalContentColor
import dev.skillbill.desktop.core.designsystem.SkillBillRadius
import dev.skillbill.desktop.core.designsystem.SkillBillTheme
import dev.skillbill.desktop.core.designsystem.composite.handCursor
import dev.skillbill.desktop.core.designsystem.icon.LucideIcons
import dev.skillbill.desktop.core.designsystem.icon.SbIcon
import dev.skillbill.desktop.core.designsystem.icon.SbIconSize

@Composable
fun SbPickerButton(
  onClick: (() -> Unit)?,
  modifier: Modifier = Modifier,
  icon: ImageVector? = null,
  open: Boolean = false,
  enabled: Boolean = true,
  onClickLabel: String? = null,
  chevronDescription: String? = null,
  chevron: ImageVector = LucideIcons.chevronDown,
  content: @Composable RowScope.() -> Unit,
) {
  val colors = SkillBillTheme.colors
  val shape = RoundedCornerShape(SkillBillRadius.sm)
  val interaction = remember { MutableInteractionSource() }
  val hovered = interaction.collectIsHoveredAsState().value && onClick != null && enabled
  Row(
    modifier.height(24.dp).alpha(if (enabled) 1f else .45f)
      .background(if (hovered) colors.bgHover else Color.Transparent, shape)
      .border(1.dp, if (open) colors.accentRing else colors.border, shape)
      .then(
        if (onClick != null) Modifier.hoverable(interaction)
          .clickable(
            interactionSource = interaction,
            indication = null,
            enabled = enabled,
            onClickLabel = onClickLabel,
            role = Role.Button,
            onClick = onClick,
          ).handCursor(enabled).sbFocusRing(interaction, shape)
        else Modifier,
      )
      .padding(horizontal = 9.dp),
    horizontalArrangement = Arrangement.spacedBy(6.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    CompositionLocalProvider(LocalContentColor provides if (hovered) colors.fg else colors.fg2) {
      icon?.let { CompositionLocalProvider(LocalContentColor provides colors.fgMuted) { SbIcon(it, SbIconSize.Xs) } }
      content()
      if (onClick != null) {
        CompositionLocalProvider(LocalContentColor provides colors.fgFaint) {
          SbIcon(chevron, SbIconSize.Xs, contentDescription = chevronDescription)
        }
      }
    }
  }
}

@Composable
fun SbPickerButton(
  label: String,
  onClick: (() -> Unit)?,
  modifier: Modifier = Modifier,
  icon: ImageVector? = null,
  open: Boolean = false,
  enabled: Boolean = true,
  onClickLabel: String? = null,
  chevronDescription: String? = null,
) {
  SbPickerButton(onClick, modifier, icon, open, enabled, onClickLabel, chevronDescription) {
    BasicText(
      label,
      Modifier.weight(1f, fill = false),
      style = SkillBillTheme.type.tSmMedium.copy(color = LocalContentColor.current, lineHeight = 1.em),
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
    )
  }
}
