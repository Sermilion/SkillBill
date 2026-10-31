package dev.skillbill.desktop.core.designsystem.component

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.areAnyPressed
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import dev.skillbill.desktop.core.designsystem.SkillBillRadius
import dev.skillbill.desktop.core.designsystem.SkillBillTheme
import dev.skillbill.desktop.core.designsystem.layeredShadow
import kotlinx.coroutines.delay

private const val TOOLTIP_DELAY_MS = 450L

@Composable
fun SbTooltip(text: String, shortcut: String? = null, content: @Composable () -> Unit) {
  val source = remember { MutableInteractionSource() }
  val hovered by source.collectIsHoveredAsState()
  var focused by remember { mutableStateOf(false) }
  var pointerPressed by remember { mutableStateOf(false) }
  val pressedKeys = remember { mutableStateMapOf<Key, Boolean>() }
  val pressed = pointerPressed || pressedKeys.isNotEmpty()
  // Focus a click gave counts as pointer focus, which shows no tooltip (as `:focus-visible` shows no ring), and a click hides
  // the hover tooltip until the pointer leaves, as a browser's does.
  var pointerFocus by remember { mutableStateOf(false) }
  var clickedSinceEnter by remember { mutableStateOf(false) }
  LaunchedEffect(hovered) { if (!hovered) clickedSinceEnter = false }
  var visible by remember { mutableStateOf(false) }
  val density = androidx.compose.ui.platform.LocalDensity.current
  LaunchedEffect(hovered, focused, pressed, pointerFocus, clickedSinceEnter) {
    visible = false
    if (((hovered && !clickedSinceEnter) || (focused && !pointerFocus)) && !pressed) {
      delay(TOOLTIP_DELAY_MS)
      visible = true
    }
  }
  Box(
    Modifier.hoverable(source)
      .onFocusChanged {
        focused = it.hasFocus
        if (!focused) {
          pressedKeys.clear()
          pointerFocus = false
        }
      }
      .onPreviewKeyEvent { event ->
        if (event.key == Key.Spacebar || event.key == Key.Enter) {
          when (event.type) {
            KeyEventType.KeyDown -> pressedKeys[event.key] = true
            KeyEventType.KeyUp -> pressedKeys.remove(event.key)
          }
        }
        false
      }
      .pointerInput(Unit) {
        try {
          awaitPointerEventScope {
            while (true) {
              pointerPressed = awaitPointerEvent(PointerEventPass.Initial).buttons.areAnyPressed
              if (pointerPressed) {
                pointerFocus = true
                clickedSinceEnter = true
              }
            }
          }
        } finally {
          pointerPressed = false
        }
      },
  ) {
    content()
    if (visible) {
      Popup(
        popupPositionProvider = TooltipPositionProvider(
          with(density) { 6.dp.roundToPx() },
          with(density) { 8.dp.roundToPx() },
        ),
        properties = PopupProperties(focusable = false, dismissOnBackPress = false, dismissOnClickOutside = false),
      ) {
        val shape = RoundedCornerShape(SkillBillRadius.sm)
        Row(
          Modifier.widthIn(max = 360.dp).clearAndSetSemantics {}
            .layeredShadow(SkillBillTheme.shadows.popover, shape)
            .background(SkillBillTheme.colors.bgOverlay, shape)
            .border(1.dp, SkillBillTheme.colors.borderStrong, shape)
            // `.sb-tip` pads 5px 8px inside its 1px border.
            .padding(horizontal = 9.dp, vertical = 6.dp),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
          BasicText(text, Modifier.weight(1f, fill = false), style = SkillBillTheme.type.tXs.copy(color = SkillBillTheme.colors.fg, lineHeight = 1.2.em))
          shortcut?.let { Kbd(it) }
        }
      }
    }
  }
}

private class TooltipPositionProvider(private val gap: Int, private val margin: Int) : PopupPositionProvider {
  override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection, popupContentSize: IntSize): IntOffset {
    val centered = anchorBounds.left + (anchorBounds.width - popupContentSize.width) / 2
    val x = centered.coerceIn(margin, (windowSize.width - popupContentSize.width - margin).coerceAtLeast(margin))
    val below = anchorBounds.bottom + gap
    val y = if (below + popupContentSize.height <= windowSize.height - margin) below else (anchorBounds.top - popupContentSize.height - gap).coerceAtLeast(margin)
    return IntOffset(x, y)
  }
}
