package dev.skillbill.desktop.core.ui.pane

import dev.skillbill.desktop.core.domain.model.ReviewFindingsAction
import dev.skillbill.desktop.core.domain.model.WorkItem
import kotlinx.coroutines.flow.StateFlow

/** The Cmd key's label on macOS and the Ctrl key's elsewhere. */
val MOD_KEY: String = if (System.getProperty("os.name").orEmpty().startsWith("Mac")) "⌘" else "Ctrl"

/** The chat toggle's shortcut label, shared by the shortcut sheet, the detail header and the chat pane. */
val CHAT_SHORTCUT: String = "$MOD_KEY L"

/** The window's chat pane, as the shell and the detail feature ask for it; the chat feature binds it. */
interface ChatCommands {
  /** The execution id whose chat the pane shows, or null when the pane is hidden or has no tab. */
  val shownChatId: StateFlow<String?>

  /** True while a new-task chat exists. */
  val newTaskDraftOpen: StateFlow<Boolean>

  /** Hides the pane when it shows [item]'s chat, or any chat when [item] is null; otherwise opens [item]'s chat, or only shows the pane. */
  fun toggleChat(item: WorkItem?)

  /** Shows or hides the chat pane without opening, creating, or switching a chat. */
  fun toggleChatPane()

  fun openChat(item: WorkItem)

  fun runReviewAction(item: WorkItem, action: ReviewFindingsAction)

  /** Ends every chat of the window; the window calls it when it is disposed. */
  fun closeChats()

  /** Closes chats before the window exits, or returns false when its new-task draft could not be saved. */
  suspend fun closeChatsSafely(): Boolean {
    closeChats()
    return true
  }
}
