package dev.skillbill.tray.composition

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.rememberWindowState
import dev.nucleusframework.application.nucleusApplication
import dev.nucleusframework.application.NucleusBackend
import dev.nucleusframework.composenativetray.tray.api.Tray
import dev.skillbill.tray.core.domain.model.DesktopUiScaleConfiguration
import dev.skillbill.tray.core.domain.model.Problem
import dev.skillbill.tray.core.domain.model.TrayStatus
import dev.skillbill.tray.core.domain.model.scaledWindowPixels
import dev.skillbill.tray.core.ui.rememberEntryViewModelStoreOwner
import dev.skillbill.tray.core.ui.rememberViewModel
import dev.skillbill.tray.core.ui.LocalRepositoryNavigation
import dev.skillbill.tray.core.ui.RepositoryNavigation
import dev.skillbill.tray.feature.specs.SpecsAction
import dev.skillbill.tray.feature.terminal.TerminalAction
import dev.skillbill.tray.feature.work.TrayAction
import dev.skillbill.tray.feature.work.TrayEffect
import dev.skillbill.tray.feature.work.trayHover
import dev.skillbill.tray.host.HostIntent
import dev.skillbill.tray.infrastructure.NativeServicesImpl
import dev.skillbill.tray.infrastructure.TrayPopoverPlacement
import dev.skillbill.tray.infrastructure.trayIconAnchor
import dev.skillbill.tray.infrastructure.NativeTrayHost
import dev.skillbill.tray.infrastructure.TrayIconRenderer
import dev.skillbill.tray.infrastructure.resolveDesktopUiScale
import dev.skillbill.tray.lifetime.SessionState
import dev.skillbill.tray.lifetime.WindowOwner
import dev.skillbill.tray.terminal.AppTerminalTabHost
import dev.skillbill.tray.terminal.TerminalSurface
import dev.skillbill.tray.ui.DesignWindow
import dev.skillbill.tray.ui.SkillBillNavHost
import dev.skillbill.tray.ui.SkillBillTheme
import dev.skillbill.tray.ui.SpecsToggle
import dev.skillbill.tray.ui.TerminalToggle
import dev.skillbill.tray.ui.TrayPopover
import dev.skillbill.tray.ui.rememberSpecsViewModel
import dev.skillbill.tray.ui.rememberTerminalViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

