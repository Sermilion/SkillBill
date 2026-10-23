package skillbill.engine.work

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import skillbill.ports.idestatus.model.WorkIncidentProgress
import skillbill.workflow.taskruntime.model.blockincident.BlockIncidentDiagnosis
import skillbill.workflow.taskruntime.model.blockincident.BlockIncidentRecoveryAttemptStatus
import skillbill.workflow.taskruntime.model.blockincident.BlockIncidentRecoveryEligibilityResult
import skillbill.workflow.taskruntime.model.blockincident.BlockIncidentRecoveryPreview

class WorkIncidentProjectionBuilderTest {
  @Test
  fun `failed recovery remains failed when eligibility becomes available`() {
    val available =
      BlockIncidentRecoveryEligibilityResult.Available(
        BlockIncidentRecoveryPreview(
          "reopen_blocked_phase",
          "Reopen blocked phase",
          null,
          "workflow",
          null,
          "implement",
          "bi_1",
          4,
        ),
      )

    assertEquals(
      WorkIncidentProgress.RECOVERY_FAILED,
      deriveWorkIncidentProgress(BlockIncidentDiagnosis.COMPLETE, BlockIncidentRecoveryAttemptStatus.FAILED, available),
    )
    assertEquals(
      WorkIncidentProgress.RESUMED,
      deriveWorkIncidentProgress(
        BlockIncidentDiagnosis.COMPLETE,
        BlockIncidentRecoveryAttemptStatus.RESUMED,
        available,
      ),
    )
  }
}
