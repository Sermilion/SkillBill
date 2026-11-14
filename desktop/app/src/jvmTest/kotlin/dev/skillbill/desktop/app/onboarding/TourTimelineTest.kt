package dev.skillbill.desktop.app.onboarding

import kotlin.test.Test
import kotlin.test.assertEquals

class TourTimelineTest {
  @Test
  fun `stateAt applies beats cumulatively and clamps outside the range`() {
    val timeline = TourTimeline(0, listOf(TourBeat { it + 1 }, TourBeat { it * 10 }, TourBeat { it + 5 }))

    assertEquals(1, timeline.stateAt(0))
    assertEquals(10, timeline.stateAt(1))
    assertEquals(15, timeline.stateAt(2))
    assertEquals(timeline.finalState(), timeline.stateAt(2))
    assertEquals(1, timeline.stateAt(-3))
    assertEquals(timeline.finalState(), timeline.stateAt(9))
  }

  @Test
  fun `an empty timeline always lands on its initial state`() {
    val timeline = TourTimeline("start", emptyList<TourBeat<String>>())

    assertEquals("start", timeline.stateAt(0))
    assertEquals("start", timeline.finalState())
    assertEquals(0, timeline.beatCount)
  }
}
