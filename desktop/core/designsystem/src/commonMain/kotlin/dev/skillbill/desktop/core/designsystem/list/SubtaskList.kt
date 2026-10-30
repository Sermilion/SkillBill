package dev.skillbill.desktop.core.designsystem.list

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.skillbill.desktop.core.designsystem.SkillBillRadius
import dev.skillbill.desktop.core.designsystem.SkillBillTheme
import dev.skillbill.desktop.core.designsystem.detail.sbBoxSegment

/**
 * `.sb-sublist`: the Subtasks index, a box that takes keyboard focus. While it has focus ↑ and ↓ call [onMove] with -1 or 1 and
 * Enter calls [onOpen]. A press inside focuses the clicked row without the ring; the 2 px `accent-ring` outline shows only for keyboard focus,
 * as `:focus-visible` does. [onFocusChange] reports focus so the shell can hand it the arrow keys. [requestFocus] takes keyboard
 * focus once the list shows, as main.js does when J or K pages away from a focused list.
 */
@Composable
fun SubtaskList(
  label: String,
  onMove: (Int) -> Unit,
  onOpen: () -> Unit,
  modifier: Modifier = Modifier,
  onFocusChange: (Boolean) -> Unit = {},
  requestFocus: Boolean = false,
  content: @Composable ColumnScope.() -> Unit,
) {
  val requester = remember { FocusRequester() }
  var focused by remember { mutableStateOf(false) }
  var pointerFocus by remember { mutableStateOf(false) }
  val move by rememberUpdatedState(onMove)
  val open by rememberUpdatedState(onOpen)
  LaunchedEffect(requestFocus) {
    if (requestFocus) {
      withFrameNanos { }
      requester.requestFocus()
    }
  }
  val ring = SkillBillTheme.colors.accentRing
  val radius = SkillBillRadius.md
  Column(
    modifier.fillMaxWidth()
      .drawBehind {
        if (focused && !pointerFocus) {
          val stroke = 2.dp.toPx()
          val outset = 1.dp.toPx() + stroke / 2
          drawRoundRect(
            ring,
            topLeft = Offset(-outset, -outset),
            size = Size(size.width + outset * 2, size.height + outset * 2),
            cornerRadius = CornerRadius(radius.toPx() + outset),
            style = Stroke(stroke),
          )
        }
      }
      .pointerInput(Unit) {
        awaitPointerEventScope {
          while (true) {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            if (event.type == PointerEventType.Press) {
              pointerFocus = true
            }
          }
        }
      }
      .onFocusChanged {
        focused = it.hasFocus
        if (!it.hasFocus) pointerFocus = false
        onFocusChange(it.hasFocus)
      }
      .onPreviewKeyEvent { event ->
        if (event.type != KeyEventType.KeyDown || event.isCtrlPressed || event.isMetaPressed || event.isAltPressed || event.isShiftPressed) {
          return@onPreviewKeyEvent false
        }
        when (event.key) {
          Key.DirectionDown -> { move(1); true }
          Key.DirectionUp -> { move(-1); true }
          Key.Enter, Key.NumPadEnter -> { open(); true }
          else -> false
        }
      }
      .focusRequester(requester)
      .focusable()
      .semantics { contentDescription = label }
      .selectableGroup()
      .sbBoxSegment(0, 1),
    content = content,
  )
}
