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
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import dev.skillbill.desktop.core.designsystem.LocalContentColor
import dev.skillbill.desktop.core.designsystem.SkillBillRadius
import dev.skillbill.desktop.core.designsystem.SkillBillTheme
import dev.skillbill.desktop.core.designsystem.hoverSpec
import dev.skillbill.desktop.core.designsystem.icon.SbIcon
import dev.skillbill.desktop.core.designsystem.icon.SbIconSize

private val INPUT_HEIGHT = 30.dp
private val MULTILINE_MAX_HEIGHT = 120.dp

/** A [multiline] input grows up to 120 dp; Enter submits and Shift+Enter inserts a newline. [visualTransformation] styles the shown text only. */
@Composable
fun SbInput(
  value: String, onValueChange: (String) -> Unit, modifier: Modifier = Modifier,
  placeholder: String? = null, leadingIcon: ImageVector? = null, mono: Boolean = false,
  isError: Boolean = false, enabled: Boolean = true, onSubmit: (() -> Unit)? = null, multiline: Boolean = false,
  visualTransformation: VisualTransformation = VisualTransformation.None,
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
  // `.sb-input` sets 400 13px/1 sans; `--mono` swaps the family and drops to 12px. The placeholder inherits the same font.
  val textStyle = (if (mono) SkillBillTheme.type.monoSm else SkillBillTheme.type.tBase).copy(lineHeight = 1.em)
  BasicTextField(
    value = value,
    onValueChange = onValueChange,
    enabled = enabled,
    singleLine = !multiline,
    visualTransformation = visualTransformation,
    textStyle = textStyle.copy(color = colors.fg),
    // caret-color defaults to the text color.
    cursorBrush = SolidColor(colors.fg),
    interactionSource = interaction,
    modifier = modifier.then(if (multiline) Modifier.heightIn(min = INPUT_HEIGHT, max = MULTILINE_MAX_HEIGHT) else Modifier.height(INPUT_HEIGHT))
      .background(colors.bgInset, shape)
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
      // `.sb-input` sets outline: none; focus shows through the border and the 3px tint ring only.
      .border(1.dp, baseBorder, shape)
      .semantics { if (!enabled) disabled() }
      // 10px padding inside the 1px border; a leading icon sits 9px from the outer edge.
      .padding(start = if (leadingIcon == null) 11.dp else 9.dp, end = 11.dp, top = if (multiline) 7.dp else 0.dp, bottom = if (multiline) 7.dp else 0.dp)
      .onPreviewKeyEvent { event ->
        if (event.key == Key.Enter && event.type == KeyEventType.KeyDown && !(multiline && event.isShiftPressed) && enabled && onSubmit != null) {
          onSubmit()
          true
        } else false
      },
    decorationBox = { innerTextField ->
      Row(
        if (multiline) Modifier else Modifier.fillMaxHeight(),
        verticalAlignment = Alignment.CenterVertically,
        // With an icon the text starts at padding-left 30px + 1px border: 9 + 14 + 8.
        horizontalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        CompositionLocalProvider(LocalContentColor provides colors.fgFaint) {
          leadingIcon?.let { SbIcon(it, SbIconSize.Sm) }
        }
        Box(if (multiline) Modifier.weight(1f) else Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.CenterStart) {
          if (value.isEmpty() && placeholder != null) {
            BasicText(placeholder, style = textStyle.copy(color = colors.fgFaint), maxLines = 1, softWrap = false, overflow = TextOverflow.Clip)
          }
          innerTextField()
        }
      }
    },
  )
}
