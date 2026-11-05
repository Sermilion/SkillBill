package skillbill.engine.work

import skillbill.ports.idestatus.model.IdeStatusPauseReasonCode
import skillbill.workflow.taskruntime.model.core.FeatureTaskRuntimeProviderLimitSignal
import skillbill.workflow.taskruntime.validation.FeatureTaskRuntimeProviderLimitPause
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

  @Test
  fun `classified provider limit blocked reason never asks for an operator decision`() {
    val reason =
      FeatureTaskRuntimeProviderLimitPause.reason(
        phaseLabel = "Goal assessment",
        agentId = "claude",
        resumePhaseId = "goal_assessment",
        signal = FeatureTaskRuntimeProviderLimitSignal(evidence = "usage limit", resetHint = null),
      )

    assertNull(blockedGoalPauseReason(reason, currentSubtaskId = 2))
  }
}
