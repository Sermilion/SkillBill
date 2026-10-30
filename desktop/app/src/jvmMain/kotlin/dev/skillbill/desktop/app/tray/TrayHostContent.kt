package dev.skillbill.desktop.app.tray

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.ApplicationScope
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.rememberWindowState
import dev.nucleusframework.composenativetray.tray.api.Tray
import dev.skillbill.desktop.app.host.HostIntent
import dev.skillbill.desktop.app.host.HostIntents
import dev.skillbill.desktop.app.tray.macos.MacStatusItemBridge
import dev.skillbill.desktop.core.designsystem.SkillBillTheme
import dev.skillbill.desktop.core.domain.model.TrayStatus
import dev.skillbill.desktop.core.domain.model.ReadProblem
import dev.skillbill.desktop.feature.tray.TrayBoard
import dev.skillbill.desktop.feature.tray.TrayPopoverContent
import dev.skillbill.desktop.feature.tray.TrayViewModel
import dev.skillbill.desktop.feature.tray.trayHover
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun ApplicationScope.TrayHost(model: TrayViewModel, availability: TrayAvailability, intents: HostIntents, mainVisible: Boolean, onQuit: () -> Unit) {
  val state by model.state.collectAsState()
  val available by availability.state.collectAsState()
  var visible by remember { mutableStateOf(false) }
  var opening by remember { mutableStateOf(false) }
  val scope = rememberCoroutineScope()
  val windowState = rememberWindowState(size = DpSize(380.dp, 590.dp))
  val placement = remember { TrayPopoverPlacement() }
  val density = LocalDensity.current.density.toDouble()
  val linux = System.getProperty("os.name").contains("linux", true)
  LaunchedEffect(available.probeComplete, available.available) {
    if (available.probeComplete && !available.available) {
      visible = false
      intents.send(HostIntent.ShowMain)
    }
  }
  LaunchedEffect(model) {
    model.effects.collect { effect ->
      when (effect) {
        is dev.skillbill.desktop.feature.tray.TrayEffect.OpenDetail -> intents.send(HostIntent.OpenDetail(effect.item))
        dev.skillbill.desktop.feature.tray.TrayEffect.Refresh -> Unit
      }
      visible = false
    }
  }
  val loaded = state.board as? TrayBoard.Loaded
  val status = loaded?.counts?.trayStatus ?: TrayStatus.Idle
  val running = loaded?.counts?.running ?: 0
  val goals = loaded?.goals.orEmpty()
  val phases = loaded?.let { it.livePhases + it.finishedPhases + it.liveOperations + it.finishedOperations }.orEmpty()
  val mac = System.getProperty("os.name").contains("mac", true)
  if (!available.probeComplete || available.available) if (mac) NativeTrayHost.MacStatusItem(status, running, goals, phases,
    onPrimary = { scope.launch(Dispatchers.Main) {
      availability.available()
      if (!mainVisible) {
        intents.send(HostIntent.ShowMain)
      } else {
        MacStatusItemBridge.statusItemAnchor?.let {
          val placement = it.placement(density)
          windowState.position = placement.position
          windowState.size = placement.size
        }
        visible = true
      }
    } },
    onMenuAction = { action -> scope.launch(Dispatchers.Main) {
      availability.available()
      when {
        action in 0..7 -> goals.getOrNull(action)?.let { intents.send(HostIntent.OpenDetail(it)) }
        action in 1000..1007 -> phases.getOrNull(action - 1000)?.let { intents.send(HostIntent.OpenDetail(it)) }
        action == 2000 -> intents.send(HostIntent.ShowMain)
        action == 2002 -> intents.send(HostIntent.Refresh)
        action == 2003 -> intents.send(HostIntent.OpenSettings)
        action == 2004 -> intents.send(HostIntent.Quit)
      }
    } },
    onAvailable = availability::available,
    onFailure = availability::unavailable,
    onDismiss = { scope.launch(Dispatchers.Main) { visible = false } },
  ) else Tray(
    iconContent = {
      SkillBillTheme(reducedMotion = false) {
        TrayIconRenderer.Content(status, running, NativeTrayHost.showsOverlay(System.getProperty("os.name")))
      }
    },
    tooltip = if (linux) state.trayHover() else when {
      state.problem != null -> "Skill Bill could not refresh the board · ${state.problem?.summary()}"
      state.stale -> "Skill Bill is showing stale work data"
      status == TrayStatus.Failed -> "Skill Bill has a failed goal"
      status == TrayStatus.NeedsYou -> "Skill Bill needs you"
      running > 0 -> "Skill Bill, $running running"
      else -> "Skill Bill is idle"
    },
    primaryAction = {
      availability.available()
      if (!mainVisible) intents.send(HostIntent.ShowMain)
      else if (visible) visible = false
      else if (!opening) {
        opening = true
        scope.launch {
          try {
            val anchor = trayIconAnchor()
            windowState.position = withContext(Dispatchers.IO) { placement.position(windowState.size, anchor, 16) }
            visible = true
          } finally { opening = false }
        }
      }
    },
  )
  if (visible) Window(
    onCloseRequest = { visible = false },
    title = "Skill Bill tray",
    transparent = true,
    alwaysOnTop = true,
    undecorated = true,
    resizable = false,
    state = windowState,
  ) {
    SkillBillTheme(reducedMotion = false) {
      TrayPopoverContent(
        state,
        onAction = model::onAction,
        onOpen = { intents.send(HostIntent.ShowMain); visible = false },
        onSettings = { intents.send(HostIntent.OpenSettings); visible = false },
        onQuit = onQuit,
      )
    }
  }
}

private fun ReadProblem.summary(): String = when (this) {
  is ReadProblem.Runtime -> message
  is ReadProblem.Storage -> failure.name
}.replace(Regex("\\s+"), " ").take(120)

