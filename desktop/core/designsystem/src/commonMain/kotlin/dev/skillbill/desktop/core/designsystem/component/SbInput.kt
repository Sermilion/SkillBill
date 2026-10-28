package dev.skillbill.desktop.core.designsystem.component

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.skillbill.desktop.core.designsystem.LocalContentColor
import dev.skillbill.desktop.core.designsystem.SkillBillRadius
import dev.skillbill.desktop.core.designsystem.SkillBillTheme
import dev.skillbill.desktop.core.designsystem.hoverSpec
import dev.skillbill.desktop.core.designsystem.icon.SbIcon
import dev.skillbill.desktop.core.designsystem.icon.SbIconSize

private val INPUT_HEIGHT = 30.dp

@Composable
fun SbInput(
  value: String, onValueChange: (String) -> Unit, modifier: Modifier = Modifier,
  placeholder: String? = null, leadingIcon: ImageVector? = null, mono: Boolean = false,
  isError: Boolean = false, enabled: Boolean = true, onSubmit: (() -> Unit)? = null,
) {
  val colors = SkillBillTheme.colors
  val shape = RoundedCornerShape(SkillBillRadius.sm)
  val interaction = remember { MutableInteractionSource() }
  val focused by interaction.collectIsFocusedAsState()
  val baseBorder = animateColorAsState(
    targetValue = if (isError) colors.stFailed.copy(alpha = .6f) else if (focused) colors.accentRing else colors.border,
    animationSpec = hoverSpec(),
    label = "input-border",
  ).value
  val focusRing = animateColorAsState(
    targetValue = if (focused) colors.accentTint else Color.Transparent,
    animationSpec = hoverSpec(),
    label = "input-focus-ring",
  ).value
  BasicTextField(
    value = value,
    onValueChange = onValueChange,
    enabled = enabled,
    singleLine = true,
    textStyle = (if (mono) SkillBillTheme.type.monoSm else SkillBillTheme.type.tBase).copy(color = colors.fg),
    cursorBrush = SolidColor(colors.accent),
    interactionSource = interaction,
    modifier = modifier.height(INPUT_HEIGHT).background(colors.bgInset, shape)
      .drawBehind {
        if (focusRing.alpha > 0f) {
          val outset = 1.5.dp.toPx()
          drawRoundRect(
            focusRing,
            Offset(-outset, -outset),
            Size(size.width + outset * 2, size.height + outset * 2),
            CornerRadius(7.5.dp.toPx()),
            style = Stroke(3.dp.toPx()),
          )
        }
      }
      .border(1.dp, baseBorder, shape)
      .sbFocusRing(interaction, shape)
      .semantics { if (!enabled) disabled() }
      .padding(start = if (leadingIcon == null) 10.dp else 9.dp, end = 10.dp)
      .onPreviewKeyEvent { event ->
        if (event.key == Key.Enter && event.type == KeyEventType.KeyDown && enabled && onSubmit != null) {
          onSubmit()
          true
        } else false
      },
    decorationBox = { innerTextField ->
      Row(
        Modifier.fillMaxHeight(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(if (leadingIcon == null) 8.dp else 7.dp),
      ) {
        CompositionLocalProvider(LocalContentColor provides colors.fgFaint) {
          leadingIcon?.let { SbIcon(it, SbIconSize.Sm) }
        }
        Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.CenterStart) {
          if (value.isEmpty() && placeholder != null) {
            BasicText(placeholder, style = SkillBillTheme.type.tBase.copy(color = colors.fgFaint), maxLines = 1)
          }
          innerTextField()
        }
      }
    },
  )
}
