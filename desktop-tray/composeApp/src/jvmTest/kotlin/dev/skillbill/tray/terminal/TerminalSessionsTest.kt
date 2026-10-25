package dev.skillbill.tray.terminal

import com.jediterm.terminal.TtyConnector
import com.jediterm.terminal.ui.settings.DefaultSettingsProvider
import dev.skillbill.tray.WindowFakes
import dev.skillbill.tray.core.testing.FakeWorkStoreSession
import dev.skillbill.tray.lifetime.SessionTransitionCoordinator
import dev.skillbill.tray.lifetime.WindowRegistry
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.SwingUtilities
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

  // Like a real process, destroying it also completes its exit future.
  private class FakeProcess(private val onDestroy: () -> Unit) : TerminalProcess {
    private var listener: (() -> Unit)? = null

    override val connector: TtyConnector get() = error("the fake view never reads the connector")

    override fun destroy() {
      onDestroy()
      listener?.invoke()
    }

    override fun onExit(listener: () -> Unit) {
      this.listener = listener
    }

    fun fireExit() = listener?.invoke()
  }

  private class FakeView : TerminalView {
    override val component: JComponent = JPanel()
    var closes = 0
    val closed get() = closes > 0

    override fun close() {
      closes++
    }
  }

  private class FakeFactory : TerminalProcessFactory {
    var starts = 0
    var destroys = 0
    var failNextStart = false
    val processes = mutableListOf<FakeProcess>()

    override fun start(launch: TerminalLaunch): TerminalProcess {
      if (failNextStart) {
        failNextStart = false
        error("no pty")
      }
      starts++
      return FakeProcess { destroys++ }.also(processes::add)
    }
  }

  private val factory = FakeFactory()
  private val views = mutableListOf<FakeView>()

  private fun session() = TerminalSession(factory, { _, _ -> FakeView().also(views::add) }, {})

  private fun sessions() = TerminalSessions(factory, { _, _ -> FakeView().also(views::add) }, addShutdownHook = {})

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
    val session = TerminalSession(nativeFactory, { _, _ -> FakeView() }, {})

    assertEquals(TerminalStart.Failed("no pty4j"), session.open(launch, settings))
    nativeLoaded = true
    assertIs<TerminalStart.Started>(session.open(launch, settings))
  }

  @Test
  fun `a failed attach destroys the process and leaves the session retryable`() {
    var attempts = 0
    val session = TerminalSession(factory, { _, _ -> if (attempts++ == 0) error("widget failed") else FakeView() }, {})

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
    val sessions = sessions()
    val owner = registry().openMain()
    val session = sessions.forTab(owner, 1)
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
  fun `two tabs in one directory get two shells, retainOnly ends only the tabs it drops and disposal ends the rest once`() = runTest {
    val sessions = sessions()
    val owner = registry().openMain()
    val tabs = (1L..3L).map { sessions.forTab(owner, it) }
    tabs.forEach { assertIs<TerminalStart.Started>(it.open(launch, settings)) }

    assertSame(tabs[0], sessions.forTab(owner, 1))
    assertEquals(3, factory.starts, "Tabs that share a directory never share a shell.")

    sessions.retainOnly(owner, setOf(1, 3, 99))

    assertEquals(1, factory.destroys)
    assertEquals(listOf(false, true, false), views.map { it.closed })
    assertEquals(TerminalStart.Closed, tabs[1].open(launch, settings))

    owner.dispose()

    assertEquals(3, factory.destroys)
    assertTrue(views.all { it.closes == 1 })
  }

  @Test
  fun `a shell that exits on its own is reported once and a close is not reported as an exit`() = runTest {
    val sessions = sessions()
    val owner = registry().openMain()
    val exiting = sessions.forTab(owner, 1)
    val retained = sessions.forTab(owner, 2)
    assertIs<TerminalStart.Started>(exiting.open(launch, settings))
    assertIs<TerminalStart.Started>(retained.open(launch, settings))

    factory.processes[0].fireExit()
    SwingUtilities.invokeAndWait {} // the view closes on the event thread, queued ahead of this no-op

    assertEquals(setOf(1L), sessions.endedTabs(owner).value)
    assertEquals(1, factory.destroys)
    assertEquals(1, views[0].closes)
    assertEquals(TerminalStart.Closed, sessions.forTab(owner, 1).open(launch, settings), "A dead tab never starts another shell.")

    sessions.retainOnly(owner, setOf(1))

    assertEquals(setOf(1L), sessions.endedTabs(owner).value, "Closing tab 2 on purpose is not an own exit.")
    assertEquals(2, factory.destroys)
    assertEquals(1, views[1].closes)
  }
}
