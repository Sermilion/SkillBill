package skillbill.engine.featuretask.phase.record

import skillbill.engine.featuretask.model.phase.FeatureTaskRuntimePhaseStateRequest
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimePhaseRecord
import kotlin.test.Test
import kotlin.test.assertEquals

class FeatureTaskRuntimePhaseRecordOutputCarryForwardTest {
  @Test
  fun `relaunching a phase keeps the settled output a later resume projects from`() {
    val settled =
      FeatureTaskRuntimePhaseRecord(
        phaseId = "implement",
        status = "completed",
        attemptCount = 1,
        startedAt = CARRY_FORWARD_SETTLED_AT,
        firstStartedAt = CARRY_FORWARD_SETTLED_AT,
        resolvedAgentId = "claude",
        outputArtifact = CARRY_FORWARD_ROUND_ONE_OUTPUT,
      )

    val relaunched =
      featureTaskRuntimePhaseRecordFor(
        request =
          FeatureTaskRuntimePhaseStateRequest(
            workflowId = "wftr-1",
            phaseId = "implement",
            status = "running",
            attemptCount = 2,
            resolvedAgentId = "claude",
            finished = false,
          ),
        previous = settled,
        now = CARRY_FORWARD_RELAUNCHED_AT,
      )

    assertEquals(
      CARRY_FORWARD_ROUND_ONE_OUTPUT,
      relaunched.outputArtifact,
      "an audit-gap re-entry projects from its own prior output, so relaunching must not erase it",
    )
  }

  @Test
  fun `a settling write replaces the prior output`() {
    val previous =
      FeatureTaskRuntimePhaseRecord(
        phaseId = "implement",
        status = "running",
        attemptCount = 2,
        startedAt = CARRY_FORWARD_SETTLED_AT,
        firstStartedAt = CARRY_FORWARD_SETTLED_AT,
        resolvedAgentId = "claude",
        outputArtifact = CARRY_FORWARD_ROUND_ONE_OUTPUT,
      )

    val settled =
      featureTaskRuntimePhaseRecordFor(
        request =
          FeatureTaskRuntimePhaseStateRequest(
            workflowId = "wftr-1",
            phaseId = "implement",
            status = "completed",
            attemptCount = 2,
            resolvedAgentId = "claude",
            finished = true,
            outputArtifact = CARRY_FORWARD_ROUND_TWO_OUTPUT,
          ),
        previous = previous,
        now = CARRY_FORWARD_RELAUNCHED_AT,
      )

    assertEquals(CARRY_FORWARD_ROUND_TWO_OUTPUT, settled.outputArtifact)
  }
}

private const val CARRY_FORWARD_SETTLED_AT = "2026-09-06T10:09:56Z"
private const val CARRY_FORWARD_RELAUNCHED_AT = "2026-09-06T13:33:15Z"
private const val CARRY_FORWARD_ROUND_ONE_OUTPUT =
  """{"phase_id":"implement","status":"completed","summary":"round one"}"""
private const val CARRY_FORWARD_ROUND_TWO_OUTPUT =
  """{"phase_id":"implement","status":"completed","summary":"round two"}"""
