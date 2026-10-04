package skillbill.workflow.engine

import skillbill.contracts.SharedPayloadKeys
import skillbill.contracts.workflow.payload.WorkflowWirePayloadKeys
import skillbill.error.shellcontent.invalidWorkflowStateSchemaError
import skillbill.workflow.engine.model.WorkflowDefinition
import skillbill.workflow.engine.model.WorkflowSnapshotView
import skillbill.workflow.engine.model.WorkflowStateSnapshot
import skillbill.workflow.engine.model.WorkflowStepState
import skillbill.workflow.model.WorkflowStepStatus
import skillbill.workflow.model.persistence.artifact.asExactIntOrNull

internal fun snapshotViewFrom(record: WorkflowStateSnapshot): WorkflowSnapshotView =
  WorkflowSnapshotView(
    workflowId = record.workflowId,
    sessionId = record.sessionId,
    workflowName = record.workflowName,
    mode = record.mode?.wireValue,
    contractVersion = record.contractVersion,
    workflowStatus = record.workflowStatus,
    currentStepId = record.currentStepId,
    steps = record.steps,
    artifacts = record.artifacts,
    startedAt = record.startedAt?.toString().orEmpty(),
    updatedAt = record.updatedAt?.toString().orEmpty(),
    finishedAt = record.finishedAt?.toString().orEmpty(),
  )

internal fun defaultSteps(
  definition: WorkflowDefinition,
  initialStepId: String,
): List<WorkflowStepState> {
  var seenInitial = false
  return definition.stepIds.map { stepId ->
    when {
      stepId == initialStepId -> {
        seenInitial = true
        WorkflowStepState(stepId, WorkflowStepStatus.RUNNING, 1)
      }
      definition.openPriorStepsCompleted && !seenInitial ->
        WorkflowStepState(stepId, WorkflowStepStatus.COMPLETED, 1)
      else -> WorkflowStepState(stepId, WorkflowStepStatus.PENDING, 0)
    }
  }
}

internal fun mergeStepUpdates(
  definition: WorkflowDefinition,
  existingSteps: List<WorkflowStepState>,
  stepUpdates: List<Map<String, Any?>>?,
): List<WorkflowStepState> {
  if (stepUpdates == null) return existingSteps
  val byStepId = existingSteps.associateByTo(LinkedHashMap(), WorkflowStepState::stepId)
  stepUpdates.forEach { update ->
    val stepId =
      update[SharedPayloadKeys.STEP_ID] as? String
        ?: invalidWorkflowStep("step_updates.step_id must be a non-empty string.")
    val statusWire =
      update[SharedPayloadKeys.STATUS] as? String
        ?: invalidWorkflowStep("step_updates.status must be a non-empty string.")
    val status =
      WorkflowStepStatus.fromWire(statusWire)
        ?: invalidWorkflowStep("step_updates.status has unsupported value '$statusWire'.")
    val attempts =
      update[WorkflowWirePayloadKeys.ATTEMPT_COUNT].asExactIntOrNull()
        ?: invalidWorkflowStep("step_updates.attempt_count must be an integer >= 0.")
    if (attempts < 0) invalidWorkflowStep("step_updates.attempt_count must be an integer >= 0.")
    byStepId[stepId] = WorkflowStepState(stepId, status, attempts)
  }
  return definition.stepIds.mapNotNull(byStepId::get)
}

private fun invalidWorkflowStep(reason: String): Nothing = throw invalidWorkflowStateSchemaError(reason)
