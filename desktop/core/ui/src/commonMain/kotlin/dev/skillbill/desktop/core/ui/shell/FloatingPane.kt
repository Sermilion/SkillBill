package dev.skillbill.desktop.core.ui.shell

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.skillbill.desktop.core.designsystem.SkillBillTheme
import dev.skillbill.desktop.core.designsystem.composite.horizontalResizePointerIcon
import dev.skillbill.desktop.core.designsystem.menuSpec
import dev.skillbill.desktop.core.ui.input.ShellLayer

@Composable
fun FloatingPane(open: Boolean, title: String, wide: Boolean = false, onDismiss: () -> Unit, content: @Composable () -> Unit) {
  val colors = SkillBillTheme.colors
  val density = LocalDensity.current
  var requestedWidth by remember(wide) { mutableStateOf<Dp?>(null) }
  BoxWithConstraints(Modifier.fillMaxSize()) {
    val compact = maxWidth < 760.dp
    val maximum = if (compact) maxWidth else maxWidth - 64.dp
    val minimum = minOf(360.dp, maximum)
    val preferred = requestedWidth ?: minOf(if (wide) 800.dp else 560.dp, maxWidth * 0.70f)
    val width = if (compact) maximum else preferred.coerceIn(minimum, maximum)
    AnimatedVisibility(
      visible = open,
      modifier = Modifier.align(Alignment.CenterEnd).width(width).fillMaxHeight(),
      enter = slideInHorizontally(animationSpec = menuSpec()) { it },
      exit = slideOutHorizontally(animationSpec = menuSpec()) { it },
    ) {
      if (open) ShellLayer(onDismiss)
      Row(
        Modifier.fillMaxSize().shadow(16.dp).background(colors.bgPanel)
          .pointerInput(Unit) { detectTapGestures { } }
          .semantics { paneTitle = title },
      ) {
        val grip = remember { MutableInteractionSource() }
        val active = grip.collectIsHoveredAsState().value || grip.collectIsDraggedAsState().value || grip.collectIsFocusedAsState().value
        Box(
          Modifier.width(7.dp).fillMaxHeight()
            .semantics { contentDescription = "Resize ${title.lowercase()} panel" }
            .then(if (compact) Modifier else Modifier.pointerHoverIcon(horizontalResizePointerIcon))
            .hoverable(grip, enabled = !compact)
            .draggable(
              orientation = Orientation.Horizontal,
              enabled = !compact,
              interactionSource = grip,
              state = rememberDraggableState { delta ->
                requestedWidth = ((requestedWidth ?: width).coerceIn(minimum, maximum) - with(density) { delta.toDp() }).coerceIn(minimum, maximum)
              },
            )
            .onKeyEvent { event ->
              if (compact || event.type != KeyEventType.KeyDown) false else when (event.key) {
                Key.DirectionLeft -> { requestedWidth = (width + 32.dp).coerceAtMost(maximum); true }
                Key.DirectionRight -> { requestedWidth = (width - 32.dp).coerceAtLeast(minimum); true }
                else -> false
              }
            }.focusable(enabled = !compact, interactionSource = grip),
        ) {
          Box(
            Modifier.align(Alignment.CenterStart).width(if (active && !compact) 2.dp else 1.dp).fillMaxHeight()
              .background(if (active && !compact) colors.accentRing else colors.border),
          )
        }
        Box(Modifier.weight(1f).fillMaxHeight()) { content() }
      }
    }
  }
}
