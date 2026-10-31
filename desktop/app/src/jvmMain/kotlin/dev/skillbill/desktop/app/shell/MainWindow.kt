package dev.skillbill.desktop.app.shell

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.ApplicationScope
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.rememberWindowState
import dev.skillbill.desktop.app.command.CommandMenu
import dev.skillbill.desktop.app.display.DesktopUiScale
import dev.skillbill.desktop.app.display.DesktopUiScaleProvider
import dev.skillbill.desktop.app.di.WindowComponent
import dev.skillbill.desktop.app.keyboard.ShortcutSheet
import dev.skillbill.desktop.app.keyboard.KeyPress
import dev.skillbill.desktop.app.keyboard.ShellKey
import dev.skillbill.desktop.app.keyboard.toKeyPress
import dev.skillbill.desktop.core.designsystem.icon.skillBillIcon
import dev.skillbill.desktop.core.designsystem.SkillBillTheme
import dev.skillbill.desktop.core.designsystem.component.ToastStack
import dev.skillbill.desktop.core.designsystem.navigation.LocalSidebarToggle
import dev.skillbill.desktop.core.ui.input.LocalShellInput
import dev.skillbill.desktop.core.ui.navigation.AppRoute
import dev.skillbill.desktop.core.ui.navigation.PaneSlot
import dev.skillbill.desktop.core.ui.navigation.SettingsRoute
import dev.skillbill.desktop.core.ui.shell.LocalShellLayout
import dev.skillbill.desktop.core.ui.shell.ShellLayout
import kotlinx.coroutines.cancel
import java.awt.GraphicsEnvironment

private const val MIN_WINDOW_WIDTH = 560
private const val MIN_WINDOW_HEIGHT = 420

/**
 * The opening size in content dp: the sidebar, the 780 dp document column and the details pane side by side with room to spare,
 * and tall enough for a header, metrics and a few sections without scrolling.
 */
private val COMFORTABLE_CONTENT = DpSize(1400.dp, 900.dp)

/** The most of the screen's usable area the opening window takes, so a small display still gets a window, not full screen. */
private const val MAX_SCREEN_SHARE = 0.9f

/**
 * [COMFORTABLE_CONTENT] in window dp. When [uiScale] overrides the platform density the window grows by its window scale, so the
 * content still gets its comfortable size; the result is then capped at [MAX_SCREEN_SHARE] of the usable screen area.
 */
private fun comfortableWindowSize(uiScale: DesktopUiScale): DpSize {
  val size = uiScale.windowSize(COMFORTABLE_CONTENT)
  if (GraphicsEnvironment.isHeadless()) return size
  val usable = GraphicsEnvironment.getLocalGraphicsEnvironment().maximumWindowBounds
  return DpSize(
    minOf(size.width.value, usable.width * MAX_SCREEN_SHARE).dp,
    minOf(size.height.value, usable.height * MAX_SCREEN_SHARE).dp,
  )
}

/** The main window: the shell frame, the routes, the command menu, the shortcut sheet and the toasts, all on [window]'s graph. */
@Composable
fun ApplicationScope.MainWindow(
  window: WindowComponent,
  uiScale: DesktopUiScale,
  reducedMotion: Boolean,
  visible: Boolean,
  focusRequest: Int,
  onCloseRequest: () -> Unit,
) {
  DisposableEffect(window) {
    onDispose {
      window.terminal.close()
      window.specsPane.close()
      window.chat.closeChats()
      window.scope.scope.cancel()
    }
  }
  LaunchedEffect(window) { window.navigator.followSession() }
  val keyboard = window.keyboard
  Window(
    onCloseRequest = onCloseRequest,
    visible = visible,
    title = "Skill Bill",
    icon = skillBillIcon(),
    state = rememberWindowState(size = remember(uiScale) { comfortableWindowSize(uiScale) }, position = WindowPosition(Alignment.Center)),
    onPreviewKeyEvent = { event ->
      val press = event.toKeyPress()
      val listNavigation = specsPaneOwnsNavigationKey(
        press,
        window.panes.specsOpen,
        window.input.specsTreeFocused,
        window.input.subtaskListFocused,
      )
      if (listNavigation)
        false
      else press?.let(keyboard::onKey) ?: false
    },
  ) {
    val awtWindow = this.window
    SideEffect { awtWindow.minimumSize = uiScale.windowDimension(MIN_WINDOW_WIDTH, MIN_WINDOW_HEIGHT) }
    LaunchedEffect(visible, focusRequest) {
      if (visible) {
        withFrameNanos { }
        val desktop = if (java.awt.Desktop.isDesktopSupported()) java.awt.Desktop.getDesktop() else null
        if (desktop?.isSupported(java.awt.Desktop.Action.APP_REQUEST_FOREGROUND) == true) desktop.requestForeground(true)
        awtWindow.toFront()
        awtWindow.requestFocus()
      }
    }
    DesktopUiScaleProvider(uiScale) {
      SkillBillTheme(reducedMotion) {
        val focusManager = LocalFocusManager.current
        SideEffect { keyboard.clearFocus = { focusManager.clearFocus() } }
        CompositionLocalProvider(LocalShellInput provides window.input) {
          BoxWithConstraints(Modifier.fillMaxSize()) {
            val narrow = maxWidth < NARROW_WIDTH
            val drawerMode = maxWidth < DRAWER_WIDTH
            SideEffect { keyboard.drawerMode = drawerMode }
            val panes = window.panes
            val layout = ShellLayout(showDetailsPane = panes.detailsPaneOpen && !panes.specsOpen && !panes.chatOpen && !narrow, drawerMode = drawerMode)
            // Below the drawer breakpoint every view header leads with the sidebar toggle, as `.sb-side-toggle`.
            val sidebarToggle: (() -> Unit)? = remember(drawerMode, panes) { if (drawerMode) ({ panes.drawerOpen = !panes.drawerOpen }) else null }
            CompositionLocalProvider(LocalShellLayout provides layout, LocalSidebarToggle provides sidebarToggle) {
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
}

internal fun specsPaneOwnsNavigationKey(
  press: KeyPress?,
  specsOpen: Boolean,
  specsTreeFocused: Boolean,
  subtaskListFocused: Boolean,
): Boolean {
  val navigationKey = when (press?.key) {
    ShellKey.Up, ShellKey.Down, ShellKey.Enter -> true
    else -> false
  }
  val unmodified = press?.let { !it.ctrl && !it.meta && !it.shift && !it.alt } == true
  return navigationKey && unmodified && (specsOpen && specsTreeFocused || subtaskListFocused)
}

@Composable
private fun ShellContent(window: WindowComponent, drawerMode: Boolean) {
  val navigator = window.navigator
  val panes = window.panes
  val session by window.session.state.collectAsState()
  val board by window.board.view.collectAsState()
  val topRoute = navigator.top.route
  val contributions = window.entries.flatMap { it.panes }
  val chat = contributions.firstOrNull { it.slot == PaneSlot.Chat }?.content
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
        sectionSizes = board.lastLoaded?.sections?.associate { it.kind to it.items.size },
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
      Box(Modifier.fillMaxSize()) {
        AppNavHost(navigator, window.entries, shellProblem(session, board)) { problem ->
          ProblemStatePanel(
            problem,
            onRefresh = { if (problem is ShellProblem.SessionFailed) window.session.reopen() else window.boardRepository.refresh() },
          )
        }
        if (topRoute !is SettingsRoute) chat?.invoke()
      }
    }
  }
}
