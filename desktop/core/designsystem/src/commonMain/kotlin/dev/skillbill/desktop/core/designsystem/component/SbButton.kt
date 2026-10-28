package dev.skillbill.desktop.core.designsystem.component

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.hoverable
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
import androidx.compose.ui.graphics.lerp
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

private val BUTTON_HEIGHT = 28.dp
private val BUTTON_SMALL_HEIGHT = 24.dp
private val BUTTON_PADDING = 10.dp
private val BUTTON_SMALL_PADDING = 8.dp
private val BUTTON_GAP = 6.dp

enum class SbButtonVariant { Secondary, Primary, Ghost, Danger, DangerSolid }
enum class SbControlSize { Regular, Small }

@Composable
fun SbButton(
  label: String?, onClick: () -> Unit, modifier: Modifier = Modifier,
  variant: SbButtonVariant = SbButtonVariant.Secondary, size: SbControlSize = SbControlSize.Regular,
  icon: ImageVector? = null, shortcut: String? = null, enabled: Boolean = true,
  tooltip: String? = null, tooltipShortcut: String? = null,
) {
  val colors = SkillBillTheme.colors
  val shape = RoundedCornerShape(SkillBillRadius.sm)
  val interaction = remember { MutableInteractionSource() }
  val hover = interaction.collectIsHoveredAsState().value
  val bg = rememberHoverColor(hover, variant.background(colors, false), variant.background(colors, true)).value
  val fg = when (variant) {
    SbButtonVariant.Primary -> colors.accentFg
    SbButtonVariant.Danger -> colors.stFailed
    SbButtonVariant.DangerSolid -> colors.fg
    SbButtonVariant.Ghost -> if (hover) colors.fg else colors.fgMuted
    SbButtonVariant.Secondary -> colors.fg
  }
  val content: @Composable () -> Unit = {
    Row(
      modifier.height(if (size == SbControlSize.Small) BUTTON_SMALL_HEIGHT else BUTTON_HEIGHT)
        .alpha(if (enabled) 1f else .45f)
        .background(bg, shape)
        .then(if (variant == SbButtonVariant.Primary || variant == SbButtonVariant.Ghost || variant == SbButtonVariant.DangerSolid) Modifier
        else Modifier.border(1.dp, if (variant == SbButtonVariant.Danger && hover) colors.stFailed.copy(alpha = .4f) else if (hover) colors.borderStrong else colors.border, shape))
        .hoverable(interaction)
        .clickable(interactionSource = interaction, indication = null, enabled = enabled, onClick = onClick)
        .sbFocusRing(interaction, shape)
        .semantics {
          role = Role.Button
          (label ?: tooltip)?.let { contentDescription = it }
          if (!enabled) disabled()
        }
        .padding(horizontal = if (size == SbControlSize.Small) BUTTON_SMALL_PADDING else BUTTON_PADDING),
      horizontalArrangement = Arrangement.spacedBy(BUTTON_GAP, Alignment.CenterHorizontally),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      CompositionLocalProvider(LocalContentColor provides fg) {
        icon?.let {
          val iconColor = when (variant) {
            SbButtonVariant.Secondary -> if (hover) colors.fg else colors.fgMuted
            SbButtonVariant.Ghost -> if (hover) colors.fg else colors.fgMuted
            else -> fg
          }
          CompositionLocalProvider(LocalContentColor provides iconColor) { SbIcon(it, SbIconSize.Sm) }
        }
        label?.let { BasicText(it, style = if (size == SbControlSize.Small) SkillBillTheme.type.tXs.copy(color = fg) else SkillBillTheme.type.tSmMedium.copy(color = fg)) }
        shortcut?.let { Kbd(it.split(' ').filter(String::isNotBlank), onAccent = variant == SbButtonVariant.Primary) }
      }
    }
  }
  val wrapped: @Composable () -> Unit = { if (tooltip != null) SbTooltip(tooltip, tooltipShortcut, content) else content() }
  androidx.compose.runtime.CompositionLocalProvider(LocalContentColor provides fg) { wrapped() }
}

private fun SbButtonVariant.background(colors: dev.skillbill.desktop.core.designsystem.SkillBillColors, hovered: Boolean) = when (this) {
  SbButtonVariant.Secondary -> if (hovered) colors.bgOverlay else colors.bgRaised
  SbButtonVariant.Primary -> if (hovered) colors.accentHover else colors.accent
  SbButtonVariant.Ghost -> if (hovered) colors.bgHover else Color.Transparent
  SbButtonVariant.Danger -> if (hovered) colors.stFailed.copy(alpha = .10f) else colors.bgRaised
  SbButtonVariant.DangerSolid -> if (hovered) lerp(colors.fg, colors.stFailed, .88f) else colors.stFailed
}
