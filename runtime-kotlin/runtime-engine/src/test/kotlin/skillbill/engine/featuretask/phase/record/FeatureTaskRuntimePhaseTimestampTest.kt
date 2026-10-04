package skillbill.engine.featuretask.phase.record

import java.time.Instant
import skillbill.contracts.JsonCodec
import skillbill.contracts.workflow.featuretask.FEATURE_TASK_RUNTIME_PERSISTENCE_CONTRACT_VERSION
import skillbill.engine.featuretask.model.phase.FeatureTaskRuntimePhaseStateRequest
import skillbill.engine.featuretask.persist.durationMillis
import skillbill.engine.goalrunner.status.completed
import skillbill.error.core.SkillBillRuntimeException
import skillbill.error.shellcontent.WorkflowFailureCode
import skillbill.workflow.taskruntime.artifact.decodePhaseRecordFromArtifact
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull

class FeatureTaskRuntimePhaseTimestampTest {
  @Test
  fun `completion uses legacy UTC start time and retains fractional duration`() {
    val previous = assertNotNull(decodePhaseRecordFromArtifact(JsonCodec.parseValue(phaseJson("2026-06-02 10:00:00"))))
    val completed =
      featureTaskRuntimePhaseRecordFor(
        FeatureTaskRuntimePhaseStateRequest(
          workflowId = "wftr-time",
          phaseId = "plan",
          status = "completed",
          attemptCount = 1,
          resolvedAgentId = "codex",
          finished = true,
        ),
        previous,
        Instant.parse("2026-06-02T10:00:01.250Z"),
      )
    assertEquals(Instant.parse("2026-06-02T10:00:00Z"), completed.startedAt)
    assertEquals(1250L, completed.durationMillis)
    assertFailsWith<SkillBillRuntimeException> {
      decodePhaseRecordFromArtifact(JsonCodec.parseValue(phaseJson("broken")))
    }.also { assertEquals(WorkflowFailureCode.INVALID_WORKFLOW_STATE_SCHEMA, it.code) }
  }

  private fun phaseJson(start: String) =
    """
    {"contract_version":"$FEATURE_TASK_RUNTIME_PERSISTENCE_CONTRACT_VERSION","record_kind":"private_phase_record",
    "phase_id":"plan","status":"running","attempt_count":1,"started_at":"$start","first_started_at":"$start",
    "resolved_agent_id":"codex","execution_origin":"agent-executed"}
    """.trimIndent()
}
