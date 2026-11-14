package dev.skillbill.desktop.app.onboarding

import dev.skillbill.desktop.core.designsystem.status.StatusKind
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TourScenesTest {
  @Test
  fun `scene timelines land on their declared final states`() = runTest {
    val sidebar = played(tourSidebarTimeline())
    assertEquals(TourTarget.Usage, sidebar.overlay.spotlight?.target)
    assertEquals("Usage", sidebar.overlay.spotlight?.tip?.title)
    assertEquals(TourNav.NewTask, sidebar.chrome.activeNav)

    val chat = played(tourChatTimeline())
    assertEquals(StartCardStage.Starting, chat.startCard)
    assertEquals(TourTarget.NavWork, chat.overlay.spotlight?.target)
    assertEquals("Started", chat.overlay.spotlight?.tip?.title)
    assertEquals(TourNav.Work, chat.chrome.activeNav)
    assertNull(chat.overlay.cursor)
    assertNull(chat.overlay.flight)
    assertEquals(
      listOf(
        TourChatLine.User(TourFixture.chatMessage1),
        TourChatLine.Agent(TourFixture.chatReply1),
        TourChatLine.User(TourFixture.chatMessage2),
        TourChatLine.Agent(TourFixture.chatReply2),
      ),
      chat.messages,
    )

    val board = played(tourWorkTimeline())
    assertEquals(StatusKind.Active, board.newRow?.status)
    assertEquals("Subtask 1/3", board.newRow?.label)
    assertEquals(false, board.newRow?.fresh)
    assertEquals(TourTarget.NewRow, board.overlay.spotlight?.target)
    assertEquals("Your new goal", board.overlay.spotlight?.tip?.title)

    val detail = played(tourRunDetailTimeline())
    assertEquals(1, detail.pipelineIndex)
    assertEquals(StatusKind.Completed, detail.subs[0].status)
    assertEquals(StatusKind.Active, detail.subs[1].status)
    assertEquals(true, detail.subs[1].current)
    assertEquals(StatusKind.Pending, detail.subs[2].status)
    assertEquals(1, detail.currentSubtask)
    assertEquals(TourChanges(2, 105, 0), detail.changes)
    assertEquals(6, detail.log.size)
    assertEquals(TourFixture.logSubtaskDone, detail.log.last().text)
    assertNull(detail.overlay.spotlight)
  }

  private suspend fun <S> played(timeline: TourTimeline<S>): S {
    var emitted: S? = null
    timeline.play(false) { emitted = it }
    assertEquals(timeline.finalState(), emitted)
    var reducedEmissions = 0
    timeline.play(true) { emitted = it; reducedEmissions++ }
    assertEquals(1, reducedEmissions)
    assertEquals(timeline.finalState(), emitted)
    return timeline.finalState()
  }
}
