package dev.skillbill.desktop.feature.terminal

import com.jediterm.terminal.TtyConnector
import com.jediterm.terminal.ui.settings.DefaultSettingsProvider
import java.nio.file.Path
import javax.swing.JComponent
import javax.swing.JPanel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class TerminalSessionTest {
  private val launch = TerminalLaunch(listOf("/bin/sh"), Path.of("/"), emptyMap())

  @Test
  fun `an own exit is reported once and a close is never reported`() {
    val exiting = FakeProcess()
    var exits = 0
    val session = TerminalSession({ ShellStart.Started(exiting) }, { _, _ -> FakeView() }) { exits++ }
    assertIs<TerminalStart.Started>(session.open(launch, DefaultSettingsProvider()))
    exiting.exit()
    exiting.exit()
    session.close()
    assertEquals(1, exits)

    val closed = FakeProcess()
    var closedExits = 0
    val other = TerminalSession({ ShellStart.Started(closed) }, { _, _ -> FakeView() }) { closedExits++ }
    other.open(launch, DefaultSettingsProvider())
    other.close()
    closed.exit()
    assertEquals(0, closedExits)
    assertIs<TerminalStart.Closed>(other.open(launch, DefaultSettingsProvider()))
  }

  private class FakeProcess : ShellProcess {
    private var listener: (() -> Unit)? = null
    override val connector: TtyConnector get() = error("unused")

    override fun destroy() = Unit

    override fun onExit(listener: () -> Unit) {
      this.listener = listener
    }

    fun exit() = listener?.invoke()
  }

  private class FakeView : TerminalView {
    override val component: JComponent = JPanel()

    override fun requestFocus() = Unit

    override fun close() = Unit
  }
}
