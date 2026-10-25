package dev.skillbill.tray.terminal

import com.jediterm.terminal.TtyConnector
import com.jediterm.terminal.ui.settings.DefaultSettingsProvider
import dev.skillbill.tray.WindowFakes
import dev.skillbill.tray.core.testing.FakeWorkStoreSession
import dev.skillbill.tray.lifetime.SessionTransitionCoordinator
import dev.skillbill.tray.lifetime.WindowRegistry
import javax.swing.JComponent
import javax.swing.JPanel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

class TerminalSessionsTest {
  private val launch = TerminalLaunch(listOf("/bin/zsh", "-l"), "/tmp", mapOf("TERM" to "xterm-256color"))
  private val settings = DefaultSettingsProvider()

  private class FakeProcess(private val onDestroy: () -> Unit) : TerminalProcess {
    override val connector: TtyConnector get() = error("the fake view never reads the connector")

    override fun destroy() = onDestroy()
  }

  private class FakeView : TerminalView {
    override val component: JComponent = JPanel()
    var closed = false

    override fun close() {
      closed = true
    }
  }

  private class FakeFactory : TerminalProcessFactory {
    var starts = 0
    var destroys = 0
    var failNextStart = false

    override fun start(launch: TerminalLaunch): TerminalProcess {
      if (failNextStart) {
        failNextStart = false
        error("no pty")
      }
      starts++
      return FakeProcess { destroys++ }
    }
  }

  private val factory = FakeFactory()
  private val views = mutableListOf<FakeView>()

  private fun session() = TerminalSession(factory) { _, _ -> FakeView().also(views::add) }

  @Test
  fun `a second open returns the same view without starting another process`() {
    val session = session()

    val first = assertIs<TerminalStart.Started>(session.open(launch, settings))
    val second = assertIs<TerminalStart.Started>(session.open(launch.copy(workingDirectory = "/elsewhere"), settings))

    assertSame(first.view, second.view)
    assertEquals(1, factory.starts)
  }

  @Test
  fun `a failed start can be retried`() {
    val session = session()
    factory.failNextStart = true

    assertEquals(TerminalStart.Failed("no pty"), session.open(launch, settings))
    assertIs<TerminalStart.Started>(session.open(launch, settings))
    assertEquals(1, factory.starts)
  }

  @Test
  fun `a missing native library is a retryable start failure rather than a crash`() {
    var nativeLoaded = false
    val nativeFactory = TerminalProcessFactory { request ->
      if (!nativeLoaded) throw UnsatisfiedLinkError("no pty4j")
      factory.start(request)
    }
    val session = TerminalSession(nativeFactory) { _, _ -> FakeView() }

    assertEquals(TerminalStart.Failed("no pty4j"), session.open(launch, settings))
    nativeLoaded = true
    assertIs<TerminalStart.Started>(session.open(launch, settings))
  }

  @Test
  fun `a failed attach destroys the process and leaves the session retryable`() {
    var attempts = 0
    val session = TerminalSession(factory) { _, _ ->
      if (attempts++ == 0) error("widget failed") else FakeView()
    }

    assertEquals(TerminalStart.Failed("widget failed"), session.open(launch, settings))
    assertEquals(1, factory.destroys)
    assertIs<TerminalStart.Started>(session.open(launch, settings))
  }

  private fun TestScope.registry(): WindowRegistry {
    val transitions = SessionTransitionCoordinator(backgroundScope, FakeWorkStoreSession(), StandardTestDispatcher(testScheduler))
    return WindowRegistry(backgroundScope, WindowFakes()::windowComponent, transitions)
  }

  @Test
  fun `hiding a window keeps its shell and disposing it ends the shell once`() = runTest {
    val sessions = TerminalSessions(factory, { _, _ -> FakeView().also(views::add) }, addShutdownHook = {})
    val owner = registry().openMain()
    val session = sessions.forWindow(owner, "/tmp")
    assertIs<TerminalStart.Started>(session.open(launch, settings))

    owner.hide()

    assertEquals(0, factory.destroys)
    assertFalse(views.single().closed)

    owner.dispose()
    owner.dispose()

    assertEquals(1, factory.destroys)
    assertTrue(views.single().closed)
    assertEquals(TerminalStart.Closed, session.open(launch, settings))
  }

  @Test
  fun `each starting directory keeps its own shell and disposing the window ends them all`() = runTest {
    val sessions = TerminalSessions(factory, { _, _ -> FakeView().also(views::add) }, addShutdownHook = {})
    val owner = registry().openMain()
    val first = sessions.forWindow(owner, "/repo/a")
    assertIs<TerminalStart.Started>(first.open(launch.copy(workingDirectory = "/repo/a"), settings))

    val second = sessions.forWindow(owner, "/repo/b")
    assertIs<TerminalStart.Started>(second.open(launch.copy(workingDirectory = "/repo/b"), settings))

    assertSame(first, sessions.forWindow(owner, "/repo/a"))
    assertEquals(2, factory.starts)
    assertEquals(0, factory.destroys, "Opening another project's shell never ends the first one.")

    owner.dispose()

    assertEquals(2, factory.destroys)
    assertTrue(views.all { it.closed })
  }
}
