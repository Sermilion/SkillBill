package skillbill.engine.featuretask.runloop.durable

import skillbill.engine.featuretask.phaserun.InMemoryPhaseRunRecords
import skillbill.engine.featuretask.runloop.planning.PlanUnit
import skillbill.engine.featuretask.runloop.planning.PlanUnitAttempt
import skillbill.engine.featuretask.runloop.planning.PlanUnitRecords
import skillbill.workflow.model.WorkflowStepStatus
import skillbill.workflow.taskruntime.model.core.FeatureTaskRuntimePlanBundle
import skillbill.workflow.taskruntime.model.core.FeatureTaskRuntimePlanBundleOrigin
import skillbill.workflow.taskruntime.model.core.FeatureTaskRuntimePlanBundleSubtask
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimePhaseRecord
import skillbill.workflow.taskruntime.phase.task.FeatureTaskRuntimePhaseWorkflowDefinition
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PlanSubtaskSettlementPersistenceTest {
  private val records = InMemoryPhaseRunRecords(Clock.fixed(Instant.EPOCH, ZoneOffset.UTC), null)

  @Test
  fun `completed agent output stays local until settlement and rejection replaces it with blocked`() {
    records.recordPlanBundle(WORKFLOW_ID, bundle)
    records.recordPlanBundleWithUnits(WORKFLOW_ID, bundle, mapOf(SUBTASK_ID to unitRecord(WorkflowStepStatus.RUNNING)))

    assertTrue(persistPlanSubtaskProgress(records, WORKFLOW_ID, SUBTASK_ID, unitRecord(WorkflowStepStatus.COMPLETED)))
    assertEquals(WorkflowStepStatus.RUNNING, records.loadPhaseRecords(WORKFLOW_ID)[UNIT_RECORD_ID]?.status)

    val rejected = PlanUnitRecords.blocked(unitRecord(WorkflowStepStatus.COMPLETED), REJECTION_REASON)
    assertTrue(records.recordPlanBundleWithUnits(WORKFLOW_ID, bundle, mapOf(SUBTASK_ID to rejected)))

    val saved = records.loadPhaseRecords(WORKFLOW_ID)[UNIT_RECORD_ID]
    assertEquals(UNIT_RECORD_ID, saved?.phaseId)
    assertEquals(WorkflowStepStatus.BLOCKED, saved?.status)
    assertEquals(REJECTION_REASON, saved?.blockedReason)
    assertFalse(saved?.status == WorkflowStepStatus.COMPLETED)
  }

  @Test
  fun `a valid settlement writes the validated bundle with its completed unit`() {
    records.recordPlanBundle(WORKFLOW_ID, bundle)
    val validatedBundle =
      bundle.withSubtask(bundle.subtasks.single().copy(detailsSha256 = "details", planText = "- Plan"))
    val completed =
      PlanUnitRecords.settled(
        PlanUnit(WORKFLOW_ID, FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_PLAN_SUBTASKS, SUBTASK_ID),
        PlanUnitAttempt(1, "agent"),
        "- Plan",
        Instant.EPOCH,
      )

    assertTrue(persistPlanSubtaskProgress(records, WORKFLOW_ID, SUBTASK_ID, unitRecord(WorkflowStepStatus.COMPLETED)))
    assertTrue(records.recordPlanBundleWithUnits(WORKFLOW_ID, validatedBundle, mapOf(SUBTASK_ID to completed)))

    assertEquals("details", records.loadPlanBundle(WORKFLOW_ID)?.subtask(SUBTASK_ID)?.detailsSha256)
    val saved = records.loadPhaseRecords(WORKFLOW_ID)[UNIT_RECORD_ID]
    assertEquals(UNIT_RECORD_ID, saved?.phaseId)
    assertEquals(WorkflowStepStatus.COMPLETED, saved?.status)
  }

  private fun unitRecord(status: WorkflowStepStatus) =
    FeatureTaskRuntimePhaseRecord(
      phaseId = UNIT_RECORD_ID,
      status = status,
      attemptCount = 1,
      startedAt = Instant.EPOCH,
      resolvedAgentId = "agent",
      outputArtifact = if (status == WorkflowStepStatus.COMPLETED) "agent output" else null,
    )

  private companion object {
    const val WORKFLOW_ID = "wf-plan-subtask-settlement"
    const val SUBTASK_ID = 1
    const val REJECTION_REASON = "Subtask 1 has no acceptance criteria or a blank Implementation Details section."
    val UNIT_RECORD_ID = FeatureTaskRuntimePhaseWorkflowDefinition.planSubtaskUnitRecordId(SUBTASK_ID)
    val bundle =
      FeatureTaskRuntimePlanBundle(
        origin = FeatureTaskRuntimePlanBundleOrigin.AUTHORED,
        parentSpecSha256 = "parent",
        manifestSha256 = "manifest",
        subtasks = listOf(FeatureTaskRuntimePlanBundleSubtask(SUBTASK_ID, "spec.md", "scope")),
      )
  }
}
