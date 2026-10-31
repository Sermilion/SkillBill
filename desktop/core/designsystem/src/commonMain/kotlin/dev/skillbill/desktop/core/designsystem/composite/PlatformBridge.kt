package dev.skillbill.desktop.core.designsystem.composite

import androidx.compose.ui.input.pointer.PointerIcon

internal expect val verticalResizePointerIcon: PointerIcon

expect val horizontalResizePointerIcon: PointerIcon

internal expect fun runAfterCurrentEvent(block: () -> Unit)
