package skillbill.engine.work.pagination

import skillbill.error.core.SkillBillRuntimeException
import skillbill.ports.idestatus.model.WorkBoardRequest
import skillbill.ports.idestatus.model.WorkBoardWindow
import skillbill.ports.work.model.WorkStoreObservation
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class WorkBoardPagePositionTest {
  @Test
  fun `filtered cursor scope uses normalized filter text`() {
    val observedAt = Instant.parse("2026-10-28T12:00:00Z")
    val cursor =
      encodeWorkPageCursor(
        "${WorkBoardWindow.SIX_HOURS.wireValue}\nissue-447",
        observedAt,
        "r\n001\n2026-10-28T11:00:00.000000000Z\nwork-1",
        WorkStoreObservation("store", 9),
      )

    val position =
      workBoardPagePosition(
        WorkBoardRequest(
          finishedWithin = WorkBoardWindow.SIX_HOURS,
          filter = "  ISSUE-447  ",
          cursor = cursor,
        ),
        observedAt,
      )

    assertEquals(observedAt, position.observedAt)
    assertEquals("r\n001\n2026-10-28T11:00:00.000000000Z\nwork-1", position.afterKey)
    assertEquals(WorkStoreObservation("store", 9), position.observation)
  }

  @Test
  fun `filtered cursor rejects a different normalized filter`() {
    val observedAt = Instant.parse("2026-10-28T12:00:00Z")
    val cursor =
      encodeWorkPageCursor(
        "${WorkBoardWindow.SIX_HOURS.wireValue}\nissue-447",
        observedAt,
        "r\n001\n2026-10-28T11:00:00.000000000Z\nwork-1",
      )

    assertFailsWith<SkillBillRuntimeException> {
      workBoardPagePosition(
        WorkBoardRequest(
          finishedWithin = WorkBoardWindow.SIX_HOURS,
          filter = "issue-448",
          cursor = cursor,
        ),
        observedAt,
      )
    }
  }
}
