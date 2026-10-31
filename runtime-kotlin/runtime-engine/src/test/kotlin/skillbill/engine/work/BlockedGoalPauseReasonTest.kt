package skillbill.engine.work

import skillbill.ports.idestatus.model.IdeStatusPauseReasonCode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class BlockedGoalPauseReasonTest {
  @Test
  fun `only a blocked goal with a current subtask asks for an operator decision`() {
    val reason = "Goal assessment did not accept the goal: assessment_unavailable."

    assertEquals(
      IdeStatusPauseReasonCode.AWAITING_OPERATOR_DECISION,
      blockedGoalPauseReason(reason, currentSubtaskId = 2)?.code,
    )
    assertNull(blockedGoalPauseReason(reason, currentSubtaskId = null))
  }
}
