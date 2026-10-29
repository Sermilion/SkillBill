package dev.skillbill.desktop.core.designsystem.composite

import androidx.compose.ui.input.pointer.PointerIcon
import java.awt.Cursor
import javax.swing.SwingUtilities

internal actual val verticalResizePointerIcon: PointerIcon = PointerIcon(Cursor(Cursor.N_RESIZE_CURSOR))

internal actual fun runAfterCurrentEvent(block: () -> Unit) = SwingUtilities.invokeLater(block)
