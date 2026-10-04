package skillbill.engine.goalplanning

import skillbill.contracts.JsonCodec
import skillbill.contracts.SharedPayloadKeys
import skillbill.error.shellcontent.invalidGoalPlanningPreparationSchemaError
import skillbill.workflow.model.WorkflowStepStatus
import skillbill.workflow.model.workflowStepStatus
import skillbill.workflow.taskruntime.model.core.FeatureTaskRuntimeWorkflowArtifactMap
import skillbill.workflow.taskruntime.model.handoff.task.NormalizedFeatureTaskRuntimePhaseOutput

internal fun readStoredPlanningRecord(
  payload: String,
  phaseId: String,
  label: String,
): NormalizedFeatureTaskRuntimePhaseOutput {
  val record =
    JsonCodec.parseObjectOrNull(payload)
      ?.let(JsonCodec::jsonElementToValue)
      ?.let(JsonCodec::anyToStringAnyMap)
      ?: throw invalidGoalPlanningPreparationSchemaError(
        label,
        "$phaseId.payload",
        "stored payload is not a JSON object",
      )
  val normalized =
    NormalizedFeatureTaskRuntimePhaseOutput.fromRecordMap(FeatureTaskRuntimeWorkflowArtifactMap.from(record))
  val produced = record[SharedPayloadKeys.PRODUCED_OUTPUTS] as? Map<*, *>
  if (normalized.phaseId != phaseId ||
    normalized.status.workflowStepStatus() != WorkflowStepStatus.COMPLETED ||
    produced?.isEmpty() != false
  ) {
    throw invalidGoalPlanningPreparationSchemaError(
      label,
      "$phaseId.payload",
      "phase output must match its phase and be completed with non-empty produced_outputs",
    )
  }
  return normalized
}
