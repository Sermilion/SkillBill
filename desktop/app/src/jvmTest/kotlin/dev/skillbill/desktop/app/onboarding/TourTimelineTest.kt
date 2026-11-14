package dev.skillbill.desktop.app.onboarding

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalCoroutinesApi::class)
class TourTimelineTest {
  @Test
  fun `reduced motion emits the final state without advancing time`() = runTest {
    val timeline = TourTimeline(0, listOf(TourCue(150) { it + 1 }, TourCue(320) { it + 1 }), 500)
    var applied: Int? = null
    var emissions = 0

    timeline.play(true) { applied = it; emissions++ }

    assertEquals(timeline.finalState(), applied)
    assertEquals(1, emissions)
    assertEquals(0, currentTime)
  }
}
