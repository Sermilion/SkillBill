package dev.skillbill.desktop.core.ui.shell

import androidx.compose.runtime.compositionLocalOf

/** What the window width and the open panes leave room for. */
data class ShellLayout(val drawerMode: Boolean)

val LocalShellLayout = compositionLocalOf { ShellLayout(drawerMode = false) }
