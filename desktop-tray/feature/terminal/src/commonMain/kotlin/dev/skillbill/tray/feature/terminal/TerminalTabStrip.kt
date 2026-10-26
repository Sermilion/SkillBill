package dev.skillbill.tray.feature.terminal

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key as pressedKey
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.skillbill.tray.core.designsystem.amber
import dev.skillbill.tray.core.designsystem.body
import dev.skillbill.tray.core.designsystem.dim
import dev.skillbill.tray.core.designsystem.ink
import dev.skillbill.tray.core.designsystem.mono
import dev.skillbill.tray.core.designsystem.muted

private val UNDERLINE_HEIGHT = 2.dp

/**
 * The pane's tabs in one horizontally scrolling row, so tabs that do not fit scroll instead of shrinking. Left and right arrows on a
 * focused tab select the neighbour, wrapping at the ends, and move focus with the selection. A right-click opens the tab's menu
 * (rename, close); F2 and the "Rename tab" accessibility action start renaming without a pointer.
 */
@Composable
internal fun TerminalTabStrip(
  tabs: List<TerminalTab>,
  activeTabId: Long?,
  onSelect: (Long) -> Unit,
  onClose: (Long) -> Unit,
  onRename: (Long, String) -> Unit,
  modifier: Modifier = Modifier,
) {
  val requesters = remember { mutableMapOf<Long, FocusRequester>() }
  requesters.keys.retainAll(tabs.mapTo(mutableSetOf(), TerminalTab::id))
  Row(modifier.horizontalScroll(rememberScrollState()).selectableGroup(), verticalAlignment = Alignment.CenterVertically) {
    tabs.forEachIndexed { index, tab ->
      key(tab.id) {
        val requester = requesters.getOrPut(tab.id) { FocusRequester() }
        val select: (Int) -> Boolean = { offset ->
          val target = tabs[(index + offset).mod(tabs.size)]
          onSelect(target.id)
          requesters[target.id]?.requestFocus()
          true
        }
        TabItem(tab, tab.id == activeTabId, requester, select, onSelect, onClose, onRename)
      }
    }
  }
}

@Composable
private fun TabItem(
  tab: TerminalTab,
  active: Boolean,
  requester: FocusRequester,
  selectBy: (Int) -> Boolean,
  onSelect: (Long) -> Unit,
  onClose: (Long) -> Unit,
  onRename: (Long, String) -> Unit,
) {
  var menuOpen by remember { mutableStateOf(false) }
  var renaming by remember { mutableStateOf(false) }
  Row(
    Modifier.fillMaxHeight().drawBehind {
      if (active) {
        val thickness = UNDERLINE_HEIGHT.toPx()
        drawRect(amber, Offset(0f, size.height - thickness), Size(size.width, thickness))
      }
    },
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Box(contentAlignment = Alignment.Center) {
      if (renaming) {
        RenameField(tab.label, onDone = { name ->
          renaming = false
          if (name != null) onRename(tab.id, name)
        })
      } else {
        Box(
          Modifier.fillMaxHeight()
            .focusRequester(requester)
            .onSecondaryPress { menuOpen = true }
            .onKeyEvent { event ->
              if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
              when (event.pressedKey) {
                Key.DirectionLeft -> selectBy(-1)
                Key.DirectionRight -> selectBy(1)
                Key.F2 -> true.also { renaming = true }
                else -> false
              }
            }
            .semantics { customActions = listOf(CustomAccessibilityAction("Rename tab") { renaming = true; true }) }
            .selectable(selected = active, role = Role.Tab, onClick = { onSelect(tab.id) })
            .padding(start = 8.dp, end = 4.dp),
          contentAlignment = Alignment.Center,
        ) {
          Text(tab.label, color = if (active) ink else muted, fontFamily = body, fontSize = 11.sp)
        }
      }
      DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
        DropdownMenuItem(text = { Text("Rename tab") }, onClick = {
          menuOpen = false
          renaming = true
        })
        DropdownMenuItem(text = { Text("Close tab") }, onClick = {
          menuOpen = false
          onClose(tab.id)
        })
      }
    }
    Box(
      Modifier.size(18.dp).clickable(role = Role.Button) { onClose(tab.id) }.clearAndSetSemantics {
        contentDescription = "Close ${tab.label} tab"
        role = Role.Button
      },
      contentAlignment = Alignment.Center,
    ) {
      Text("×", color = dim, fontFamily = mono, fontSize = 13.sp)
    }
  }
}

/**
 * The inline editor that replaces a tab's label while it is renamed, starting with [current] selected. Enter or leaving the field
 * reports the typed name; Escape reports null, which keeps the old name.
 */
@Composable
private fun RenameField(current: String, onDone: (String?) -> Unit) {
  var value by remember { mutableStateOf(TextFieldValue(current, TextRange(0, current.length))) }
  val focus = remember { FocusRequester() }
  var focused by remember { mutableStateOf(false) }
  var done by remember { mutableStateOf(false) }
  val finish: (String?) -> Unit = { name ->
    if (!done) {
      done = true
      onDone(name)
    }
  }
  LaunchedEffect(Unit) { focus.requestFocus() }
  BasicTextField(
    value,
    onValueChange = { value = it },
    singleLine = true,
    textStyle = TextStyle(color = ink, fontFamily = body, fontSize = 11.sp),
    cursorBrush = SolidColor(amber),
    modifier = Modifier.padding(start = 8.dp, end = 4.dp).widthIn(min = 48.dp, max = 200.dp).width(IntrinsicSize.Min)
      .focusRequester(focus)
      .onFocusChanged { state ->
        if (focused && !state.isFocused) finish(value.text)
        focused = state.isFocused
      }
      .onPreviewKeyEvent { event ->
        if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
        when (event.pressedKey) {
          Key.Enter, Key.NumPadEnter -> true.also { finish(value.text) }
          Key.Escape -> true.also { finish(null) }
          else -> false
        }
      }
      .semantics { contentDescription = "Tab name" },
  )
}

/** Runs [onPress] on a secondary-button (right-click) press, which a selectable's click handling never sees. */
private fun Modifier.onSecondaryPress(onPress: () -> Unit): Modifier = pointerInput(Unit) {
  awaitPointerEventScope {
    while (true) {
      val event = awaitPointerEvent()
      if (event.type == PointerEventType.Press && event.buttons.isSecondaryPressed) {
        event.changes.forEach { it.consume() }
        onPress()
      }
    }
  }
}
