package skillbill.engine.goalrunner.persist

import skillbill.engine.goalrunner.execution.support.recoverySafeAction
import skillbill.engine.goalrunner.model.GoalRunnerWorkflowProgress
import skillbill.engine.goalrunner.planning.recovery.goalPlanningHardResetRemedy
import skillbill.engine.recovery.DurableChildRecoveryClass
import skillbill.engine.recovery.classifyDurableChild
import skillbill.engine.recovery.recommendedDurableChildRecoveryCommand
import skillbill.engine.recovery.scopedChildRecoveryCommand
import skillbill.workflow.model.DecompositionStatus
import kotlin.test.Test
import kotlin.test.assertEquals

class GoalChildRecoveryTest {
  @Test
  fun `classification distinguishes absent active resumable and incompatible children`() {
    assertEquals(DurableChildRecoveryClass.ABSENT, classifyDurableChild(null))
    assertEquals(DurableChildRecoveryClass.ACTIVE, classifyDurableChild(progress("running")))
    assertEquals(DurableChildRecoveryClass.RESUMABLE, classifyDurableChild(progress("pending")))
    assertEquals(DurableChildRecoveryClass.RESUMABLE, classifyDurableChild(progress("paused")))
    listOf("blocked", "failed", "abandoned", "timed_out", "completed").forEach { status ->
      assertEquals(
        DurableChildRecoveryClass.INCOMPATIBLE_TERMINAL,
        classifyDurableChild(progress(status)),
        status,
      )
    }
  }

  @Test
  fun `scoped recovery command is stable and explicit`() {
    assertEquals(
      "skill-bill goal reset SKILL-143 --subtask 2 --delete-child-workflow",
      scopedChildRecoveryCommand("SKILL-143", 2),
    )
  }

  @Test
  fun `in progress incompatible terminal child recommends hard reset`() {
    assertEquals(
      "skill-bill goal reset SKILL-143 --hard --yes",
      recommendedDurableChildRecoveryCommand(
        "SKILL-143",
        2,
        DecompositionStatus.IN_PROGRESS,
        progress("failed"),
      ),
    )
  }

  @Test
  fun `blocked incompatible terminal child recommends scoped delete`() {
    assertEquals(
      scopedChildRecoveryCommand("SKILL-143", 2),
      recommendedDurableChildRecoveryCommand(
        "SKILL-143",
        2,
        DecompositionStatus.BLOCKED,
        progress("failed"),
      ),
    )
  }

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
      scopedChildRecoveryCommand("SKILL-143", 2),
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
