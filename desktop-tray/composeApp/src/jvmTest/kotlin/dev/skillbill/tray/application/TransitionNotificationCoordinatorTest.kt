package dev.skillbill.tray.application

import dev.skillbill.tray.core.domain.model.AppSettings
import dev.skillbill.tray.core.domain.model.ExecutionScope
import dev.skillbill.tray.core.domain.model.Lifecycle
import dev.skillbill.tray.core.domain.model.QaVerdict
import dev.skillbill.tray.core.domain.model.SettingsState
import dev.skillbill.tray.core.domain.model.WorkItem
import dev.skillbill.tray.core.domain.model.defaultNotifications
import dev.skillbill.tray.core.testing.FakeSettingsRepository
import dev.skillbill.tray.core.testing.FakeWorkBoardRepository
import dev.skillbill.tray.core.testing.fixtureInstant
import dev.skillbill.tray.core.testing.workItem
import dev.skillbill.tray.core.ui.StoreObservation
import dev.skillbill.tray.core.ui.TrackedSections
import dev.skillbill.tray.core.ui.loadedList
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

class TransitionNotificationCoordinatorTest {
  private val native = FakeNativeServices()
  private val settings = FakeSettingsRepository(SettingsState.Ready(AppSettings()))
  private lateinit var subject: TransitionNotificationCoordinator

  private fun item(lifecycle: Lifecycle, minutes: Int = 0): WorkItem =
    workItem("a", lifecycle).copy(summary = "Goal a", updatedAt = fixtureInstant.plus(minutes.minutes))

  private fun settled(item: WorkItem, store: StoreObservation? = StoreObservation(1, null)): TrackedSections = TrackedSections(
    needsYou = loadedList(emptyList(), hasMore = false),
    goals = loadedList(listOf(item), hasMore = false),
    phases = loadedList(emptyList(), hasMore = false),
    recent = loadedList(emptyList(), hasMore = false),
    store = store,
    observation = store,
  )

  private fun TestScope.coordinator() {
    subject = TransitionNotificationCoordinator(backgroundScope, FakeWorkBoardRepository(), settings, native) {}
    runCurrent()
  }

  private fun TestScope.show(state: TrackedSections) {
    subject.observe(state)
    runCurrent()
  }

  @Test
  fun `the first settled state is a silent baseline and only a later change notifies`() = runTest {
    coordinator()

    show(settled(item(Lifecycle.FAILED), store = null))
    show(TrackedSections(goals = loadedList(listOf(item(Lifecycle.FAILED)), hasMore = false), store = StoreObservation(1, null)))
    assertEquals(0, native.queued, "Nothing is baselined or announced before every section has been read.")

    show(settled(item(Lifecycle.ACTIVE)))
    assertEquals(0, native.queued, "Work that was already there when the app opened never notifies.")

    show(settled(item(Lifecycle.FAILED, minutes = 1)))
    assertEquals(1, native.queued)
    native.authorizeAll()
    assertEquals(listOf("Goal a"), native.notified)
  }

  @Test
  fun `a different store starts the baseline over`() = runTest {
    coordinator()
    show(settled(item(Lifecycle.ACTIVE)))

    show(settled(item(Lifecycle.FAILED, minutes = 1), store = StoreObservation(2, "/other.db")))
    assertEquals(0, native.queued, "The new store's first read is its own baseline.")

    show(settled(item(Lifecycle.ACTIVE, minutes = 2), store = StoreObservation(2, "/other.db")))
    show(settled(item(Lifecycle.FAILED, minutes = 3), store = StoreObservation(2, "/other.db")))
    assertEquals(1, native.queued)
  }

  @Test
  fun `a reconnect to the same store is a silent baseline once every section was read through it`() = runTest {
    coordinator()
    show(settled(item(Lifecycle.ACTIVE)))
    show(settled(item(Lifecycle.FAILED, minutes = 1)))
    show(settled(item(Lifecycle.ACTIVE, minutes = 2)))
    assertEquals(1, native.queued)

    val reconnected = StoreObservation(1, null, observationSession = 1)
    show(settled(item(Lifecycle.FAILED, minutes = 3)).copy(store = reconnected, observation = null))
    show(settled(item(Lifecycle.FAILED, minutes = 3), store = reconnected))
    assertEquals(1, native.queued, "What changed while the connection was away is the new session's baseline.")

    show(settled(item(Lifecycle.ACTIVE, minutes = 4), store = reconnected))
    show(settled(item(Lifecycle.FAILED, minutes = 5), store = reconnected))
    assertEquals(2, native.queued)
    native.authorizeAll()
    assertEquals(listOf("Goal a", "Goal a"), native.notified, "A reconnect keeps what was queued before it.")
  }

  @Test
  fun `a notification queued for the previous store is dropped when it would be sent`() = runTest {
    coordinator()
    show(settled(item(Lifecycle.ACTIVE)))
    show(settled(item(Lifecycle.FAILED, minutes = 1)))
    assertEquals(1, native.queued)

    show(settled(item(Lifecycle.ACTIVE), store = StoreObservation(2, "/other.db")))
    native.authorizeAll()

    assertTrue(native.notified.isEmpty())
  }

  @Test
  fun `a baseline reset drops what is queued and makes the next settled read silent, even from the same store`() = runTest {
    coordinator()
    show(settled(item(Lifecycle.ACTIVE)))
    show(settled(item(Lifecycle.FAILED, minutes = 1)))
    assertEquals(1, native.queued)

    subject.resetBaseline()
    native.authorizeAll()
    assertTrue(native.notified.isEmpty(), "A switch drops a notification the previous session queued.")

    show(settled(item(Lifecycle.ACTIVE, minutes = 2)))
    show(settled(item(Lifecycle.FAILED, minutes = 3)))
    assertEquals(1, native.queued, "The read right after the reset is a baseline; only the change after it notifies.")
  }

  @Test
  fun `a notification turned off before it is sent is not sent`() = runTest {
    coordinator()
    show(settled(item(Lifecycle.ACTIVE)))
    show(settled(item(Lifecycle.FAILED, minutes = 1)))

    settings.publish(SettingsState.Ready(AppSettings(notifications = defaultNotifications + ("failed" to false))))
    runCurrent()
    native.authorizeAll()

    assertTrue(native.notified.isEmpty())
  }

  @Test
  fun `a transition that is turned off is never queued`() = runTest {
    settings.publish(SettingsState.Ready(AppSettings(notifications = defaultNotifications + ("failed" to false))))
    coordinator()
    show(settled(item(Lifecycle.ACTIVE)))

    show(settled(item(Lifecycle.FAILED, minutes = 1)))

    assertEquals(0, native.queued)
  }

  @Test
  fun `only transitions the user has enabled are announced`() {
    val failed = item(Lifecycle.FAILED) to "failed"
    val finished = item(Lifecycle.TERMINAL) to "standalone_phase_finished"
    val unknown = item(Lifecycle.ACTIVE) to "something_else"

    assertEquals(listOf(failed), enabledTransitions(listOf(failed, finished, unknown), AppSettings()))
  }

  @Test
  fun `a finished standalone phase notification names its recorded outcome`() {
    val skipped = workItem("qa-run", Lifecycle.TERMINAL, family = null, issueKey = null)
      .copy(scope = ExecutionScope.STANDALONE_PHASE, phaseId = "qa", currentStepLabel = "qa", qaVerdict = QaVerdict.SKIPPED)

    assertEquals("qa · Skipped", notificationBody(skipped))
  }
}
