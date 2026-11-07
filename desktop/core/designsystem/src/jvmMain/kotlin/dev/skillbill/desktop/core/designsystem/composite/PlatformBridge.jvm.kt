package dev.skillbill.desktop.core.designsystem.composite

import androidx.compose.ui.input.pointer.PointerIcon
import java.awt.Cursor
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import javax.swing.SwingUtilities
import kotlin.time.Instant
import kotlin.time.toJavaInstant

internal actual val verticalResizePointerIcon: PointerIcon = PointerIcon(Cursor(Cursor.N_RESIZE_CURSOR))

actual val horizontalResizePointerIcon: PointerIcon = PointerIcon(Cursor(Cursor.W_RESIZE_CURSOR))

internal actual fun runAfterCurrentEvent(block: () -> Unit) = SwingUtilities.invokeLater(block)

internal actual fun formatClockTime(instant: Instant): String =
  DateTimeFormatter.ofPattern("HH:mm", Locale.getDefault()).format(instant.toJavaInstant().atZone(ZoneId.systemDefault()))
