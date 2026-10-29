package dev.skillbill.desktop.core.designsystem.composite

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import dev.skillbill.desktop.core.designsystem.SkillBillRadius
import dev.skillbill.desktop.core.designsystem.SkillBillTheme
import dev.skillbill.desktop.core.designsystem.hoverSpec

internal enum class Edge { Top, Bottom, Start, End }

internal fun Modifier.hairline(edge: Edge, color: Color) = drawBehind {
  val stroke = 1.dp.toPx()
  val half = stroke / 2
  val rtl = layoutDirection == LayoutDirection.Rtl
  val startX = if (rtl) size.width - half else half
  val endX = if (rtl) half else size.width - half
  when (edge) {
    Edge.Top -> drawLine(color, Offset(0f, half), Offset(size.width, half), stroke)
    Edge.Bottom -> drawLine(color, Offset(0f, size.height - half), Offset(size.width, size.height - half), stroke)
    Edge.Start -> drawLine(color, Offset(startX, 0f), Offset(startX, size.height), stroke)
    Edge.End -> drawLine(color, Offset(endX, 0f), Offset(endX, size.height), stroke)
  }
}

@Composable
internal fun Modifier.focusBar(visible: Boolean): Modifier {
  if (!visible) return this
  val accent = SkillBillTheme.colors.accent
  return drawBehind {
    val width = 2.dp.toPx()
    val x = if (layoutDirection == LayoutDirection.Rtl) size.width - width else 0f
    drawRect(accent, topLeft = Offset(x, 0f), size = Size(width, size.height))
  }
}

@Composable
internal fun Modifier.rowFill(hovered: Boolean, focused: Boolean, selected: Boolean): Modifier {
  val colors = SkillBillTheme.colors
  val target = when {
    selected -> colors.bgSelected
    focused -> colors.bgActive
    hovered -> colors.bgHover
    else -> Color.Transparent
  }
  val fill = animateColorAsState(target, hoverSpec(), label = "composite-row-fill").value
  return background(fill)
}

/** Draws one lazy-list item as a segment of an [dev.skillbill.desktop.core.designsystem.detail.SbBox]: borders on both sides, top edge and corners on the first item, bottom edge and corners on the last. */
@Composable
fun Modifier.boxSegment(index: Int, count: Int): Modifier {
  val first = index == 0
  val last = index == count - 1
  val radius = SkillBillRadius.md
  val shape = RoundedCornerShape(
    topStart = if (first) radius else 0.dp,
    topEnd = if (first) radius else 0.dp,
    bottomStart = if (last) radius else 0.dp,
    bottomEnd = if (last) radius else 0.dp,
  )
  val border = SkillBillTheme.colors.borderSubtle
  return clip(shape).drawBehind {
    val stroke = 1.dp.toPx()
    val half = stroke / 2
    drawLine(border, Offset(half, 0f), Offset(half, size.height), stroke)
    drawLine(border, Offset(size.width - half, 0f), Offset(size.width - half, size.height), stroke)
    if (first) drawLine(border, Offset(0f, half), Offset(size.width, half), stroke)
    if (last) drawLine(border, Offset(0f, size.height - half), Offset(size.width, size.height - half), stroke)
  }
}

internal fun Modifier.expandSemantics(expanded: Boolean) = semantics { stateDescription = if (expanded) "Expanded" else "Collapsed" }
