package skillbill.workflow.taskruntime.model.phase

import skillbill.error.shellcontent.invalidWorkflowStateSchemaError
import skillbill.workflow.taskruntime.model.core.FeatureTaskRuntimePhaseIds

private val REMOVED_FEATURE_TASK_RUNTIME_PHASE_IDS: Set<String> = setOf("plan_fix")

private val KNOWN_FEATURE_TASK_RUNTIME_PHASE_IDS: Set<String> =
  FeatureTaskRuntimePhaseIds.all.toSet()

internal fun requireKnownFeatureTaskRuntimePhaseId(
  phaseId: String,
  fieldPath: String,
): String {
  if (phaseId in REMOVED_FEATURE_TASK_RUNTIME_PHASE_IDS) {
    throw invalidWorkflowStateSchemaError(
      "Feature-task-runtime artifact field '$fieldPath' names removed phase '$phaseId'.",
    )
  }
  if (phaseId !in KNOWN_FEATURE_TASK_RUNTIME_PHASE_IDS) {
    throw invalidWorkflowStateSchemaError(
      "Feature-task-runtime artifact field '$fieldPath' has unknown phase '$phaseId'.",
    )
  }
  return phaseId
}
