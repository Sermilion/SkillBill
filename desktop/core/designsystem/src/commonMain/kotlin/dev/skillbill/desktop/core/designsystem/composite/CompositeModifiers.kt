package dev.skillbill.desktop.core.designsystem.composite

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import dev.skillbill.desktop.core.designsystem.SkillBillTheme

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

/** `cursor: pointer` on an enabled control; a disabled one keeps the default arrow. */
internal fun Modifier.handCursor(enabled: Boolean = true): Modifier = if (enabled) pointerHoverIcon(PointerIcon.Hand) else this

internal fun Modifier.expandSemantics(expanded: Boolean) = semantics { stateDescription = if (expanded) "Expanded" else "Collapsed" }
