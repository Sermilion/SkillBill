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
import dev.skillbill.desktop.core.domain.model.ChatPermissionMode
import dev.skillbill.desktop.core.ui.pane.CHAT_SHORTCUT

/** One tab per chat, several per execution when the operator starts more, above the active chat's transcript and composer. */
@Composable
fun ChatPane(workspace: ChatWorkspace) {
  val state by workspace.tabs.collectAsState()
  BoxWithConstraints(Modifier.fillMaxSize()) {
    TerminalPaneChrome(
      height = maxHeight,
      tabs = state.tabs.map { TerminalTabChrome(it.id, it.label) },
      activeId = state.activeId,
      renamingId = null,
      onSelectTab = workspace::activate,
      onStartRename = null,
      onRename = { _, _ -> },
      onCancelRename = {},
      renameFieldGate = { _, _ -> Modifier },
      onCloseTab = workspace::close,
      onNewTab = workspace::newChat,
      onResizeBy = null,
      onClose = workspace::hide,
      icon = LucideIcons.bot,
      noun = "chat",
      newTabShortcut = null,
      closeShortcut = CHAT_SHORTCUT,
      equalWidthTabs = true,
    ) {
      val session = state.activeId?.let(workspace::session)
      val approvalsTab = state.activeId?.let(workspace::approvalsTab)
      if (approvalsTab != null) {
        val tabId = checkNotNull(state.activeId)
        key(approvalsTab) {
          val live by approvalsTab.liveState.collectAsState()
          val approvals = ChatApprovalActions(
            live = live,
            onDecide = { key, optionId, always -> workspace.decideRepositoryApproval(tabId, key, optionId, always) },
            onDismiss = { key -> workspace.dismissRepositoryApproval(tabId, key) },
            onOpenRules = { workspace.refreshApprovalRules(tabId) },
          )
          Column(Modifier.fillMaxSize()) {
            RepositoryRulesPanel(
              tab = approvalsTab,
              onRefresh = { workspace.refreshApprovalRules(tabId) },
              onRevoke = { ruleId -> workspace.revokeApprovalRule(tabId, ruleId) },
            )
            ChatTranscript(
              approvalsTab.thread,
              gone = false,
              mode = ChatPermissionMode.ReadOnly,
              modifier = Modifier.weight(1f),
              approvals = approvals,
              onAction = null,
            )
          }
        }
      } else if (session == null) {
        EmptyState(title = "No chats", icon = LucideIcons.bot, body = "Open an execution and press $CHAT_SHORTCUT or + to chat about it.")
      } else {
        key(session) {
          val gone by session.gone.collectAsState()
          val coordinationMode by session.coordinationMode.collectAsState()
          val approvalLive by session.approvalLive.collectAsState()
          val approvals = ChatApprovalActions(
            live = approvalLive,
            onDecide = { key, optionId, always -> workspace.decideApproval(session.id, key, optionId, always) },
            onDismiss = { key -> workspace.dismissApproval(session.id, key) },
            onOpenRules = { workspace.openApprovals(session.item.repositoryRoot) },
          )
          Column(Modifier.fillMaxSize()) {
            ChatHeader(session) { workspace.openApprovals(session.item.repositoryRoot) }
            ChatTranscript(
              session.thread,
              gone,
              coordinationMode,
              Modifier.weight(1f),
              approvals = approvals,
              operationCards = workspace.operationCardActions(session.id),
            ) { reply, action ->
              workspace.executeAction(session.id, reply, action)
            }
            ChatComposer(session, workspace)
          }
        }
      }
    }
  }
}
