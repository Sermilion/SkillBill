package dev.skillbill.desktop.app.onboarding

import dev.skillbill.desktop.core.designsystem.status.StatusKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TourScenesTest {
  @Test
  fun `each staged step is a single beat that lands on its merged state`() {
    assertEquals(1, tourBeatCount(1))
    assertEquals(1, tourBeatCount(2))
    assertEquals(1, tourBeatCount(3))
    assertEquals(1, tourBeatCount(4))

    val start = tourStartTimeline().stateAt(0)
    assertEquals(StatusKind.Active, start.newRow?.status)
    assertEquals("Subtask 1/3", start.newRow?.label)
    assertEquals(false, start.newRow?.fresh)
    assertEquals(TourTarget.NewRow, start.overlay.spotlight?.target)
    assertEquals("Your new goal", start.overlay.spotlight?.tip?.title)
    assertEquals(TourNav.Work, start.chrome.activeNav)

    val kinds = tourKindsTimeline().stateAt(0)
    assertEquals(TourTarget.BoardLanes, kinds.overlay.spotlight?.target)
    assertEquals("One board, three kinds", kinds.overlay.spotlight?.tip?.title)
    assertEquals(TourFixture.newGoalRowActive, kinds.newRow)

    val steer = tourSteerTimeline().stateAt(0)
    assertNull(steer.chrome.workAttention)
    assertEquals(false, steer.banner)
    assertEquals(2, steer.currentSubtask)
    assertEquals(StatusKind.Completed, steer.subs[0].status)
    assertEquals(StatusKind.Completed, steer.subs[1].status)
    assertEquals(StatusKind.Active, steer.subs[2].status)
    assertEquals(true, steer.subs[2].current)
    assertEquals(TourDetailPill.Active, steer.pill)
    assertEquals(TourFixture.logResumed, steer.log.last().text)
    assertEquals(TourTarget.Pipeline, steer.overlay.spotlight?.target)
    assertEquals("The same steps every time", steer.overlay.spotlight?.tip?.title)
    assertNull(steer.overlay.keycap)

    val ship = tourShipTimeline().stateAt(0)
    ship.checks.forEach { assertEquals(StatusKind.Completed, it) }
    assertEquals(TourFixture.finishSteps.size, ship.done)
    assertEquals(true, ship.prShown)
    assertEquals(true, ship.prReady)
    assertEquals(TourTarget.PrCard, ship.overlay.spotlight?.target)
    assertEquals("Ready for you", ship.overlay.spotlight?.tip?.title)
  }
}
