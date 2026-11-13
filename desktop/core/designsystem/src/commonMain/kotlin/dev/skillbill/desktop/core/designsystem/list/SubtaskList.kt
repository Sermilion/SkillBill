package dev.skillbill.desktop.core.designsystem.list

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import dev.skillbill.desktop.core.designsystem.detail.sbBoxSegment

internal val LocalSubtaskRowOpen = staticCompositionLocalOf<(() -> Unit)?> { null }

/**
 * `.sb-sublist`: the Subtasks index, a box that takes keyboard focus. While it has focus ↑ and ↓ call [onMove] with -1 or 1 and
 * Enter calls [onOpen]. The list draws no focus outline. [onFocusChange] reports focus so the shell can hand it the arrow keys.
 * [requestFocus] takes keyboard focus once the list shows and reports completion through [onFocusRequestComplete].
 */
@Composable
fun SubtaskList(
  label: String,
  onMove: (Int) -> Unit,
  onOpen: () -> Unit,
  modifier: Modifier = Modifier,
  onFocusChange: (Boolean) -> Unit = {},
  requestFocus: Boolean = false,
  onFocusRequestComplete: (focused: Boolean) -> Unit = {},
  content: @Composable ColumnScope.() -> Unit,
) {
  val requester = remember { FocusRequester() }
  var focused by remember { mutableStateOf(false) }
  val move by rememberUpdatedState(onMove)
  val open by rememberUpdatedState(onOpen)
  val completeFocusRequest by rememberUpdatedState(onFocusRequestComplete)
  var focusRequestCompleted by remember { mutableStateOf(false) }
  LaunchedEffect(requestFocus) {
    if (!requestFocus) {
      focusRequestCompleted = false
      return@LaunchedEffect
    }
    focusRequestCompleted = false
    withFrameNanos { }
    if (focused || requester.requestFocus()) {
      if (!focusRequestCompleted) {
        focusRequestCompleted = true
        completeFocusRequest(true)
      }
    } else if (!focusRequestCompleted) {
      focusRequestCompleted = true
      completeFocusRequest(false)
    }
  }
  Column(
    modifier.fillMaxWidth()
      .onFocusChanged {
        focused = it.hasFocus
        if (it.hasFocus && requestFocus && !focusRequestCompleted) {
          focusRequestCompleted = true
          completeFocusRequest(true)
        }
        onFocusChange(it.hasFocus)
      }
      .onKeyEvent { event ->
        if (event.type != KeyEventType.KeyDown || event.isCtrlPressed || event.isMetaPressed || event.isAltPressed || event.isShiftPressed) {
          return@onKeyEvent false
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
    content = { CompositionLocalProvider(LocalSubtaskRowOpen provides { open() }) { content() } },
  )
}
