package dev.skillbill.desktop.feature.chat

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import dev.skillbill.desktop.core.designsystem.component.EmptyState
import dev.skillbill.desktop.core.designsystem.detail.TerminalPaneChrome
import dev.skillbill.desktop.core.designsystem.detail.TerminalTabChrome
import dev.skillbill.desktop.core.designsystem.icon.LucideIcons
import dev.skillbill.desktop.core.ui.pane.CHAT_SHORTCUT

/** One tab per execution chat, above the active chat's transcript and composer. */
@Composable
fun ChatPane(workspace: ChatWorkspace) {
  val state by workspace.tabs.collectAsState()
  BoxWithConstraints(Modifier.fillMaxSize()) {
    TerminalPaneChrome(
      height = maxHeight,
      tabs = state.tabs.map { TerminalTabChrome(it.executionId, it.title) },
      activeId = state.activeId,
      renamingId = null,
      onSelectTab = workspace::activate,
      onStartRename = null,
      onRename = { _, _ -> },
      onCancelRename = {},
      renameFieldGate = { _, _ -> Modifier },
      onCloseTab = workspace::close,
      onNewTab = null,
      onResizeBy = null,
      onClose = workspace::hide,
      icon = LucideIcons.bot,
      noun = "chat",
      newTabShortcut = null,
      closeShortcut = CHAT_SHORTCUT,
    ) {
      val session = state.activeId?.let(workspace::session)
      if (session == null) {
        EmptyState(title = "No chats", icon = LucideIcons.bot, body = "Open an execution and press $CHAT_SHORTCUT to chat about it.")
      } else {
        key(session) {
          Column(Modifier.fillMaxSize()) {
            ChatTranscript(session, Modifier.weight(1f)) { reply, action -> workspace.executeAction(session.item.id, reply, action) }
            ChatComposer(session, workspace)
          }
        }
      }
    }
  }
}
