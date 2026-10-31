package dev.skillbill.desktop.core.ui.pane

import dev.skillbill.desktop.core.domain.model.WorkItem
import kotlinx.coroutines.flow.StateFlow

/** The chat toggle's shortcut label, shared by the shortcut sheet, the detail header and the chat pane. */
val CHAT_SHORTCUT: String = if (System.getProperty("os.name").orEmpty().startsWith("Mac")) "⌘ L" else "Ctrl L"

/** The window's chat pane, as the shell and the detail feature ask for it; the chat feature binds it. */
interface ChatCommands {
  /** The execution id whose chat the pane shows, or null when the pane is hidden or has no tab. */
  val shownChatId: StateFlow<String?>

  /** Hides the pane when it shows [item]'s chat, or any chat when [item] is null; otherwise opens [item]'s chat, or only shows the pane. */
  fun toggleChat(item: WorkItem?)

  fun openChat(item: WorkItem)

  /** Ends every chat of the window; the window calls it when it is disposed. */
  fun closeChats()
}
