package skillbill.application.workflow.decomposition

import skillbill.contracts.SharedPayloadKeys
import skillbill.contracts.workflow.payload.WorkflowWirePayloadKeys
import skillbill.workflow.engine.model.DurableWorkflowArtifactFamily
import skillbill.workflow.engine.model.WorkflowArtifactPatch
import skillbill.workflow.engine.model.WorkflowStepUpdates
import skillbill.workflow.model.WorkflowStepStatus

private const val PLAN_WORKFLOW_ID_ARTIFACT_FIELD = "workflow_id"
private val IMPORTED_PLAN_STEP_IDS = listOf("preplan", "plan")

fun importedPlanStepUpdates(planWorkflowId: String?): WorkflowStepUpdates =
  requireNotNull(
    WorkflowStepUpdates.from(
      IMPORTED_PLAN_STEP_IDS.map { stepId ->
        buildMap {
          put(SharedPayloadKeys.STEP_ID, stepId)
          put(SharedPayloadKeys.STATUS, WorkflowStepStatus.COMPLETED.wireValue)
          put(WorkflowWirePayloadKeys.ATTEMPT_COUNT, 1)
          planWorkflowId?.let { put(WorkflowWirePayloadKeys.PLAN_WORKFLOW_ID, it) }
        }
      },
    ),
  )

fun WorkflowArtifactPatch.withImportedPlan(planWorkflowId: String?): WorkflowArtifactPatch =
  if (planWorkflowId == null) {
    this
  } else {
    requireNotNull(
      WorkflowArtifactPatch.from(
        this +
          DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_PLAN_WORKFLOW.entry(
            mapOf(PLAN_WORKFLOW_ID_ARTIFACT_FIELD to planWorkflowId),
          ),
      ),
    )
  }
