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
import dev.skillbill.tray.application.DashboardController
import dev.skillbill.tray.core.domain.model.*
import dev.skillbill.tray.infrastructure.NativeServicesImpl
import dev.skillbill.tray.infrastructure.TrayPopoverPlacement
import dev.skillbill.tray.infrastructure.trayIconAnchor
import dev.skillbill.tray.infrastructure.NativeTrayHost
import dev.skillbill.tray.infrastructure.TrayIconRenderer
import dev.skillbill.tray.infrastructure.resolveDesktopUiScale
import dev.skillbill.tray.ui.DesignWindow
import dev.skillbill.tray.ui.MainWindow
import dev.skillbill.tray.ui.SkillBillTheme
import dev.skillbill.tray.ui.SettingsWindow
import dev.skillbill.tray.ui.TrayPopover
import kotlinx.coroutines.Dispatchers
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
    val trayPopoverWindowState = rememberWindowState(size = DpSize(uiScale.scaledWindowPixels(380).dp, uiScale.scaledWindowPixels(590).dp))
    val uiScope = rememberCoroutineScope()
    val native = remember { NativeServicesImpl() }
    val trayPlacement = remember { TrayPopoverPlacement() }
    val model = remember {
      DashboardController(application.boards, application.details, application.controls, application.policy, application.storeSession,
        application.settings, native, application.dispatchers, application.clock) { uiScope.launch(Dispatchers.Main) { mainVisible = true } }
    }
    val quit: () -> Unit = {
      uiScope.launch {
        model.closeAndJoin()
        application.shutdown.closeAndJoin()
        exitApplication()
      }
    }
    val state by model.state.collectAsState()
    val windowIcon = androidx.compose.ui.res.painterResource("icons/skill-bill.svg")
    val density = LocalDensity.current.density.toDouble()
    val isMac = System.getProperty("os.name").contains("mac", true)
    val isLinux = System.getProperty("os.name").contains("linux", true)
    LaunchedEffect(isLinux, state.trayProbeComplete) {
      if (isLinux && !state.trayProbeComplete) {
        delay(4000)
        if (!model.state.value.trayProbeComplete) model.trayUnavailable()
      }
    }
    val projected = dev.skillbill.tray.presentation.presentPopover(state)
    val trayState = dev.skillbill.tray.presentation.presentTrayState(state)
    val trayProblem = state.trayProblem
    val problem = state.problem
    val tooltip = when {
      trayProblem != null -> trayProblem.summary
      problem != null -> "Skill Bill could not refresh the board · ${problem.summary}"
      state.stale -> "Skill Bill is showing stale work data"
      trayState.kind == dev.skillbill.tray.presentation.TrayStateKind.FAILED -> "Skill Bill has a failed goal"
      trayState.kind == dev.skillbill.tray.presentation.TrayStateKind.NEEDS_YOU -> "Skill Bill needs you"
      trayState.running > 0 -> "Skill Bill, ${trayState.running} running"
      else -> "Skill Bill is idle"
    }

    if (isMac) NativeTrayHost.MacStatusItem(trayState.kind, trayState.running, projected.goals, projected.phases,
      onPrimary = {
        model.trayAvailable()
        dev.skillbill.tray.infrastructure.macos.MacStatusItemBridge.statusItemAnchor?.let { anchor ->
          val placement = anchor.placePopover(density)
          trayPopoverWindowState.position = placement.position
          trayPopoverWindowState.size = placement.size
        }
        trayPopoverVisible = true
      },
      onMenuAction = { action ->
        model.trayAvailable()
        when {
          action in 0..7 -> projected.goals.getOrNull(action)?.let { model.select(it.identity); mainVisible = true }
          action in 1000..1007 -> projected.phases.getOrNull(action - 1000)?.let { model.select(it.identity); mainVisible = true }
          action == 2000 -> mainVisible = true
          action == 2002 -> model.refresh()
          action == 2003 -> settingsVisible = true
          action == 2004 -> quit()
        }
      }, model::trayUnavailable, { trayPopoverVisible = false })
    else Tray(
      iconContent = {
        val platform = System.getProperty("os.name").lowercase()
        val nativeState = NativeTrayHost.state(trayState.kind, trayState.running)
        TrayIconRenderer.Content(windowIcon, nativeState.kind, nativeState.running, NativeTrayHost.showsOverlay(platform))
      },
      tooltip = if (isLinux) dev.skillbill.tray.presentation.presentTrayHover(state) else tooltip,
      primaryAction = {
        if (!trayPopoverOpening) {
          val anchor = trayIconAnchor()
          if (!trayPopoverVisible) {
            trayPopoverOpening = true
            uiScope.launch {
              try {
                model.trayAvailable()
                trayPopoverWindowState.position = withContext(Dispatchers.IO) {
                  trayPlacement.position(trayPopoverWindowState.size, anchor, uiScale.scaledWindowPixels(16))
                }
                trayPopoverVisible = true
              } finally {
                trayPopoverOpening = false
              }
            }
          } else {
            model.trayAvailable()
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
      SkillBillTheme(uiScale) { TrayPopover(model, { trayPopoverVisible = false }, { mainVisible = true; trayPopoverVisible = false }, { settingsVisible = true; trayPopoverVisible = false }, quit) }
    }

    val mainWindowState = rememberWindowState(size = DpSize(uiScale.scaledWindowPixels(1060).dp, uiScale.scaledWindowPixels(698).dp))
    val hideMain = { if (state.trayProbeComplete && state.trayProblem == null) mainVisible = false }
    Window(visible = mainVisible, state = mainWindowState, icon = windowIcon, undecorated = true, transparent = true, onCloseRequest = hideMain, title = "Skill Bill") {
      DisposableEffect(window, uiScale) {
        window.minimumSize = java.awt.Dimension(uiScale.scaledWindowPixels(1060), uiScale.scaledWindowPixels(698))
        onDispose { }
      }
      SkillBillTheme(uiScale) {
        DesignWindow(mainWindowState, hideMain) {
          MainWindow(model, { settingsVisible = true })
        }
      }
    }
    if (settingsVisible) Window(onCloseRequest = { settingsVisible = false }, title = "Skill Bill Settings", resizable = false, state = rememberWindowState(size = DpSize(uiScale.scaledWindowPixels(460).dp, uiScale.scaledWindowPixels(680).dp))) {
      val current by model.state.collectAsState()
      SkillBillTheme(uiScale) { SettingsWindow(current.settings, model::updateSettings, current.settingsProblem, current.databasePath) { settingsVisible = false } }
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
