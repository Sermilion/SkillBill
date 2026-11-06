package skillbill.engine.goalrunner.status

import skillbill.goalrunner.model.GoalRunnerLivenessSnapshot
import skillbill.goalrunner.model.GoalRunnerProcessState
import skillbill.goalrunner.model.GoalRunnerStopReason
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class FailurePhaseEvidenceTest {
  @Test
  fun `child exit uses only phase evidence from the same workflow`() {
    val liveness =
      GoalRunnerLivenessSnapshot(
        phase = "watchdog",
        reason = "process_exited",
        processState = GoalRunnerProcessState.EXITED,
        workflowId = "child",
        workflowStep = "audit_plan_fix",
      )
    assertEquals(
      "audit_plan_fix",
      failurePhaseEvidence(GoalRunnerStopReason.NO_TERMINAL_STORE_OUTCOME, "child", liveness)?.phaseId,
    )
    assertNull(failurePhaseEvidence(GoalRunnerStopReason.NO_TERMINAL_STORE_OUTCOME, "other", liveness)?.phaseId)
    assertNull(
      failurePhaseEvidence(
        GoalRunnerStopReason.NO_TERMINAL_STORE_OUTCOME,
        "child",
        liveness.copy(workflowStep = "other"),
      )?.phaseId,
    )
    assertNull(failurePhaseEvidence(GoalRunnerStopReason.BLOCKED, "child", liveness))
  }
}