fun main() {
  System.setProperty("compose.interop.blending", "true")
  val uiScale = resolveDesktopUiScale()
  val application = createApplicationComponent()
  nucleusApplication(backend = NucleusBackend.Awt) {
    var trayPopoverVisible by remember { mutableStateOf(false) }
    var trayPopoverOpening by remember { mutableStateOf(false) }
    val trayPopoverWindowState = rememberWindowState(size = DpSize(uiScale.scaledWindowPixels(380).dp, uiScale.scaledWindowPixels(590).dp))
    val uiScope = rememberCoroutineScope()
    val native = remember { NativeServicesImpl() }
    val trayPlacement = remember { TrayPopoverPlacement() }

    // The tray surfaces (icon, status item, popover) share one tray ViewModel. The main windows and the notifications observe the
    // board through their own state, so no surface's filter or lifecycle decides what another one shows.
    val applicationOwner = rememberEntryViewModelStoreOwner("application")
    val trayModel = rememberViewModel(applicationOwner, "tray") { application.viewModels.tray() }
    val owner = remember {
      AppOwner(
        application = application,
        native = native,
        outliving = uiScope,
        releaseViewModels = { applicationOwner.viewModelStore.clear() },
        exit = { exitApplication() },
        refresh = { trayModel.onAction(TrayAction.Refresh) },
      )
    }
    DisposableEffect(owner) { onDispose { owner.cancelScopes() } }
    val tray = owner.tray

    val board by trayModel.state.collectAsState()
    val trayState by tray.state.collectAsState()
    LaunchedEffect(trayModel) {
      trayModel.effects.collect { effect ->
        if (effect is TrayEffect.OpenDetail) owner.router.dispatch(HostIntent.OpenWork(effect.identity))
      }
    }
    val storeProblem by owner.storeSession.problem.collectAsState()
    val windowIcon = androidx.compose.ui.res.painterResource("icons/skill-bill.svg")
    val density = LocalDensity.current.density.toDouble()
    val isMac = System.getProperty("os.name").contains("mac", true)
    val isLinux = System.getProperty("os.name").contains("linux", true)
    LaunchedEffect(isLinux, trayState.probeComplete) {
      if (isLinux && !trayState.probeComplete) {
        delay(4000)
        if (!tray.state.value.probeComplete) tray.unavailable()
      }
    }
    val trayProblem = trayState.problem
    val problem = board.liveProblem
    val tooltip = when {
      trayProblem != null -> trayProblem.summary
      problem != null -> "Skill Bill could not refresh the board · ${problem.summary}"
      board.stale -> "Skill Bill is showing stale work data"
      board.trayStatus == TrayStatus.FAILED -> "Skill Bill has a failed goal"
      board.trayStatus == TrayStatus.NEEDS_YOU -> "Skill Bill needs you"
      board.running > 0 -> "Skill Bill, ${board.running} running"
      else -> "Skill Bill is idle"
    }

    if (isMac) NativeTrayHost.MacStatusItem(board.trayStatus, board.running, board.popoverGoals, board.popoverLivePhases + board.popoverFinishedPhases + board.popoverLiveOperations + board.popoverFinishedOperations,
      onPrimary = {
        tray.available()
        dev.skillbill.tray.infrastructure.macos.MacStatusItemBridge.statusItemAnchor?.let { anchor ->
          val placement = anchor.placePopover(density)
          trayPopoverWindowState.position = placement.position
          trayPopoverWindowState.size = placement.size
        }
        trayPopoverVisible = true
      },
      onMenuAction = { action ->
        tray.available()
        val phases = board.popoverLivePhases + board.popoverFinishedPhases + board.popoverLiveOperations + board.popoverFinishedOperations
        // The menu callback may arrive on a native thread; the router moves every intent onto the UI dispatcher.
        when {
          action in 0..7 -> board.popoverGoals.getOrNull(action)?.let { owner.router.dispatch(HostIntent.OpenWork(it.identity)) }
          action in 1000..1007 -> phases.getOrNull(action - 1000)?.let { owner.router.dispatch(HostIntent.OpenWork(it.identity)) }
          action == 2000 -> owner.router.dispatch(HostIntent.ShowMain)
          action == 2002 -> owner.router.dispatch(HostIntent.Refresh)
          action == 2003 -> owner.router.dispatch(HostIntent.OpenSettings)
          action == 2004 -> owner.router.dispatch(HostIntent.Quit)
        }
      }, tray::unavailable, { trayPopoverVisible = false })
    else Tray(
      iconContent = {
        val platform = System.getProperty("os.name").lowercase()
        val nativeState = NativeTrayHost.state(board.trayStatus, board.running)
        TrayIconRenderer.Content(nativeState.kind, nativeState.running, NativeTrayHost.showsOverlay(platform))
      },
      tooltip = if (isLinux) board.trayHover() else tooltip,
      primaryAction = {
        if (!trayPopoverOpening) {
          val anchor = trayIconAnchor()
          if (!trayPopoverVisible) {
            trayPopoverOpening = true
            uiScope.launch {
              try {
                tray.available()
                trayPopoverWindowState.position = withContext(Dispatchers.IO) {
                  trayPlacement.position(trayPopoverWindowState.size, anchor, uiScale.scaledWindowPixels(16))
                }
                trayPopoverVisible = true
              } finally {
                trayPopoverOpening = false
              }
            }
          } else {
            tray.available()
            trayPopoverVisible = false
          }
        }
      },
    )

    if (trayPopoverVisible) Window(
      onCloseRequest = { trayPopoverVisible = false },
      title = "Skill Bill tray",
      transparent = true,
      alwaysOnTop = true,
      undecorated = true,
      resizable = false,
      state = trayPopoverWindowState,
    ) {
      SkillBillTheme(uiScale) {
        TrayPopover(
          trayModel,
          owner.windows.target()?.navigator?.selected,
          { trayPopoverVisible = false },
          { owner.router.dispatch(HostIntent.ShowMain); trayPopoverVisible = false },
          { owner.router.dispatch(HostIntent.OpenSettings); trayPopoverVisible = false },
          { owner.router.dispatch(HostIntent.Quit) },
          { owner.router.dispatch(HostIntent.Refresh) },
        )
      }
    }

    val shellProblem = trayProblem ?: storeProblem
    owner.windows.windows.forEach { windowOwner ->
      key(windowOwner.id) {
        if (windowOwner.navigator.section == null) {
          SettingsWindow(owner, windowOwner, uiScale)
        } else {
          MainWindow(owner, windowOwner, uiScale, windowIcon, isMac, trayState.canHideToTray, shellProblem, native)
        }
      }
    }
  }
}

