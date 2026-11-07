package dev.skillbill.desktop.core.designsystem.component

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.FocusInteraction
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import dev.skillbill.desktop.core.designsystem.SkillBillTheme
import dev.skillbill.desktop.core.designsystem.hoverSpec

/**
 * Keyboard focus uses a neutral background highlight, leaving selection borders to the control.
 * A pointer press suppresses the highlight until focus leaves.
 */
@Composable
fun Modifier.sbFocusRing(interactionSource: InteractionSource, shape: Shape): Modifier {
  val focused = interactionSource.collectIsFocusedAsState().value
  var pointerFocus by remember(interactionSource) { mutableStateOf(false) }
  LaunchedEffect(interactionSource) {
    interactionSource.interactions.collect { if (it is FocusInteraction.Unfocus) pointerFocus = false }
  }
  val color = SkillBillTheme.colors.bgActive
  return pointerInput(interactionSource) {
    awaitPointerEventScope {
      while (true) {
        // Observe without consuming, ahead of the clickable, so the press still clicks.
        if (awaitPointerEvent(PointerEventPass.Initial).type == PointerEventType.Press) pointerFocus = true
      }
    }
  }.drawBehind {
    if (focused && !pointerFocus) {
      drawOutline(shape.createOutline(size, layoutDirection, this), color)
    }
  }
}

@Composable
fun rememberHoverColor(hovered: Boolean, base: Color, hover: Color): State<Color> =
  animateColorAsState(if (hovered) hover else base, hoverSpec(), label = "hover-color")
