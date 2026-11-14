package dev.skillbill.desktop.app.onboarding

import dev.skillbill.desktop.core.common.Outcome
import dev.skillbill.desktop.core.domain.settings.SettingsRejection
import dev.skillbill.desktop.core.testing.FakeAppSettingsRepository
import dev.skillbill.desktop.core.ui.navigation.WindowCoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class OnboardingTourStateTest {
  @Test
  fun `first launch opens at the welcome step and marks the tour seen`() = runTest {
    val settings = FakeAppSettingsRepository()
    val tour = OnboardingTourState(settings, WindowCoroutineScope(backgroundScope))

    tour.startOnLaunch()
    runCurrent()

    assertTrue(tour.open)
    assertEquals(0, tour.step)
    assertTrue(settings.onboardingTourSeen)
  }

  @Test
  fun `first launch marks and opens even when marking fails`() = runTest {
    val settings = FakeAppSettingsRepository().apply { onboardingTourWriteFailure = true }
    val tour = OnboardingTourState(settings, WindowCoroutineScope(backgroundScope))

    tour.startOnLaunch()
    runCurrent()

    assertTrue(tour.open)
    assertEquals(0, tour.step)
    assertFalse(settings.onboardingTourSeen)
  }

  @Test
  fun `seen and unreadable preferences keep automatic launch closed while show remains available`() = runTest {
    val settings = FakeAppSettingsRepository().apply { onboardingTourReadFailure = true }
    val tour = OnboardingTourState(settings, WindowCoroutineScope(backgroundScope))

    tour.startOnLaunch()
    runCurrent()
    assertFalse(tour.open)
    assertEquals(Outcome.Failure(SettingsRejection.ReadFailed), settings.readOnboardingTourSeen())

    tour.show()
    assertTrue(tour.open)
    assertEquals(0, tour.step)
    assertFalse(settings.onboardingTourSeen)
  }

  @Test
  fun `next advances one merged step at a time and back returns to the previous step`() = runTest {
    val tour = OnboardingTourState(FakeAppSettingsRepository(), WindowCoroutineScope(backgroundScope))
    tour.show()
    tour.goTo(1)

    assertEquals(0, tour.beat)
    tour.next()
    assertEquals(2, tour.step)
    assertEquals(0, tour.beat)
    tour.back()
    assertEquals(1, tour.step)
    assertEquals(0, tour.beat)
  }

  @Test
  fun `a previously seen tour stays closed on launch`() = runTest {
    val settings = FakeAppSettingsRepository().apply { onboardingTourSeen = true }
    val tour = OnboardingTourState(settings, WindowCoroutineScope(backgroundScope))

    tour.startOnLaunch()
    runCurrent()

    assertFalse(tour.open)
  }
}
