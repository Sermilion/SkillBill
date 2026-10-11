package skillbill.engine.featuretask.runner

import skillbill.contracts.SharedPayloadKeys
import skillbill.workflow.taskruntime.model.core.FeatureTaskRuntimeWorkflowArtifactMap
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimeFailureDisposition
import kotlin.test.Test
import kotlin.test.assertEquals

class TerminalBlockedReasonTest {
  @Test
  fun `blocked settlement preserves the full reason beyond its compact summary`() {
    val reason = "Important detail. ".repeat(30) + "Required operator action at the end."
    val output =
      FeatureTaskRuntimeWorkflowArtifactMap.from(
        mapOf(
          SharedPayloadKeys.STATUS to "blocked",
          SharedPayloadKeys.SUMMARY to reason.take(237) + "...",
          SharedPayloadKeys.PRODUCED_OUTPUTS to mapOf(SharedPayloadKeys.VALUE to reason),
        ),
      )
    assertEquals(
      "Phase output reported status 'blocked'. $reason",
      terminalBlockedReasonFrom("plan", output, FeatureTaskRuntimeFailureDisposition.NEEDS_USER_ACTION),
    )
  }
}
