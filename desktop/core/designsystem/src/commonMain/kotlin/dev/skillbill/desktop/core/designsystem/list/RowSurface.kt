package dev.skillbill.desktop.core.designsystem.list

import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.shape.RoundedCornerShape
import dev.skillbill.desktop.core.designsystem.SkillBillRadius
import dev.skillbill.desktop.core.designsystem.SkillBillTheme

@Composable
fun Modifier.sbRowSurface(
  interactionSource: MutableInteractionSource,
  selected: Boolean = false,
  focused: Boolean = false,
  line: Boolean = false,
  shape: Shape = RoundedCornerShape(SkillBillRadius.sm),
): Modifier {
  val hovered by interactionSource.collectIsHoveredAsState()
  val colors = SkillBillTheme.colors
  val fill = when {
    selected -> colors.bgSelected
    focused -> colors.bgActive
    hovered -> colors.bgHover
    else -> Color.Transparent
  }
  val accent = colors.accent
  val border = colors.borderSubtle
  return clip(shape).hoverable(interactionSource).drawBehind {
    if (fill != Color.Transparent) drawRect(fill)
    val stroke = 1.dp.toPx()
    val inner = if (line) size.height - stroke else size.height
    if (focused) {
      val bar = 2.dp.toPx()
      val x = if (layoutDirection == LayoutDirection.Rtl) size.width - bar else 0f
      drawRect(accent, topLeft = Offset(x, 0f), size = Size(bar, inner))
    }
    if (line) drawRect(border, topLeft = Offset(0f, inner), size = Size(size.width, stroke))
  }
}