/**
 * One main window. Closing the last one hides it to the tray when the tray works, as before; closing any other one ends it. The
 * window that was activated last is where tray and notification intents land, so activation is reported to the registry.
 */
@Composable
private fun MainWindow(
  owner: AppOwner,
  windowOwner: WindowOwner,
  uiScale: DesktopUiScaleConfiguration,
  windowIcon: Painter,
  isMac: Boolean,
  canHideToTray: Boolean,
  shellProblem: Problem?,
  repositoryNavigation: RepositoryNavigation,
) {
  val mainWindowState = rememberWindowState(size = DpSize(uiScale.scaledWindowPixels(1380).dp, uiScale.scaledWindowPixels(698).dp))
  val session by owner.transitions.state.collectAsState()
  val closeRequest = {
    if (owner.windows.windows.count { it.navigator.section != null } > 1) owner.windows.dispose(windowOwner)
    else if (canHideToTray) windowOwner.hide()
  }
  Window(
    visible = windowOwner.visible,
    state = mainWindowState,
    icon = windowIcon,
    undecorated = true,
    transparent = true,
    onCloseRequest = closeRequest,
    title = "Skill Bill",
    onPreviewKeyEvent = { event ->
      val opensWindow = event.isNewWindowShortcut(isMac)
      if (opensWindow) owner.windows.openMain()
      opensWindow
    },
  ) {
    DisposableEffect(window, uiScale) {
      window.minimumSize = java.awt.Dimension(uiScale.scaledWindowPixels(1060), uiScale.scaledWindowPixels(698))
      onDispose { }
    }
    DisposableEffect(window, windowOwner) {
      val listener = object : java.awt.event.WindowAdapter() {
        override fun windowActivated(event: java.awt.event.WindowEvent?) {
          owner.windows.activate(windowOwner)
        }
      }
      window.addWindowListener(listener)
      onDispose { window.removeWindowListener(listener) }
    }
    FocusOnRequest(windowOwner, window)
    CompositionLocalProvider(LocalRepositoryNavigation provides repositoryNavigation) {
      SkillBillTheme(uiScale) {
        // The pane's ViewModel starts once a database is open, so its first read never lands before the session is.
        val specs = if (session is SessionState.Active) rememberSpecsViewModel(windowOwner) else null
        val specsState = specs?.state?.collectAsState()?.value
        val terminal = if (session is SessionState.Active) rememberTerminalViewModel(windowOwner) else null
        val terminalState = terminal?.state?.collectAsState()?.value
        DesignWindow(
          mainWindowState,
          closeRequest,
          accessory = {
            if (specs != null && specsState != null) {
              SpecsToggle(specsState.paneOpen, specsState.anyInProgress) { specs.onAction(SpecsAction.TogglePane) }
            }
            if (terminal != null && terminalState != null) {
              TerminalToggle(terminalState.paneOpen) { terminal.onAction(TerminalAction.TogglePane) }
            }
          },
        ) {
          SkillBillNavHost(
            window = windowOwner,
            session = session,
            shellProblem = shellProblem,
            onRefreshShell = { owner.router.dispatch(HostIntent.Refresh) },
            onOpenSettings = { owner.router.dispatch(HostIntent.OpenSettings) },
            onCloseWindow = closeRequest,
            specs = specs,
            terminal = terminal,
            terminalTabs = AppTerminalTabHost,
            terminalSurface = { tab -> TerminalSurface(windowOwner, tab.id, tab.directory, Modifier.fillMaxSize()) },
          )
        }
      }
    }
  }
}

