package dev.skillbill.desktop.app.tray

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInParent
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.ApplicationScope
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.rememberWindowState
import dev.nucleusframework.composenativetray.tray.api.Tray
import dev.skillbill.desktop.app.display.DesktopUiScale
import dev.skillbill.desktop.app.display.DesktopUiScaleProvider
import dev.skillbill.desktop.app.host.HostIntent
import dev.skillbill.desktop.app.host.HostIntents
import dev.skillbill.desktop.app.tray.macos.MacStatusItemBridge
import dev.skillbill.desktop.core.designsystem.icon.skillBillIcon
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
import java.awt.SystemTray
import java.awt.event.WindowEvent
import java.awt.event.WindowFocusListener

@Composable
fun ApplicationScope.TrayHost(
  model: TrayViewModel,
  availability: TrayAvailability,
  intents: HostIntents,
  uiScale: DesktopUiScale,
  mainVisible: Boolean,
  reducedMotion: Boolean,
  onQuit: () -> Unit,
) {
  val state by model.state.collectAsState()
  val available by availability.state.collectAsState()
  var visible by remember { mutableStateOf(false) }
  var opening by remember { mutableStateOf(false) }
  var popoverAlignment by remember { mutableStateOf<Alignment>(Alignment.TopCenter) }
  val scope = rememberCoroutineScope()
  val mac = System.getProperty("os.name").contains("mac", true)
  // The macOS popover takes its size and position from the status item in native points, so it keeps the platform density.
  val popoverScale = if (mac) DesktopUiScale() else uiScale
  val windowState = rememberWindowState(size = popoverScale.windowSize(DpSize(380.dp, 590.dp)))
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
  ) else {
    SideEffect {
      if (SystemTray.isSupported()) availability.available() else availability.unavailable()
    }
    Tray(
      iconContent = {
        SkillBillTheme(reducedMotion) {
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
              val location = withContext(Dispatchers.IO) { placement.locate(windowState.size, anchor, 16) }
              windowState.position = location.position
              popoverAlignment = location.alignment
              visible = true
            } finally { opening = false }
          }
        }
      },
    )
  }
  if (visible) Window(
    onCloseRequest = { visible = false },
    title = "Skill Bill tray",
    icon = skillBillIcon(),
    transparent = true,
    alwaysOnTop = true,
    undecorated = true,
    resizable = false,
    state = windowState,
    onPreviewKeyEvent = { event ->
      if (event.key == Key.Escape && event.type == KeyEventType.KeyDown) {
        visible = false
        true
      } else false
    },
  ) {
    val popoverWindow = window
    DisposableEffect(popoverWindow) {
      val listener = object : WindowFocusListener {
        override fun windowGainedFocus(event: WindowEvent) = Unit
        override fun windowLostFocus(event: WindowEvent) { visible = false }
      }
      popoverWindow.addWindowFocusListener(listener)
      onDispose { popoverWindow.removeWindowFocusListener(listener) }
    }
    var cardBounds by remember { mutableStateOf(Rect.Zero) }
    DesktopUiScaleProvider(popoverScale) {
      SkillBillTheme(reducedMotion) {
        // The 380 dp transparent window leaves 10 dp around the 360 dp popover for its shadow.
        Box(
          Modifier.fillMaxSize().pointerInput(Unit) {
            awaitPointerEventScope {
              while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                if (event.type == PointerEventType.Press && event.changes.any { !cardBounds.contains(it.position) }) {
                  visible = false
                  event.changes.forEach { it.consume() }
                }
              }
            }
          }.padding(10.dp),
          contentAlignment = popoverAlignment,
        ) {
          Box(Modifier.onGloballyPositioned { cardBounds = it.boundsInParent() }) {
            TrayPopoverContent(
              state,
              onAction = model::onAction,
              onOpen = { intents.send(HostIntent.ShowMain); visible = false },
              onSettings = { intents.send(HostIntent.OpenSettings); visible = false },
              onQuit = onQuit,
              onDismiss = { visible = false },
            )
          }
        }
      }
    }
  }
}

private fun ReadProblem.summary(): String = when (this) {
  is ReadProblem.Runtime -> message
  is ReadProblem.Storage -> failure.name
}.replace(Regex("\\s+"), " ").take(120)

