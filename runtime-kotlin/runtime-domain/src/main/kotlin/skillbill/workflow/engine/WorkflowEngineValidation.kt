package skillbill.workflow.engine

import skillbill.contracts.SharedPayloadKeys
import skillbill.contracts.workflow.payload.WorkflowWirePayloadKeys
import skillbill.error.shellcontent.invalidWorkflowStateSchemaError
import skillbill.workflow.engine.model.WorkflowDefinition
import skillbill.workflow.engine.model.WorkflowStateSnapshot
import skillbill.workflow.engine.model.WorkflowUpdateInput
import skillbill.workflow.engine.model.isTerminalStatus
import skillbill.workflow.model.WorkflowStepStatus
import skillbill.workflow.model.persistence.artifact.asExactIntOrNull

internal fun requireTerminalFinishedAt(
  existing: WorkflowStateSnapshot,
  input: WorkflowUpdateInput,
) = existing.finishedAt ?: input.terminalInstant
  ?: throw invalidWorkflowStateSchemaError("A terminal workflow update requires a terminal instant.")

internal fun refuseChangedOrdinaryTerminalReplay(
  existing: WorkflowStateSnapshot,
  updated: WorkflowStateSnapshot,
) {
  val sameIdentity = updated.copy(artifacts = existing.artifacts) == existing
  val sameArtifacts = updated.artifacts.toMap() == existing.artifacts.toMap()
  if (!sameIdentity || !sameArtifacts) {
    throw invalidWorkflowStateSchemaError(
      "An ordinary terminal replay cannot change the accepted workflow snapshot '${existing.workflowId}'.",
    )
  }
}

internal fun validateWorkflowOpen(
  definition: WorkflowDefinition,
  currentStepId: String,
): String? = validateWorkflowEnum(currentStepId, definition.stepIds, "current_step_id")

internal fun validateWorkflowUpdate(
  definition: WorkflowDefinition,
  existing: WorkflowStateSnapshot,
  input: WorkflowUpdateInput,
): String? {
  if (input.workflowStatus !in definition.workflowStatusEnums) {
    return "Invalid workflow_status '${input.workflowStatus.wireValue}'. Allowed: " +
      definition.workflowStatusEnums.joinToString { it.wireValue }
  }
  if (input.currentStepId.isNotBlank()) {
    validateWorkflowEnum(input.currentStepId, definition.stepIds, "current_step_id")?.let { return it }
  }
  if (definition.isTerminalStatus(existing.workflowStatus) && input.workflowStatus != existing.workflowStatus) {
    return "A terminal workflow cannot transition from '${existing.workflowStatus.wireValue}' " +
      "to '${input.workflowStatus.wireValue}' through an ordinary update."
  }
  return input.stepUpdates?.let { validateWorkflowStepUpdates(definition, it.asEntries()) }
}

private fun validateWorkflowStepUpdates(
  definition: WorkflowDefinition,
  updates: List<Map<String, Any?>>,
): String? {
  val seenStepIds = mutableSetOf<String>()
  for ((index, update) in updates.withIndex()) {
    validateOneStepUpdate(definition, index, update, seenStepIds)?.let { return it }
  }
  return null
}

private fun validateOneStepUpdate(
  definition: WorkflowDefinition,
  index: Int,
  update: Map<String, Any?>,
  seenStepIds: MutableSet<String>,
): String? {
  val stepId = update[SharedPayloadKeys.STEP_ID] as? String
  if (stepId.isNullOrBlank()) {
    return "step_updates[$index].step_id must be a non-empty string."
  }
  validateWorkflowEnum(stepId, definition.stepIds, "step_updates.step_id")?.let { return it }
  if (!seenStepIds.add(stepId)) {
    return "Duplicate step_id '$stepId' in step_updates."
  }
  return validateStepStatusAndAttempt(definition, index, update)
}

private fun validateStepStatusAndAttempt(
  definition: WorkflowDefinition,
  index: Int,
  update: Map<String, Any?>,
): String? {
  val status = update[SharedPayloadKeys.STATUS] as? String
  if (status.isNullOrBlank()) {
    return "step_updates[$index].status must be a non-empty string."
  }
  val decodedStatus = WorkflowStepStatus.fromWire(status)
  if (decodedStatus == null || decodedStatus !in definition.stepStatusEnums) {
    return "Invalid step_updates.status '$status'. Allowed: " +
      definition.stepStatusEnums.joinToString { it.wireValue }
  }
  val attemptCount = update[WorkflowWirePayloadKeys.ATTEMPT_COUNT].asExactIntOrNull()
  if (attemptCount == null || attemptCount < 0) {
    return "step_updates[$index].attempt_count must be an integer >= 0."
  }
  return null
}

private fun validateWorkflowEnum(
  value: String,
  allowed: Collection<String>,
  fieldName: String,
): String? =
  if (value !in allowed) {
    "Invalid $fieldName '$value'. Allowed: ${allowed.joinToString()}"
  } else {
    null
  }
