package dev.skillbill.desktop.core.designsystem.composite

import androidx.compose.ui.input.pointer.PointerIcon
import kotlin.time.Instant

internal expect val verticalResizePointerIcon: PointerIcon

expect val horizontalResizePointerIcon: PointerIcon

internal expect fun runAfterCurrentEvent(block: () -> Unit)

internal expect fun formatClockTime(instant: Instant): String
