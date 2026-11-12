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
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import dev.skillbill.desktop.app.shell.qadevice.QaDeviceSwitcher
import dev.skillbill.desktop.app.keyboard.ShortcutSheet
import dev.skillbill.desktop.app.keyboard.KeyPress
import dev.skillbill.desktop.app.keyboard.ShellKey
import dev.skillbill.desktop.app.keyboard.toKeyPress
import dev.skillbill.desktop.app.usage.UsageSidebar
import dev.skillbill.desktop.core.designsystem.icon.skillBillIcon
import dev.skillbill.desktop.core.designsystem.SkillBillSize
import dev.skillbill.desktop.core.designsystem.SkillBillTheme
import dev.skillbill.desktop.core.designsystem.component.ToastStack
import dev.skillbill.desktop.core.designsystem.navigation.LocalSidebarToggle
import dev.skillbill.desktop.core.domain.model.ChangeScope
import dev.skillbill.desktop.core.ui.changes.ChangesSource
import dev.skillbill.desktop.core.ui.changes.ChangesWindows
import dev.skillbill.desktop.core.ui.input.LocalShellInput
import dev.skillbill.desktop.core.ui.navigation.AppRoute
import dev.skillbill.desktop.core.ui.navigation.PaneSlot
import dev.skillbill.desktop.core.ui.navigation.SettingsRoute
import dev.skillbill.desktop.core.ui.shell.LocalShellLayout
import dev.skillbill.desktop.core.ui.shell.ShellLayout
import dev.skillbill.desktop.core.ui.shell.ShellPanes
import dev.skillbill.desktop.feature.changes.ChangesWindowKeys
import dev.skillbill.desktop.feature.changes.ChangesWindowViewModel
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.awt.GraphicsEnvironment
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent

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
  changes: ChangesWindows,
  createChangesViewModel: (ChangesSource, ChangeScope, String?) -> ChangesWindowViewModel,
  onCloseRequest: () -> Unit,
) {
  DisposableEffect(window) {
    onDispose {
      window.usage.release()
      window.terminal.close()
      window.specsPane.close()
      window.chat.closeChats()
      window.chatPaneWidth.close()
      window.scope.scope.cancel()
    }
  }
  LaunchedEffect(window) { window.navigator.followSession() }
  val keyboard = window.keyboard
  val changesEntry by changes.current.collectAsState()
  val changesKeys = remember { ChangesWindowKeys() }
  val closeScope = rememberCoroutineScope()
  val windowState = rememberWindowState(size = remember(uiScale) { comfortableWindowSize(uiScale) }, position = WindowPosition(Alignment.Center))
  Window(
    onCloseRequest = { closeScope.launch { if (window.chat.closeChatsSafely()) onCloseRequest() } },
    visible = visible,
    title = "Sona",
    icon = skillBillIcon(),
    state = windowState,
    onPreviewKeyEvent = onPreviewKeyEvent@{ event ->
      // The Changes panel is modal: its keys win, and no shell shortcut fires under it.
      if (changesEntry != null) return@onPreviewKeyEvent changesKeys.handle(event)
      val press = event.toKeyPress()
      val listNavigation = specsPaneOwnsNavigationKey(
        press,
        window.panes.specsOpen,
        window.input.specsTreeFocused,
        window.input.subtaskListFocused,
        window.input.specsBarFocused,
        window.input.layerOpen || window.menu.open,
        window.input.editOpen,
        window.specsPane.state.value.selectedPath != null,
      )
      if (listNavigation && press?.key == ShellKey.Escape) {
        window.specsPane.onAction(dev.skillbill.desktop.feature.specs.SpecsAction.Deselect)
        true
      } else if (listNavigation)
        false
      else press?.let(keyboard::onKey) ?: false
    },
  ) {
    val awtWindow = this.window
    DisposableEffect(awtWindow, windowState, visible) {
      fun publish(focused: Boolean = awtWindow.isFocused) = window.usage.setWindowFacts(visible, windowState.isMinimized, focused)
      val windowListener = object : WindowAdapter() {
        override fun windowIconified(e: WindowEvent) = publish()
        override fun windowDeiconified(e: WindowEvent) = publish()
        override fun windowOpened(e: WindowEvent) = publish()
        override fun windowClosing(e: WindowEvent) = publish()
        override fun windowGainedFocus(e: WindowEvent) = publish(true)
        override fun windowLostFocus(e: WindowEvent) = publish(false)
      }
      publish()
      awtWindow.addWindowListener(windowListener)
      awtWindow.addWindowFocusListener(windowListener)
      onDispose {
        awtWindow.removeWindowListener(windowListener)
        awtWindow.removeWindowFocusListener(windowListener)
      }
    }
    LaunchedEffect(visible, windowState.isMinimized) {
      window.usage.setWindowFacts(visible, windowState.isMinimized, awtWindow.isFocused)
    }
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
            val drawerMode = maxWidth < DRAWER_WIDTH
            SideEffect { keyboard.drawerMode = drawerMode }
            val panes = window.panes
            val layout = ShellLayout(drawerMode = drawerMode)
            // Below the drawer breakpoint every view header leads with the sidebar toggle, as `.sb-side-toggle`.
            val sidebarToggle: (() -> Unit)? = remember(drawerMode, panes) { if (drawerMode) ({ panes.drawerOpen = !panes.drawerOpen }) else null }
            CompositionLocalProvider(LocalShellLayout provides layout, LocalSidebarToggle provides sidebarToggle) {
              ShellContent(window, drawerMode)
              CommandMenu(window)
              if (panes.shortcutsOpen) ShortcutSheet { panes.shortcutsOpen = false }
              changesEntry?.let { entry ->
                key(entry.source.id) { ChangesOverlay(entry, changesKeys, createChangesViewModel, changes::close) }
              }
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
  specsBarFocused: Boolean = false,
  layerOpen: Boolean = false,
  editOpen: Boolean = false,
  hasSelection: Boolean = false,
): Boolean {
  val noModifiers = press?.let { !it.ctrl && !it.meta && !it.alt } == true
  val unmodified = press?.let { noModifiers && !it.shift } == true
  val specsFocused = specsOpen && (specsTreeFocused || specsBarFocused)
  if (specsFocused && noModifiers && !layerOpen && !editOpen) {
    if (specsTreeFocused && press?.key == ShellKey.Escape) return hasSelection
    if (specsTreeFocused && unmodified && press?.key in setOf(ShellKey.Up, ShellKey.Down, ShellKey.Enter)) return true
    val char = (press?.key as? ShellKey.Char)?.char?.lowercaseChar()
    if (unmodified && char != null && char in setOf('j', 'k', 'r', 'p', 'a', 'f')) return true
    if (noModifiers && char == '/') return true
  }
  return subtaskListFocused && unmodified && press?.key in setOf(ShellKey.Up, ShellKey.Down, ShellKey.Enter)
}

@Composable
private fun ShellContent(window: WindowComponent, drawerMode: Boolean) {
  val navigator = window.navigator
  val panes = window.panes
  val session by window.session.state.collectAsState()
  val board by window.board.view.collectAsState()
  val favorites by window.favorites.entries.collectAsState()
  val specFavorites by window.favorites.specEntries.collectAsState()
  val newTaskDraft by window.chat.newTaskDraftOpen.collectAsState()
  val newTaskOpenChats by window.chat.newTaskOpenChats.collectAsState()
  val topRoute = navigator.top.route
  val contributions = window.entries.flatMap { it.panes }
  val chatShown = panes.chatOpen && topRoute !is SettingsRoute
  val chat = contributions.firstOrNull { it.slot == PaneSlot.Chat }?.content
  val specs = contributions.firstOrNull { it.slot == PaneSlot.Right }?.takeIf { panes.specsOpen && topRoute !is SettingsRoute }?.content
  val right = if (chatShown) chat else specs
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
        favorites = favorites,
        specFavorites = specFavorites,
        specsOpen = panes.specsOpen,
        terminalOpen = panes.terminalOpen,
        chatOpen = panes.chatOpen,
        newTaskDraft = newTaskDraft,
        newTaskOpenChats = newTaskOpenChats,
        contributions = window.entries.flatMap { it.sidebarItems },
        usage = { UsageSidebar(window.usage) },
        onNavigate = navigate,
        onOpenCommandMenu = window.menu::toggle,
        onToggleSpecs = {
          if (!panes.specsOpen) panes.drawerOpen = false
          panes.specsOpen = !panes.specsOpen
        },
        onToggleTerminal = window.terminal::toggle,
        onToggleChat = window.chat::toggleChatPane,
        onShowShortcuts = { panes.shortcutsOpen = true },
        onRemoveFavorite = window.favorites::remove,
        onRemoveSpecFavorite = window.favorites::removeSpec,
        onOpenSpecFavorite = { projectId, path ->
          openSpecFavorite(panes, window.specsPane::open, window.specsPane::revealFavorite, projectId, path)
        },
        qaDeviceSwitcher = { QaDeviceSwitcher(window.qaDevice) },
      )
    }
  }
  SideEffect {
    window.usage.setSidebarFacts(
      settings = topRoute is SettingsRoute,
      inlineVisible = sidebar != null && !drawerMode,
      drawerIsOpen = sidebar != null && drawerMode && panes.drawerOpen,
    )
  }
  ShellFrame(sidebar, drawerMode, panes.drawerOpen, onDismissDrawer = { panes.drawerOpen = false }) {
    WorkArea(drawerMode, right, bottom, rightWidth = if (chatShown) null else SkillBillSize.specsPane) {
      Box(Modifier.fillMaxSize()) {
        AppNavHost(navigator, window.entries, window.workImportRequests, shellProblem(session, board)) { problem ->
          ProblemStatePanel(
            problem,
            onRefresh = { if (problem is ShellProblem.SessionFailed) window.session.reopen() else window.boardRepository.refresh() },
          )
        }
      }
    }
  }
}

internal fun openSpecFavorite(
  panes: ShellPanes,
  openSpecs: () -> Unit,
  revealFavorite: (String, String) -> Unit,
  projectId: String,
  path: String,
) {
  panes.drawerOpen = false
  openSpecs()
  panes.specsOpen = true
  revealFavorite(projectId, path)
}
