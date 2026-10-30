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
import androidx.compose.ui.unit.em
import dev.skillbill.desktop.core.designsystem.LocalContentColor
import dev.skillbill.desktop.core.designsystem.SkillBillRadius
import dev.skillbill.desktop.core.designsystem.SkillBillTheme
import dev.skillbill.desktop.core.designsystem.composite.handCursor
import dev.skillbill.desktop.core.designsystem.icon.SbIcon
import dev.skillbill.desktop.core.designsystem.icon.SbIconSize

private val BUTTON_HEIGHT = 28.dp
private val BUTTON_SMALL_HEIGHT = 24.dp
// `.sb-btn` pads 10px (8px small) inside a 1px border that every variant keeps, transparent or not.
private val BUTTON_BORDER = 1.dp
private val BUTTON_PADDING = 10.dp + BUTTON_BORDER
private val BUTTON_SMALL_PADDING = 8.dp + BUTTON_BORDER
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
  // `[disabled]` sets pointer-events: none, so a disabled button never shows its hover state.
  val hover = interaction.collectIsHoveredAsState().value && enabled
  val bg = rememberHoverColor(hover, variant.background(colors, false), variant.background(colors, true)).value
  // `.sb-btn` transitions border-color with the background over --dur-fast.
  val borderColor = rememberHoverColor(
    hover,
    if (variant == SbButtonVariant.Danger || variant == SbButtonVariant.Secondary) colors.border else Color.Transparent,
    when (variant) {
      SbButtonVariant.Danger -> colors.stFailed.copy(alpha = .4f)
      SbButtonVariant.Secondary -> colors.borderStrong
      else -> Color.Transparent
    },
  ).value
  val fg = when (variant) {
    SbButtonVariant.Primary -> colors.accentFg
    SbButtonVariant.Danger -> colors.stFailed
    SbButtonVariant.DangerSolid -> Color.White
    SbButtonVariant.Ghost -> if (hover) colors.fg else colors.fgMuted
    SbButtonVariant.Secondary -> colors.fg
  }
  val content: @Composable () -> Unit = {
    Row(
      modifier.height(if (size == SbControlSize.Small) BUTTON_SMALL_HEIGHT else BUTTON_HEIGHT)
        .alpha(if (enabled) 1f else .45f)
        .background(bg, shape)
        .then(if (variant == SbButtonVariant.Primary || variant == SbButtonVariant.Ghost || variant == SbButtonVariant.DangerSolid) Modifier
        else Modifier.border(BUTTON_BORDER, borderColor, shape))
        .hoverable(interaction)
        .clickable(interactionSource = interaction, indication = null, enabled = enabled, onClick = onClick)
        .handCursor(enabled)
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
          // `.sb-btn .sb-icon` stays fg-muted on hover; only primary and the danger variants recolor it.
          val iconColor = when (variant) {
            SbButtonVariant.Secondary, SbButtonVariant.Ghost -> colors.fgMuted
            else -> fg
          }
          CompositionLocalProvider(LocalContentColor provides iconColor) { SbIcon(it, SbIconSize.Sm) }
        }
        label?.let {
          val style = if (size == SbControlSize.Small) SkillBillTheme.type.tXs else SkillBillTheme.type.tSmMedium
          BasicText(it, style = style.copy(color = fg, lineHeight = 1.em), maxLines = 1, softWrap = false)
        }
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
  SbButtonVariant.DangerSolid -> if (hovered) lerp(Color.White, colors.stFailed, .88f) else colors.stFailed
}
