package dev.skillbill.desktop.core.designsystem.component

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.FocusInteraction
import androidx.compose.foundation.shape.CornerBasedShape
import androidx.compose.foundation.shape.CornerSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.skillbill.desktop.core.designsystem.SkillBillTheme
import dev.skillbill.desktop.core.designsystem.hoverSpec

/**
 * The `:focus-visible` ring: 2px `accent-ring`, 1px outside the element. Like the browser, it shows only for focus that came
 * from the keyboard. A pointer press on the element marks its focus as pointer focus, which draws no ring until focus leaves.
 */
@Composable
fun Modifier.sbFocusRing(interactionSource: InteractionSource, shape: Shape): Modifier {
  val focused = interactionSource.collectIsFocusedAsState().value
  var pointerFocus by remember(interactionSource) { mutableStateOf(false) }
  LaunchedEffect(interactionSource) {
    interactionSource.interactions.collect { if (it is FocusInteraction.Unfocus) pointerFocus = false }
  }
  val color = SkillBillTheme.colors.accentRing
  return pointerInput(interactionSource) {
    awaitPointerEventScope {
      while (true) {
        // Observe without consuming, ahead of the clickable, so the press still clicks.
        if (awaitPointerEvent(PointerEventPass.Initial).type == PointerEventType.Press) pointerFocus = true
      }
    }
  }.drawBehind {
    if (focused && !pointerFocus) {
      val stroke = 2.dp.toPx()
      val outset = 2.dp
      val outsetPx = outset.toPx()
      val expandedShape = shape.outsetCorners(outset)
      val expandedSize = Size(size.width + outsetPx * 2, size.height + outsetPx * 2)
      withTransform({ translate(-outsetPx, -outsetPx) }) {
        drawOutline(expandedShape.createOutline(expandedSize, layoutDirection, this), color, style = Stroke(stroke))
      }
    }
  }
}

private fun Shape.outsetCorners(outset: Dp): Shape = when (this) {
  is CornerBasedShape -> copy(
    topStart = topStart.outsetBy(outset),
    topEnd = topEnd.outsetBy(outset),
    bottomEnd = bottomEnd.outsetBy(outset),
    bottomStart = bottomStart.outsetBy(outset),
  )
  else -> this
}

private fun CornerSize.outsetBy(outset: Dp): CornerSize = object : CornerSize {
  override fun toPx(shapeSize: Size, density: Density): Float =
    this@outsetBy.toPx(shapeSize, density) + with(density) { outset.toPx() }
}

@Composable
fun rememberHoverColor(hovered: Boolean, base: Color, hover: Color): State<Color> =
  animateColorAsState(if (hovered) hover else base, hoverSpec(), label = "hover-color")
