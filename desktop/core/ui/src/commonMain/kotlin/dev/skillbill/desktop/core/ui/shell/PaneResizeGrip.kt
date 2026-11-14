package dev.skillbill.desktop.core.ui.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsPropertyReceiver
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.skillbill.desktop.core.designsystem.SkillBillTheme
import dev.skillbill.desktop.core.designsystem.composite.horizontalResizePointerIcon

private val KeyStep = 32.dp

/**
 * The start-edge grip of a side pane. It reports deltas and does not own the width: [onDrag] gets the pointer movement, so the
 * caller subtracts it and dragging toward the start grows the pane; [onDragStopped] follows the last drag delta or a cancelled
 * drag; [onStep] gets [keyStep] for Left and its negative for Right; [onDoubleClick] runs on a double-click and on Home. Disabled,
 * it draws only the hairline, takes no drag or key input, and tells assistive technology it is disabled.
 */
@Composable
fun PaneResizeGrip(
  title: String,
  enabled: Boolean,
  onDrag: (Dp) -> Unit,
  onStep: (Dp) -> Unit,
  modifier: Modifier = Modifier,
  onDragStopped: () -> Unit = {},
  gripWidth: Dp = 7.dp,
  keyStep: Dp = KeyStep,
  onDoubleClick: (() -> Unit)? = null,
  additionalSemantics: SemanticsPropertyReceiver.() -> Unit = {},
) {
  val colors = SkillBillTheme.colors
  val density = LocalDensity.current
  val grip = remember { MutableInteractionSource() }
  val currentDrag by rememberUpdatedState(onDrag)
  val currentStep by rememberUpdatedState(onStep)
  val currentDoubleClick by rememberUpdatedState(onDoubleClick)
  val active = grip.collectIsHoveredAsState().value || grip.collectIsDraggedAsState().value || grip.collectIsFocusedAsState().value
  Box(
    modifier.width(gripWidth).fillMaxHeight()
      .semantics {
        contentDescription = "Resize ${title.lowercase()} panel"
        if (!enabled) disabled()
        if (enabled) additionalSemantics()
      }
      .then(if (enabled) Modifier.pointerHoverIcon(horizontalResizePointerIcon) else Modifier)
      .hoverable(grip, enabled = enabled)
      .pointerInput(enabled, onDoubleClick != null) {
        if (enabled && onDoubleClick != null) detectTapGestures(onDoubleTap = { currentDoubleClick?.invoke() })
      }
      .draggable(
        orientation = Orientation.Horizontal,
        enabled = enabled,
        interactionSource = grip,
        state = rememberDraggableState { delta -> currentDrag(with(density) { delta.toDp() }) },
        onDragStopped = { onDragStopped() },
      )
      .onKeyEvent { event ->
        if (!enabled || event.type != KeyEventType.KeyDown) false else when (event.key) {
          Key.DirectionLeft -> { currentStep(keyStep); true }
          Key.DirectionRight -> { currentStep(-keyStep); true }
          Key.MoveHome -> onDoubleClick?.let { it(); true } ?: false
          else -> false
        }
      }.focusable(enabled = enabled, interactionSource = grip),
  ) {
    Box(
      Modifier.align(Alignment.CenterStart).width(if (active && enabled) 2.dp else 1.dp).fillMaxHeight()
        .background(if (active && enabled) colors.accentRing else colors.border),
    )
  }
}
