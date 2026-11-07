package dev.skillbill.desktop.core.designsystem.component

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import dev.skillbill.desktop.core.designsystem.LocalContentColor
import dev.skillbill.desktop.core.designsystem.SkillBillRadius
import dev.skillbill.desktop.core.designsystem.SkillBillTheme
import dev.skillbill.desktop.core.designsystem.icon.SbIcon
import dev.skillbill.desktop.core.designsystem.icon.SbIconSize
import dev.skillbill.desktop.core.designsystem.status.StatusDot

/** `Label({ dot: false })` is [Plain]; `Dot(null)` keeps the fg-faint dot that `Label` draws when no color is given. */
sealed interface SbLabelStyle {
  data object Plain : SbLabelStyle
  data class Dot(val color: Color? = null) : SbLabelStyle
  data class Tint(val color: Color) : SbLabelStyle
  data object Mono : SbLabelStyle
}

@Composable
fun SbLabel(
  text: String,
  style: SbLabelStyle = SbLabelStyle.Plain,
  icon: ImageVector? = null,
  tooltip: String? = null,
  dashed: Boolean = false,
  modifier: Modifier = Modifier,
  maxWidth: Dp? = null,
  size: SbControlSize = SbControlSize.Regular,
) {
  val colors = SkillBillTheme.colors
  val tint = (style as? SbLabelStyle.Tint)?.color
  val textColor = tint ?: if (dashed) colors.fgMuted else colors.fg2
  val outline = tint?.copy(alpha = .35f) ?: colors.border
  val shape = SkillBillRadius.full
  val content: @Composable () -> Unit = { Row(
    modifier.then(if (maxWidth != null) Modifier.widthIn(max = maxWidth) else Modifier)
      .height(if (size == SbControlSize.Small) 18.dp else 22.dp)
      .clip(shape)
      .background(tint?.copy(alpha = .10f) ?: Color.Transparent, shape)
      .then(
        if (dashed) {
          Modifier.drawBehind {
            val stroke = 1.dp.toPx()
            drawRoundRect(
              color = outline,
              topLeft = Offset(stroke / 2, stroke / 2),
              size = Size(this.size.width - stroke, this.size.height - stroke),
              cornerRadius = CornerRadius(this.size.height / 2),
              style = Stroke(stroke, pathEffect = PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(), 3.dp.toPx()))),
            )
          }
        } else {
          Modifier.border(1.dp, outline, shape)
        },
      )
      // `.sb-label` pads 8px inside its 1px border.
      .padding(horizontal = if (size == SbControlSize.Small) 7.dp else 9.dp),
    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
  ) {
    when (style) {
      is SbLabelStyle.Dot -> StatusDot(style.color ?: colors.fgFaint)
      is SbLabelStyle.Tint, SbLabelStyle.Mono, SbLabelStyle.Plain -> Unit
    }
    CompositionLocalProvider(LocalContentColor provides textColor) {
      icon?.let { SbIcon(it, SbIconSize.Xs) }
      BasicText(
        text,
        style =
          (if (style == SbLabelStyle.Mono) SkillBillTheme.type.t2xs else SkillBillTheme.type.tXs)
            .copy(color = textColor, lineHeight = 1.em),
        maxLines = 1,
        softWrap = false,
        overflow = TextOverflow.Ellipsis,
      )
    }
  } }
  if (tooltip != null) SbTooltip(tooltip) { content() } else content()
}
