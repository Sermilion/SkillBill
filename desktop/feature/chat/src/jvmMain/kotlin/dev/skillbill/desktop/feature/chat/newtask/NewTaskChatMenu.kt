package dev.skillbill.desktop.feature.chat.newtask

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import dev.skillbill.desktop.core.designsystem.component.SbMenu
import dev.skillbill.desktop.core.designsystem.component.SbMenuEntry
import dev.skillbill.desktop.core.designsystem.component.SbPopover
import dev.skillbill.desktop.core.designsystem.icon.LucideIcons
import dev.skillbill.desktop.core.ui.input.ShellLayer
import dev.skillbill.desktop.core.ui.pane.MOD_KEY
import kotlin.math.roundToInt

/** Where an open chat's menu shows, relative to its tab or row; null while it is closed. */
internal class ChatMenuAnchor {
  var at by mutableStateOf<IntOffset?>(null)
  var size by mutableStateOf(IntSize.Zero)

  fun open(point: Offset) {
    at = IntOffset(point.x.roundToInt(), point.y.roundToInt())
  }

  /** Shift+F10 and the menu key have no pointer position, so the menu opens under the tab's start edge. */
  fun openFromKeyboard() {
    at = IntOffset(0, size.height)
  }

  fun close() {
    at = null
  }
}

/** Opens [anchor] on a secondary click, Shift+F10 or the menu key. */
internal fun Modifier.chatMenuTrigger(anchor: ChatMenuAnchor): Modifier = this
  .onSizeChanged { anchor.size = it }
  .pointerInput(anchor) {
    awaitPointerEventScope {
      while (true) {
        val event = awaitPointerEvent()
        if (event.type == PointerEventType.Press && event.buttons.isSecondaryPressed) {
          anchor.open(event.changes.first().position)
          event.changes.forEach { it.consume() }
        }
      }
    }
  }
  .onPreviewKeyEvent {
    val menuKey = it.key == Key.Menu || (it.key == Key.F10 && it.isShiftPressed)
    if (it.type == KeyEventType.KeyDown && menuKey) {
      anchor.openFromKeyboard()
      true
    } else {
      false
    }
  }

/**
 * An open chat's menu: Close, and Discard once the chat has a conversation. Discard ends the chat and deletes its save, so it
 * also leaves Recent chats, where Close keeps it. Place it in a Box that shares the tab's or row's bounds.
 */
@Composable
internal fun NewTaskChatMenu(anchor: ChatMenuAnchor, item: OpenChatItem, onClose: () -> Unit, onDiscard: () -> Unit, returnFocusTo: FocusRequester?) {
  val open = anchor.at != null
  val dismiss = anchor::close
  if (open) ShellLayer(dismiss)
  // Stays composed while closed so the popover can hand focus back to the tab or row.
  Box(Modifier.offset { anchor.at ?: IntOffset.Zero }.size(0.dp)) {
    // -4 cancels the popover's anchor gap so the menu's corner sits on the pointer.
    SbPopover(expanded = open, onDismissRequest = dismiss, offset = DpOffset(0.dp, (-4).dp), returnFocusTo = returnFocusTo) {
      SbMenu(
        buildList {
          add(SbMenuEntry.Item("Close", { dismiss(); onClose() }, icon = LucideIcons.x, shortcut = "$MOD_KEY W"))
          if (item.hasConversation) add(SbMenuEntry.Item("Discard", { dismiss(); onDiscard() }, icon = LucideIcons.trash, danger = true))
        },
      )
    }
  }
}
