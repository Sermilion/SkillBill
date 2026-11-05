package dev.skillbill.desktop.feature.changes

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type

internal enum class WindowKey { OpenPicker, Previous, Next, All, NextFile, PreviousFile, FocusFilter, Open, Escape, Comment, AddReview }

/** The window binding for a key-down without Ctrl, Alt or Meta; null for anything else. */
internal fun windowKeyFor(event: KeyEvent): WindowKey? {
  if (event.type != KeyEventType.KeyDown || event.isAltPressed) return null
  if (event.key == Key.Enter && (event.isMetaPressed || event.isCtrlPressed)) return WindowKey.AddReview
  if (event.isCtrlPressed || event.isMetaPressed) return null
  return when (event.key) {
    Key.S -> WindowKey.OpenPicker
    Key.LeftBracket -> WindowKey.Previous
    Key.RightBracket -> WindowKey.Next
    Key.A -> WindowKey.All
    Key.J -> WindowKey.NextFile
    Key.K -> WindowKey.PreviousFile
    Key.Slash -> WindowKey.FocusFilter
    Key.O -> WindowKey.Open
    Key.Escape -> WindowKey.Escape
    Key.C -> WindowKey.Comment
    else -> null
  }
}

/**
 * One per Changes panel. The main window's `onPreviewKeyEvent` calls [handle] while the panel is open, so keys work with no focused
 * node and no shell shortcut fires under it; the panel content installs the handler while it is shown.
 */
class ChangesWindowKeys {
  internal var handler: (KeyEvent) -> Boolean = { false }

  fun handle(event: KeyEvent): Boolean = handler(event)
}
