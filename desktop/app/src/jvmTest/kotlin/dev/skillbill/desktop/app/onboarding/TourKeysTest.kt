package dev.skillbill.desktop.app.onboarding

import androidx.compose.ui.input.key.Key
import dev.skillbill.desktop.core.testing.FakeAppSettingsRepository
import dev.skillbill.desktop.core.ui.navigation.WindowCoroutineScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TourKeysTest {
  @Test
  fun `tour navigation keys are handled and shell shortcuts are swallowed`() {
    assertEquals(TourKeyAction.Swallow, tourKeyAction(Key.P, true, false))
    assertEquals(TourKeyAction.Swallow, tourKeyAction(Key.R, true, false))
    assertEquals(TourKeyAction.Swallow, tourKeyAction(Key.G, true, false))
    assertEquals(TourKeyAction.Swallow, tourKeyAction(Key.K, true, true))
    assertEquals(TourKeyAction.Next, tourKeyAction(Key.DirectionRight, true, false))
    assertEquals(TourKeyAction.Back, tourKeyAction(Key.DirectionLeft, true, false))
    assertEquals(TourKeyAction.Escape, tourKeyAction(Key.Escape, true, false))
    assertEquals(TourKeyAction.PassToFocus, tourKeyAction(Key.Enter, true, false))
  }

  @Test
  fun `applied actions move steps and escape skips to the outro then closes`() = runTest {
    val tour = OnboardingTourState(FakeAppSettingsRepository(), WindowCoroutineScope(backgroundScope))
    tour.show()
    tour.goTo(3)

    applyTourAction(TourKeyAction.Swallow, tour)
    assertEquals(3, tour.step)
    applyTourAction(TourKeyAction.Next, tour)
    assertEquals(3, tour.step)
    repeat(tourBeatCount(3) - 1) { applyTourAction(TourKeyAction.Next, tour) }
    assertEquals(4, tour.step)
    applyTourAction(TourKeyAction.Back, tour)
    assertEquals(3, tour.step)
    applyTourAction(TourKeyAction.Escape, tour)
    assertEquals(OnboardingTourState.LAST_STEP, tour.step)
    assertTrue(tour.open)
    applyTourAction(TourKeyAction.Escape, tour)
    assertFalse(tour.open)
  }
}
