package dev.skillbill.desktop.app

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModelStore
import dev.nucleusframework.application.NucleusBackend
import dev.nucleusframework.application.nucleusApplication
import dev.skillbill.desktop.app.host.HostIntent
import dev.skillbill.desktop.app.tray.TrayHost
import dev.skillbill.desktop.app.motion.OsReducedMotionReader
import dev.skillbill.desktop.app.motion.ReducedMotionRead
import dev.skillbill.desktop.app.shell.MainWindow
import dev.skillbill.desktop.core.common.DiagnosticsSwitch
import dev.skillbill.desktop.core.common.HostEnvironment
import dev.skillbill.desktop.core.ui.navigation.DetailRoute
import dev.skillbill.desktop.core.ui.navigation.SettingsPageId
import dev.skillbill.desktop.core.ui.navigation.SettingsRoute

/** The app entry the launcher starts inside the class loader that holds the installed runtime's jars. */
fun main() {
  val diagnostics = DiagnosticsSwitch(
    System.getenv("SKILL_BILL_DESKTOP_DIAGNOSTICS") == "1" || System.getProperty("skillbill.desktop.diagnostics") == "true",
  )
  val host = HostEnvironment(System.getenv(), System.getProperty("user.home"), System.getProperty("user.dir"))
  val component = AppComponent::class.create(host, diagnostics)
  component.databaseSessionManager.start()
  component.startAtLoginSync.start()
  val reducedMotion = when (val read = OsReducedMotionReader().read()) {
    is ReducedMotionRead.Known -> read.reduced
    is ReducedMotionRead.Unavailable -> {
      val source = read.source.replace(Regex("\\s+"), " ").take(120)
      val reason = read.reason.replace(Regex("\\s+"), " ").take(120)
      System.getLogger("dev.skillbill.desktop").log(System.Logger.Level.WARNING, "desktop:reduced-motion-unavailable source=$source reason=$reason")
      false
    }
  }
  component.transitionNotifier.start()
  val window = component.windowComponentFactory.createWindowComponent()
  nucleusApplication(backend = NucleusBackend.Awt) {
    var mainVisible by remember { mutableStateOf(true) }
    var focusRequest by remember { mutableStateOf(0) }
    val trayStore = remember { ViewModelStore() }
    val trayModel = remember { component.trayViewModel().also { trayStore.put("tray", it) } }
    LaunchedEffect(component.hostIntents, window) {
      component.hostIntents.intents.collect { intent ->
        when (intent) {
          HostIntent.ShowMain -> { mainVisible = true; focusRequest++ }
          is HostIntent.OpenDetail -> { mainVisible = true; focusRequest++; window.navigator.navigate(DetailRoute(intent.item)) }
          HostIntent.OpenSettings -> { mainVisible = true; focusRequest++; window.navigator.navigate(SettingsRoute(SettingsPageId.App)) }
          HostIntent.Refresh -> component.workBoardRepository.refresh()
          HostIntent.Quit -> exitApplication()
        }
      }
    }
    TrayHost(trayModel, component.trayAvailability, component.hostIntents, mainVisible, onQuit = { exitApplication() })
    MainWindow(window, reducedMotion, mainVisible, focusRequest) {
      if (component.trayAvailability.state.value.canHideToTray) mainVisible = false else exitApplication()
    }
  }
}
