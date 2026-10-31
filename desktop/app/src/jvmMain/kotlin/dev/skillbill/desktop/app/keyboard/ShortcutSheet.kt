package dev.skillbill.desktop.app.keyboard

import androidx.compose.runtime.Composable
import dev.skillbill.desktop.core.designsystem.component.SbButton
import dev.skillbill.desktop.core.designsystem.component.SbButtonVariant
import dev.skillbill.desktop.core.designsystem.component.SbDialog
import dev.skillbill.desktop.core.designsystem.icon.LucideIcons
import dev.skillbill.desktop.core.designsystem.icon.SbIcon
import dev.skillbill.desktop.core.designsystem.overlay.ShortcutEntry
import dev.skillbill.desktop.core.designsystem.overlay.ShortcutGrid
import dev.skillbill.desktop.core.ui.pane.CHAT_SHORTCUT

/** The Cmd key's label on macOS and the Ctrl key's elsewhere. */
val MOD_KEY: String = if (System.getProperty("os.name").orEmpty().startsWith("Mac")) "⌘" else "Ctrl"

private val SHORTCUTS = listOf(
  ShortcutEntry("Command menu", "$MOD_KEY K"),
  ShortcutEntry("Go to Work", "G W"),
  ShortcutEntry("Go to History", "G H"),
  ShortcutEntry("Toggle specs pane", "G S"),
  ShortcutEntry("Go to Settings", "G ,"),
  ShortcutEntry("Toggle terminal", "⌃ `"),
  ShortcutEntry("New terminal tab", "⌃ ⇧ `"),
  ShortcutEntry("Filter", "/"),
  ShortcutEntry("Move down / up", "J K"),
  ShortcutEntry("Open", "↵"),
  ShortcutEntry("Pause after subtask", "P"),
  ShortcutEntry("Pause now", "⇧ P"),
  ShortcutEntry("Resume", "R"),
  ShortcutEntry("Toggle details pane", "$MOD_KEY I"),
  ShortcutEntry("Toggle chat panel", CHAT_SHORTCUT),
  ShortcutEntry("Toggle sidebar", "["),
  ShortcutEntry("Back / close", "esc"),
)

@Composable
fun ShortcutSheet(onDismiss: () -> Unit) {
  SbDialog(
    title = "Keyboard shortcuts",
    onDismissRequest = onDismiss,
    icon = { SbIcon(LucideIcons.keyboard) },
    actions = { SbButton("Done", onDismiss, variant = SbButtonVariant.Primary) },
  ) { ShortcutGrid(SHORTCUTS) }
}
