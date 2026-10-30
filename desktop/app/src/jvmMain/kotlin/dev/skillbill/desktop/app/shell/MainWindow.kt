package dev.skillbill.desktop.app.shell

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.ApplicationScope
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.rememberWindowState
import dev.skillbill.desktop.app.command.CommandMenu
import dev.skillbill.desktop.app.di.WindowComponent
import dev.skillbill.desktop.app.keyboard.ShortcutSheet
import dev.skillbill.desktop.app.keyboard.toKeyPress
import dev.skillbill.desktop.core.designsystem.SkillBillTheme
import dev.skillbill.desktop.core.designsystem.component.ToastStack
import dev.skillbill.desktop.core.ui.input.LocalShellInput
import dev.skillbill.desktop.core.ui.navigation.AppRoute
import dev.skillbill.desktop.core.ui.navigation.PaneSlot
import dev.skillbill.desktop.core.ui.navigation.SettingsPageId
import dev.skillbill.desktop.core.ui.navigation.SettingsRoute
import dev.skillbill.desktop.core.ui.shell.LocalShellLayout
import dev.skillbill.desktop.core.ui.shell.ShellLayout
import kotlinx.coroutines.cancel
import java.awt.Dimension

private val MIN_WINDOW_SIZE = Dimension(560, 420)

/** The main window: the shell frame, the routes, the command menu, the shortcut sheet and the toasts, all on [window]'s graph. */
@Composable
fun ApplicationScope.MainWindow(window: WindowComponent, reducedMotion: Boolean, visible: Boolean, focusRequest: Int, onCloseRequest: () -> Unit) {
  DisposableEffect(window) {
    onDispose {
      window.terminal.close()
      window.specsPane.close()
      window.scope.scope.cancel()
    }
  }
  LaunchedEffect(window) { window.navigator.followSession() }
  val keyboard = window.keyboard
  Window(
    onCloseRequest = onCloseRequest,
    visible = visible,
    title = "Skill Bill",
    state = rememberWindowState(size = DpSize(1280.dp, 820.dp)),
    onPreviewKeyEvent = { event -> event.toKeyPress()?.let(keyboard::onKey) ?: false },
  ) {
    val awtWindow = this.window
    SideEffect { awtWindow.minimumSize = MIN_WINDOW_SIZE }
    LaunchedEffect(visible, focusRequest) {
      if (visible) {
        withFrameNanos { }
        val desktop = if (java.awt.Desktop.isDesktopSupported()) java.awt.Desktop.getDesktop() else null
        if (desktop?.isSupported(java.awt.Desktop.Action.APP_REQUEST_FOREGROUND) == true) desktop.requestForeground(true)
        awtWindow.toFront()
        awtWindow.requestFocus()
      }
    }
    SkillBillTheme(reducedMotion) {
      val focusManager = LocalFocusManager.current
      SideEffect { keyboard.clearFocus = { focusManager.clearFocus() } }
      CompositionLocalProvider(LocalShellInput provides window.input) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
          val narrow = maxWidth < NARROW_WIDTH
          val drawerMode = maxWidth < DRAWER_WIDTH
          SideEffect { keyboard.drawerMode = drawerMode }
          val panes = window.panes
          val layout = ShellLayout(showDetailsPane = panes.detailsPaneOpen && !panes.specsOpen && !narrow, drawerMode = drawerMode)
          CompositionLocalProvider(LocalShellLayout provides layout) {
            ShellContent(window, drawerMode)
            CommandMenu(window)
            if (panes.shortcutsOpen) ShortcutSheet { panes.shortcutsOpen = false }
            ToastStack(window.toasts.host)
          }
        }
      }
    }
  }
}

@Composable
private fun ShellContent(window: WindowComponent, drawerMode: Boolean) {
  val navigator = window.navigator
  val panes = window.panes
  val session by window.session.state.collectAsState()
  val board by window.board.view.collectAsState()
  val topRoute = navigator.top.route
  val contributions = window.entries.flatMap { it.panes }
  val right = contributions.firstOrNull { it.slot == PaneSlot.Right }?.takeIf { panes.specsOpen && topRoute !is SettingsRoute }?.content
  val bottom = contributions.firstOrNull { it.slot == PaneSlot.Bottom }?.takeIf { panes.terminalOpen && topRoute !is SettingsRoute }?.content
  val navigate: (AppRoute) -> Unit = { route ->
    panes.drawerOpen = false
    navigator.navigate(route)
  }
  val sidebar: (@Composable () -> Unit)? = if (topRoute is SettingsRoute) null else {
    {
      ShellSidebar(
        topRoute = topRoute,
        counts = board.lastLoaded?.counts,
        specsOpen = panes.specsOpen,
        terminalOpen = panes.terminalOpen,
        contributions = window.entries.flatMap { it.sidebarItems },
        onNavigate = navigate,
        onOpenCommandMenu = window.menu::toggle,
        onToggleSpecs = { panes.specsOpen = !panes.specsOpen },
        onToggleTerminal = window.terminal::toggle,
        onShowShortcuts = { panes.shortcutsOpen = true },
      )
    }
  }
  ShellFrame(sidebar, drawerMode, panes.drawerOpen, onDismissDrawer = { panes.drawerOpen = false }) {
    WorkArea(drawerMode, right, bottom) {
      AppNavHost(navigator, window.entries, shellProblem(session, board)) { problem ->
        ProblemStatePanel(
          problem,
          onRefresh = { if (problem is ShellProblem.SessionFailed) window.session.reopen() else window.boardRepository.refresh() },
          onOpenSettings = { navigate(SettingsRoute(SettingsPageId.App)) },
        )
      }
    }
  }
}
