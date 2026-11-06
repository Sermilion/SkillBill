package skillbill.engine.featuretask.incident

import skillbill.engine.diagnostics.block.BlockIncidentAliases
import skillbill.engine.diagnostics.block.BlockIncidentIdentity
import skillbill.engine.diagnostics.block.FailurePhaseEvidence
import skillbill.ports.blockincident.model.BlockedWorkflowWithoutIncident
import skillbill.ports.workflow.model.WorkflowFamily
import skillbill.workflow.engine.WorkflowEngine
import skillbill.workflow.engine.model.WorkflowArtifactPatch
import skillbill.workflow.engine.model.WorkflowStepUpdates
import skillbill.workflow.engine.model.WorkflowUpdateInput
import skillbill.workflow.model.WorkflowStatus
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class BlockIncidentRecoveredFailurePhaseTest {
  @Test
  fun `incident reconstruction preserves failure evidence independently of resume phase`() {
    val now = Instant.EPOCH
    val workflowId = "wftr-child"
    val alias = BlockIncidentAliases.goal(workflowId, "audit", null, null, null)
    val identity =
      BlockIncidentIdentity(
        stepId = "audit",
        attempt = null,
        generation = null,
        childWorkflowId = null,
        childIncidentToken = null,
        incidentToken = BlockIncidentAliases.token(alias),
        alias = alias,
        failurePhaseEvidence = FailurePhaseEvidence("audit_plan_fix"),
      )
    val engine = WorkflowEngine()
    val opened = engine.openRecord(WorkflowFamily.TASK_RUNTIME.definition, workflowId, "session", "audit")
    val record =
      engine.updateRecord(
        WorkflowFamily.TASK_RUNTIME.definition,
        opened,
        WorkflowUpdateInput(
          workflowStatus = WorkflowStatus.BLOCKED,
          currentStepId = "audit",
          stepUpdates =
            WorkflowStepUpdates.from(
              listOf(mapOf("step_id" to "audit", "status" to "blocked", "attempt_count" to 0)),
            ),
          artifactsPatch = WorkflowArtifactPatch.from(mapOf(identity.artifactEntry())),
          sessionId = "session",
        ),
      )
    val candidate = BlockedWorkflowWithoutIncident(workflowId, WorkflowFamily.TASK_RUNTIME, now, now)
    val recovered =
      BlockIncidentRecoveredObservations.build(
        candidate,
        record,
        BlockIncidentIdentity.fromArtifacts(record.artifacts),
      )
    assertEquals("audit_plan_fix", recovered.phaseId)
    assertNull(recovered.attempt)
    assertEquals("audit", record.currentStepId)
  }
}
