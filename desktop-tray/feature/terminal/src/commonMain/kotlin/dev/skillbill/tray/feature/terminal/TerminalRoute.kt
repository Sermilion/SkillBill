package dev.skillbill.tray.feature.terminal

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.skillbill.tray.core.designsystem.canvas
import dev.skillbill.tray.core.designsystem.dim
import dev.skillbill.tray.core.designsystem.mono

/**
 * The bottom pane chrome around a platform-supplied terminal [surface]: a tab strip, a new-tab button and the close button. Only
 * the active tab's surface composes, keyed by its id, so each tab keeps its own surface state. The shell picks the tab's directory
 * through [onNewTab] and draws the divider above the pane.
 */
@Composable
fun TerminalRoute(
  viewModel: TerminalViewModel,
  onNewTab: () -> Unit,
  surface: @Composable (TerminalTab) -> Unit,
  modifier: Modifier = Modifier,
) {
  val state by viewModel.state.collectAsState()
  if (!state.paneOpen) return
  Column(modifier.fillMaxSize()) {
    Row(
      Modifier.fillMaxWidth().height(28.dp).background(canvas).padding(horizontal = 10.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      TerminalTabStrip(
        state.tabs,
        state.activeTabId,
        onSelect = { viewModel.onAction(TerminalAction.SelectTab(it)) },
        onClose = { viewModel.onAction(TerminalAction.CloseTab(it)) },
        onRename = { id, name -> viewModel.onAction(TerminalAction.RenameTab(id, name)) },
        Modifier.weight(1f),
      )
      NewTabButton(onNewTab)
      CloseButton { viewModel.onAction(TerminalAction.ClosePane) }
    }
    Box(Modifier.weight(1f).fillMaxWidth()) {
      state.tabs.firstOrNull { it.id == state.activeTabId }?.let { tab -> key(tab.id) { surface(tab) } }
    }
  }
}

@Composable
private fun NewTabButton(onClick: () -> Unit) {
  Box(
    Modifier.size(24.dp).clickable(role = Role.Button, onClick = onClick).clearAndSetSemantics {
      contentDescription = "New terminal tab"
      role = Role.Button
    },
    contentAlignment = Alignment.Center,
  ) {
    Text("+", color = dim, fontFamily = mono, fontSize = 13.sp)
  }
}

@Composable
private fun CloseButton(onClick: () -> Unit) {
  Box(
    Modifier.size(24.dp).clickable(role = Role.Button, onClick = onClick).clearAndSetSemantics {
      contentDescription = "Close terminal"
      role = Role.Button
    },
    contentAlignment = Alignment.Center,
  ) {
    Text("×", color = dim, fontFamily = mono, fontSize = 13.sp)
  }
}
