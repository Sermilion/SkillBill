package skillbill.engine.goalrunner.persist

import skillbill.engine.goalrunner.execution.support.recoverySafeAction
import skillbill.engine.goalrunner.model.GoalRunnerWorkflowProgress
import skillbill.engine.goalrunner.planning.recovery.goalPlanningHardResetRemedy
import skillbill.workflow.model.DecompositionStatus
import kotlin.test.Test
import kotlin.test.assertEquals

class GoalChildRecoveryTest {
  @Test
  fun `ledger safe action distinguishes resumable and terminal children`() {
    assertEquals(
      "resume_from_last_resumable_step",
      recoverySafeAction(
        "SKILL-143",
        2,
        progress("paused"),
        "inspect_blocked_reason",
        DecompositionStatus.IN_PROGRESS,
      ),
    )
    assertEquals(
      goalPlanningHardResetRemedy("SKILL-143"),
      recoverySafeAction(
        "SKILL-143",
        2,
        progress("failed"),
        "inspect_blocked_reason",
        DecompositionStatus.IN_PROGRESS,
      ),
    )
    assertEquals(
      "sona goal reset SKILL-143 --subtask 2 --delete-child-workflow",
      recoverySafeAction(
        "SKILL-143",
        2,
        progress("failed"),
        "inspect_blocked_reason",
        DecompositionStatus.BLOCKED,
      ),
    )
  }

  private fun progress(status: String) =
    GoalRunnerWorkflowProgress(
      workflowId = "child-1",
      workflowStatus = status,
      currentStepId = "implement",
      progressToken = "token",
    )
}
