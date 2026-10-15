package dev.skillbill.tray.composition

import androidx.compose.runtime.*
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.rememberWindowState
import dev.nucleusframework.application.nucleusApplication
import dev.nucleusframework.application.NucleusBackend
import dev.nucleusframework.composenativetray.tray.api.Tray
import dev.skillbill.tray.application.StoreSessionCoordinator
import dev.skillbill.tray.application.TransitionNotificationCoordinator
import dev.skillbill.tray.application.TrayAvailability
import dev.skillbill.tray.core.domain.model.TrayStatus
import dev.skillbill.tray.core.domain.model.WorkIdentity
import dev.skillbill.tray.core.ui.rememberEntryViewModelStoreOwner
import dev.skillbill.tray.core.ui.rememberViewModel
import dev.skillbill.tray.feature.settings.SettingsRoute
import dev.skillbill.tray.feature.work.TrayAction
import dev.skillbill.tray.feature.work.TrayEffect
import dev.skillbill.tray.feature.work.trayHover
import dev.skillbill.tray.infrastructure.NativeServicesImpl
import dev.skillbill.tray.infrastructure.TrayPopoverPlacement
import dev.skillbill.tray.infrastructure.trayIconAnchor
import dev.skillbill.tray.infrastructure.NativeTrayHost
import dev.skillbill.tray.infrastructure.TrayIconRenderer
import dev.skillbill.tray.infrastructure.resolveDesktopUiScale
import dev.skillbill.tray.ui.DesignWindow
import dev.skillbill.tray.ui.MainWindow
import dev.skillbill.tray.ui.SkillBillTheme
import dev.skillbill.tray.ui.TrayPopover
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

fun main() {
  val uiScale = resolveDesktopUiScale()
  val application = createApplicationComponent()
  nucleusApplication(backend = NucleusBackend.Awt) {
    var mainVisible by remember { mutableStateOf(true) }
    var settingsVisible by remember { mutableStateOf(false) }
    var trayPopoverVisible by remember { mutableStateOf(false) }
    var trayPopoverOpening by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<WorkIdentity?>(null) }
    val trayPopoverWindowState = rememberWindowState(size = DpSize(uiScale.scaledWindowPixels(380).dp, uiScale.scaledWindowPixels(590).dp))
    val uiScope = rememberCoroutineScope()
    val native = remember { NativeServicesImpl() }
    val trayPlacement = remember { TrayPopoverPlacement() }

    // The tray surfaces (icon, status item, popover) share one tray ViewModel. The main window and the notifications observe the
    // board through their own state, so no surface's filter or lifecycle decides what another one shows.
    val applicationOwner = rememberEntryViewModelStoreOwner("application")
    val trayModel = rememberViewModel(applicationOwner, "tray") { application.viewModels.tray() }
    val shellScope = remember { CoroutineScope(SupervisorJob() + application.dispatchers.io) }
    DisposableEffect(shellScope) { onDispose { shellScope.cancel() } }
    // Single-threaded, because the board sections the notifications read are not synchronised.
    val notificationScope = remember { CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate) }
    DisposableEffect(notificationScope) { onDispose { notificationScope.cancel() } }
    val tray = remember { TrayAvailability(shellScope, native) }
    val storeSession = remember { StoreSessionCoordinator(shellScope, application.settings, application.storeSession, native) }
    remember {
      TransitionNotificationCoordinator(notificationScope, application.boards, application.settings, native) { identity ->
        uiScope.launch(Dispatchers.Main) {
          selected = identity
          mainVisible = true
        }
      }
    }
    val quit: () -> Unit = {
      uiScope.launch {
        shellScope.coroutineContext[Job]?.cancelAndJoin()
        notificationScope.coroutineContext[Job]?.cancelAndJoin()
        applicationOwner.viewModelStore.clear()
        application.shutdown.closeAndJoin()
        exitApplication()
      }
    }
    val board by trayModel.state.collectAsState()
    val trayState by tray.state.collectAsState()
    LaunchedEffect(trayModel) {
      trayModel.effects.collect { effect ->
        if (effect is TrayEffect.OpenDetail) {
          selected = effect.identity
          mainVisible = true
        }
      }
    }
    val storeProblem by storeSession.problem.collectAsState()
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

    if (isMac) NativeTrayHost.MacStatusItem(board.trayStatus, board.running, board.popoverGoals, board.popoverLivePhases + board.popoverFinishedPhases,
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
        val phases = board.popoverLivePhases + board.popoverFinishedPhases
        when {
          action in 0..7 -> board.popoverGoals.getOrNull(action)?.let { selected = it.identity; mainVisible = true }
          action in 1000..1007 -> phases.getOrNull(action - 1000)?.let { selected = it.identity; mainVisible = true }
          action == 2000 -> mainVisible = true
          action == 2002 -> trayModel.onAction(TrayAction.Refresh)
          action == 2003 -> settingsVisible = true
          action == 2004 -> quit()
        }
      }, tray::unavailable, { trayPopoverVisible = false })
    else Tray(
      iconContent = {
        val platform = System.getProperty("os.name").lowercase()
        val nativeState = NativeTrayHost.state(board.trayStatus, board.running)
        TrayIconRenderer.Content(windowIcon, nativeState.kind, nativeState.running, NativeTrayHost.showsOverlay(platform))
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
      SkillBillTheme(uiScale) { TrayPopover(trayModel, selected, { trayPopoverVisible = false }, { mainVisible = true; trayPopoverVisible = false }, { settingsVisible = true; trayPopoverVisible = false }, quit) }
    }

    val mainWindowState = rememberWindowState(size = DpSize(uiScale.scaledWindowPixels(1060).dp, uiScale.scaledWindowPixels(698).dp))
    val hideMain = { if (trayState.canHideToTray) mainVisible = false }
    Window(visible = mainVisible, state = mainWindowState, icon = windowIcon, undecorated = true, transparent = true, onCloseRequest = hideMain, title = "Skill Bill") {
      DisposableEffect(window, uiScale) {
        window.minimumSize = java.awt.Dimension(uiScale.scaledWindowPixels(1060), uiScale.scaledWindowPixels(698))
        onDispose { }
      }
      SkillBillTheme(uiScale) {
        DesignWindow(mainWindowState, hideMain) {
          val windowOwner = rememberEntryViewModelStoreOwner("main-window")
          val work = rememberViewModel(windowOwner, "work") { application.viewModels.work() }
          val history = rememberViewModel(windowOwner, "history") { application.viewModels.history() }
          MainWindow(work, history, application.viewModels, selected, { selected = it }, trayProblem ?: storeProblem, { settingsVisible = true })
        }
      }
    }
    if (settingsVisible) Window(onCloseRequest = { settingsVisible = false }, title = "Skill Bill Settings", resizable = false, state = rememberWindowState(size = DpSize(uiScale.scaledWindowPixels(460).dp, uiScale.scaledWindowPixels(680).dp))) {
      val settingsOwner = rememberEntryViewModelStoreOwner("settings")
      val settings = rememberViewModel(settingsOwner, "settings") { application.viewModels.settings() }
      SkillBillTheme(uiScale) { SettingsRoute(settings) { settingsVisible = false } }
    }
  }
}

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
