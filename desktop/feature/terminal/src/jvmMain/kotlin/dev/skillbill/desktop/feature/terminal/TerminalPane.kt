package dev.skillbill.desktop.feature.terminal

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.SwingPanel
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import dev.skillbill.desktop.core.designsystem.SkillBillTheme
import dev.skillbill.desktop.core.designsystem.detail.TerminalPaneChrome
import dev.skillbill.desktop.core.designsystem.detail.TerminalTabChrome
import dev.skillbill.desktop.core.ui.input.ShellEdit
import dev.skillbill.desktop.core.ui.input.gatesShortcutsWhileFocused
import javax.swing.SwingUtilities

// From components.css: the pane never takes more than 70 % of the work column, so the main panel keeps its space.
private const val MAX_COLUMN_SHARE = 0.7f

/**
 * The bottom terminal pane: the tab bar, the grip and the active tab's shell. The cap changes what renders; a resize starts from
 * the rendered height and stays under the cap, and a height stored in a taller window is kept until the user resizes.
 */
@Composable
fun TerminalPane(workspace: TerminalWorkspace, modifier: Modifier = Modifier) {
  val state by workspace.state.collectAsState()
  if (!state.paneOpen) return
  BoxWithConstraints(modifier) {
    val cap = if (constraints.hasBoundedHeight) maxHeight * MAX_COLUMN_SHARE else Dp.Infinity
    TerminalPaneChrome(
      height = min(state.heightDp.dp, cap),
      tabs = state.tabs.map { TerminalTabChrome(it.id.toString(), it.label) },
      activeId = state.activeId?.toString(),
      renamingId = state.renamingId?.toString(),
      onSelectTab = { workspace.selectTab(it.toLong()) },
      onStartRename = { workspace.startRename(it.toLong()) },
      onRename = { id, name -> workspace.rename(id.toLong(), name) },
      onCancelRename = workspace::cancelRename,
      renameFieldGate = { cancel, commit ->
        ShellEdit(onCancel = cancel, onSave = commit)
        Modifier.gatesShortcutsWhileFocused()
      },
      onCloseTab = { workspace.closeTab(it.toLong()) },
      onNewTab = workspace::newTerminalTab,
      onResizeBy = { workspace.resizeBy(it.value, cap.value) },
      onClose = workspace::closePane,
    ) {
      state.tabs.firstOrNull { it.id == state.activeId }?.let { tab -> key(tab.id) { TerminalSurface(workspace, tab) } }
    }
  }
}

/**
 * One tab's shell in a [SwingPanel]. Sessions outlive composition, so switching tabs or hiding the pane reparents the same widget
 * and keeps the shell and its scrollback.
 */
@Composable
private fun TerminalSurface(workspace: TerminalWorkspace, tab: TerminalTabUi) {
  val colors = SkillBillTheme.colors
  val font by produceState(workspace.loadedFont) { value = workspace.font() }
  val start by produceState<TerminalStart?>(null, font) {
    val loaded = font ?: return@produceState
    value = workspace.session(tab.id).open(workspace.launch(tab), TerminalSettings(colors, loaded))
  }
  when (val current = start) {
    // SwingPanel calls its factory once, so another session's widget needs a new panel.
    is TerminalStart.Started -> key(current.view) {
      SwingPanel(factory = { current.view.component }, modifier = Modifier.fillMaxSize())
      LaunchedEffect(current.view) { SwingUtilities.invokeLater(current.view::requestFocus) }
    }
    is TerminalStart.Failed -> LaunchedEffect(current) { workspace.startFailed(tab.id, current.message) }
    TerminalStart.Closed, null -> Box(Modifier.fillMaxSize())
  }
}
