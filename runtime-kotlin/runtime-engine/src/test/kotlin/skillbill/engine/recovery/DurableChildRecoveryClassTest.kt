package skillbill.engine.recovery

import skillbill.workflow.model.DecompositionStatus
import skillbill.workflow.model.WorkflowStatus
import kotlin.test.Test
import kotlin.test.assertEquals

class DurableChildRecoveryClassTest {
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
      "sona goal reset SKILL-143 --subtask 2 --delete-child-workflow",
      scopedChildRecoveryCommand("SKILL-143", 2),
    )
  }

  @Test
  fun `in progress incompatible terminal child recommends hard reset`() {
    assertEquals(
      "sona goal reset SKILL-143 --hard --yes",
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

  private fun progress(status: String): WorkflowStatus = requireNotNull(WorkflowStatus.fromWire(status))
}