/** The settings window: closing it ends it, so reopening starts from the saved preferences. */
@Composable
private fun SettingsWindow(owner: AppOwner, windowOwner: WindowOwner, uiScale: DesktopUiScaleConfiguration) {
  val session by owner.transitions.state.collectAsState()
  val close = { owner.windows.dispose(windowOwner) }
  Window(
    onCloseRequest = close,
    title = "Skill Bill Settings",
    resizable = false,
    state = rememberWindowState(size = DpSize(uiScale.scaledWindowPixels(460).dp, uiScale.scaledWindowPixels(680).dp)),
  ) {
    FocusOnRequest(windowOwner, window)
    SkillBillTheme(uiScale) {
      SkillBillNavHost(windowOwner, session, shellProblem = null, onOpenSettings = {}, onCloseWindow = close)
    }
  }
}

/**
 * Raises and focuses [window] each time [windowOwner] is asked to come forward by a host intent. It waits one frame so a window
 * that was just made visible is shown before it is raised, and on macOS asks for the application to the foreground first, since a
 * tray application is usually not the active one.
 */
@Composable
private fun FocusOnRequest(windowOwner: WindowOwner, window: java.awt.Window) {
  LaunchedEffect(windowOwner.focusRequests) {
    if (windowOwner.focusRequests == 0) return@LaunchedEffect
    withFrameNanos { }
    val desktop = if (java.awt.Desktop.isDesktopSupported()) java.awt.Desktop.getDesktop() else null
    if (desktop?.isSupported(java.awt.Desktop.Action.APP_REQUEST_FOREGROUND) == true) desktop.requestForeground(true)
    window.toFront()
    window.requestFocus()
  }
}

/** Cmd+N on macOS and Ctrl+N elsewhere open another main window. */
private fun KeyEvent.isNewWindowShortcut(isMac: Boolean): Boolean =
  type == KeyEventType.KeyDown && key == Key.N && if (isMac) isMetaPressed else isCtrlPressed

private data class MacPopoverPlacement(val position: WindowPosition, val size: DpSize)

private fun dev.skillbill.tray.infrastructure.macos.MacStatusItemBridge.StatusItemAnchor.placePopover(density: Double): MacPopoverPlacement {
  val scale = backingScale / density
  val screenLeft = screenX * scale
  val screenTop = screenY * scale
  val screenRight = (screenX + screenWidth) * scale
  val screenBottom = (screenY + screenHeight) * scale
  val width = minOf(380.0, (screenRight - screenLeft).coerceAtLeast(0.0))
  val height = minOf(590.0, (screenBottom - screenTop).coerceAtLeast(0.0))
  val preferredX = (iconX + iconWidth / 2) * scale - width / 2
  val preferredY = (iconY + iconHeight) * scale
  val x = preferredX.coerceIn(screenLeft, (screenRight - width).coerceAtLeast(screenLeft))
  val y = preferredY.coerceIn(screenTop, (screenBottom - height).coerceAtLeast(screenTop))
  return MacPopoverPlacement(WindowPosition(x = x.dp, y = y.dp), DpSize(width.dp, height.dp))
}
