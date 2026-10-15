package dev.skillbill.tray.application

import dev.skillbill.tray.core.domain.model.AppSettings
import dev.skillbill.tray.core.domain.model.Problem
import dev.skillbill.tray.core.domain.model.ProblemCode
import dev.skillbill.tray.core.domain.model.SettingsState
import dev.skillbill.tray.core.testing.FakeSettingsRepository
import dev.skillbill.tray.core.testing.FakeWorkStoreSession
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StoreSessionCoordinatorTest {
  private var current = AppSettings(databasePath = "/a.db")
  private val settings = FakeSettingsRepository(SettingsState.Ready(current))
  private val session = FakeWorkStoreSession()
  private val native = FakeNativeServices()

  private fun TestScope.coordinator(switch: suspend (String?) -> Unit = session::selectDatabase): StoreSessionCoordinator =
    StoreSessionCoordinator(backgroundScope, settings, switch, native).also { runCurrent() }

  private fun TestScope.change(transform: (AppSettings) -> AppSettings) {
    current = transform(current)
    settings.publish(SettingsState.Ready(current))
    runCurrent()
  }

  @Test
  fun `the first read selects the database and applies start at login once`() = runTest {
    val coordinator = coordinator()

    assertEquals(listOf<String?>("/a.db"), session.selectedPaths)
    assertEquals(listOf(true), native.loginCalls)
    assertNull(coordinator.problem.value)
  }

  @Test
  fun `settings that are not readable yet change nothing`() = runTest {
    settings.publish(SettingsState.Loading)
    coordinator()
    settings.publish(SettingsState.Failed(Problem(ProblemCode.SETTINGS_CORRUPT, "Settings are corrupt")))
    runCurrent()

    assertTrue(session.selectedPaths.isEmpty())
    assertTrue(native.loginCalls.isEmpty())
  }

  @Test
  fun `a new database path switches the store and leaves login alone`() = runTest {
    coordinator()

    change { it.copy(databasePath = "/b.db") }

    assertEquals(listOf<String?>("/a.db", "/b.db"), session.selectedPaths)
    assertEquals(listOf(true), native.loginCalls)
  }

  @Test
  fun `a changed start at login preference is applied and leaves the store alone`() = runTest {
    coordinator()

    change { it.copy(startAtLogin = false) }

    assertEquals(listOf(true, false), native.loginCalls)
    assertEquals(listOf<String?>("/a.db"), session.selectedPaths)
  }

  @Test
  fun `a login change the system refuses is reported until one succeeds`() = runTest {
    native.startAtLoginResult = false
    val coordinator = coordinator()
    assertEquals(ProblemCode.LOGIN_SETTING_FAILED, coordinator.problem.value?.code)

    native.startAtLoginResult = true
    change { it.copy(startAtLogin = false) }

    assertNull(coordinator.problem.value)
  }

  @Test
  fun `a database that cannot be opened is reported and does not stop later preferences`() = runTest {
    val coordinator = coordinator { path -> throw IllegalStateException("cannot open $path") }
    assertEquals(ProblemCode.DATABASE_ACCESS, coordinator.problem.value?.code)

    change { it.copy(startAtLogin = false) }

    assertEquals(listOf(true, false), native.loginCalls)
  }
}
