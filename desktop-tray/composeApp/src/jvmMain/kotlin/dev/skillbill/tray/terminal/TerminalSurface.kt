package dev.skillbill.tray.terminal

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
 * The terminal pane's content: the window's session widget in a [SwingPanel]. The session outlives composition, so re-entering
 * it reparents the same widget and keeps the shell and its scrollback. [workingDirectory] only seeds the first launch.
 */
@Composable
fun TerminalSurface(window: WindowOwner, workingDirectory: String?, modifier: Modifier = Modifier) {
  val session = remember(window) { appTerminalSessions.forWindow(window) }
  val colors = terminalColors()
  val font by produceState<Font?>(null) { value = terminalFont() }
  var attempt by remember { mutableIntStateOf(0) }
  val start by produceState<TerminalStart?>(null, session, font, attempt) {
    val loaded = font ?: return@produceState
    val launch = terminalLaunch(
      System.getenv(),
      System.getProperty("os.name"),
      System.getProperty("user.home"),
      workingDirectory,
    ) { File(it).isDirectory }
    value = session.open(launch, TerminalSettingsProvider(colors, loaded))
  }
  when (val current = start) {
    is TerminalStart.Started -> SwingPanel(factory = { current.view.component }, modifier = modifier)
    is TerminalStart.Failed -> Column(modifier) {
      Text("Couldn't start the terminal", style = MaterialTheme.typography.titleSmall)
      Text(current.message, style = MaterialTheme.typography.bodyMedium)
      TextButton(onClick = { attempt++ }) { Text("Retry") }
    }
    TerminalStart.Closed, null -> Box(modifier)
  }
}
