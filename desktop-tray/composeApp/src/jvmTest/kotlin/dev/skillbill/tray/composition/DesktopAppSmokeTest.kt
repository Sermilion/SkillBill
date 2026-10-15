package dev.skillbill.tray.composition

import dev.skillbill.tray.application.FakeNativeServices
import dev.skillbill.tray.core.common.HostEnvironment
import dev.skillbill.tray.core.domain.model.AppSettings
import dev.skillbill.tray.core.domain.model.Board
import dev.skillbill.tray.core.domain.model.Detail
import dev.skillbill.tray.core.domain.model.Retention
import dev.skillbill.tray.core.domain.model.SettingsState
import dev.skillbill.tray.core.domain.model.WorkSection
import dev.skillbill.tray.core.domain.repository.Outcome
import dev.skillbill.tray.host.HostIntent
import dev.skillbill.tray.lifetime.SessionState
import dev.skillbill.tray.navigation.Section
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Test
import skillbill.infrastructure.sqlite.establishTemporarySchemaReadiness
import skillbill.infrastructure.sqlite.sqliteSessionFactoryForTests
import skillbill.ports.idestatus.model.StandalonePhaseStatusRegistration
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The real application owner over the generated graph and a disposable runtime-migrated store: it opens the store, browses live,
 * history and detail, edits a setting, runs two main windows, takes tray intents, hides a window and exits. A leaked connection
 * or watcher, a window that outlives exit or a child process left running passes every unit test, and fails here. A process that
 * starts and exits before the end of the run is not observed; PATH discovery is blocked by the PATH-free host environment.
 *
 * Not exercised in process, and recorded as such: pause or stop of a target (the data layer's control tests own those outcomes,
 * and a real stop signals the lease owner), launch (needs an installed harness), native tray clicks, OS notification display and
 * login-start registration (need a real desktop session).
 */
class DesktopAppSmokeTest {
  @Test
  fun `the app owner serves a disposable store through two windows and tray intents and exits with nothing left behind`() = runBlocking<Unit> {
    establishTemporarySchemaReadiness("desktop-app-smoke").use { fixture ->
      val home = fixture.tempDir.toRealPath()
      val now = Instant.now()
      sqliteSessionFactoryForTests(fixture.tempDir, fixture.dbPath.toString(), emptyMap()).transaction { unit ->
        unit.standalonePhaseStatuses.register(
          StandalonePhaseStatusRegistration(
            "repo-root-realpath-v1:$home", "main", "SKILL-424", null, "smoke", "review",
            "execution-smoke", "active", "review", now, "owned-test", 1, now.plusSeconds(300),
          ),
        )
      }
      // A non-empty environment without PATH: an empty one makes the runtime fall back to the host environment.
      val host = HostEnvironment(mapOf("SKILL_BILL_TEST" to "1"), home.toString(), home.toString())
      val application = createApplicationComponent(host, NoLegacyPreferences)
      val outliving = CoroutineScope(SupervisorJob())
      val exited = CompletableDeferred<Unit>()
      var viewModelReleases = 0
      val childrenBefore = childProcesses()
      val owner = AppOwner(
        application = application,
        native = FakeNativeServices(),
        outliving = outliving,
        releaseViewModels = { viewModelReleases++ },
        exit = { exited.complete(Unit) },
        refresh = {},
      )
      try {
        withTimeout(10_000) { application.settings.settings.first { it !is SettingsState.Loading } }
        assertIs<Outcome.Value<*>>(application.settings.update { it.copy(databasePath = fixture.dbPath.toString()) })

        // Live: the store behind the selected path serves the board, and the windows settle on its generation.
        val live = eventually("the live board") {
          runCatching { (application.boards.board(WorkSection.PHASES, Retention.DAY) as? Outcome.Value<*>)?.value as? Board }.getOrNull()
            ?.items?.singleOrNull()
        }
        val identity = live.identity
        eventually("the windows to settle on the selected store") {
          (owner.transitions.state.value as? SessionState.Active)?.ref?.takeIf { it.generation == application.storeSession.generation.value }
        }

        // Two main windows with navigators of their own.
        val first = checkNotNull(owner.windows.target()) { "The application owner opens a main window." }
        val second = owner.windows.openMain()
        assertNotSame(first, second)
        assertNotSame(first.navigator, second.navigator)
        assertEquals(2, owner.windows.windows.count { it.navigator.section != null })

        // History and detail.
        first.navigator.switchTo(Section.HISTORY)
        assertEquals(Section.HISTORY, first.navigator.section)
        assertIs<Outcome.Value<Board>>(application.boards.board(WorkSection.HISTORY, Retention.DAY))
        assertEquals(identity, assertIs<Outcome.Value<Detail>>(application.details.detail(identity)).value.item.identity)

        // A preference edit reaches the disposable preferences file and its readers.
        val edited = assertIs<Outcome.Value<AppSettings>>(application.settings.update { it.copy(retention = Retention.WEEK) })
        assertEquals(Retention.WEEK, edited.value.retention)

        // Tray intents land in the window used last, and only there.
        owner.router.dispatch(HostIntent.OpenWork(identity))
        eventually("the tray intent to reach the window") { second.takeIf { it.focusRequests > 0 } }
        assertEquals(identity, second.navigator.selected)
        assertNull(first.navigator.selected)
        owner.router.dispatch(HostIntent.OpenSettings)
        eventually("the settings window") { owner.windows.windows.firstOrNull { it.navigator.section == null } }
        assertEquals(2, owner.windows.windows.count { it.navigator.section != null })

        // Hiding a window keeps it; with every main window hidden, the tray brings the last one back.
        first.hide()
        second.hide()
        assertFalse(second.visible)
        assertFalse(second.disposed)
        owner.router.dispatch(HostIntent.ShowMain)
        eventually("the hidden window to return") { second.takeIf { it.visible } }
        assertFalse(first.visible)
        second.hide()

        // Exit ends every window, then the coordinators, then the stores, and only then asks the process to end.
        val windowsAtExit = owner.windows.windows.toList()
        owner.router.dispatch(HostIntent.Quit)
        withTimeout(15_000) { exited.await() }
        outliving.coroutineContext[Job]!!.children.toList().forEach { it.join() }

        assertEquals(emptyList(), owner.windows.windows)
        assertTrue(windowsAtExit.all { it.disposed && !it.scope.isActive }, "Every window ended with the application.")
        assertEquals(1, viewModelReleases)
        assertTrue(outliving.coroutineContext[Job]!!.children.none { it.isActive }, "No job the application launched outlives exit.")
        // Closed means closed: the one session owner accepts no further selection, so no new connection can open.
        assertFailsWith<IllegalStateException> { application.storeSession.selectDatabase(fixture.dbPath.toString()) }
        assertEquals(emptySet(), childProcesses() - childrenBefore, "No child process outlives exit.")
      } finally {
        if (!exited.isCompleted) {
          owner.cancelScopes()
          application.shutdown.closeAndJoin()
        }
        outliving.coroutineContext[Job]!!.cancel()
      }
    }
  }

  private fun childProcesses(): Set<Long> =
    ProcessHandle.current().children().use { children -> children.map { it.pid() }.toList().toSet() }

  private suspend fun <T : Any> eventually(what: String, block: suspend () -> T?): T = try {
    withTimeout(15_000) {
      var value = block()
      while (value == null) {
        delay(25)
        value = block()
      }
      checkNotNull(value)
    }
  } catch (timeout: TimeoutCancellationException) {
    throw AssertionError("Timed out waiting for $what.", timeout)
  }
}
