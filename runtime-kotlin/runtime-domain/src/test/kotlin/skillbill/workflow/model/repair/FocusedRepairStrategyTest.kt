package skillbill.workflow.model.repair

import kotlin.test.Test
import kotlin.test.assertEquals

class FocusedRepairStrategyTest {
  @Test
  fun `the last repair is verified before its exhausted budget can block`() {
    val targets = listOf(RepairTargetProgress("first", 3), RepairTargetProgress("second", 0))

    assertEquals(
      FocusedRepairAction.Verify("first"),
      FocusedRepairStrategy.next(targets, FocusedRepairStage.VERIFICATION),
    )
    assertEquals(FocusedRepairAction.Blocked("first"), FocusedRepairStrategy.next(targets, FocusedRepairStage.PLANNING))
    assertEquals(
      FocusedRepairAction.Plan("second"),
      FocusedRepairStrategy.next(targets.drop(1), FocusedRepairStage.PLANNING),
    )
  }
}
