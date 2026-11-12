package dev.skillbill.desktop.app

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModelStore
import dev.nucleusframework.application.NucleusBackend
import dev.nucleusframework.application.nucleusApplication
import dev.skillbill.desktop.app.display.resolveDesktopUiScale
import dev.skillbill.desktop.app.host.HostIntent
import dev.skillbill.desktop.app.host.withShellEnvironment
import dev.skillbill.desktop.app.tray.TrayHost
import dev.skillbill.desktop.app.motion.OsReducedMotionReader
import dev.skillbill.desktop.app.motion.ReducedMotionRead
import dev.skillbill.desktop.app.shell.MainWindow
import dev.skillbill.desktop.core.common.DiagnosticsSwitch
import dev.skillbill.desktop.core.common.HostEnvironment
import dev.skillbill.desktop.core.ui.navigation.DetailRoute
import dev.skillbill.desktop.core.ui.navigation.SettingsPageId
import dev.skillbill.desktop.core.ui.navigation.SettingsRoute
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** The app entry the launcher starts inside the class loader that holds the installed runtime's jars. */
fun main() {
  val diagnostics = DiagnosticsSwitch(
    System.getenv("SKILL_BILL_DESKTOP_DIAGNOSTICS") == "1" || System.getProperty("skillbill.desktop.diagnostics") == "true",
  )
  val environment = withShellEnvironment(System.getenv(), System.getProperty("os.name", ""))
  val host = HostEnvironment(environment, System.getProperty("user.home"), System.getProperty("user.dir"))
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
  component.chatSummarizer.start()
  val window = component.windowComponentFactory.createWindowComponent()
  val uiScale = resolveDesktopUiScale()
  nucleusApplication(backend = NucleusBackend.Awt) {
    DisposableEffect(component.providerUsageRepository) {
      onDispose { component.providerUsageRepository.closeUsage() }
    }
    var mainVisible by remember { mutableStateOf(true) }
    var focusRequest by remember { mutableStateOf(0) }
    val appScope = rememberCoroutineScope()
    var quitting by remember { mutableStateOf<Job?>(null) }
    fun closeUsageAndExit() {
      if (quitting?.isActive == true) return
      quitting = appScope.launch {
        try {
          if (window.chat.closeChatsSafely()) {
            component.providerUsageRepository.closeUsage()
            exitApplication()
          } else {
            mainVisible = true
            focusRequest++
          }
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
          throw cancelled
        } catch (failure: Exception) {
          System.getLogger("dev.skillbill.desktop").log(System.Logger.Level.WARNING, "desktop:quit-chat-close-failed", failure)
          mainVisible = true
          focusRequest++
        }
      }
    }
    val trayStore = remember { ViewModelStore() }
    val trayModel = remember { component.trayViewModel().also { trayStore.put("tray", it) } }
    LaunchedEffect(component.hostIntents, window) {
      component.hostIntents.intents.collect { intent ->
        when (intent) {
          HostIntent.ShowMain -> { mainVisible = true; focusRequest++ }
          is HostIntent.OpenDetail -> { mainVisible = true; focusRequest++; component.changesWindows.close(); window.navigator.navigate(DetailRoute(intent.item)) }
          HostIntent.OpenSettings -> { mainVisible = true; focusRequest++; component.changesWindows.close(); window.navigator.navigate(SettingsRoute(SettingsPageId.App)) }
          HostIntent.Refresh -> component.workBoardRepository.refresh()
          HostIntent.Quit -> closeUsageAndExit()
        }
      }
    }
    TrayHost(trayModel, component.trayAvailability, component.hostIntents, uiScale, mainVisible, reducedMotion, ::closeUsageAndExit)
    MainWindow(window, uiScale, reducedMotion, mainVisible, focusRequest, component.changesWindows, component.changesWindowViewModel) {
      if (component.trayAvailability.state.value.canHideToTray) mainVisible = false else {
        component.trayAvailability.unavailable()
        closeUsageAndExit()
      }
    }
  }
}
