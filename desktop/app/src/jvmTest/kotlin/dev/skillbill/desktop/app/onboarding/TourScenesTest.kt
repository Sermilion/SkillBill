package dev.skillbill.desktop.app.onboarding

import dev.skillbill.desktop.core.designsystem.status.StatusKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TourScenesTest {
  @Test
  fun `scene timelines land on their declared final states`() {
    val sidebar = tourSidebarTimeline().finalState()
    assertEquals(TourTarget.Usage, sidebar.overlay.spotlight?.target)
    assertEquals("Usage", sidebar.overlay.spotlight?.tip?.title)
    assertEquals(TourNav.NewTask, sidebar.chrome.activeNav)

    val chat = tourChatTimeline().finalState()
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

    val board = tourWorkTimeline().finalState()
    assertEquals(StatusKind.Active, board.newRow?.status)
    assertEquals("Subtask 1/3", board.newRow?.label)
    assertEquals(false, board.newRow?.fresh)
    assertEquals(TourTarget.NewRow, board.overlay.spotlight?.target)
    assertEquals("Your new goal", board.overlay.spotlight?.tip?.title)

    val detail = tourRunDetailTimeline().finalState()
    assertEquals(1, detail.pipelineIndex)
    assertEquals(StatusKind.Completed, detail.subs[0].status)
    assertEquals(StatusKind.Active, detail.subs[1].status)
    assertEquals(true, detail.subs[1].current)
    assertEquals(StatusKind.Pending, detail.subs[2].status)
    assertEquals(1, detail.currentSubtask)
    assertEquals(TourChanges(2, 105, 0), detail.changes)
    assertEquals(6, detail.log.size)
    assertEquals(TourFixture.logSubtaskDone, detail.log.last().text)
    assertEquals(TourTarget.ActivityLog, detail.overlay.spotlight?.target)
  }

  @Test
  fun `steps five to eight land on their declared final states`() {
    val control = tourControlTimeline().finalState()
    assertNull(control.chrome.workAttention)
    assertEquals(false, control.banner)
    assertEquals(2, control.currentSubtask)
    assertEquals(StatusKind.Completed, control.subs[0].status)
    assertEquals(StatusKind.Completed, control.subs[1].status)
    assertEquals(StatusKind.Active, control.subs[2].status)
    assertEquals(true, control.subs[2].current)
    assertEquals(TourDetailPill.Active, control.pill)
    assertEquals(1, control.pipelineIndex)
    assertEquals(TourFixture.logResumed, control.log.last().text)
    assertEquals(6, control.log.size)
    assertEquals("R", control.overlay.keycap?.key)
    assertNull(control.overlay.spotlight)

    val review = tourReviewTimeline().finalState()
    assertEquals(TourFixture.csvFixedLine, review.rows[7].text)
    assertEquals(TourClaudeReply.Replied, review.claude)
    assertEquals("Sent to agent", review.youTag)
    assertEquals("Review added", review.reviewLabel)
    assertEquals(true, review.toastShown)
    assertEquals(false, review.targetTint)
    assertEquals(false, review.plusShown)
    assertNull(review.draft)
    assertNull(review.overlay.spotlight)
    assertEquals(1, review.overlay.cursor?.presses)

    val finish = tourFinishTimeline().finalState()
    finish.checks.forEach { assertEquals(StatusKind.Completed, it) }
    assertEquals(TourFixture.finishSteps.size, finish.done)
    assertEquals(-1, finish.running)
    assertEquals(true, finish.prShown)
    assertEquals(true, finish.prReady)
    assertEquals(TourTarget.PrCard, finish.overlay.spotlight?.target)
    assertEquals("Ready for you", finish.overlay.spotlight?.tip?.title)

    val commands = tourCommandsTimeline().finalState()
    assertEquals(true, commands.paletteOpen)
    assertEquals(TourFixture.paletteQuery, commands.query)
    assertEquals(0, commands.highlight)
    assertEquals(
      listOf("Pause after subtask", "Pause now"),
      tourFilteredCommandGroups(commands.query).flatMap { it.items }.map { it.label },
    )
  }
}
