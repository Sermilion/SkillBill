package dev.skillbill.desktop.feature.changes

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type

internal enum class WindowKey { OpenPicker, Previous, Next, All, NextFile, PreviousFile, FocusFilter, Open, Escape }

/** The window binding for a key-down without Ctrl, Alt or Meta; null for anything else. */
internal fun windowKeyFor(event: KeyEvent): WindowKey? {
  if (event.type != KeyEventType.KeyDown || event.isCtrlPressed || event.isAltPressed || event.isMetaPressed) return null
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
    else -> null
  }
}

/**
 * One per Changes window. The window's `onPreviewKeyEvent` calls [handle], so keys work with no focused node; the window content
 * installs the handler while it is shown.
 */
class ChangesWindowKeys {
  internal var handler: (KeyEvent) -> Boolean = { false }

  fun handle(event: KeyEvent): Boolean = handler(event)
}
