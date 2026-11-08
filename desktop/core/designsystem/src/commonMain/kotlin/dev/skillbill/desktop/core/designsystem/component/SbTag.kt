package dev.skillbill.desktop.core.designsystem.component

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.skillbill.desktop.core.designsystem.SkillBillRadius
import dev.skillbill.desktop.core.designsystem.SkillBillTheme
import dev.skillbill.desktop.core.designsystem.composite.handCursor
import dev.skillbill.desktop.core.designsystem.icon.SbIcon
import dev.skillbill.desktop.core.designsystem.icon.SbIconSize

private val SB_TAG_HEIGHT = 18.dp
private val SB_TAG_BORDER_WIDTH = 1.dp
private val SB_TAG_HORIZONTAL_PADDING = 6.dp

@Composable
fun SbTag(
  text: String,
  modifier: Modifier = Modifier,
  tone: Color? = null,
  outlined: Boolean = false,
  mono: Boolean = false,
  icon: ImageVector? = null,
  onClick: (() -> Unit)? = null,
  tooltip: String? = null,
) {
  val colors = SkillBillTheme.colors
  val type = SkillBillTheme.type
  val interaction = remember { MutableInteractionSource() }
  val hovered by interaction.collectIsHoveredAsState()
  val fill = when {
    onClick != null && hovered -> colors.bgHover
    tone != null -> tone.copy(alpha = 0.12f)
    outlined -> Color.Transparent
    else -> colors.bgRaised
  }
  val border = when {
    tone != null -> null
    outlined -> colors.borderSubtle
    else -> colors.border
  }
  val foreground = when {
    tone != null -> tone
    outlined -> colors.fgFaint
    else -> colors.fgMuted
  }
  val clickable = if (onClick != null) {
    Modifier.hoverable(interaction).handCursor()
      .clickable(interaction, indication = null, role = Role.Button, onClick = onClick)
  } else Modifier
  val content: @Composable () -> Unit = {
    Row(
      modifier.then(clickable).sbTagFrame(fill, border),
      horizontalArrangement = Arrangement.spacedBy(4.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      icon?.let { SbIcon(it, SbIconSize.Xs) }
      BasicText(
        text,
        style = (if (mono) type.monoSm else type.tXs.copy(fontWeight = FontWeight.Medium)).copy(color = foreground),
        maxLines = 1,
        softWrap = false,
        overflow = TextOverflow.Ellipsis,
      )
    }
  }
  if (tooltip != null) SbTooltip(tooltip, content = content) else content()
}

internal fun Modifier.sbTagFrame(fill: Color, border: Color?, dashed: Boolean = false): Modifier {
  val shape = RoundedCornerShape(SkillBillRadius.xs)
  return background(fill, shape)
    .then(
      if (border == null) Modifier
      else if (!dashed) Modifier.border(SB_TAG_BORDER_WIDTH, border, shape)
      else Modifier.drawBehind {
        val stroke = SB_TAG_BORDER_WIDTH.toPx()
        drawRoundRect(
          border,
          topLeft = Offset(stroke / 2, stroke / 2),
          size = Size(size.width - stroke, size.height - stroke),
          cornerRadius = CornerRadius(SkillBillRadius.xs.toPx()),
          style = Stroke(stroke, pathEffect = PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(), 2.dp.toPx()))),
        )
      },
    )
    .heightIn(min = SB_TAG_HEIGHT)
    .padding(horizontal = SB_TAG_HORIZONTAL_PADDING)
}
