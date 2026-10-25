package dev.skillbill.tray.terminal

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.SwingPanel
import dev.skillbill.tray.lifetime.WindowOwner
import java.awt.Font
import java.io.File

/**
 * One tab's terminal in a [SwingPanel]: the window's session for [tabId], started in [workingDirectory]. Sessions outlive
 * composition, so re-entering one reparents the same widget and keeps the shell and its scrollback; a tab without one starts a new
 * shell. Nothing here ends a shell, and a failed start with its retry belongs to this tab alone.
 */
@Composable
fun TerminalSurface(window: WindowOwner, tabId: Long, workingDirectory: String, modifier: Modifier = Modifier) {
  val launch = remember(workingDirectory) {
    terminalLaunch(
      System.getenv(),
      System.getProperty("os.name"),
      System.getProperty("user.home"),
      workingDirectory,
    ) { File(it).isDirectory }
  }
  val session = remember(window, tabId) { appTerminalSessions.forTab(window, tabId) }
  val colors = terminalColors()
  val font by produceState<Font?>(null) { value = terminalFont() }
  var attempt by remember(tabId) { mutableIntStateOf(0) }
  val start by produceState<TerminalStart?>(null, session, font, attempt) {
    val loaded = font ?: return@produceState
    value = session.open(launch, TerminalSettingsProvider(colors, loaded))
  }
  when (val current = start) {
    // SwingPanel calls its factory once, so another session's widget needs a new panel.
    is TerminalStart.Started -> key(current.view) { SwingPanel(factory = { current.view.component }, modifier = modifier) }
    is TerminalStart.Failed -> Column(modifier) {
      Text("Couldn't start the terminal", style = MaterialTheme.typography.titleSmall)
      Text(current.message, style = MaterialTheme.typography.bodyMedium)
      TextButton(onClick = { attempt++ }) { Text("Retry") }
    }
    TerminalStart.Closed, null -> Box(modifier)
  }
}
