package skillbill.engine.goalrunner.execution.support

import skillbill.goalrunner.model.GoalRunnerStopReason
import kotlin.test.Test
import kotlin.test.assertEquals

class GoalRunnerReAttemptCauseTest {
  @Test
  fun `non-regeneration loop on a reconciled resumable stop classifies as crash_resume`() {
    assertEquals(
      "crash_resume",
      reAttemptCauseFor(GoalRunnerStopReason.RECONCILED_RESUMABLE, mapOf("review_fix" to 1)),
    )
  }

  @Test
  fun `backward edge on a non-resumable stop classifies as backward_edge`() {
    assertEquals(
      "backward_edge",
      reAttemptCauseFor(GoalRunnerStopReason.BLOCKED, mapOf("audit_gap" to 1)),
    )
  }

  @Test
  fun `stop with no loops yields no re-attempt cause`() {
    assertEquals(null, reAttemptCauseFor(GoalRunnerStopReason.TIMEOUT, emptyMap()))
  }
}
