package dev.skillbill.tray.composition

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.rememberWindowState
import dev.nucleusframework.application.nucleusApplication
import dev.nucleusframework.composenativetray.trayapp.TrayApp
import dev.skillbill.tray.application.DashboardController
import dev.skillbill.tray.domain.*
import dev.skillbill.tray.infrastructure.PreferencesSettingsStore
import dev.skillbill.tray.infrastructure.NativeServicesImpl
import dev.skillbill.tray.infrastructure.NativeTrayHost
import dev.skillbill.tray.infrastructure.TrayIconRenderer
import dev.skillbill.tray.infrastructure.cli.CliBoardSource
import dev.skillbill.tray.ui.HistoryWindow
import dev.skillbill.tray.ui.MainWindow
import dev.skillbill.tray.ui.SettingsWindow
import dev.skillbill.tray.ui.TrayPopover
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

fun main() = nucleusApplication {
  var mainVisible by remember { mutableStateOf(true) }
  var historyVisible by remember { mutableStateOf(false) }
  var settingsVisible by remember { mutableStateOf(false) }
  var trayPopoverVisible by remember { mutableStateOf(false) }
  val trayPopoverWindowState = rememberWindowState(size = DpSize(390.dp, 590.dp))
  val uiScope = rememberCoroutineScope()
  val native = remember { NativeServicesImpl() }
  val model = remember {
    DashboardController(CliBoardSource(), PreferencesSettingsStore(), native) { uiScope.launch(Dispatchers.Main) { mainVisible = true } }
  }
  val state by model.state.collectAsState()
  val density = LocalDensity.current.density.toDouble()
  val isMac = System.getProperty("os.name").contains("mac", true)
  val isLinux = System.getProperty("os.name").contains("linux", true)
  LaunchedEffect(isLinux, state.trayProbeComplete) {
    if (isLinux && !state.trayProbeComplete) {
      delay(4000)
      if (!model.state.value.trayProbeComplete) model.trayUnavailable()
    }
  }
  val projected = state.board?.let { dev.skillbill.tray.application.project(it, java.time.Instant.now()) }
  val trayState = dev.skillbill.tray.presentation.presentTrayState(state)
  val trayIcon = when (trayState.kind) {
    dev.skillbill.tray.presentation.TrayStateKind.FAILED -> Icons.Default.Error
    dev.skillbill.tray.presentation.TrayStateKind.NEEDS_YOU -> Icons.Default.NotificationsActive
    dev.skillbill.tray.presentation.TrayStateKind.RUNNING -> Icons.Default.Bolt
    dev.skillbill.tray.presentation.TrayStateKind.IDLE -> Icons.Default.CheckCircle
  }
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

  if (isMac) NativeTrayHost.MacStatusItem(trayState.kind, trayState.running, projected?.goals.orEmpty(), projected?.phases.orEmpty(),
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
        action in 0..7 -> projected?.goals?.getOrNull(action)?.let { model.select(it.identity); mainVisible = true }
        action in 1000..1007 -> projected?.phases?.getOrNull(action - 1000)?.let { model.select(it.identity); mainVisible = true }
        action == 2000 -> mainVisible = true
        action == 2001 -> historyVisible = true
        action == 2002 -> model.refresh()
        action == 2003 -> settingsVisible = true
        action == 2004 -> { model.close(); exitApplication() }
      }
    }, model::trayUnavailable, { trayPopoverVisible = false })
  else TrayApp(
    iconContent = {
      val platform = System.getProperty("os.name").lowercase()
      val nativeState = NativeTrayHost.state(trayState.kind, trayState.running)
      TrayIconRenderer.Content(trayIcon, nativeState.kind, nativeState.running, NativeTrayHost.showsOverlay(platform))
    },
    tooltip = tooltip,
    windowSize = DpSize(420.dp, 590.dp),
    windowsTitle = "Skill Bill",
    resizable = false,
    menu = {
      SubMenu(label = "Goals") {
        projected?.goals?.take(8)?.forEach { item ->
          Item(label = "${item.issueKey ?: item.currentStepLabel}: ${item.summary.take(50)}") {
            model.trayAvailable()
            model.select(item.identity)
            mainVisible = true
          }
        }
      }
      SubMenu(label = "Phases") {
        projected?.phases?.take(8)?.forEach { item ->
          Item(label = "${item.phaseId ?: item.currentStepLabel}: ${item.summary.take(50)}") {
            model.trayAvailable()
            model.select(item.identity)
            mainVisible = true
          }
        }
      }
          Item(label = "Open Skill Bill") { model.trayAvailable(); mainVisible = true }
          Item(label = "History") { model.trayAvailable(); historyVisible = true }
          Item(label = "Refresh now") { model.trayAvailable(); model.refresh() }
          Item(label = "Settings") { model.trayAvailable(); settingsVisible = true }
          Item(label = "Quit") { model.trayAvailable(); model.close(); exitApplication() }
    },
  ) {
    MaterialTheme { TrayPopover(model, { mainVisible = true }, { historyVisible = true }, { settingsVisible = true }) { model.close(); exitApplication() } }
  }

  if (isMac && trayPopoverVisible) Window(
    onCloseRequest = { trayPopoverVisible = false },
    title = "Skill Bill",
    undecorated = true,
    resizable = false,
    state = trayPopoverWindowState,
  ) {
    MaterialTheme { TrayPopover(model, { mainVisible = true; trayPopoverVisible = false }, { historyVisible = true; trayPopoverVisible = false }, { settingsVisible = true; trayPopoverVisible = false }) { model.close(); exitApplication() } }
  }

  if (mainVisible) Window(onCloseRequest = {
    if (state.trayProbeComplete && state.trayProblem == null) mainVisible = false
  }, title = "Skill Bill") {
    MaterialTheme { MainWindow(model, { historyVisible = true }, { settingsVisible = true }, { if (state.trayProbeComplete && state.trayProblem == null) mainVisible = false }) { model.close(); exitApplication() } }
  }
  if (historyVisible) Window(onCloseRequest = { historyVisible = false }, title = "Skill Bill History", resizable = true) {
    MaterialTheme { HistoryWindow(model.state.collectAsState().value, model) { historyVisible = false } }
  }
  if (settingsVisible) Window(onCloseRequest = { settingsVisible = false }, title = "Skill Bill Settings", resizable = false) {
    val current by model.state.collectAsState()
    MaterialTheme { SettingsWindow(current.settings, model::updateSettings, current.settingsProblem) { settingsVisible = false } }
  }
}

private data class MacPopoverPlacement(val position: WindowPosition, val size: DpSize)

private fun dev.skillbill.tray.infrastructure.macos.MacStatusItemBridge.StatusItemAnchor.placePopover(density: Double): MacPopoverPlacement {
  val scale = backingScale / density
  val screenLeft = screenX * scale
  val screenTop = screenY * scale
  val screenRight = (screenX + screenWidth) * scale
  val screenBottom = (screenY + screenHeight) * scale
  val width = minOf(390.0, (screenRight - screenLeft).coerceAtLeast(0.0))
  val height = minOf(590.0, (screenBottom - screenTop).coerceAtLeast(0.0))
  val preferredX = (iconX + iconWidth / 2) * scale - width / 2
  val preferredY = (iconY + iconHeight) * scale
  val x = preferredX.coerceIn(screenLeft, (screenRight - width).coerceAtLeast(screenLeft))
  val y = preferredY.coerceIn(screenTop, (screenBottom - height).coerceAtLeast(screenTop))
  return MacPopoverPlacement(WindowPosition(x = x.dp, y = y.dp), DpSize(width.dp, height.dp))
}
