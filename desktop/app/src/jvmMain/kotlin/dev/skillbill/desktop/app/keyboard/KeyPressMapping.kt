package dev.skillbill.desktop.app.keyboard

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.nativeKeyCode
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.utf16CodePoint
import java.awt.event.KeyEvent.VK_A
import java.awt.event.KeyEvent.VK_Z

/**
 * A key down as the key map reads it. Named keys come from [KeyEvent.key] and characters from the typed code point; with Ctrl or
 * Cmd held the code point is a control character, so letters come from the key code instead.
 */
fun KeyEvent.toKeyPress(): KeyPress? {
  if (type != KeyEventType.KeyDown) return null
  val shellKey = when (key) {
    Key.Enter, Key.NumPadEnter -> ShellKey.Enter
    Key.Escape -> ShellKey.Escape
    Key.DirectionUp -> ShellKey.Up
    Key.DirectionDown -> ShellKey.Down
    Key.Grave -> ShellKey.Grave
    else -> {
      val code = key.nativeKeyCode
      when {
        (isCtrlPressed || isMetaPressed) && code in VK_A..VK_Z -> ShellKey.Char('a' + (code - VK_A))
        utf16CodePoint >= ' '.code -> ShellKey.Char(utf16CodePoint.toChar())
        else -> return null
      }
    }
  }
  return KeyPress(shellKey, ctrl = isCtrlPressed, meta = isMetaPressed, shift = isShiftPressed, alt = isAltPressed)
}
